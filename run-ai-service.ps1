# Launch the faster-whisper FastAPI service on port 8001 (uses the project venv).
Set-Location (Join-Path $PSScriptRoot 'ai-service')
& '.\.venv\Scripts\python.exe' -m uvicorn app.main:app --port 8001
