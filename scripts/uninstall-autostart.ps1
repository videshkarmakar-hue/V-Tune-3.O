[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

$startup = [Environment]::GetFolderPath('Startup')
$lnkPath = Join-Path $startup 'V-Tune 3.O Auto Push.lnk'

if (Test-Path -LiteralPath $lnkPath) {
    Remove-Item -LiteralPath $lnkPath -Force
    Write-Host "Removed startup shortcut: $lnkPath"
} else {
    Write-Host 'Startup shortcut not found (already removed).'
}
