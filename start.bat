@echo off
rem Forwarder: the real portable launcher lives in dist\aifanyi-win64\.
rem This copy of the project has NO docker dependency (H2 embedded DB).
setlocal
set "DIST=%~dp0dist\aifanyi-win64"
if exist "%DIST%\start.bat" (
  call "%DIST%\start.bat" %*
) else (
  echo Portable bundle not built yet.
  echo Run:  powershell -ExecutionPolicy Bypass -File "%~dp0build-portable.ps1"
  echo Then this script will launch dist\aifanyi-win64\start.bat
  pause
)
