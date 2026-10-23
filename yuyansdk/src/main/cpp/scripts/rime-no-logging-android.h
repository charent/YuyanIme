// librime 在 ENABLE_LOGGING=OFF 时使用本文件替代 glog。
// 上游实现把 LOG/VLOG 全部编译成空语句，Android 上等于完全静默，
// 排查「schema 加载失败」「prism 格式不对」这类问题时会看不到任何线索。
//
// 这里改成写 logcat（tag: librime），行为与 glog 的流式语法保持一致。
// 仅替换源码树里的 src/rime/no_logging.h，不修改上游逻辑。

#ifndef RIME_NO_LOGGING_H_
#define RIME_NO_LOGGING_H_

#if defined(__ANDROID__)

#include <sstream>
#include <android/log.h>

namespace rime {

class AndroidLogStream {
 public:
  explicit AndroidLogStream(int priority) : priority_(priority) {}
  ~AndroidLogStream() {
    __android_log_print(priority_, "librime", "%s", os_.str().c_str());
  }
  std::ostringstream& stream() { return os_; }

 private:
  int priority_;
  std::ostringstream os_;
};

// 用于把「不该输出的日志」整条表达式编译成空语句
class VoidLogger {
 public:
  VoidLogger() {}
  VoidLogger& stream() { return *this; }
  template <class T>
  VoidLogger& operator<<(const T&) {
    return *this;
  }
};
class Voidify {
 public:
  Voidify() {}
  void operator&(VoidLogger&) {}
};

}  // namespace rime

#define RIME_NO_LOG \
  true ? (void)0 : rime::Voidify() & rime::VoidLogger().stream()

#define RIME_LOG_PRIO_INFO ANDROID_LOG_INFO
#define RIME_LOG_PRIO_WARNING ANDROID_LOG_WARN
#define RIME_LOG_PRIO_ERROR ANDROID_LOG_ERROR
#define RIME_LOG_PRIO_FATAL ANDROID_LOG_FATAL

// LOG 保留（加载失败、格式不匹配这类信息量很小但很关键）
#define LOG(severity) rime::AndroidLogStream(RIME_LOG_PRIO_##severity).stream()
// DLOG 是逐键打印的调试日志（read:/traverse:），Release 下必须静默，
// 否则每次按键几十行 logcat IO，输入直接卡死
#define VLOG(verboselevel) RIME_NO_LOG
#define LOG_IF(severity, condition) \
  if (!(condition)) {               \
  } else                            \
    LOG(severity)
#define LOG_EVERY_N(severity, n) LOG(severity)
#define LOG_IF_EVERY_N(severity, condition, n) LOG_IF(severity, condition)
#define LOG_ASSERT(condition) LOG_IF(FATAL, !(condition))

#else  // 非 Android 平台保持上游的静默行为

namespace rime {
class VoidLogger {
 public:
  VoidLogger() {}
  VoidLogger& stream() { return *this; }
  template <class T>
  VoidLogger& operator<<(const T&) {
    return *this;
  }
};
class Voidify {
 public:
  Voidify() {}
  void operator&(VoidLogger&) {}
};
}  // namespace rime

#define RIME_NO_LOG \
  true ? (void)0 : rime::Voidify() & rime::VoidLogger().stream()
#define LOG(severity) RIME_NO_LOG
#define VLOG(verboselevel) RIME_NO_LOG
#define LOG_IF(severity, condition) RIME_NO_LOG
#define LOG_EVERY_N(severity, n) RIME_NO_LOG
#define LOG_IF_EVERY_N(severity, condition, n) RIME_NO_LOG
#define LOG_ASSERT(condition) RIME_NO_LOG

#endif  // __ANDROID__

// 断言类宏保持为空：这些原本由 glog 提供，一旦生效会导致进程 abort，
// 在移动端不适合作为默认行为。
#define RIME_NO_CHECK (void)0

#define CHECK(condition) RIME_NO_CHECK
#define CHECK_EQ(val1, val2) RIME_NO_CHECK
#define CHECK_NE(val1, val2) RIME_NO_CHECK
#define CHECK_LE(val1, val2) RIME_NO_CHECK
#define CHECK_LT(val1, val2) RIME_NO_CHECK
#define CHECK_GE(val1, val2) RIME_NO_CHECK
#define CHECK_GT(val1, val2) RIME_NO_CHECK
#define CHECK_NOTNULL(val) RIME_NO_CHECK
#define CHECK_STREQ(str1, str2) RIME_NO_CHECK
#define CHECK_STRCASEEQ(str1, str2) RIME_NO_CHECK
#define CHECK_STRNE(str1, str2) RIME_NO_CHECK
#define CHECK_STRCASENE(str1, str2) RIME_NO_CHECK

#define DLOG(severity) RIME_NO_LOG
#define DVLOG(verboselevel) RIME_NO_LOG
#define DLOG_IF(severity, condition) RIME_NO_LOG
#define DLOG_EVERY_N(severity, n) RIME_NO_LOG
#define DLOG_IF_EVERY_N(severity, condition, n) RIME_NO_LOG
#define DLOG_ASSERT(condition) RIME_NO_LOG

#define DCHECK(condition) CHECK(condition)
#define DCHECK_EQ(val1, val2) CHECK_EQ(val1, val2)
#define DCHECK_NE(val1, val2) CHECK_NE(val1, val2)
#define DCHECK_LE(val1, val2) CHECK_LE(val1, val2)
#define DCHECK_LT(val1, val2) CHECK_LT(val1, val2)
#define DCHECK_GE(val1, val2) CHECK_GE(val1, val2)
#define DCHECK_GT(val1, val2) CHECK_GT(val1, val2)
#define DCHECK_NOTNULL(val) CHECK_NOTNULL(val)
#define DCHECK_STREQ(str1, str2) CHECK_STREQ(str1, str2)
#define DCHECK_STRCASEEQ(str1, str2) CHECK_STRCASEEQ(str1, str2)
#define DCHECK_STRNE(str1, str2) CHECK_STRNE(str1, str2)
#define DCHECK_STRCASENE(str1, str2) CHECK_STRCASENE(str1, str2)

#endif  // RIME_NO_LOGGING_H_
