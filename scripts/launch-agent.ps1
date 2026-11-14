[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'

$self = $MyInvocation.MyCommand.Path
if (-not $self) { $self = $PSCommandPath }
$scriptDir = Split-Path -Parent $self
$ps1 = Join-Path $scriptDir 'auto-push.ps1'
if (-not (Test-Path -LiteralPath $ps1)) { throw "Missing $ps1" }

# UseShellExecute (Start-Process default) detaches the child so it does not
# inherit this process's console/pipe handles.
$argLine = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $ps1
$exe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
Start-Process -FilePath $exe -ArgumentList $argLine -WindowStyle Hidden
