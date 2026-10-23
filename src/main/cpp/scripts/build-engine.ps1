# 一键构建原生引擎：依赖 → librime → JNI → 覆盖 libs/<abi>/libyuyanime.so
#
# 用法：
#   pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a
#   pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a,armeabi-v7a,x86,x86_64
#   pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a -SkipDeps     # 依赖已编好时
#
# 说明：
#   - 首次执行会先跑 build-deps.ps1（需源码，见 fetch-sources.ps1），耗时较长
#   - 产物直接覆盖 yuyansdk/libs/<abi>/libyuyanime.so，Gradle 侧无需改动
#   - 同时保留未 strip 的版本在 native-build/build/<abi>/jni/

param(
  [string]$Abi = 'arm64-v8a',
  [int]$ApiLevel = 23,
  [switch]$SkipDeps
)

$ErrorActionPreference = 'Stop'

$CppRoot = Split-Path -Parent $PSScriptRoot          # ...\cpp
$YuyanSdk = Resolve-Path (Join-Path $CppRoot '..\..\..')   # ...\yuyansdk
$NativeRoot = Join-Path $CppRoot 'native-build'

$localProps = Join-Path (Resolve-Path (Join-Path $CppRoot '..\..\..\..')) 'local.properties'
$Sdk = $null
if (Test-Path $localProps) {
  $line = (Get-Content $localProps | Select-String '^\s*sdk\.dir').ToString()
  $Sdk = $line.Substring($line.IndexOf('=') + 1).Trim() -replace '\\:', ':' -replace '\\\\', '\'
}
if (-not $Sdk -or -not (Test-Path $Sdk)) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk -or -not (Test-Path $Sdk)) { throw '未找到 Android SDK' }

$Ndk = Join-Path $Sdk 'ndk\28.0.13004108'
$Cmake = Join-Path $Sdk 'cmake\3.31.6\bin\cmake.exe'
$Ninja = Join-Path $Sdk 'cmake\3.31.6\bin\ninja.exe'
$Strip = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
$Toolchain = Join-Path $Ndk 'build\cmake\android.toolchain.cmake'

$Abis = $Abi -split ','

if (-not $SkipDeps) {
  Write-Host "=== 构建依赖（$($Abis -join ', ')）===" -ForegroundColor Cyan
  & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'build-deps.ps1') -Abi $Abi -ApiLevel $ApiLevel
  if ($LASTEXITCODE -ne 0) { throw '依赖构建失败' }
}

foreach ($a in $Abis) {
  $buildDir = Join-Path $NativeRoot "build\$a\jni"
  New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

  Write-Host "`n=== [$a] 编译 JNI ===" -ForegroundColor Cyan
  & $Cmake -S $CppRoot -B $buildDir -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$Ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$Toolchain" `
    "-DANDROID_ABI=$a" `
    "-DANDROID_PLATFORM=android-$ApiLevel" `
    '-DANDROID_STL=c++_static' `
    '-DCMAKE_BUILD_TYPE=Release'
  if ($LASTEXITCODE -ne 0) { throw "[$a] configure 失败" }

  & $Cmake --build $buildDir --parallel
  if ($LASTEXITCODE -ne 0) { throw "[$a] 编译失败" }

  $built = Join-Path $buildDir 'libyuyanime.so'
  if (-not (Test-Path $built)) { throw "[$a] 未生成 libyuyanime.so" }

  $dest = Join-Path $YuyanSdk "libs\$a"
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  $stripped = Join-Path $buildDir 'libyuyanime.stripped.so'
  Copy-Item $built $stripped -Force
  & $Strip --strip-unneeded $stripped

  Copy-Item $stripped (Join-Path $dest 'libyuyanime.so') -Force
  Write-Host ("[{0}] -> {1}  {2} MB（strip 后）" -f $a, (Join-Path $dest 'libyuyanime.so'), [math]::Round((Get-Item $stripped).Length/1MB,2)) -ForegroundColor Green
}

Write-Host "`n完成。重新打包 APK 即可生效。" -ForegroundColor Green
