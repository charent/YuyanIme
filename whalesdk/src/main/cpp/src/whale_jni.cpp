// 小鲸鱼 IME 原生层入口（JNI）
//
// 对应 Kotlin: com.whale.inputmethod.core.Rime 的 external 方法。
//
// ## 设计约束（升级引擎时唯一要守住的一条）
//
// 本文件**只** include `rime_api.h`，只调用 `rime_get_api()` 返回的函数表，
// 不 include、也不引用任何 `rime/*.h` 里的内部 C++ 类（Context / Service / Menu / Table ...）。
// 这样升级 librime 时这里只需逐个核对函数签名，不必跟进内部实现。
//
// 唯一仍绑内部 C++ 的地方是 associate_rear.cpp（联想词，上游没有对应 C API），
// 已在该文件头标明。
//
// ## 已迁到 Kotlin 的逻辑（原先写在这里）
//
// - 组合串局部替换（原 `rime::Context::ReplaceInput` 的复刻）：现为 `Rime.replaceKey`，
//   底层只用 `get_input` / `set_input` 两个直通方法；
// - 键名 → 键码（原 `KeycodeByName` 内置表）：现为常量 `RimeKeycode`；
// - 翻页与候选下标（原合成 `Page_Down` 键 + 页内下标）：现走
//   `candidate_list_from_index` / `candidate_list_next` 全量列表，索引即全局下标，
//   分页完全由 Kotlin 侧（RimeEngine）负责。

#include <jni.h>

#include <string>
#include <vector>

#include <android/log.h>

#include "rime_api.h"

#include "associate_rear.h"
#include "utf8.h"

// 诊断日志：librime 自身的 glog 在 Android 上被关掉了（ENABLE_LOGGING=OFF），
// 这里把 JNI 边界上的关键返回值打出来，便于定位「引擎是否起来、按键是否被吃」。
#define YLOG(...) __android_log_print(ANDROID_LOG_INFO, "WhaleRime", __VA_ARGS__)

namespace {

RimeApi* g_api = nullptr;
RimeSessionId g_session = 0;

inline RimeApi* Api() {
  return g_api;
}

// 函数表是自版本化的：老版本 librime 可能没有某些成员，用前先判断存在性
#define WHALE_HAS_API(func) \
  (Api() != nullptr && RIME_STRUCT_HAS_MEMBER(*Api(), Api()->func) && Api()->func)

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

  g_refs.candidate_item = load("com/whale/inputmethod/core/CandidateListItem");
  // data class CandidateListItem(var comment: String, var text: String)
  g_refs.candidate_item_ctor = env->GetMethodID(
      g_refs.candidate_item, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");

  g_refs.composition = load("com/whale/inputmethod/core/RimeComposition");
  // (length, cursorPos, selStart, selEnd, preedit)
  g_refs.composition_ctor = env->GetMethodID(
      g_refs.composition, "<init>", "(IIIILjava/lang/String;)V");

  g_refs.menu = load("com/whale/inputmethod/core/RimeMenu");
  // (pageSize, pageNo, isLastPage, highlightedCandidateIndex, numCandidates)
  g_refs.menu_ctor = env->GetMethodID(g_refs.menu, "<init>", "(IIZII)V");

  g_refs.context = load("com/whale/inputmethod/core/RimeContext");
  // (composition, menu, commitTextPreview, selectLabels)
  g_refs.context_ctor = env->GetMethodID(
      g_refs.context, "<init>",
      "(Lcom/whale/inputmethod/core/RimeComposition;"
      "Lcom/whale/inputmethod/core/RimeMenu;"
      "Ljava/lang/String;[Ljava/lang/String;)V");

  g_refs.status = load("com/whale/inputmethod/core/RimeStatus");
  // (schemaId, schemaName, isDisable, isComposing, isAsciiMode, isFullShape,
  //  isSimplified, isTraditional, isAsciiPunch)
  g_refs.status_ctor = env->GetMethodID(
      g_refs.status, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;ZZZZZZZ)V");

  g_refs.commit = load("com/whale/inputmethod/core/RimeCommit");
  g_refs.commit_ctor =
      env->GetMethodID(g_refs.commit, "<init>", "(Ljava/lang/String;)V");
}

jobject MakeCandidateItem(JNIEnv* env, const RimeCandidate& c) {
  jstring text = whale::NewJString(env, c.text);
  jstring comment = whale::NewJString(env, c.comment);
  jobject item = env->NewObject(g_refs.candidate_item, g_refs.candidate_item_ctor,
                                comment, text);
  env->DeleteLocalRef(text);
  env->DeleteLocalRef(comment);
  return item;
}

jobject MakeComposition(JNIEnv* env, const RimeComposition& comp) {
  jstring preedit = whale::NewJString(env, comp.preedit);
  jobject obj = env->NewObject(g_refs.composition, g_refs.composition_ctor,
                               comp.length, comp.cursor_pos, comp.sel_start,
                               comp.sel_end, preedit);
  env->DeleteLocalRef(preedit);
  return obj;
}

// 候选数组不再编组：候选统一由 getRimeCandidates 走 candidate_list_* 提供，
// 这里只保留分页元信息，避免每次按键都把整页候选复制进 Java 堆。
jobject MakeMenu(JNIEnv* env, const RimeMenu& menu) {
  return env->NewObject(g_refs.menu, g_refs.menu_ctor, menu.page_size,
                        menu.page_no, static_cast<jboolean>(menu.is_last_page),
                        menu.highlighted_candidate_index, menu.num_candidates);
}

jobject MakeContext(JNIEnv* env, const RimeContext& ctx) {
  jobject composition = MakeComposition(env, ctx.composition);
  jobject menu = MakeMenu(env, ctx.menu);

  // select_labels 是以 nullptr 结尾的 char**
  std::vector<jstring> labels;
  if (ctx.select_labels != nullptr) {
    for (char** p = ctx.select_labels; *p != nullptr; ++p) {
      labels.push_back(whale::NewJString(env, *p));
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

  jstring preview = whale::NewJString(env, ctx.commit_text_preview);
  jobject obj = env->NewObject(g_refs.context, g_refs.context_ctor, composition,
                               menu, preview, label_array);
  env->DeleteLocalRef(composition);
  env->DeleteLocalRef(menu);
  env->DeleteLocalRef(preview);
  env->DeleteLocalRef(label_array);
  return obj;
}

jobject MakeStatus(JNIEnv* env, const RimeStatus& s) {
  jstring schema_id = whale::NewJString(env, s.schema_id);
  jstring schema_name = whale::NewJString(env, s.schema_name);
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
Java_com_whale_inputmethod_core_Rime_startupRime(JNIEnv* env, jclass, jobject,
                                                 jstring shared_dir,
                                                 jstring user_dir,
                                                 jboolean full_check) {
  if (Api() == nullptr) g_api = rime_get_api();
  if (Api() == nullptr) {
    YLOG("startup: rime_get_api() returned null");
    return;
  }

  const std::string shared = whale::JStringToUtf8(env, shared_dir);
  const std::string user = whale::JStringToUtf8(env, user_dir);
  YLOG("startup: shared=%s user=%s full_check=%d", shared.c_str(), user.c_str(),
       full_check ? 1 : 0);

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.log_dir = user.c_str();
  traits.distribution_name = "WhaleIME";
  traits.distribution_code_name = "rime.whale";
  traits.distribution_version = "1.17.0";
  traits.app_name = "rime.whale";
  // 预编译词典仍在 <shared>/build，与默认值一致，这里显式写出来便于排查
  // 注意：c_str() 的生命周期必须覆盖 initialize 调用
  const std::string prebuilt = shared + "/build";
  traits.prebuilt_data_dir = prebuilt.c_str();

  Api()->setup(&traits);
  Api()->initialize(&traits);

  // 联想词要自己打开 <shared>/build/pinyin.table.bin
  whale::AssociateRear::SetSharedDataDir(shared);

  if (full_check) {
    Api()->start_maintenance(True);
    Api()->join_maintenance_thread();
  }
  g_session = Api()->create_session();
  YLOG("startup: session=%lu", static_cast<unsigned long>(g_session));
}

extern "C" JNIEXPORT void JNICALL
Java_com_whale_inputmethod_core_Rime_exitRime(JNIEnv*, jclass) {
  if (Api() == nullptr) return;
  if (g_session != 0) {
    Api()->destroy_session(g_session);
    g_session = 0;
  }
  Api()->finalize();
}

// ---------------------------------------------------------------------------
// 按键与组合串
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_whale_inputmethod_core_Rime_processRimeKey(JNIEnv*, jclass, jint keycode,
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

extern "C" JNIEXPORT jstring JNICALL
Java_com_whale_inputmethod_core_Rime_getRimeInput(JNIEnv* env, jclass) {
  if (g_session == 0 || !WHALE_HAS_API(get_input)) {
    return whale::NewJString(env, "");
  }
  const char* input = Api()->get_input(g_session);
  return whale::NewJString(env, input);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_whale_inputmethod_core_Rime_setRimeInput(JNIEnv* env, jclass,
                                                  jstring input) {
  if (g_session == 0 || !WHALE_HAS_API(set_input)) return JNI_FALSE;
  // set_input 内部就是「赋值 + 光标移到末尾 + update_notifier」，与旧的
  // Context::ReplaceInput 末尾两步语义一致（见 docs/engine-stock-vs-custom.md §2.1）
  const std::string text = whale::JStringToUtf8(env, input);
  return Api()->set_input(g_session, text.c_str()) == True ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_whale_inputmethod_core_Rime_clearRimeComposition(JNIEnv*, jclass) {
  if (g_session != 0 && Api() != nullptr) Api()->clear_composition(g_session);
}

// ---------------------------------------------------------------------------
// 状态读取
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jobject JNICALL
Java_com_whale_inputmethod_core_Rime_getRimeCommit(JNIEnv* env, jclass) {
  if (g_session == 0 || Api() == nullptr) return nullptr;
  RIME_STRUCT(RimeCommit, commit);
  if (Api()->get_commit(g_session, &commit) != True) return nullptr;
  EnsureRefs(env);
  jstring text = whale::NewJString(env, commit.text);
  jobject result = env->NewObject(g_refs.commit, g_refs.commit_ctor, text);
  env->DeleteLocalRef(text);
  Api()->free_commit(&commit);
  return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_whale_inputmethod_core_Rime_getRimeContext(JNIEnv* env, jclass) {
  if (g_session == 0 || Api() == nullptr) return nullptr;
  RIME_STRUCT(RimeContext, ctx);
  if (Api()->get_context(g_session, &ctx) != True) return nullptr;
  EnsureRefs(env);
  env->PushLocalFrame(64);
  jobject result = MakeContext(env, ctx);
  result = env->PopLocalFrame(result);
  Api()->free_context(&ctx);
  return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_whale_inputmethod_core_Rime_getRimeStatus(JNIEnv* env, jclass) {
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
Java_com_whale_inputmethod_core_Rime_setRimeOption(JNIEnv* env, jclass,
                                                   jstring option,
                                                   jboolean value) {
  if (g_session == 0 || Api() == nullptr) return;
  const std::string name = whale::JStringToUtf8(env, option);
  Api()->set_option(g_session, name.c_str(), value == JNI_TRUE ? True : False);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whale_inputmethod_core_Rime_getCurrentRimeSchema(JNIEnv* env, jclass) {
  if (g_session == 0 || Api() == nullptr) return whale::NewJString(env, "");
  char schema_id[256] = {0};
  Api()->get_current_schema(g_session, schema_id, sizeof(schema_id));
  return whale::NewJString(env, schema_id);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_whale_inputmethod_core_Rime_selectRimeSchema(JNIEnv* env, jclass,
                                                      jstring schema_id) {
  if (g_session == 0 || Api() == nullptr) return JNI_FALSE;
  const std::string id = whale::JStringToUtf8(env, schema_id);
  return Api()->select_schema(g_session, id.c_str()) == True ? JNI_TRUE
                                                             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_whale_inputmethod_core_Rime_selectRimeCandidate(JNIEnv*, jclass,
                                                         jint index) {
  if (g_session == 0 || Api() == nullptr) return JNI_FALSE;
  // index 是**全局下标**（不是页内偏移），与 getRimeCandidates 返回的顺序一一对应
  return Api()->select_candidate(g_session, static_cast<size_t>(index)) == True
             ? JNI_TRUE
             : JNI_FALSE;
}

// 全量候选列表的一段：从全局下标 offset 起最多取 limit 条。
//
// 用 candidate_list_from_index / candidate_list_next 绕过分页：Menu::GetCandidateAt 会按需
// Prepare(index + 1)，所以取多少就只算多少，不依赖 schema 的 menu/page_size。
// 返回数组的第 i 项对应全局下标 offset + i（候选必然连续）。
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_whale_inputmethod_core_Rime_getRimeCandidates(JNIEnv* env, jclass,
                                                        jint offset,
                                                        jint limit) {
  EnsureRefs(env);
  auto empty_array = [env]() -> jobjectArray {
    return env->NewObjectArray(0, g_refs.candidate_item, nullptr);
  };

  if (g_session == 0 || limit <= 0 || offset < 0) return empty_array();
  if (!WHALE_HAS_API(candidate_list_from_index) ||
      !WHALE_HAS_API(candidate_list_next) || !WHALE_HAS_API(candidate_list_end)) {
    return empty_array();
  }

  RimeCandidateListIterator it;
  if (Api()->candidate_list_from_index(g_session, &it, offset) != True) {
    return empty_array();
  }

  // 每个候选要 3 个局部引用（comment/text/对象），预分配够用的容量
  env->PushLocalFrame(64 + limit * 4);
  std::vector<jobject> items;
  items.reserve(static_cast<size_t>(limit));
  while (static_cast<jint>(items.size()) < limit &&
         Api()->candidate_list_next(&it) == True) {
    items.push_back(MakeCandidateItem(env, it.candidate));
  }
  Api()->candidate_list_end(&it);

  jobjectArray result = env->NewObjectArray(static_cast<jsize>(items.size()),
                                            g_refs.candidate_item, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(items.size()); ++i) {
    env->SetObjectArrayElement(result, i, items[static_cast<size_t>(i)]);
  }
  return static_cast<jobjectArray>(env->PopLocalFrame(result));
}

// ---------------------------------------------------------------------------
// 联想词（唯一仍依赖 librime 内部 C++ 的能力，原因见 associate_rear.cpp）
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_whale_inputmethod_core_Rime_getRimeAssociateList(JNIEnv* env, jclass,
                                                          jstring key) {
  const std::string text = whale::JStringToUtf8(env, key);
  std::vector<std::string> words;
  whale::AssociateRear rear(g_session);
  rear.GetAssociateWord(text, 50, &words);

  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray result = env->NewObjectArray(static_cast<jsize>(words.size()),
                                            string_class, nullptr);
  for (jsize i = 0; i < static_cast<jsize>(words.size()); ++i) {
    jstring item = whale::NewJString(env, words[i].c_str());
    env->SetObjectArrayElement(result, i, item);
    env->DeleteLocalRef(item);
  }
  env->DeleteLocalRef(string_class);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_whale_inputmethod_core_Rime_selectRimeAssociate(JNIEnv*, jclass,
                                                         jint index) {
  whale::AssociateRear rear(g_session);
  return rear.SelectAssociateWord(index) ? JNI_TRUE : JNI_FALSE;
}
