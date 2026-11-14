@echo off
setlocal
set "STATE=%~dp0..\.auto-push"
set "PIDFILE=%STATE%\auto-push.pid"
if not exist "%PIDFILE%" (
  echo V-Tune 3.O auto-push agent is not running.
  exit /b 0
)
set /p AGENTPID=<"%PIDFILE%"
if not defined AGENTPID (
  echo PID file is empty.
  del /q "%PIDFILE%" >nul 2>&1
  exit /b 0
)
taskkill /PID %AGENTPID% /F >nul 2>&1
if errorlevel 1 (
  echo Could not stop PID %AGENTPID% ^(it may have already exited^).
) else (
  echo Stopped V-Tune 3.O auto-push agent ^(PID %AGENTPID%^).
)
del /q "%PIDFILE%" >nul 2>&1
del /q "%STATE%\auto-push.lock" >nul 2>&1
endlocal
