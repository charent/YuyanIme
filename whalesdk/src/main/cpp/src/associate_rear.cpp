// 联想词（AssociateRear）实现
//
// ###########################################################################
// ## 本文件是整个原生层**唯一**依赖 librime 内部 C++ 类的地方（补丁面 0，内部依赖 1）
// ##
// ## 升级 librime 时必须复核：
// ##   1. rime/dict/table.h 里 `Table::Load()` / `Table::metadata()` 的签名；
// ##   2. `Table::Metadata` 的字段布局（`string_table` / `string_table_size`）；
// ##   3. rime/dict/string_table.h 里 `StringTable(const char*, size_t)` /
// ##      `Predict(query, vector<StringId>*)` / `GetString(StringId)` 的签名。
// ## 1.11 -> 1.17 就是在这里踩过一次（Metadata 字段挪位）。
// ##
// ## 上游没有预测/联想接口（rime_api.h 98 个成员里没有 predict/associate），
// ## 所以这部分保留 native。若将来要彻底解耦，替代方案是用桌面脚本把
// ## table.bin 的字符串表 dump 成自建索引，由 Kotlin 侧 mmap + 二分前缀查找
// ## （见 docs/engine-stock-vs-custom.md §2.3 方案 C3）。
// ###########################################################################
//
// ## 与旧实现的差异
//
// 旧版把联想做在 librime 内部（`AssociateRear` + `Table::Predict` + 用户词库联想条目），
// 那部分源码不公开。升级到上游 1.17 后改用**上游已有能力**重建：
//
//   librime 1.17 的 `StringTable` 本身就提供 `Predict(query, &ids)`，
//   底层是 marisa-trie 的 `predictive_search`，数据源正是 `build/pinyin.table.bin`
//   里内嵌的字符串表（191 万词条，7.1 MB）。
//
// 也就是说联想词不需要任何额外词表，也不需要给 librime 打补丁：
// 打开 table 文件 → 取 metadata 里的字符串表 → 前缀搜索 → 反查文本。
//
// ## 仍与旧版不同的地方
//
// - 旧版会顺带写入用户词库的联想条目（隐式学习），这里暂时不做；
// - 排序用「字数升序 + 词典序」，而非旧版权重排序（marisa 前缀搜索不返回权重）。
//   若后续需要更接近旧版的手感，可以按候选 code 回查 `Entry.weight` 重新排序。

#include "associate_rear.h"

#include <algorithm>
#include <string>
#include <unordered_set>
#include <vector>

#include <rime/common.h>
#include <rime/dict/string_table.h>
#include <rime/dict/table.h>

namespace whale {
namespace {

// 联想的最大词长（Unicode 字符数），超过的多半是长句，不适合放在候选栏
constexpr size_t kMaxAssociateChars = 16;
// 单次前缀扫描的上限，防止 "一" 这类单字前缀拖慢输入（会命中数万条）
constexpr size_t kMaxScan = 400;
// 联想键最多回退到多长的后缀（字符数）
constexpr size_t kMaxPrefixChars = 8;

std::string g_shared_data_dir;

size_t Utf8CharLen(unsigned char lead) {
  if (lead < 0x80) return 1;
  if ((lead & 0xE0) == 0xC0) return 2;
  if ((lead & 0xF0) == 0xE0) return 3;
  if ((lead & 0xF8) == 0xF0) return 4;
  return 1;  // 非法首字节，按单字节前进，避免死循环
}

size_t Utf8Length(const std::string& s) {
  size_t count = 0;
  for (size_t i = 0; i < s.size();) {
    i += Utf8CharLen(static_cast<unsigned char>(s[i]));
    ++count;
  }
  return count;
}

// 从末尾数 n 个字符的起始字节偏移
size_t Utf8SuffixStart(const std::string& s, size_t n) {
  size_t end = s.size();
  while (n > 0 && end > 0) {
    size_t p = end - 1;
    // 回退到该字符的首字节
    while (p > 0 && (static_cast<unsigned char>(s[p]) & 0xC0) == 0x80) --p;
    end = p;
    --n;
  }
  return end;
}

// 词条清洗：剔除带空格/控制字符的脏数据
// （实测 pinyin.table.bin 里存在 " 你好好休息吧"、"我 们的家园" 这类条目）
bool IsCleanKey(const std::string& s) {
  if (s.empty()) return false;
  for (unsigned char c : s) {
    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') return false;
    if (c < 0x20) return false;
  }
  return true;
}

}  // namespace

void AssociateRear::SetSharedDataDir(const std::string& dir) {
  g_shared_data_dir = dir;
}

AssociateRear::AssociateRear(RimeSessionId session_id) : session_id_(session_id) {}

AssociateRear::~AssociateRear() = default;

bool AssociateRear::EnsureTable() {
  if (strings_) return true;
  if (g_shared_data_dir.empty()) return false;

  const std::string path = g_shared_data_dir + "/build/pinyin.table.bin";
  auto table = rime::New<rime::Table>(rime::path(path));
  if (!table || !table->Load()) return false;

  auto* meta = table->metadata();
  if (meta == nullptr || !meta->string_table || meta->string_table_size == 0) {
    return false;
  }

  // 字符串表是 table 文件里的一段 marisa-trie 镜像，可直接按指针映射
  auto strings = rime::New<rime::StringTable>(meta->string_table.get(),
                                              meta->string_table_size);
  table_ = table;
  strings_ = strings;
  return true;
}

void AssociateRear::GetAssociateWord(const std::string& text, int limit,
                                     std::vector<std::string>* out) {
  out->clear();
  current_.clear();
  if (text.empty() || limit <= 0) return;
  if (!EnsureTable()) return;

  // 候选前缀：整段（截断到 kMaxPrefixChars）→ 末尾 2 字 → 末尾 1 字
  std::vector<std::string> prefixes;
  const size_t text_chars = Utf8Length(text);
  const size_t back_chars = std::min(text_chars, kMaxPrefixChars);
  prefixes.push_back(text.substr(Utf8SuffixStart(text, back_chars)));
  if (text_chars > 2) {
    prefixes.push_back(text.substr(Utf8SuffixStart(text, 2)));
  }
  if (text_chars > 1) {
    prefixes.push_back(text.substr(Utf8SuffixStart(text, 1)));
  }

  std::unordered_set<std::string> seen;
  std::vector<rime::StringId> ids;

  for (const auto& prefix : prefixes) {
    if (prefix.empty()) continue;

    ids.clear();
    strings_->Predict(prefix, &ids);
    if (ids.empty()) continue;

    std::vector<std::string> batch;
    batch.reserve(std::min(ids.size(), kMaxScan));
    for (size_t i = 0; i < ids.size() && i < kMaxScan; ++i) {
      std::string word = strings_->GetString(ids[i]);
      if (word == prefix) continue;
      if (!IsCleanKey(word)) continue;
      if (Utf8Length(word) > kMaxAssociateChars) continue;
      if (!seen.insert(word).second) continue;
      batch.push_back(std::move(word));
    }

    // 短词优先：联想栏里「你好吗」比「你好好休息吧」更常用
    std::stable_sort(batch.begin(), batch.end(),
                     [](const std::string& a, const std::string& b) {
                       return Utf8Length(a) < Utf8Length(b);
                     });

    for (auto& word : batch) {
      out->push_back(word);
      if (static_cast<int>(out->size()) >= limit) break;
    }
    if (static_cast<int>(out->size()) >= limit) break;
  }

  current_ = *out;
}

bool AssociateRear::SelectAssociateWord(int index) {
  if (index < 0 || index >= static_cast<int>(current_.size())) return false;
  // 上屏文本由 Java 侧（RimeEngine.selectAssociation）用 preCommitText 提交，
  // 这里只负责校验 index 有效。
  return true;
}

}  // namespace whale
