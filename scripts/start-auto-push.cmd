@echo off
setlocal
set "PS1=%~dp0auto-push.ps1"
if not exist "%PS1%" (
  echo [ERROR] auto-push.ps1 not found next to this launcher.
  exit /b 1
)
echo Starting V-Tune 3.O auto-push agent...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File','%PS1%') -WindowStyle Hidden"
if errorlevel 1 (
  echo [ERROR] Failed to start the agent.
  exit /b 1
)
echo Agent started in the background.
echo Log: %~dp0..\.auto-push\auto-push.log
endlocal
