// 语燕 IME 原生层入口（JNI）
//
// 对应 Kotlin: com.yuyan.inputmethod.core.Rime 的 16 个 external 方法。
//
// ## 与 1.11 版的差异
//
// librime 1.12 起把 C API 收敛到 `RimeApi` 函数表（`rime_get_api()`），不再导出
// `RimeProcessKey` 之类的独立符号，所有调用都要走 `api->xxx(...)`。
//
// `Context` 的内部结构也变了：1.11 时代可以直接改 `ctx.input` / `ctx.caret_pos`，
// 1.17 里这两个成员是私有的，只能通过 `set_input()` / `set_caret_pos()`，
// 好在它们内部同样会触发 `update_notifier_`，语义与旧实现一致。
//
// 页大小不再能通过 Context 改（1.17 的 Context 没有 menu 成员），
// 改为在 default.yaml 里配置 `menu/page_size: 100`，`setRimePageSize` 保留为空实现。

#include <jni.h>

#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

#include <android/log.h>

#include "rime_api.h"
#include "rime/common.h"
#include "rime/context.h"
#include "rime/service.h"

#include "associate_rear.h"
#include "rime_ext.h"
#include "utf8.h"

// 诊断日志：librime 自身的 glog 在 Android 上被关掉了（ENABLE_LOGGING=OFF），
// 这里把 JNI 边界上的关键返回值打出来，便于定位「引擎是否起来、按键是否被吃」。
#define YLOG(...) __android_log_print(ANDROID_LOG_INFO, "YuyanRime", __VA_ARGS__)

namespace {

RimeApi* g_api = nullptr;
RimeSessionId g_session = 0;

inline RimeApi* Api() {
  return g_api;
}

struct JavaRefs {
  jclass candidate_item = nullptr;
  jmethodID candidate_item_ctor = nullptr;
  jclass composition = nullptr;
  jmethodID composition_ctor = nullptr;
  jclass menu = nullptr;
  jmethodID menu_ctor = nullptr;
  jclass context = nullptr;
  jmethodID context_ctor = nullptr;
  jclass status = nullptr;
  jmethodID status_ctor = nullptr;
  jclass commit = nullptr;
  jmethodID commit_ctor = nullptr;
};

JavaRefs g_refs;

void EnsureRefs(JNIEnv* env) {
  if (g_refs.candidate_item != nullptr) return;

  // 必须转成全局引用：局部引用在本次 JNI 调用返回后即失效
  auto load = [env](const char* name) -> jclass {
    jclass local = env->FindClass(name);
    if (local == nullptr) return nullptr;
    auto global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return global;
  };

  g_refs.candidate_item = load("com/yuyan/inputmethod/core/CandidateListItem");
  // data class CandidateListItem(var comment: String, var text: String)
  g_refs.candidate_item_ctor = env->GetMethodID(
      g_refs.candidate_item, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");

  g_refs.composition = load("com/yuyan/inputmethod/core/RimeComposition");
  // (length, cursorPos, selStart, selEnd, preedit)
  g_refs.composition_ctor = env->GetMethodID(
      g_refs.composition, "<init>", "(IIIILjava/lang/String;)V");

  g_refs.menu = load("com/yuyan/inputmethod/core/RimeMenu");
  // (pageSize, pageNo, isLastPage, highlightedCandidateIndex, numCandidates, candidates)
  g_refs.menu_ctor = env->GetMethodID(
      g_refs.menu, "<init>",
      "(IIZII[Lcom/yuyan/inputmethod/core/CandidateListItem;)V");

  g_refs.context = load("com/yuyan/inputmethod/core/RimeContext");
  // (composition, menu, commitTextPreview, selectLabels)
  g_refs.context_ctor = env->GetMethodID(
      g_refs.context, "<init>",
      "(Lcom/yuyan/inputmethod/core/RimeComposition;"
      "Lcom/yuyan/inputmethod/core/RimeMenu;"
      "Ljava/lang/String;[Ljava/lang/String;)V");

  g_refs.status = load("com/yuyan/inputmethod/core/RimeStatus");
  // (schemaId, schemaName, isDisable, isComposing, isAsciiMode, isFullShape,
  //  isSimplified, isTraditional, isAsciiPunch)
  g_refs.status_ctor = env->GetMethodID(
      g_refs.status, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V");

  g_refs.commit = load("com/yuyan/inputmethod/core/RimeCommit");
  g_refs.commit_ctor =
      env->GetMethodID(g_refs.commit, "<init>", "(Ljava/lang/String;)V");
}

jobject MakeCandidateItem(JNIEnv* env, const RimeCandidate& c) {
  jstring text = yuyan::NewJString(env, c.text);
  jstring comment = yuyan::NewJString(env, c.comment);
  jobject item = env->NewObject(g_refs.candidate_item, g_refs.candidate_item_ctor,
                                comment, text);
  env->DeleteLocalRef(text);
  env->DeleteLocalRef(comment);
  return item;
}

jobject MakeComposition(JNIEnv* env, const RimeComposition& comp) {
  jstring preedit = yuyan::NewJString(env, comp.preedit);
  jobject obj = env->NewObject(g_refs.composition, g_refs.composition_ctor,
                               comp.length, comp.cursor_pos, comp.sel_start,
                               comp.sel_end, preedit);
  env->DeleteLocalRef(preedit);
  return obj;
}

jobject MakeMenu(JNIEnv* env, const RimeMenu& menu) {
  if (menu.num_candidates <= 0 || menu.candidates == nullptr) {
    jobjectArray empty = env->NewObjectArray(0, g_refs.candidate_item, nullptr);
    jobject obj = env->NewObject(g_refs.menu, g_refs.menu_ctor, menu.page_size,
                                 menu.page_no, JNI_FALSE,
                                 menu.highlighted_candidate_index, 0, empty);
    env->DeleteLocalRef(empty);
    return obj;
  }
  jobjectArray items = env->NewObjectArray(
      menu.num_candidates, g_refs.candidate_item, nullptr);
  for (int i = 0; i < menu.num_candidates; ++i) {
    jobject item = MakeCandidateItem(env, menu.candidates[i]);
    env->SetObjectArrayElement(items, i, item);
    env->DeleteLocalRef(item);
  }
  jobject obj =
      env->NewObject(g_refs.menu, g_refs.menu_ctor, menu.page_size, menu.page_no,
                     static_cast<jboolean>(menu.is_last_page != 0),
                     menu.highlighted_candidate_index, menu.num_candidates, items);
  env->DeleteLocalRef(items);
  return obj;
}

jobject MakeContext(JNIEnv* env, const RimeContext& ctx) {
  jobject composition = MakeComposition(env, ctx.composition);
  jobject menu = MakeMenu(env, ctx.menu);

  // select_labels 是以 nullptr 结尾的 char**
  std::vector<jstring> labels;
  if (ctx.select_labels != nullptr) {
    for (char** p = ctx.select_labels; *p != nullptr; ++p) {
      labels.push_back(yuyan::NewJString(env, *p));
    }
  }
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray label_array =
      env->NewObjectArray(static_cast<jsize>(labels.size()), string_class, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(labels.size()); ++i) {
    env->SetObjectArrayElement(label_array, i, labels[i]);
    env->DeleteLocalRef(labels[i]);
  }
  env->DeleteLocalRef(string_class);

  jstring preview = yuyan::NewJString(env, ctx.commit_text_preview);
  jobject obj = env->NewObject(g_refs.context, g_refs.context_ctor, composition,
                               menu, preview, label_array);
  env->DeleteLocalRef(composition);
  env->DeleteLocalRef(menu);
  env->DeleteLocalRef(preview);
  env->DeleteLocalRef(label_array);
  return obj;
}

jobject MakeStatus(JNIEnv* env, const RimeStatus& s) {
  jstring schema_id = yuyan::NewJString(env, s.schema_id);
  jstring schema_name = yuyan::NewJString(env, s.schema_name);
  // 注意 Kotlin 字段名是 isDisable（单数），对应 rime 的 is_disabled
  jobject obj = env->NewObject(
      g_refs.status, g_refs.status_ctor, schema_id, schema_name,
      static_cast<jboolean>(s.is_disabled != 0),
      static_cast<jboolean>(s.is_composing != 0),
      static_cast<jboolean>(s.is_ascii_mode != 0),
      static_cast<jboolean>(s.is_full_shape != 0),
      static_cast<jboolean>(s.is_simplified != 0),
      static_cast<jboolean>(s.is_traditional != 0),
      static_cast<jboolean>(s.is_ascii_punct != 0));
  env->DeleteLocalRef(schema_id);
  env->DeleteLocalRef(schema_name);
  return obj;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
  // 1.12 起 C API 全部挂在函数表上
  g_api = rime_get_api();
  return JNI_VERSION_1_6;
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_startupRime(JNIEnv* env, jclass, jobject,
                                                 jstring shared_dir,
                                                 jstring user_dir,
                                                 jboolean full_check) {
  if (Api() == nullptr) g_api = rime_get_api();
  if (Api() == nullptr) {
    YLOG("startup: rime_get_api() returned null");
    return;
  }

  const std::string shared = yuyan::JStringToUtf8(env, shared_dir);
  const std::string user = yuyan::JStringToUtf8(env, user_dir);
  YLOG("startup: shared=%s user=%s full_check=%d", shared.c_str(), user.c_str(),
       full_check ? 1 : 0);

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.log_dir = user.c_str();
  traits.distribution_name = "YuyanIme";
  traits.distribution_code_name = "rime.yuyan";
  traits.distribution_version = "1.17.0";
  traits.app_name = "rime.yuyan";
  // 预编译词典仍在 <shared>/build，与默认值一致，这里显式写出来便于排查
  // 注意：c_str() 的生命周期必须覆盖 initialize 调用
  const std::string prebuilt = shared + "/build";
  traits.prebuilt_data_dir = prebuilt.c_str();

  Api()->setup(&traits);
  Api()->initialize(&traits);

  // 联想词要自己打开 <shared>/build/pinyin.table.bin
  yuyan::AssociateRear::SetSharedDataDir(shared);

  if (full_check) {
    Api()->start_maintenance(True);
    Api()->join_maintenance_thread();
  }
  g_session = Api()->create_session();
  YLOG("startup: session=%lu", static_cast<unsigned long>(g_session));
}

extern "C" JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_exitRime(JNIEnv*, jclass) {
  if (Api() == nullptr) return;
  if (g_session != 0) {
    Api()->destroy_session(g_session);
    g_session = 0;
  }
  Api()->finalize();
}

// ---------------------------------------------------------------------------
// 会话设置
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_setRimePageSize(JNIEnv*, jclass,
                                                     jint page_size) {
  // 1.17 的 Context 没有 menu 成员，页大小只能来自 schema 配置（menu/page_size）。
  // 预置的 default.yaml 已设成 100，这里保留空实现以维持 Java 侧调用契约。
  (void)page_size;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_processRimeKey(JNIEnv*, jclass, jint keycode,
                                                    jint mask) {
  if (g_session == 0 || Api() == nullptr) {
    YLOG("processKey: no session (session=%lu keycode=%d)",
         static_cast<unsigned long>(g_session), keycode);
    return JNI_FALSE;
  }
  Bool ok = Api()->process_key(g_session, keycode, mask);
  // 正常按键不写日志（每次按键都打会拖慢输入）；只在引擎「没接住」时留一条线索
  if (ok != True) {
    YLOG("processKey unhandled: keycode=%d mask=%d", keycode, mask);
  }
  return ok == True ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_replaceRimeKey(JNIEnv* env, jclass,
                                                    jint caret_pos, jint length,
                                                    jstring key) {
  const std::string text = yuyan::JStringToUtf8(env, key);
  return yuyan::ext::ReplaceInput(g_session, caret_pos, length, text) ? JNI_TRUE
                                                                     : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_clearRimeComposition(JNIEnv*, jclass) {
  if (g_session != 0 && Api() != nullptr) Api()->clear_composition(g_session);
}

// ---------------------------------------------------------------------------
// 状态读取
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jobject JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeCommit(JNIEnv* env, jclass) {
  if (g_session == 0 || Api() == nullptr) return nullptr;
  RIME_STRUCT(RimeCommit, commit);
  if (Api()->get_commit(g_session, &commit) != True) return nullptr;
  EnsureRefs(env);
  jstring text = yuyan::NewJString(env, commit.text);
  jobject result = env->NewObject(g_refs.commit, g_refs.commit_ctor, text);
  env->DeleteLocalRef(text);
  Api()->free_commit(&commit);
  return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeContext(JNIEnv* env, jclass) {
  if (g_session == 0 || Api() == nullptr) return nullptr;
  RIME_STRUCT(RimeContext, ctx);
  if (Api()->get_context(g_session, &ctx) != True) return nullptr;
  EnsureRefs(env);
  // 候选数 × 每个 2 个字符串 + 结构体，局部引用可能超过默认 512
  env->PushLocalFrame(256 + ctx.menu.num_candidates * 2);
  jobject result = MakeContext(env, ctx);
  result = env->PopLocalFrame(result);
  Api()->free_context(&ctx);
  return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeStatus(JNIEnv* env, jclass) {
  if (g_session == 0 || Api() == nullptr) return nullptr;
  RIME_STRUCT(RimeStatus, status);
  if (Api()->get_status(g_session, &status) != True) {
    YLOG("getStatus: failed");
    return nullptr;
  }
  EnsureRefs(env);
  jobject result = MakeStatus(env, status);
  Api()->free_status(&status);
  return result;
}

// ---------------------------------------------------------------------------
// 方案 / 选项 / 候选
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT void JNICALL
Java_com_yuyan_inputmethod_core_Rime_setRimeOption(JNIEnv* env, jclass,
                                                   jstring option,
                                                   jboolean value) {
  if (g_session == 0 || Api() == nullptr) return;
  const std::string name = yuyan::JStringToUtf8(env, option);
  Api()->set_option(g_session, name.c_str(), value == JNI_TRUE ? True : False);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yuyan_inputmethod_core_Rime_getCurrentRimeSchema(JNIEnv* env, jclass) {
  if (g_session == 0 || Api() == nullptr) return yuyan::NewJString(env, "");
  char schema_id[256] = {0};
  Api()->get_current_schema(g_session, schema_id, sizeof(schema_id));
  return yuyan::NewJString(env, schema_id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_selectRimeSchema(JNIEnv* env, jclass,
                                                      jstring schema_id) {
  if (g_session == 0 || Api() == nullptr) return JNI_FALSE;
  const std::string id = yuyan::JStringToUtf8(env, schema_id);
  return Api()->select_schema(g_session, id.c_str()) == True ? JNI_TRUE
                                                             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_selectRimeCandidate(JNIEnv*, jclass,
                                                         jint index) {
  if (g_session == 0 || Api() == nullptr) return JNI_FALSE;
  return Api()->select_candidate(g_session, static_cast<size_t>(index)) == True
             ? JNI_TRUE
             : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// 私有扩展
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jint JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeKeycodeByName(JNIEnv* env, jclass,
                                                          jstring name) {
  const std::string key_name = yuyan::JStringToUtf8(env, name);
  return yuyan::ext::KeycodeByName(key_name.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_yuyan_inputmethod_core_Rime_getRimeAssociateList(JNIEnv* env, jclass,
                                                          jstring key) {
  const std::string text = yuyan::JStringToUtf8(env, key);
  std::vector<std::string> words;
  yuyan::AssociateRear rear(g_session);
  rear.GetAssociateWord(text, 50, &words);

  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(words.size()),
                                            string_class, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(words.size()); ++i) {
    jstring item = yuyan::NewJString(env, words[i].c_str());
    env->SetObjectArrayElement(result, i, item);
    env->DeleteLocalRef(item);
  }
  env->DeleteLocalRef(string_class);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuyan_inputmethod_core_Rime_selectRimeAssociate(JNIEnv*, jclass,
                                                         jint index) {
  yuyan::AssociateRear rear(g_session);
  return rear.SelectAssociateWord(index) ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// ext 实现
// ---------------------------------------------------------------------------

namespace yuyan {
namespace ext {

bool ReplaceInput(RimeSessionId session_id, int pos, int length,
                  const std::string& text) {
  auto session = rime::Service::instance().GetSession(session_id);
  if (!session) return false;
  auto* ctx = session->context();
  if (ctx == nullptr) return false;

  // 以下逻辑由 libyuyanime.so 的 rime::Context::ReplaceInput 反汇编还原
  // （1.11.2 @ 0x1ed420，140 字节，见 docs/engine-re-findings.md）：
  //   if (pos + len > input.length()) return false;
  //   input.replace(pos, len, text);
  //   caret_pos = input.length();
  //   update_notifier(this);
  //
  // 1.17 把 input_/caret_pos_ 改成了私有成员，但 set_input() 的实现正是
  // 「赋值 + caret 移到末尾 + update_notifier_」，与原实现逐条对应。
  const std::string& current = ctx->input();
  const size_t p = static_cast<size_t>(pos);
  const size_t n = static_cast<size_t>(length);
  if (pos < 0 || length < 0 || p + n > current.size()) return false;

  std::string next = current;
  next.replace(p, n, text);
  ctx->set_input(next);
  return true;
}

void GetAssociateWords(RimeSessionId session_id, const std::string& text,
                       int limit, std::vector<std::string>* out) {
  AssociateRear rear(session_id);
  rear.GetAssociateWord(text, limit, out);
}

bool SelectAssociateWord(RimeSessionId session_id, int index) {
  AssociateRear rear(session_id);
  return rear.SelectAssociateWord(index);
}

int KeycodeByName(const char* name) {
  // 上游未导出键名表，这里内置工程实际用到的键（X11 keysym）
  if (name == nullptr) return 0;
  const std::string n(name);
  if (n == "Page_Down" || n == "Next") return 0xFF56;
  if (n == "Page_Up" || n == "Prior") return 0xFF55;
  if (n == "Return" || n == "Enter") return 0xFF0D;
  if (n == "BackSpace") return 0xFF08;
  if (n == "Tab") return 0xFF09;
  if (n == "Escape") return 0xFF1B;
  if (n == "space") return 0x20;
  if (n == "Left") return 0xFF51;
  if (n == "Up") return 0xFF52;
  if (n == "Right") return 0xFF53;
  if (n == "Down") return 0xFF54;
  if (n.size() == 1) return static_cast<int>(static_cast<unsigned char>(n[0]));
  return 0;
}

}  // namespace ext
}  // namespace yuyan
