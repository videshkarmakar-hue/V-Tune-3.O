<#
.SYNOPSIS
    V-Tune 3.O auto-push agent.

.DESCRIPTION
    Watches the repository for working-tree changes and unpushed commits.
    After edits settle, it stages everything (respecting .gitignore),
    commits with a timestamp, rebases onto the remote, and pushes.

    Run it in the background:
        scripts\start-auto-push.cmd

    Stop it:
        scripts\stop-auto-push.cmd

.PARAMETER Once
    Run a single check/sync and exit (useful for testing).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\auto-push.ps1 -Once
#>
[CmdletBinding()]
param(
    [string]$RepoPath = (Split-Path -Parent $PSScriptRoot),
    [string]$Remote = 'origin',
    [string]$Branch = 'main',
    [int]$IntervalSeconds = 15,
    [int]$QuietChecks = 2,
    [string]$CommitPrefix = 'auto',
    [switch]$Once
)

$ErrorActionPreference = 'Continue'

# Keep git from waiting for input in a background window.
$env:GIT_TERMINAL_PROMPT = '0'
$env:GCM_INTERACTIVE = 'never'

if (-not (Test-Path -LiteralPath (Join-Path $RepoPath '.git'))) {
    throw "Not a git repository: $RepoPath"
}
$RepoPath = (Resolve-Path -LiteralPath $RepoPath).Path

$stateDir = Join-Path $RepoPath '.auto-push'
if (-not (Test-Path -LiteralPath $stateDir)) {
    New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
}
$logFile  = Join-Path $stateDir 'auto-push.log'
$pidFile  = Join-Path $stateDir 'auto-push.pid'
$lockFile = Join-Path $stateDir 'auto-push.lock'

function Write-Log {
    param([string]$Message, [string]$Level = 'INFO')
    $line = '{0} [{1}] {2}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Level, $Message
    try { Add-Content -LiteralPath $logFile -Value $line -Encoding UTF8 } catch { }
    Write-Host $line

    # Keep the log small.
    try {
        $fi = Get-Item -LiteralPath $logFile -ErrorAction SilentlyContinue
        if ($fi -and $fi.Length -gt 2MB) {
            $old = Join-Path $stateDir 'auto-push.old.log'
            Move-Item -LiteralPath $logFile -Destination $old -Force
        }
    } catch { }
}

function Invoke-Git {
    param([string[]]$GitArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out  = & git -C $RepoPath @GitArgs 2>&1
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    $text = ($out | Out-String).Trim()
    return [pscustomobject]@{ Code = $code; Output = $text }
}

function Get-StatusSignature {
    $r = Invoke-Git @('status', '--porcelain', '--untracked-files=normal')
    if ($r.Code -ne 0) { throw "git status failed: $($r.Output)" }
    return $r.Output
}

function Get-AheadCount {
    $r = Invoke-Git @('rev-list', '--count', '@{u}..HEAD')
    if ($r.Code -ne 0) { return 0 }
    $n = 0
    if ([int]::TryParse($r.Output.Trim(), [ref]$n)) { return $n }
    return 0
}

function Sync-Repository {
    Write-Log 'Change detected - syncing...'

    $add = Invoke-Git @('add', '-A')
    if ($add.Code -ne 0) {
        Write-Log "git add failed: $($add.Output)" 'ERROR'
        return $false
    }

    $staged = Invoke-Git @('diff', '--cached', '--quiet')
    if ($staged.Code -ne 0) {
        $msg = "${CommitPrefix}: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
        $commit = Invoke-Git @('commit', '-m', $msg)
        if ($commit.Code -ne 0) {
            Write-Log "git commit failed: $($commit.Output)" 'ERROR'
            return $false
        }
        Write-Log "Committed: $msg"
    }

    $ahead = Get-AheadCount
    if ($ahead -le 0) {
        Write-Log 'Nothing to push.'
        return $true
    }

    for ($attempt = 1; $attempt -le 3; $attempt++) {
        $pull = Invoke-Git @('pull', '--rebase', '--autostash', $Remote, $Branch)
        if ($pull.Code -ne 0) {
            Write-Log "pull --rebase failed (attempt $attempt): $($pull.Output)" 'WARN'
            Invoke-Git @('rebase', '--abort') | Out-Null
            Start-Sleep -Seconds (5 * $attempt)
            continue
        }

        $push = Invoke-Git @('push', $Remote, $Branch)
        if ($push.Code -eq 0) {
            Write-Log "Pushed to $Remote/$Branch (attempt $attempt)."
            return $true
        }

        Write-Log "push failed (attempt $attempt): $($push.Output)" 'WARN'
        Start-Sleep -Seconds (5 * $attempt)
    }

    Write-Log 'Sync failed after 3 attempts; will retry on next change.' 'ERROR'
    return $false
}

# --- single instance guard -------------------------------------------------
if (-not $Once) {
    if (Test-Path -LiteralPath $lockFile) {
        $oldPid = (Get-Content -LiteralPath $lockFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        if ($oldPid) {
            $proc = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
            if ($proc) {
                Write-Host "Auto-push is already running (PID $oldPid). Exiting."
                exit 0
            }
        }
    }
    Set-Content -LiteralPath $lockFile -Value $PID -Encoding ASCII
    Set-Content -LiteralPath $pidFile  -Value $PID -Encoding ASCII
}

try {
    if ($Once) {
        Write-Log 'Single-run sync requested.'
        $ok = Sync-Repository
        if ($ok) { exit 0 } else { exit 1 }
    }

    Write-Log "Auto-push started. Repo=$RepoPath Branch=$Remote/$Branch Interval=${IntervalSeconds}s PID=$PID"
    Write-Log 'Press Ctrl+C to stop (or run scripts\stop-auto-push.cmd).'

    $lastSignature = Get-StatusSignature
    $lastAhead     = Get-AheadCount
    $pendingSince  = $null
    $quietCount    = 0

    while ($true) {
        Start-Sleep -Seconds $IntervalSeconds
        try {
            $sig   = Get-StatusSignature
            $ahead = Get-AheadCount

            $changed = ($sig -ne $lastSignature) -or ($ahead -ne $lastAhead)
            if ($changed) {
                $lastSignature = $sig
                $lastAhead     = $ahead
                $pendingSince  = Get-Date
                $quietCount    = 0
                if ($sig) { Write-Log 'Edits detected; waiting for them to settle...' }
                continue
            }

            $hasWork = ($sig -ne '') -or ($ahead -gt 0)
            if (-not $hasWork) {
                $pendingSince = $null
                $quietCount   = 0
                continue
            }

            if ($null -eq $pendingSince) {
                $pendingSince = Get-Date
                $quietCount   = 1
                continue
            }

            $quietCount++
            if ($quietCount -lt $QuietChecks) { continue }

            Sync-Repository | Out-Null

            $pendingSince  = $null
            $quietCount    = 0
            $lastSignature = Get-StatusSignature
            $lastAhead     = Get-AheadCount
        } catch {
            Write-Log "Watcher error: $($_.Exception.Message)" 'ERROR'
            Start-Sleep -Seconds 10
        }
    }
}
finally {
    if (-not $Once) {
        Remove-Item -LiteralPath $lockFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $pidFile  -Force -ErrorAction SilentlyContinue
        Write-Log 'Auto-push stopped.'
    }
}
