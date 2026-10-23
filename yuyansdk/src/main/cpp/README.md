# 原生层构建说明（librime 1.17.0）

联想词、九宫格点选等原先在作者私有 fork 里的能力，这里全部用上游 1.17 的公开接口重建：

- **librime 源码树只改 1 处**：`src/rime/no_logging.h` 被构建脚本覆盖成 Android 版静默实现
  （`scripts/rime-no-logging-android.h`，只动日志宏，不碰输入逻辑）。这是显式登记的唯一补丁。
- **JNI 桥只依赖公开 C API**：`yuyan_jni.cpp` 只 include `rime_api.h`，不引用任何内部 C++ 类。
- **唯一仍绑内部 C++ 的地方**：`associate_rear.cpp`（联想词，上游没有 predict 接口），
  文件头列了升级时要复核的三处签名。

移植细节见 `docs/engine-upgrade-implementation.md`，方案取舍与迁移记录见
`docs/engine-stock-vs-custom.md`。

## 目录

```
cpp/
├── CMakeLists.txt          JNI 层构建（只有一个目标：libyuyanime.so）
├── src/
│   ├── yuyan_jni.cpp       16 个 JNI 方法，全部直通 rime_api.h
│   ├── associate_rear.cpp  联想词（Table + StringTable::Predict）——唯一内部依赖
│   ├── associate_rear.h
│   └── utf8.h              UTF-8 ↔ jstring（规避 NewStringUTF 的 4 字节坑）
├── scripts/
│   ├── fetch-sources.ps1      下载 6 个源码包
│   ├── parallel-download.ps1  HTTP Range 多线程下载
│   ├── build-deps.ps1         交叉编译依赖 + librime → prebuilt/<abi>
│   ├── build-engine.ps1       依赖 + JNI → libs/<abi>/libyuyanime.so
│   ├── rime-no-logging-android.h  补丁：覆盖到 librime 源码树
│   └── boost-regex/           boost 1.86 没有 CMake 工程，这里单独编 libboost_regex.a
├── prebuilt/<abi>/         依赖静态库与头文件（gitignore，由脚本生成）
└── native-build/           源码、构建目录、安装目录（gitignore）
```

## JNI 方法与 Kotlin 的分工

`yuyan_jni.cpp` 里只有两类东西：结构体编组（`RimeContext` / `RimeStatus` / 候选数组）
和 `rime_get_api()` 的直通。所有能表达成普通字符串/数组操作的逻辑都在 Kotlin：

| 能力 | Kotlin 位置 | 用到的公开 API |
|---|---|---|
| 组合串局部替换（九宫格点选 / 拼音点选） | `Rime.replaceKey` | `get_input` + `set_input` |
| 键名 → 键码 | `RimeKeycode` 常量 | —（X11 keysym） |
| 候选分页 / 全量列表 | `Rime.getCandidates` + `RimeEngine.appendNextPage` | `candidate_list_from_index` / `candidate_list_next` |

候选下标统一是**全局下标**（`candidate_list_*` 的顺序），可以直接喂给 `select_candidate`，
不再有「页内偏移」与「全局下标」两套语义。

## 一键构建

```powershell
# 首次：下载源码（boost 141 MB，走多线程）
pwsh -File scripts\fetch-sources.ps1

# 编译依赖 + JNI，并把 .so 覆盖到 yuyansdk/libs/<abi>/
pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a

# 四个 ABI
pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a,armeabi-v7a,x86,x86_64
```

`build-engine.ps1` 默认会先跑 `build-deps.ps1`；依赖已编好时加 `-SkipDeps`
（只改 `cpp/src/**` 时用这个，几秒钟出结果）。

## 依赖版本与关键参数

| 依赖 | 版本 | 说明 |
|---|---|---|
| librime | 1.17.0 | `BUILD_SHARED_LIBS=OFF` → `librime.a`；`ENABLE_LOGGING=OFF` 免 glog |
| marisa-trie | 0.3.1 | CMake 工程，`ENABLE_TOOLS=OFF` |
| yaml-cpp | 0.8.0 | 静态 |
| leveldb | 1.23 | 静态，关测试；snappy/crc32c 自动探测为「无」 |
| OpenCC | 1.1.9 | `USE_SYSTEM_MARISA=ON` 复用 0.3.1，避免重复符号 |
| boost | 1.86.0 | 只有 `boost::regex` 需要编译，其余 header-only |

踩过的坑（都已在脚本里处理）：

1. **boost 1.86 的 tarball 没有 CMakeLists**（官方 CMake 工程自 1.87 才有），
   改为用 `scripts/boost-regex` 直接编 `libs/regex/src/*.cpp`。
2. **CMake 3.31 已移除 FindBoost**，`find_package(Boost)` 只会失败；
   librime 里该调用非 REQUIRED，靠 `-I<boost>` + 最终链接 `libboost_regex.a` 兜底。
3. **librime 的 `FindYamlCpp` 用两次 `find_path` 判断 0.5 新 API**，
   交叉编译时必须把 `YamlCpp_LIBRARY` / `YamlCpp_INCLUDE_PATH` / `YamlCpp_NEW_API` 全部预设。
4. **OpenCC 的头是 `.hpp`**（不是 `.h`），且 `opencc_config.h` 在 build 目录里生成。
5. **`rime/key_event.h` 依赖 `<X11/keysym.h>`**，Android 没有；JNI 层只 include `rime_api.h`，
   不会碰到它（键码现在由 Kotlin 的 `RimeKeycode` 提供）。
6. **`$Args` 是 PowerShell 自动变量**，函数参数用这个名字会静默丢参数（早期构建全踩过）。
7. **`rime::make` 在 1.17 不存在**，构造 shared_ptr 用 `rime::New<T>`；
   `rime::Table` 的构造函数收 `rime::path` 而非 `std::string`。
8. **UTF-8 ↔ jstring 一律走 `utf8.h`**：`NewStringUTF` 期望 Modified UTF-8，
   4 字节序列（emoji、CJK 扩展 B）会截断。

## 与 Gradle 的关系

不接 `externalNativeBuild`：`libs/` 下同时存在手写等第三方 `.so`，
两条路径都会产出 `libyuyanime.so`，打包时冲突。
`build-engine.ps1` 直接把产物覆盖到 `libs/<abi>/`，Gradle 侧无需改动。
