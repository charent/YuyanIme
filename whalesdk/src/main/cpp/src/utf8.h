// UTF-8 -> jstring
//
// 不能用 JNIEnv::NewStringUTF：它期望的是 Modified UTF-8，遇到 4 字节序列
// （emoji、部分生僻字、CJK 扩展 B 以上）会截断或抛异常。候选词里这类字符并不罕见。
// 这里按标准 UTF-8 解码成 UTF-16，再交给 NewString。

#pragma once

#include <jni.h>

#include <string>

namespace whale {

inline std::u16string Utf8ToUtf16(const char* s) {
  std::u16string out;
  if (!s) return out;
  const auto* p = reinterpret_cast<const unsigned char*>(s);
  while (*p) {
    unsigned char c = *p;
    char32_t cp = 0;
    int extra = 0;
    if (c < 0x80) {
      cp = c;
    } else if ((c & 0xE0) == 0xC0) {
      cp = c & 0x1F;
      extra = 1;
    } else if ((c & 0xF0) == 0xE0) {
      cp = c & 0x0F;
      extra = 2;
    } else if ((c & 0xF8) == 0xF0) {
      cp = c & 0x07;
      extra = 3;
    } else {
      // 非法起始字节：吞掉，用替换字符占位
      out.push_back(static_cast<char16_t>(0xFFFD));
      ++p;
      continue;
    }
    bool ok = true;
    for (int i = 1; i <= extra; ++i) {
      if ((p[i] & 0xC0) != 0x80) {
        ok = false;
        break;
      }
      cp = (cp << 6) | (p[i] & 0x3F);
    }
    if (!ok) {
      out.push_back(static_cast<char16_t>(0xFFFD));
      ++p;
      continue;
    }
    p += extra + 1;
    if (cp <= 0xFFFF) {
      out.push_back(static_cast<char16_t>(cp));
    } else {
      cp -= 0x10000;
      out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
      out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
    }
  }
  return out;
}

// 注意：绝不能对 nullptr 返回 nullptr。
// Kotlin 侧 CandidateListItem/RimeContext 等字段声明为非空 String，
// 传 null 会抛 NullPointerException；该异常在 JNI 调用中未被清除，
// 后续任何 JNI 调用（例如 SetObjectArrayElement）都会触发
// "JNI DETECTED ERROR IN APPLICATION" 并 SIGABRT。
// librime 的 candidate.comment / commit.text 在没有内容时正是 NULL。
inline jstring NewJString(JNIEnv* env, const char* s) {
  if (!s) s = "";
  const std::u16string u16 = Utf8ToUtf16(s);
  return env->NewString(reinterpret_cast<const jchar*>(u16.data()),
                        static_cast<jsize>(u16.size()));
}

// 把 jstring 读成 UTF-8 std::string（路径、schema id、option 名等均为 ASCII/UTF-8）
inline std::string JStringToUtf8(JNIEnv* env, jstring js) {
  if (!js) return {};
  const jclass str_class = env->FindClass("java/lang/String");
  const jmethodID get_bytes = env->GetMethodID(str_class, "getBytes", "()[B");
  auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(js, get_bytes));
  const jsize len = env->GetArrayLength(bytes);
  std::string out(static_cast<size_t>(len), '\0');
  env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(out.data()));
  env->DeleteLocalRef(bytes);
  return out;
}

}  // namespace whale
