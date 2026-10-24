// 联想词（AssociateRear）
//
// 注意：这是原生层**唯一**依赖 librime 内部 C++ 类（rime::Table / rime::StringTable）
// 的模块，升级引擎时必须复核这三处签名，详见 associate_rear.cpp 文件头。
//
// 旧版在 librime 内部实现（AssociateRear / Table::Predict / 用户词库联想条目），
// 源码不公开。这里用上游 1.17 自带能力重建：
//   rime::Table 打开 build/pinyin.table.bin
//   → metadata()->string_table 是一段 marisa-trie 镜像
//   → rime::StringTable::Predict() 做前缀搜索
//   → GetString(id) 反查词条文本
//
// 不需要修改 librime，也不需要额外的联想词表。
// 实现细节与已知差异见 associate_rear.cpp 顶部注释。

#pragma once

#include <memory>
#include <string>
#include <vector>

#include "rime_api.h"

namespace rime {
class Table;
class StringTable;
}  // namespace rime

namespace whale {

class AssociateRear {
 public:
  explicit AssociateRear(RimeSessionId session_id);
  ~AssociateRear();

  // 由 startupRime 调用，记录 shared_data_dir（table 文件位于 <dir>/build/）
  static void SetSharedDataDir(const std::string& dir);

  // 前缀预测，最多返回 limit 条
  void GetAssociateWord(const std::string& text, int limit,
                        std::vector<std::string>* out);

  // 校验下标；上屏文本由 Java 侧提交
  bool SelectAssociateWord(int index);

 private:
  bool EnsureTable();

  RimeSessionId session_id_;
  std::shared_ptr<rime::Table> table_;
  std::shared_ptr<rime::StringTable> strings_;
  std::vector<std::string> current_;
};

}  // namespace whale
