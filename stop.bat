@echo off
rem Forwarder to the portable bundle's stop script.
setlocal
set "DIST=%~dp0dist\aifanyi-win64"
if exist "%DIST%\stop.bat" (
  call "%DIST%\stop.bat" %*
) else (
  echo Portable bundle not built yet - nothing to stop.
  pause
)
