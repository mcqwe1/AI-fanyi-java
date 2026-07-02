@echo off
REM Double-click to stop the aifanyi app servers (Docker stays up).
powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0stop-all.ps1"
