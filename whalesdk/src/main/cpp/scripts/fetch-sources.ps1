# 下载 librime 1.17.0 及其全部第三方依赖源码
#
# 用法：pwsh -File scripts\fetch-sources.ps1
#
# 说明：
#   - 下载到 native-build\dl，解压到 native-build\src
#   - 已存在则跳过，可反复执行
#   - 走 TEMP 中转：部分环境下直接写工作区会被沙箱拦截

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# 代理：默认读 HTTPS_PROXY / HTTP_PROXY 环境变量（如 http://127.0.0.1:10808）
$Proxy = $env:HTTPS_PROXY; if (-not $Proxy) { $Proxy = $env:HTTP_PROXY }
$ProxyArgs = @{}; if ($Proxy) { $ProxyArgs = @{ Proxy = $Proxy }; Write-Host "[proxy] $Proxy" }

$Root = Join-Path (Split-Path -Parent $PSScriptRoot) 'native-build'   # ...\cpp\native-build
$Dl = Join-Path $Root 'dl'
$Src = Join-Path $Root 'src'
New-Item -ItemType Directory -Force -Path $Dl, $Src | Out-Null

# name -> @(url, archive, dir)
$Sources = @(
  @{ Name = 'librime';   Url = 'https://codeload.github.com/rime/librime/tar.gz/refs/tags/1.17.0';        Archive = 'librime-1.17.0.tar.gz';        Dir = 'librime-1.17.0' },
  @{ Name = 'marisa';    Url = 'https://codeload.github.com/s-yata/marisa-trie/tar.gz/refs/tags/v0.3.1';   Archive = 'marisa-trie-0.3.1.tar.gz';      Dir = 'marisa-trie-0.3.1' },
  @{ Name = 'yaml-cpp';  Url = 'https://codeload.github.com/jbeder/yaml-cpp/tar.gz/refs/tags/0.8.0';        Archive = 'yaml-cpp-0.8.0.tar.gz';         Dir = 'yaml-cpp-0.8.0' },
  @{ Name = 'leveldb';   Url = 'https://codeload.github.com/google/leveldb/tar.gz/refs/tags/1.23';          Archive = 'leveldb-1.23.tar.gz';           Dir = 'leveldb-1.23' },
  @{ Name = 'opencc';    Url = 'https://codeload.github.com/BYVoid/OpenCC/tar.gz/refs/tags/ver.1.1.9';      Archive = 'OpenCC-1.1.9.tar.gz';           Dir = 'OpenCC-1.1.9' },
  @{ Name = 'boost';     Url = 'https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.gz';       Archive = 'boost_1_86_0.tar.gz';           Dir = 'boost_1_86_0' }
)

foreach ($s in $Sources) {
  $archive = Join-Path $Dl $s.Archive
  $dir = Join-Path $Src $s.Dir

  if (Test-Path $dir) {
    Write-Host "[skip] $($s.Name) 已解压"
    continue
  }

  if (-not (Test-Path $archive)) {
    $tmp = Join-Path $env:TEMP $s.Archive
    Write-Host "[get ] $($s.Name) <- $($s.Url)"
    Invoke-WebRequest -Uri $s.Url -OutFile $tmp -TimeoutSec 600 @ProxyArgs
    [System.IO.File]::Copy($tmp, $archive, $true)
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
  } else {
    Write-Host "[have] $($s.Name) 压缩包已存在"
  }

  Write-Host "[unpack] $($s.Name)"
  # Git Bash 的 GNU tar 会把 "E:\..." 当成远程主机，必须用 Windows 自带的 bsdtar
  & "$env:SystemRoot\System32\tar.exe" -xzf $archive -C $Src
  if ($LASTEXITCODE -ne 0) { throw "解压失败: $archive" }
}

Write-Host ''
Write-Host '源码就绪：'
Get-ChildItem $Src -Directory | Select-Object -ExpandProperty Name
