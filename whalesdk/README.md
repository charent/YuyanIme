# 小鲸鱼输入法 SDK（WhaleIME）

本模块是完整开源重建后的 IME 实现（基于 Rime 1.17.0 公开源码，fork 自上游输入法项目并全量改名），作为 Android library 被 `app` 壳工程引用。中文名「小鲸鱼输入法」，英文名「WhaleIME」。

## 模块结构

```
whalesdk/
├── build.gradle               # namespace com.whale.imemodule
├── libs/<abi>/                # 预编译 native 库（libwhaleime.so 等，由脚本构建覆盖）
├── src/main/assets/rime/      # Rime schema / 词典（~112 MB，随 APK 分发）
├── src/main/assets/hw/        # 手写引擎数据
├── src/main/cpp/              # 原生引擎源码（librime 1.17.0 + JNI 桥），见 cpp/README.md
└── src/main/java/com/whale/
    ├── imemodule/             # Android/UI 层：服务、键盘、设置、Room 数据库
    └── inputmethod/           # 引擎层：RimeEngine、HWEngine、CustomEngine、JNI 绑定
```

## 原生引擎（libwhaleime.so）构建流程与踩坑

引擎**不是闭源预编译库**，可从公开源码完整重建。完整技术说明见
[`src/main/cpp/README.md`](src/main/cpp/README.md)（架构、JNI 分工、依赖版本表），
这里记录实际操作流程和真实踩过的坑。

### 环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| Android SDK | 任意，路径写在 `local.properties` 的 `sdk.dir` | |
| NDK | **28.0.13004108**（路径写死在脚本里） | `$SDK/ndk/28.0.13004108` |
| CMake / Ninja | **3.31.6**（Android SDK 自带版本） | `$SDK/cmake/3.31.6/bin/` |
| PowerShell | pwsh 7+ | 脚本不兼容 Windows PowerShell 5 |
| 网络 | 能访问 GitHub / archives.boost.io | 支持代理，见下 |

### 一次完整构建

```powershell
# 首次（下载 6 个源码包 + 编译依赖 + 4 个 ABI）：
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'   # 按需设置代理
pwsh -File scripts\build-engine.ps1 -FetchSources -Abi arm64-v8a,armeabi-v7a,x86,x86_64

# 只改了 cpp/src/**（JNI 层）时的快速循环（~20s/ABI）：
pwsh -File scripts\build-engine.ps1 -Abi x86_64 -SkipDeps
```

产物直接覆盖到 `whalesdk/libs/<abi>/libwhaleime.so`，之后 `./gradlew :app:assembleOfflineDebug`
重新打包即可，Gradle 侧无需任何改动（**不接 externalNativeBuild**，因为 `libs/` 下还有
第三方 `.so`，两条构建路径会产出同名库导致打包冲突）。

源码包缓存在 `cpp/native-build/dl/`，解压在 `cpp/native-build/src/`，依赖静态库在
`cpp/prebuilt/<abi>/`（均已 gitignore）。重跑 `-FetchSources` 时已解压的包会跳过，
只有损坏/缺失的包会重新下载。

### 踩坑记录（2026-10 首次全量重建）

1. **GNU tar 把 Windows 盘符当远程主机**。pwsh 里直接调 `tar.exe` 在装有 Git Bash
   的机器上会解析到 Git Bash 的 GNU tar，报
   `tar (child): Cannot connect to E: resolve failed`——它把 `E:\...` 的 `E:` 当成
   `host:path`。已修复：`fetch-sources.ps1` 显式调用 `$env:SystemRoot\System32\tar.exe`
   （Windows 自带 bsdtar）。
2. **下载必须走代理**（国内网络环境）。直连 codeload.github.com / archives.boost.io
   很慢且容易下载不完整——曾出现 librime 压缩包只有 2.6 MB（完整约 2.6 MB 是正常的，
   但解压报 `unexpected end of file` 时先怀疑下载截断）。两个下载脚本现已支持
   `HTTPS_PROXY`/`HTTP_PROXY` 环境变量，自动透传给 `Invoke-WebRequest`。
   下载失败/损坏后，删掉 `native-build/dl/` 下对应压缩包重跑即可。
3. **部分下载的压缩包不会被自动重新下载**（脚本看到文件存在就跳过），排查解压失败时
   先用 `tar -t` 或 `file` 验证压缩包完整性。
4. **NDK/CMake 版本写死**：脚本里是 `ndk\28.0.13004108` 和 `cmake\3.31.6` 的绝对路径。
   升级 SDK 组件后要同步改 `cpp/scripts/build-engine.ps1`。
5. **依赖编译本身耗时较长**（librime + 5 个第三方库 × 4 个 ABI），但只在首次或升级
   librime 版本时需要；日常 JNI 改动一律用 `-SkipDeps`。
6. **librime 只改 1 处**：`src/rime/no_logging.h` 被替换为 Android 静默日志实现
   （`scripts/rime-no-logging-android.h`）。升级 librime 版本时需要重新套这个补丁，
   其余 JNI 层只依赖公开 `rime_api.h`；唯一绑内部 C++ 的是联想词
   `associate_rear.cpp`，升级时复核其文件头列出的三处签名。

### 引擎升级 checklist

1. 改 `fetch-sources.ps1` 里的版本号 / URL，删除 `native-build/dl`、`native-build/src`、
   `prebuilt/` 后重跑 `-FetchSources`；
2. 按新版本复核 `rime-no-logging-android.h` 补丁是否仍然适用、`associate_rear.cpp`
   的内部签名是否变化；
3. 跑完整构建（不带 `-SkipDeps`），确认 4 个 ABI 都产出并覆盖到 `libs/`；
4. 重新打包 APK，真机验证拼音/九宫格/联想词/手写。
