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

$SubmodulePresent = Test-Path (Join-Path $Sdk '.git')
$SubTree = $null
if ($SubmodulePresent) {
    # normal mode: submodule worktree exists
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
} else {
    # resume mode: worktree was removed earlier/manually, gitlink must still be in index
    $gitlink = @(git ls-files -s yuyansdk | Select-String '^160000')
    if ($gitlink.Count -eq 0) { Die 'yuyansdk worktree absent and gitlink not in index - nothing to resume; restore with: git submodule update --init yuyansdk' }
    $SubSha = (($gitlink[0].ToString()) -split '\s+')[1].Trim()
    Info 'yuyansdk worktree absent but gitlink present - RESUME MODE'

    # push check without a worktree: compare gitlink sha with the remote branch head
    $subUrl = (git config -f .gitmodules submodule.yuyansdk.url).Trim()
    $remoteHead = (git ls-remote $subUrl refs/heads/master)
    if ($LASTEXITCODE -ne 0 -or -not $remoteHead) { Die "cannot check remote state of $subUrl" }
    $remoteSha = ($remoteHead -split '\s+')[0].Trim()
    if ($remoteSha -ne $SubSha) {
        Die "remote master ($remoteSha) != gitlink ($SubSha) - push charent/yuyansdk first"
    }
    Ok 'remote master matches gitlink sha - history is safe on origin'
}
Info "submodule HEAD : $SubSha"
if ($SubTree) { Info "submodule tree : $SubTree" }

# main repo must be clean, except the not-yet-staged yuyansdk deletion in resume mode
$dirtyMain = @(git status --porcelain --untracked-files=no | Where-Object { $_ -notmatch '^\s*[D]+\s+yuyansdk\s*$' })
if ($dirtyMain.Count -gt 0) { Die ("main repo has uncommitted changes:`n" + ($dirtyMain -join "`n")) }
Ok 'main repo tracked tree is clean'

# ---------------------------------------------------------------- branch
Step "Create working branch '$Branch'"
if (git rev-parse --verify --quiet $Branch) {
    $aheadCount = @(git rev-list --count "master..$Branch" 2>$null)
    if ($LASTEXITCODE -eq 0 -and $aheadCount.Count -gt 0 -and [int]$aheadCount[0] -eq 0) {
        Info "branch '$Branch' exists but has no commits (interrupted earlier run) - recreating"
        git switch master
        if ($LASTEXITCODE -ne 0) { Die 'git switch master failed' }
        git branch -D $Branch
        if ($LASTEXITCODE -ne 0) { Die "cannot delete stale branch '$Branch'" }
    } else {
        Die "branch '$Branch' already exists with commits - delete it or pass -Branch <other>"
    }
}
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
$cachePairs = @(
    @{ Src = Join-Path $Cpp 'native-build'; Dst = Join-Path $BackupRoot 'native-build'; Label = 'native-build (sources + build dirs)' },
    @{ Src = Join-Path $Cpp 'prebuilt';      Dst = Join-Path $BackupRoot 'prebuilt';      Label = 'prebuilt (cross-compiled static libs)' }
)
foreach ($c in $cachePairs) {
    if (Test-Path $c.Src) {
        CopyTree $c.Src $c.Dst
        Ok ("backed up " + $c.Label)
    } elseif (Test-Path $c.Dst) {
        Ok ((($c.Label) + ' already in backup (source absent from an earlier run) - reusing backup'))
    } else {
        Die ($c.Label + ' missing both in repo and backup - cannot proceed safely')
    }
}

$SubGitBackup = Join-Path $BackupRoot 'yuyansdk.git'
CopyTree (Join-Path $Repo '.git\modules\yuyansdk') $SubGitBackup
Ok 'submodule gitdir backed up (subtree import source, no network needed)'

# The copied gitdir still carries core.worktree = ../../../yuyansdk (a RELATIVE path).
# From the backup location that target does not exist and every git call dies with
# "fatal: cannot chdir". Turn the backup into a proper bare repo instead.
$backupConfig = Join-Path $SubGitBackup 'config'
$cfg = [IO.File]::ReadAllText($backupConfig)
$cfg = $cfg -replace '(?m)^\s*worktree\s*=.*\r?\n', ''
$cfg = $cfg -replace '(?m)^(\s*bare\s*=\s*)false', '$1true'
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText($backupConfig, $cfg, $utf8NoBom)
Ok 'backup config: core.worktree stripped, bare = true'

# sanity: the backup must resolve the same sha
$BackupSha = (git -C $SubGitBackup rev-parse HEAD).Trim()
if ($BackupSha -ne $SubSha) { Die "backup gitdir HEAD ($BackupSha) != submodule HEAD ($SubSha)" }
Ok "backup gitdir resolves $SubSha"

if (-not $SubTree) {
    $SubTree = (git -C $SubGitBackup rev-parse 'master^{tree}').Trim()
    Info "submodule tree : $SubTree (resolved from backup gitdir)"
}

# ---------------------------------------------------------------- remove submodule
Step 'Remove submodule'
if (Test-Path $Sdk) {
    git submodule deinit -f yuyansdk
    if ($LASTEXITCODE -ne 0) {
        Info 'deinit reported an error (possibly already unregistered) - continuing'
    }
    if (Test-Path $Sdk) {
        # hard-delete leftovers: robocopy empty-mirror is the fastest way on Windows
        Info 'worktree still present - force-removing (may take a while on HDD)'
        New-Item -ItemType Directory -Force -Path "$env:TEMP\empty-for-delete" | Out-Null
        robocopy "$env:TEMP\empty-for-delete" $Sdk /MIR /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
        Remove-Item -Recurse -Force $Sdk -ErrorAction SilentlyContinue
        if (Test-Path $Sdk) { Die 'could not remove yuyansdk worktree (files locked by another process?) - close IDEs and re-run' }
        Ok 'worktree force-removed'
    } else {
        Ok 'deinit done (worktree cleared - caches are safe in the backup)'
    }
} else {
    Info 'yuyansdk worktree already absent - skipping deinit'
}

git rm -f yuyansdk
if ($LASTEXITCODE -ne 0) {
    Info 'git rm yuyansdk failed (missing worktree?) - trying index-only removal'
    git rm -f --cached yuyansdk
    if ($LASTEXITCODE -ne 0) { Die 'git rm yuyansdk failed' }
}
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
