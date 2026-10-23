#Requires -Version 7
<#
.SYNOPSIS
    Merge the yuyansdk git submodule into the monorepo (git subtree, full history).
.DESCRIPTION
    Runs all git operations on a NEW branch (default: merge-monorepo). master is never touched.
    Backs up gitignored build caches (native-build, prebuilt) and the submodule gitdir
    to a directory OUTSIDE the repo, then:
      deinit/remove submodule -> subtree add -> verify tree hash -> restore caches
          -> drop "submodules: true" from CI workflow.
    See docs/monorepo-merge-plan.md for the full plan.
.PARAMETER Branch
    Working branch name. Must not already exist.
.PARAMETER BackupRoot
    Backup directory. Default: <repo parent>\yuyansdk-monorepo-backup-<timestamp>
.EXAMPLE
    pwsh -File scripts/merge-monorepo.ps1
#>
param(
    [string]$Branch = 'merge-monorepo',
    [string]$BackupRoot = ''
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# ---------------------------------------------------------------- helpers
function Step([string]$msg)  { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }
function Ok([string]$msg)    { Write-Host "  [ok] $msg" -ForegroundColor Green }
function Info([string]$msg)  { Write-Host "  $msg" }
function Die([string]$msg)   { Write-Host "  [FAIL] $msg" -ForegroundColor Red; throw $msg }

function CopyTree([string]$src, [string]$dst) {
    if (-not (Test-Path $src)) { Die "source not found: $src" }
    # robocopy is far faster than Copy-Item for large trees
    robocopy $src $dst /E /NFL /NDL /NJH /NJS /NP /R:2 /W:2 | Out-Null
    if ($LASTEXITCODE -ge 8) { Die "robocopy failed ($LASTEXITCODE): $src -> $dst" }
}

# ---------------------------------------------------------------- locate repo
$Repo = Split-Path -Parent $PSScriptRoot
Set-Location $Repo
$Sdk = Join-Path $Repo 'yuyansdk'
Info "repo root : $Repo"
Info "submodule : $Sdk"

# ---------------------------------------------------------------- preflight
Step 'Preflight checks'

if (-not (Test-Path (Join-Path $Sdk '.git'))) { Die 'yuyansdk submodule not found' }

$dirtyMain = git status --porcelain --untracked-files=no
if ($dirtyMain) { Die "main repo has uncommitted changes:`n$dirtyMain" }
Ok 'main repo tracked tree is clean'

$dirtySub = git -C $Sdk status --porcelain --untracked-files=no
if ($dirtySub) { Die "yuyansdk has uncommitted changes:`n$dirtySub" }
Ok 'yuyansdk tracked tree is clean'

Info 'fetching yuyansdk origin to verify everything is pushed...'
git -C $Sdk fetch origin --quiet
if ($LASTEXITCODE -ne 0) { Die 'git fetch origin failed inside yuyansdk (check network / ssh)' }

$ahead = @(git -C $Sdk rev-list --count 'origin/master..master')
if ($LASTEXITCODE -ne 0) { Die 'cannot compare yuyansdk master with origin/master' }
if ([int]$ahead[0] -gt 0) {
    Die ("yuyansdk has {0} unpushed commit(s). Push charent/yuyansdk first - this is the only safety net for the history." -f [int]$ahead[0])
}
Ok 'yuyansdk master is fully pushed to origin'

$SubSha = (git -C $Sdk rev-parse HEAD).Trim()
$SubTree = (git -C $Sdk rev-parse 'master^{tree}').Trim()
Info "submodule HEAD : $SubSha"
Info "submodule tree : $SubTree"

# ---------------------------------------------------------------- branch
Step "Create working branch '$Branch'"
if (git rev-parse --verify --quiet $Branch) { Die "branch '$Branch' already exists (delete it or pass -Branch <other>)" }
git switch -c $Branch
if ($LASTEXITCODE -ne 0) { Die 'git switch -c failed' }
Ok "on branch $Branch"

# ---------------------------------------------------------------- backup
Step 'Backup build caches and submodule gitdir (outside the repo)'
if (-not $BackupRoot) {
    $BackupRoot = Join-Path (Split-Path -Parent $Repo) ("yuyansdk-monorepo-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
}
New-Item -ItemType Directory -Force -Path $BackupRoot | Out-Null
Info "backup dir : $BackupRoot"

$Cpp = Join-Path $Sdk 'src\main\cpp'
CopyTree (Join-Path $Cpp 'native-build') (Join-Path $BackupRoot 'native-build')
Ok 'native-build backed up (sources + build dirs)'
CopyTree (Join-Path $Cpp 'prebuilt') (Join-Path $BackupRoot 'prebuilt')
Ok 'prebuilt backed up (cross-compiled static libs)'

$SubGitBackup = Join-Path $BackupRoot 'yuyansdk.git'
CopyTree (Join-Path $Repo '.git\modules\yuyansdk') $SubGitBackup
Ok 'submodule gitdir backed up (subtree import source, no network needed)'

# sanity: the backup must resolve the same sha
$BackupSha = (git -C $SubGitBackup rev-parse HEAD).Trim()
if ($BackupSha -ne $SubSha) { Die "backup gitdir HEAD ($BackupSha) != submodule HEAD ($SubSha)" }
Ok "backup gitdir resolves $SubSha"

# ---------------------------------------------------------------- remove submodule
Step 'Remove submodule'
git submodule deinit -f yuyansdk
if ($LASTEXITCODE -ne 0) { Die 'git submodule deinit failed' }
Ok 'deinit done (worktree cleared - caches are safe in the backup)'

git rm -f yuyansdk
if ($LASTEXITCODE -ne 0) { Die 'git rm yuyansdk failed' }
git rm -f .gitmodules
if ($LASTEXITCODE -ne 0) { Die 'git rm .gitmodules failed' }

$modulesDir = Join-Path $Repo '.git\modules\yuyansdk'
if (Test-Path $modulesDir) { Remove-Item -Recurse -Force $modulesDir }
Ok 'gitlink, .gitmodules and .git/modules entry removed'

git commit -m 'chore: remove yuyansdk submodule (merging into monorepo via subtree)'
if ($LASTEXITCODE -ne 0) { Die 'commit of submodule removal failed' }
Ok 'submodule removal committed'

# ---------------------------------------------------------------- subtree add
Step 'Import yuyansdk history via git subtree (full history, path prefix yuyansdk/)'
$Src = ($SubGitBackup -replace '\\', '/')
git subtree add --prefix=yuyansdk $Src master -m 'chore: merge yuyansdk into monorepo (git subtree, full history)'
if ($LASTEXITCODE -ne 0) { Die 'git subtree add failed' }
Ok 'subtree import committed'

# ---------------------------------------------------------------- verify
Step 'Verify content integrity (tree object hash)'
$MergedTree = (git rev-parse 'HEAD:yuyansdk').Trim()
Info "expected tree : $SubTree"
Info "merged  tree  : $MergedTree"
if ($MergedTree -ne $SubTree) {
    Die 'TREE HASH MISMATCH - do NOT merge this branch. Inspect with: git log --graph -20'
}
Ok 'tree hashes match: imported yuyansdk/ is byte-identical to the submodule'

# ---------------------------------------------------------------- restore caches
Step 'Restore build caches into yuyansdk/src/main/cpp'
CopyTree (Join-Path $BackupRoot 'native-build') (Join-Path $Cpp 'native-build')
CopyTree (Join-Path $BackupRoot 'prebuilt') (Join-Path $Cpp 'prebuilt')
Ok 'native-build and prebuilt restored (gitignored, will not pollute the repo)'

# ---------------------------------------------------------------- CI workflow
Step 'Patch .github/workflows/build.yml (drop submodule checkout)'
$ymlPath = Join-Path $Repo '.github\workflows\build.yml'
if (Test-Path $ymlPath) {
    $yml = [IO.File]::ReadAllText($ymlPath)
    $patched = $yml -replace '(?m)^\s*submodules:\s*true\r?\n', ''
    $patched = $patched -replace '(?m)^\s*token:\s*\$\{\{\s*secrets\.GITHUB_TOKEN\s*\}\}\r?\n', ''
    if ($patched -ne $yml) {
        $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($ymlPath, $patched, $utf8NoBom)
        git add .github/workflows/build.yml
        git commit -m 'chore(ci): drop submodule checkout (monorepo)'
        Ok 'workflow patched and committed'
    } else {
        Info 'workflow had no submodule settings - nothing to patch'
    }
}

# ---------------------------------------------------------------- summary
Step 'DONE'
Write-Host @"

Merge branch '$Branch' is ready. Manual follow-ups:

  1. Verify (see docs/monorepo-merge-plan.md section 4.4):
       git log --oneline -- yuyansdk | Measure-Object -Line   # >= 22 commits
       .\gradlew.bat :app:assembleOfflineDebug
       # smoke test on emulator: adb logcat -s YuyanRime librime

  2. Update docs that still mention the submodule:
       README.md (~line 67), AGENTS.md (lines 7/13-17), docs/build-and-run.md (45/48/401-404)

  3. When satisfied:
       git switch master
       git merge --ff-only $Branch
       git push

  4. Archive (do NOT delete) https://github.com/charent/yuyansdk afterwards.

  5. Backup kept at: $BackupRoot
     Delete it only after arm64 device regression passes.

Rollback (any time before step 3):
       git switch master
       git branch -D $Branch
       git submodule update --init yuyansdk
       # then restore caches from the backup dir if needed
"@ -ForegroundColor Yellow
