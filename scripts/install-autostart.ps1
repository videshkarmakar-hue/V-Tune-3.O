[CmdletBinding()]
param(
    [string]$RepoPath
)
$ErrorActionPreference = 'Stop'

$self = $MyInvocation.MyCommand.Path
if (-not $self) { $self = $PSCommandPath }
$scriptDir = Split-Path -Parent $self
if (-not $RepoPath) { $RepoPath = Split-Path -Parent $scriptDir }

$ps1 = Join-Path $scriptDir 'auto-push.ps1'
if (-not (Test-Path -LiteralPath $ps1)) { throw "Missing $ps1" }

$startup = [Environment]::GetFolderPath('Startup')
$lnkPath = Join-Path $startup 'V-Tune 3.O Auto Push.lnk'

$ws = New-Object -ComObject WScript.Shell
$sc = $ws.CreateShortcut($lnkPath)
$sc.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$sc.Arguments = ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $ps1)
$sc.WorkingDirectory = (Resolve-Path -LiteralPath $RepoPath).Path
$sc.WindowStyle = 7
$sc.Description = 'V-Tune 3.O automatic git commit and push agent'
$sc.Save()

Write-Host "Installed startup shortcut: $lnkPath"
Write-Host 'The agent will start automatically the next time you sign in.'
