# 原生层构建说明（librime 1.17.0）

联想词、九宫格点选等原先在作者私有 fork 里的能力，这里全部用上游 1.17 的公开接口重建，
**librime 保持纯上游，补丁面为 0**。移植细节见 `docs/engine-upgrade-implementation.md`，
方案取舍见 `docs/engine-upgrade-plan.md`。

## 目录

```
cpp/
├── CMakeLists.txt          JNI 层构建（只有一个目标：libyuyanime.so）
├── src/
│   ├── yuyan_jni.cpp       16 个 JNI 方法 + ReplaceInput 复刻
│   ├── associate_rear.cpp  联想词（Table + StringTable::Predict）
│   ├── associate_rear.h
│   ├── rime_ext.h          私有扩展声明
│   └── utf8.h              UTF-8 ↔ jstring（规避 NewStringUTF 的 4 字节坑）
├── scripts/
│   ├── fetch-sources.ps1      下载 6 个源码包
│   ├── parallel-download.ps1  HTTP Range 多线程下载
│   ├── build-deps.ps1         交叉编译依赖 + librime → prebuilt/<abi>
│   ├── build-engine.ps1       依赖 + JNI → libs/<abi>/libyuyanime.so
│   └── boost-regex/           boost 1.86 没有 CMake 工程，这里单独编 libboost_regex.a
├── prebuilt/<abi>/         依赖静态库与头文件（gitignore，由脚本生成）
└── native-build/           源码、构建目录、安装目录（gitignore）
```

## 一键构建

```powershell
# 首次：下载源码（boost 141 MB，走多线程）
pwsh -File scripts\fetch-sources.ps1

# 编译依赖 + JNI，并把 .so 覆盖到 yuyansdk/libs/<abi>/
pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a

# 四个 ABI
pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a,armeabi-v7a,x86,x86_64
```

`build-engine.ps1` 默认会先跑 `build-deps.ps1`；依赖已编好时加 `-SkipDeps`。

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
5. **`rime/key_event.h` 依赖 `<X11/keysym.h>`**，Android 没有，JNI 层不要 include 它。
6. **`$Args` 是 PowerShell 自动变量**，函数参数用这个名字会静默丢参数（早期构建全踩过）。
7. **`rime::make` 在 1.17 不存在**，构造 shared_ptr 用 `rime::New<T>`；
   `rime::Table` 的构造函数收 `rime::path` 而非 `std::string`。

## 与 Gradle 的关系

不接 `externalNativeBuild`：`libs/` 下同时存在手写等第三方 `.so`，
两条路径都会产出 `libyuyanime.so`，打包时冲突。
`build-engine.ps1` 直接把产物覆盖到 `libs/<abi>/`，Gradle 侧无需改动。
