// 语燕对 librime 的私有扩展接口
//
// 这些能力在 librime 1.17.0 上游不存在，原实现是作者在 librime 源码里加方法实现的
// （见 docs/engine-upgrade-plan.md §2）。这里的处理原则：
//   1. 能用公开 API 或上游内部 API 组合出来的，写在 yuyan_jni.cpp 里，不打补丁；
//   2. 必须改 librime 的，集中在 rime_ext.h，便于移植时一眼看到全部改动点；
//   3. 编译期开关 YUYAN_HAS_* 用来区分「已打补丁」与「降级实现」。

#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "rime_api.h"

// 若把 Context::ReplaceInput 打进 librime，则定义此宏走原生路径
// #define YUYAN_HAS_REPLACE_INPUT 1

namespace yuyan {
namespace ext {

// 对应 Kotlin: Rime.replaceKey(caretPos, length, key)
//
// 原实现是 rime::Context::ReplaceInput(size_t pos, size_t len, const string& text)，
// 已通过反编译还原（1.11.2 @ 0x1ed420，见 docs/engine-re-findings.md）：
//   pos + len > input.length() → 返回 false
//   否则 input.replace(pos, len, text)；caret_pos = input.length()；update_notifier(this)
// 参数语义确认为 (pos, len, text)：从 pos 起删除 len 个字符再插入 text。
// 该逻辑只用 Context 的公开成员，**不需要给 librime 打补丁**。
bool ReplaceInput(RimeSessionId session_id, int pos, int length,
                  const std::string& text);

// 对应 Kotlin: Rime.getAssociateList(text) / Rime.chooseAssociate(index)
//
// 原实现链路：Session::GetAssociateWord → AssociateRear::GetAssociateWord
//   → {GetAssociateWordInSession, GetUserDictAssociateWord, GetDefaultDictAssociateWord}
//   → MergeTwoResultList → FilterPrefixForList → RimeAssociateList::SortAssociateList
// 若走「联想词外移到 Java」路线（方案 §6 路线 D），这两个函数不再需要实现。
void GetAssociateWords(RimeSessionId session_id, const std::string& text,
                       int limit, std::vector<std::string>* out);
bool SelectAssociateWord(RimeSessionId session_id, int index);

// 对应 Kotlin: Rime.getRimeKeycodeByName("Page_Down")
// 原实现是语燕新增的 extern "C" 函数 RimeGetKeycodeByName。
// 1.17 上游是否仍导出待核实；未导出时走内置键名表（见 yuyan_jni.cpp）。
int KeycodeByName(const char* name);

}  // namespace ext
}  // namespace yuyan
