# 语燕 IME 原生层（librime 1.17.0 + JNI）
#
# 默认不参与 Gradle 构建。开启方式见 README.md。
# 前置产物（每个 ABI 一份，由 scripts/build-deps.ps1 生成）：
#   prebuilt/<abi>/lib/{librime.a, libopencc.a, libyaml-cpp.a, libleveldb.a, libmarisa.a, libboost_regex.a}
#   prebuilt/<abi>/include/{rime_api.h, rime/*.h, rime/{algo,config,dict,gear,lever}/*.h, opencc/*.h, ...}

cmake_minimum_required(VERSION 3.22)
project(yuyanime CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(YUYAN_PREBUILT "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}")

if(NOT EXISTS "${YUYAN_PREBUILT}/lib/librime.a")
  message(FATAL_ERROR
    "找不到 ${YUYAN_PREBUILT}/lib/librime.a\n"
    "请先执行 scripts/build-deps.ps1 -Abi ${ANDROID_ABI} 生成依赖，"
    "或改用默认预编译库（不加 -PenableNativeEngine=true）。")
endif()

add_library(yuyanime SHARED
  src/yuyan_jni.cpp
  src/associate_rear.cpp
)

target_include_directories(yuyanime PRIVATE
  "${YUYAN_PREBUILT}/include"
  # boost 是 header-only（除 regex），与 ABI 无关，所有 ABI 共用一份
  "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/include"
)

# 静态链接 librime 及其依赖，产物仍为单个 libyuyanime.so。
# 用 --start-group 处理 librime 内部与 opencc↔marisa 的循环引用。
target_link_libraries(yuyanime
  -Wl,--start-group
  "${YUYAN_PREBUILT}/lib/librime.a"
  "${YUYAN_PREBUILT}/lib/libopencc.a"
  "${YUYAN_PREBUILT}/lib/libyaml-cpp.a"
  "${YUYAN_PREBUILT}/lib/libleveldb.a"
  "${YUYAN_PREBUILT}/lib/libmarisa.a"
  "${YUYAN_PREBUILT}/lib/libboost_regex.a"
  -Wl,--end-group
  android
  log
  z
)

# 只导出 JNI 入口，减小体积并避免符号冲突
target_compile_options(yuyanime PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden -O2)

# Android 15+ 要求 16 KB 页对齐；NDK r28 默认已是，显式声明防回归
target_link_options(yuyanime PRIVATE "-Wl,-z,max-page-size=16384")

target_link_options(yuyanime PRIVATE "-Wl,--exclude-libs,ALL")
