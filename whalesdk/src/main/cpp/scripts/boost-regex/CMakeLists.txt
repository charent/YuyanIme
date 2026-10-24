# 独立编译 boost.regex 静态库（Boost 1.86 的 tarball 不含 CMake 工程）
#
# 由 build-deps.ps1 传入 BOOST_SRC（boost 源码根目录）。
# librime 只在 algo/calculus 与 algo/encoder 用到 boost::regex，其余 boost 组件均为 header-only，
# 因此这里只需要编出 libboost_regex.a，头文件由 librime 直接引用源码树。

cmake_minimum_required(VERSION 3.22)
project(boost_regex CXX)

if(NOT BOOST_SRC)
  message(FATAL_ERROR "需要 -DBOOST_SRC=<boost 源码根目录>")
endif()

file(GLOB BOOST_REGEX_SOURCES "${BOOST_SRC}/libs/regex/src/*.cpp")

# 排除依赖 ICU 与仅 Windows 可用的实现
list(FILTER BOOST_REGEX_SOURCES EXCLUDE REGEX "icu\\.cpp$")
list(FILTER BOOST_REGEX_SOURCES EXCLUDE REGEX "win32.*\\.cpp$")
list(FILTER BOOST_REGEX_SOURCES EXCLUDE REGEX "w32.*\\.cpp$")

list(LENGTH BOOST_REGEX_SOURCES BOOST_REGEX_COUNT)
message(STATUS "boost.regex 源文件数: ${BOOST_REGEX_COUNT}")
foreach(f ${BOOST_REGEX_SOURCES})
  get_filename_component(n "${f}" NAME)
  message(STATUS "  - ${n}")
endforeach()

add_library(boost_regex STATIC ${BOOST_REGEX_SOURCES})

target_include_directories(boost_regex PUBLIC "${BOOST_SRC}")
target_compile_definitions(boost_regex PUBLIC BOOST_ALL_NO_LIB)
set_target_properties(boost_regex PROPERTIES
  POSITION_INDEPENDENT_CODE ON
  OUTPUT_NAME "boost_regex")

install(TARGETS boost_regex ARCHIVE DESTINATION lib)
