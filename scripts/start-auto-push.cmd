@echo off
setlocal
set "LAUNCH=%~dp0launch-agent.ps1"
if not exist "%LAUNCH%" (
  echo [ERROR] launch-agent.ps1 not found next to this launcher.
  exit /b 1
)
echo Starting V-Tune 3.O auto-push agent...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%LAUNCH%"
if errorlevel 1 (
  echo [ERROR] Failed to start the agent.
  exit /b 1
)
echo Agent started in the background.
echo Log: %~dp0..\.auto-push\auto-push.log
endlocal
