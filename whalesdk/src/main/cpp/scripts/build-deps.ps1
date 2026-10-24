# 交叉编译 librime 1.17.0 全部依赖（Android）
#
# 用法：
#   pwsh -File scripts\build-deps.ps1 -Abi arm64-v8a
#   pwsh -File scripts\build-deps.ps1 -Abi arm64-v8a -Clean
#   pwsh -File scripts\build-deps.ps1 -Abi armeabi-v7a,x86,x86_64
#
# 产物：native-build\install\<abi>\{lib,include}
# 依赖顺序：marisa -> yaml-cpp -> leveldb -> boost(regex) -> opencc -> librime

param(
  [string]$Abi = 'arm64-v8a',
  [int]$ApiLevel = 23,
  [switch]$Clean,
  [switch]$SkipBoost
)

$ErrorActionPreference = 'Stop'

$Root      = Split-Path -Parent $PSScriptRoot          # ...\cpp
$NativeRoot = Join-Path $Root 'native-build'
$SrcRoot   = Join-Path $NativeRoot 'src'
$BuildRoot = Join-Path $NativeRoot 'build'
$InstallRoot = Join-Path $NativeRoot 'install'

# local.properties 里的 sdk.dir 是 properties 转义格式（E\:\\Software\\Android\\SDK）
$localProps = Join-Path (Resolve-Path (Join-Path $Root '..\..\..\..')) 'local.properties'
$Sdk = $null
if (Test-Path $localProps) {
  $line = (Get-Content $localProps | Select-String '^\s*sdk\.dir').ToString()
  $Sdk = $line.Substring($line.IndexOf('=') + 1).Trim() -replace '\\:', ':' -replace '\\\\', '\'
}
if (-not $Sdk -or -not (Test-Path $Sdk)) {
  $Sdk = $env:ANDROID_SDK_ROOT
  if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
}
if (-not $Sdk -or -not (Test-Path $Sdk)) { throw '未能定位 Android SDK，请检查 local.properties 的 sdk.dir' }
Write-Host "SDK = $Sdk"
$Ndk = Join-Path $Sdk 'ndk\28.0.13004108'
$Cmake = Join-Path $Sdk 'cmake\3.31.6\bin\cmake.exe'
$Ninja = Join-Path $Sdk 'cmake\3.31.6\bin\ninja.exe'
$Toolchain = Join-Path $Ndk 'build\cmake\android.toolchain.cmake'

foreach ($p in @($Ndk, $Cmake, $Ninja, $Toolchain)) {
  if (-not (Test-Path $p)) { throw "缺少构建工具: $p" }
}

$Abis = $Abi -split ','

function Invoke-CMakeBuild {
  param(
    [string]$Name,
    [string]$SourceDir,
    [string]$BuildDir,
    [string]$InstallDir,
    # 注意：不能命名为 $Args —— 与 PowerShell 自动变量冲突，会导致参数被丢弃
    [string[]]$ConfigureArgs,
    [string[]]$Targets = @('install')
  )

  if ($Clean -and (Test-Path $BuildDir)) { Remove-Item $BuildDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

  # CMake 对反斜杠路径的转义处理不一致，统一转成正斜杠
  $posixInstall = $InstallDir -replace '\\', '/'

  $baseArgs = @(
    '-S', $SourceDir,
    '-B', $BuildDir,
    '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$Ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$Toolchain",
    "-DANDROID_ABI=$CurrentAbi",
    "-DANDROID_PLATFORM=android-$ApiLevel",
    '-DCMAKE_BUILD_TYPE=Release',
    "-DCMAKE_INSTALL_PREFIX=$posixInstall",
    '-DCMAKE_POSITION_INDEPENDENT_CODE=ON',
    '-DCMAKE_C_FLAGS=-O2 -fPIC',
    '-DCMAKE_CXX_FLAGS=-O2 -fPIC'
  ) + $ConfigureArgs

  Write-Host "`n=== [$CurrentAbi] $Name : configure ===" -ForegroundColor Cyan
  & $Cmake @baseArgs
  if ($LASTEXITCODE -ne 0) { throw "$Name configure 失败" }

  Write-Host "=== [$CurrentAbi] $Name : build ($($Targets -join ', ')) ===" -ForegroundColor Cyan
  & $Cmake --build $BuildDir --target @Targets --parallel
  if ($LASTEXITCODE -ne 0) { throw "$Name build 失败" }
}

foreach ($CurrentAbi in $Abis) {
  $install = Join-Path $InstallRoot $CurrentAbi
  $buildAbi = Join-Path $BuildRoot $CurrentAbi
  New-Item -ItemType Directory -Force -Path $install, $buildAbi | Out-Null

  # ---------------------------------------------------------------- marisa
  Invoke-CMakeBuild -Name 'marisa' `
    -SourceDir (Join-Path $SrcRoot 'marisa-trie-0.3.1') `
    -BuildDir (Join-Path $buildAbi 'marisa') `
    -InstallDir $install `
    -ConfigureArgs @('-DBUILD_SHARED_LIBS=OFF', '-DENABLE_TOOLS=OFF', '-DBUILD_TESTING=OFF')

  # ---------------------------------------------------------------- yaml-cpp
  Invoke-CMakeBuild -Name 'yaml-cpp' `
    -SourceDir (Join-Path $SrcRoot 'yaml-cpp-0.8.0') `
    -BuildDir (Join-Path $buildAbi 'yaml-cpp') `
    -InstallDir $install `
    -ConfigureArgs @(
      '-DBUILD_SHARED_LIBS=OFF',
      '-DYAML_CPP_BUILD_TESTS=OFF',
      '-DYAML_CPP_BUILD_TOOLS=OFF',
      '-DYAML_CPP_BUILD_CONTRIB=OFF',
      '-DYAML_CPP_INSTALL=ON'
    )

  # ---------------------------------------------------------------- leveldb
  Invoke-CMakeBuild -Name 'leveldb' `
    -SourceDir (Join-Path $SrcRoot 'leveldb-1.23') `
    -BuildDir (Join-Path $buildAbi 'leveldb') `
    -InstallDir $install `
    -ConfigureArgs @(
      '-DBUILD_SHARED_LIBS=OFF',
      '-DLEVELDB_BUILD_TESTS=OFF',
      '-DLEVELDB_BUILD_BENCHMARKS=OFF',
      '-DLEVELDB_INSTALL=ON'
    )

  # ---------------------------------------------------------------- boost (仅 regex 需要编译)
  # Boost 1.86 的 tarball 没有 CMakeLists（官方 CMake 工程自 1.87 才有），
  # 这里用 scripts\boost-regex 里的独立工程只编 libboost_regex.a。
  $boostSrc = (Join-Path $SrcRoot 'boost_1_86_0') -replace '\\', '/'
  if (-not $SkipBoost) {
    Invoke-CMakeBuild -Name 'boost.regex' `
      -SourceDir (Join-Path $PSScriptRoot 'boost-regex') `
      -BuildDir (Join-Path $buildAbi 'boost-regex') `
      -InstallDir $install `
      -ConfigureArgs @("-DBOOST_SRC=$boostSrc")
  }
  # 头文件不入 prebuilt：仅在编 librime 时引用源码树，避免每个 ABI 复制一份 ~150 MB 头
  $boostInclude = $boostSrc

  # ---------------------------------------------------------------- opencc
  # 用系统 marisa（0.3.1）避免与内置 0.2.6 产生重复符号；
  # USE_SYSTEM_MARISA 分支只 find_library 不加 include，需自己补头路径
  $posixInstall2 = $install -replace '\\', '/'
  Invoke-CMakeBuild -Name 'opencc' `
    -SourceDir (Join-Path $SrcRoot 'OpenCC-ver.1.1.9') `
    -BuildDir (Join-Path $buildAbi 'opencc') `
    -InstallDir $install `
    -ConfigureArgs @(
      '-DBUILD_SHARED_LIBS=OFF',
      '-DENABLE_GTEST=OFF',
      '-DENABLE_BENCHMARK=OFF',
      '-DBUILD_DOCUMENTATION=OFF',
      '-DUSE_SYSTEM_MARISA=ON',
      # 交叉编译下 find_library 不一定走 CMAKE_LIBRARY_PATH，直接给结果
      "-DLIBMARISA=$((Join-Path $install 'lib\libmarisa.a') -replace '\\', '/')",
      "-DCMAKE_PREFIX_PATH=$posixInstall2",
      "-DCMAKE_LIBRARY_PATH=$posixInstall2/lib",
      "-DCMAKE_INCLUDE_PATH=$posixInstall2/include",
      '-DCMAKE_CXX_STANDARD=17',
      "-DCMAKE_CXX_FLAGS=-O2 -fPIC -I$posixInstall2/include"
    ) `
    -Targets @('libopencc')

  # OpenCC 的 install 目标会顺带构建 data/ 下的词典（需求 Python 与大量时间），
  # 这里只取库与头文件 —— 运行期数据由 assets/rime/opencc 提供。
  $openccLib = Get-ChildItem (Join-Path $buildAbi 'opencc') -Recurse -Filter 'libopencc.a' -ErrorAction SilentlyContinue | Select-Object -First 1
  if (-not $openccLib) { throw 'opencc 静态库未生成' }
  New-Item -ItemType Directory -Force -Path (Join-Path $install 'lib') | Out-Null
  Copy-Item $openccLib.FullName (Join-Path $install 'lib\libopencc.a') -Force
  New-Item -ItemType Directory -Force -Path (Join-Path $install 'include\opencc') | Out-Null
  Copy-Item (Join-Path $SrcRoot 'OpenCC-ver.1.1.9\src\*.hpp') (Join-Path $install 'include\opencc') -Force
  Copy-Item (Join-Path $SrcRoot 'OpenCC-ver.1.1.9\src\*.h') (Join-Path $install 'include\opencc') -Force
  # opencc_config.h 是 configure 时生成的（源码树里那个是空模板）
  $openccCfg = Get-ChildItem (Join-Path $buildAbi 'opencc') -Recurse -Filter 'opencc_config.h' -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($openccCfg) {
    Copy-Item $openccCfg.FullName (Join-Path $install 'include\opencc\opencc_config.h') -Force
  }

  # ---------------------------------------------------------------- librime
  # 这里不用 Invoke-CMakeBuild：librime 的 Find*.cmake 依赖较多预设变量，
  # 显式列出来更容易排查，也避免参数在函数边界上被改写。
  $posixInstall3 = $install -replace '\\', '/'
  $rimeBuild = Join-Path $buildAbi 'librime'
  New-Item -ItemType Directory -Force -Path $rimeBuild | Out-Null

  # ENABLE_LOGGING=OFF 时 librime 用 no_logging.h，上游实现是全部编译成空语句，
  # Android 上等于完全静默。换成写 logcat 的版本（tag: librime），便于排查部署/加载问题。
  # 注意：librime 的 include 顺序里 install/include 优先于源码树，
  # 所以上一轮 install 出来的那份也要覆盖，否则改源码树不生效。
  $androidNoLogging = Join-Path $PSScriptRoot 'rime-no-logging-android.h'
  Copy-Item $androidNoLogging `
    (Join-Path $SrcRoot 'librime-1.17.0\src\rime\no_logging.h') -Force
  $installedNoLogging = Join-Path $install 'include\rime\no_logging.h'
  if (Test-Path $installedNoLogging) {
    Copy-Item $androidNoLogging $installedNoLogging -Force
  }

  Write-Host "`n=== [$CurrentAbi] librime : configure ===" -ForegroundColor Cyan
  & $Cmake -S (Join-Path $SrcRoot 'librime-1.17.0') -B $rimeBuild -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$Ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$Toolchain" `
    "-DANDROID_ABI=$CurrentAbi" `
    "-DANDROID_PLATFORM=android-$ApiLevel" `
    '-DCMAKE_BUILD_TYPE=Release' `
    "-DCMAKE_INSTALL_PREFIX=$posixInstall3" `
    '-DBUILD_SHARED_LIBS=OFF' `
    '-DBUILD_STATIC=ON' `
    '-DBUILD_TEST=OFF' `
    '-DBUILD_SAMPLE=OFF' `
    '-DBUILD_DATA=OFF' `
    '-DBUILD_SEPARATE_LIBS=OFF' `
    '-DENABLE_LOGGING=OFF' `
    '-DENABLE_EXTERNAL_PLUGINS=OFF' `
    '-DENABLE_THREADING=ON' `
    '-DINSTALL_PRIVATE_HEADERS=ON' `
    "-DCMAKE_PREFIX_PATH=$posixInstall3" `
    "-DMarisa_LIBRARY=$posixInstall3/lib/libmarisa.a" `
    "-DMarisa_INCLUDE_PATH=$posixInstall3/include" `
    "-DYamlCpp_LIBRARY=$posixInstall3/lib/libyaml-cpp.a" `
    "-DYamlCpp_INCLUDE_PATH=$posixInstall3/include" `
    "-DYamlCpp_NEW_API=$posixInstall3/include" `
    "-DLevelDb_LIBRARY=$posixInstall3/lib/libleveldb.a" `
    "-DLevelDb_INCLUDE_PATH=$posixInstall3/include" `
    "-DOpencc_LIBRARY=$posixInstall3/lib/libopencc.a" `
    "-DOpencc_INCLUDE_PATH=$posixInstall3/include" `
    "-DBOOST_INCLUDEDIR=$boostInclude" `
    "-DBOOST_LIBRARYDIR=$posixInstall3/lib" `
    "-DCMAKE_CXX_FLAGS=-O2 -fPIC -I$boostInclude"
  if ($LASTEXITCODE -ne 0) { throw 'librime configure 失败' }

  Write-Host "=== [$CurrentAbi] librime : build+install ===" -ForegroundColor Cyan
  & $Cmake --build $rimeBuild --target install --parallel
  if ($LASTEXITCODE -ne 0) { throw 'librime build 失败' }

  Write-Host "`n[$CurrentAbi] 完成：$install" -ForegroundColor Green
  Get-ChildItem (Join-Path $install 'lib') -Filter '*.a' -ErrorAction SilentlyContinue |
    Select-Object Name, Length | Format-Table -AutoSize

  # ---------------------------------------------------------------- 汇总到 prebuilt
  # CMakeLists.txt 的输入目录：prebuilt/<abi>/{lib,include}
  $prebuiltAbi = Join-Path $Root "prebuilt\$CurrentAbi"
  New-Item -ItemType Directory -Force -Path "$prebuiltAbi\lib", "$prebuiltAbi\include" | Out-Null
  Copy-Item "$install\lib\*.a" "$prebuiltAbi\lib" -Force
  Copy-Item "$install\include\*" "$prebuiltAbi\include" -Recurse -Force
  Write-Host "[$CurrentAbi] prebuilt 已更新：$prebuiltAbi" -ForegroundColor Green

  # boost 是 header-only，与 ABI 无关，所有 ABI 共用 prebuilt/include/boost
  $sharedHeaders = Join-Path $Root 'prebuilt\include'
  if (-not (Test-Path "$sharedHeaders\boost\version.hpp")) {
    New-Item -ItemType Directory -Force -Path $sharedHeaders | Out-Null
    Write-Host '安装 boost 头文件（约 140 MB，仅一次）...'
    robocopy (Join-Path $boostSrc 'boost') "$sharedHeaders\boost" /E /MT:16 /NFL /NDL /NJH /NJS /NP | Out-Null
  }
}
