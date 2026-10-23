# 多线程分段下载（HTTP Range）
#
# 用法：
#   pwsh -File scripts\parallel-download.ps1 -Url <url> -OutFile <path> [-Threads 16]
#   或作为函数复用：. scripts\parallel-download.ps1 -AsFunction

param(
  [string]$Url,
  [string]$OutFile,
  [int]$Threads = 16,
  [switch]$AsFunction
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Invoke-ParallelDownload {
  param(
    [Parameter(Mandatory)][string]$Url,
    [Parameter(Mandatory)][string]$OutFile,
    [int]$Threads = 16
  )

  $head = Invoke-WebRequest -Uri $Url -Method Head -TimeoutSec 60
  $total = [long]$head.Headers['Content-Length'][0]
  $ranges = $head.Headers['Accept-Ranges']
  if ($total -le 0) { throw "无法获取 Content-Length: $Url" }

  $outDir = Split-Path -Parent $OutFile
  if ($outDir) { New-Item -ItemType Directory -Force -Path $outDir | Out-Null }

  # 不支持 Range 时退回单线程
  if (-not ($ranges -and $ranges[0] -eq 'bytes') -or $total -lt 4MB) {
    Write-Host "[1 thread] 服务器不支持分段，或文件过小"
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -TimeoutSec 3600
    return
  }

  $chunk = [math]::Ceiling($total / $Threads)
  $partDir = Join-Path ([System.IO.Path]::GetDirectoryName($OutFile)) ('.parts_' + [System.IO.Path]::GetFileName($OutFile))
  if (Test-Path $partDir) { Remove-Item $partDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $partDir | Out-Null

  $sw = [System.Diagnostics.Stopwatch]::StartNew()
  Write-Host "[$Threads threads] $([math]::Round($total/1MB,1)) MB, 每段 $([math]::Round($chunk/1MB,1)) MB"

  0..($Threads - 1) | ForEach-Object -Parallel {
    $i = $_
    $start = [long]$i * [long]$using:chunk
    $end = [math]::Min($start + [long]$using:chunk - 1, [long]$using:total - 1)
    if ($start -gt $end) { return }
    $part = Join-Path $using:partDir ('part_{0:D2}' -f $i)
    $ok = $false
    for ($try = 1; $try -le 3 -and -not $ok; $try++) {
      try {
        Invoke-WebRequest -Uri $using:Url -Headers @{ Range = "bytes=$start-$end" } `
          -OutFile $part -TimeoutSec 900
        $ok = $true
      } catch {
        if ($try -eq 3) { throw "分段 $i 下载失败: $($_.Exception.Message)" }
        Start-Sleep -Seconds 2
      }
    }
  } -ThrottleLimit $Threads

  # 按序拼接
  $fs = [System.IO.File]::Create($OutFile)
  try {
    for ($i = 0; $i -lt $Threads; $i++) {
      $part = Join-Path $partDir ('part_{0:D2}' -f $i)
      if (-not (Test-Path $part)) { continue }
      $bytes = [System.IO.File]::ReadAllBytes($part)
      $fs.Write($bytes, 0, $bytes.Length)
    }
  } finally { $fs.Close() }

  $sw.Stop()
  $got = (Get-Item $OutFile).Length
  Remove-Item $partDir -Recurse -Force

  if ($got -ne $total) { throw "文件长度不符：期望 $total，实际 $got" }
  Write-Host ("[done] {0} MB in {1} s ({2} KB/s)" -f `
    [math]::Round($got/1MB,1), [math]::Round($sw.Elapsed.TotalSeconds,1), `
    [math]::Round($got/1KB/$sw.Elapsed.TotalSeconds,0))
}

if (-not $AsFunction) {
  if (-not $Url -or -not $OutFile) { throw '需要 -Url 与 -OutFile' }
  Invoke-ParallelDownload -Url $Url -OutFile $OutFile -Threads $Threads
}
