#Requires -Version 7
<#
.SYNOPSIS
    Root-level entry point for building the native Rime engine (libyuyanime.so).
.DESCRIPTION
    Forwards to yuyansdk\src\main\cpp\scripts\build-engine.ps1, so the engine can be
    built from the repo root after the monorepo merge. The output .so files are
    written directly into yuyansdk\libs\<abi>\ and picked up by Gradle automatically.

    First run on a clean checkout:  pwsh -File scripts\build-engine.ps1 -FetchSources -Abi arm64-v8a,armeabi-v7a,x86,x86_64
    JNI-only fast loop (~20s):      pwsh -File scripts\build-engine.ps1 -Abi x86_64 -SkipDeps

    Environment: NDK 28.0.13004108, CMake/Ninja 3.31.6 (under the Android SDK dir),
    SDK location resolved from local.properties / ANDROID_SDK_ROOT / ANDROID_HOME.
    Full reference: yuyansdk\src\main\cpp\README.md and docs\engine-upgrade-implementation.md
#>
param(
    # Target ABIs, comma-separated
    [string]$Abi = 'arm64-v8a',
    # Android API level (minSdk)
    [int]$ApiLevel = 23,
    # Skip dependency build (librime + third-party static libs already in prebuilt\)
    [switch]$SkipDeps,
    # Run fetch-sources.ps1 first (downloads 6 source tarballs, boost is 141 MB)
    [switch]$FetchSources
)

$ErrorActionPreference = 'Stop'

$EngineScripts = Join-Path (Split-Path -Parent $PSScriptRoot) 'yuyansdk\src\main\cpp\scripts'
$BuildEngine = Join-Path $EngineScripts 'build-engine.ps1'
if (-not (Test-Path $BuildEngine)) {
    throw "engine build script not found: $BuildEngine"
}

if ($FetchSources) {
    $Fetch = Join-Path $EngineScripts 'fetch-sources.ps1'
    if (-not (Test-Path $Fetch)) { throw "fetch script not found: $Fetch" }
    Write-Host '=== fetching engine sources ===' -ForegroundColor Cyan
    & pwsh -NoProfile -File $Fetch
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$engineArgs = @('-NoProfile', '-File', $BuildEngine, '-Abi', $Abi, '-ApiLevel', $ApiLevel)
if ($SkipDeps) { $engineArgs += '-SkipDeps' }
& pwsh @engineArgs
exit $LASTEXITCODE
