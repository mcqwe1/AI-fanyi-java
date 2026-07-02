# ============================================================
#  aifanyi one-click startup
#  Cold-starts the whole stack:
#    Docker Desktop -> MySQL/Redis -> vertex2openai(+SA creds)
#    -> backend(8080) -> ai-service(8001) -> frontend(5173) -> browser
#  Safe to re-run: any service already listening is skipped.
#  ASCII-only on purpose (PS 5.1 mis-decodes non-BOM UTF-8 as GBK).
# ============================================================
$ErrorActionPreference = 'Continue'
$root          = $PSScriptRoot
$DockerExe     = 'C:\Program Files\Docker\Docker\Docker Desktop.exe'
$SaCredHostDir = 'E:\h\AIRP\vertex\vertex2openai\credentials'
$SaCredFile    = 'sa.json'
$VertexKey     = 'zy1234567'

$script:useNetstat = -not (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)
function Test-Port($port) {
  if ($script:useNetstat) {
    return [bool](netstat -ano | Select-String (":$port ") | Select-String 'LISTENING')
  }
  return [bool](Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
}
function Wait-Port($port, $seconds) {
  $deadline = (Get-Date).AddSeconds($seconds)
  while ((Get-Date) -lt $deadline) {
    if (Test-Port $port) { return $true }
    Start-Sleep -Seconds 2
  }
  return (Test-Port $port)
}

Write-Host ''
Write-Host '==== aifanyi startup ====' -ForegroundColor Cyan

# --- 1. Docker daemon ---
Write-Host '[1/6] Docker...' -ForegroundColor Yellow
docker info *>$null
if ($LASTEXITCODE -ne 0) {
  if (Test-Path $DockerExe) {
    Write-Host '      launching Docker Desktop, waiting for daemon (up to 240s)...'
    Start-Process $DockerExe
  } else {
    Write-Host '      Docker Desktop exe not found; start Docker manually.' -ForegroundColor Red
  }
  $deadline = (Get-Date).AddSeconds(240)
  while ($true) {
    Start-Sleep -Seconds 3
    docker info *>$null
    if ($LASTEXITCODE -eq 0) { break }
    if ((Get-Date) -gt $deadline) {
      Write-Host '      Docker daemon timeout. Aborting.' -ForegroundColor Red
      Read-Host 'Press Enter to exit'; exit 1
    }
  }
}
Write-Host '      Docker ready.' -ForegroundColor Green

# --- 2. MySQL + Redis (docker compose) ---
Write-Host '[2/6] MySQL + Redis...' -ForegroundColor Yellow
Push-Location $root
docker compose up -d
Pop-Location
$deadline = (Get-Date).AddSeconds(90)
while ($true) {
  $h = (docker inspect -f '{{.State.Health.Status}}' aifanyi-mysql 2>$null)
  if ($h -eq 'healthy') { break }
  if ((Get-Date) -gt $deadline) { Write-Host '      MySQL health timeout; continuing.' -ForegroundColor Red; break }
  Start-Sleep -Seconds 3
}
Write-Host '      MySQL/Redis up.' -ForegroundColor Green

# --- 3. vertex2openai proxy (start + ensure SA credentials loaded) ---
Write-Host '[3/6] vertex2openai proxy...' -ForegroundColor Yellow
$exists = (docker ps -a --filter 'name=vertex2openai' --format '{{.Names}}' 2>$null)
if ($exists) {
  $running = (docker ps --filter 'name=vertex2openai' --format '{{.Names}}' 2>$null)
  if (-not $running) { docker start vertex2openai *>$null; Start-Sleep -Seconds 3 }
  # Probe /v1/models: valid response with empty data => creds missing; connection error => still booting (retry).
  $credsOk = $false
  for ($i = 0; $i -lt 5; $i++) {
    try {
      $r = Invoke-RestMethod -Uri 'http://localhost:8050/v1/models' -Headers @{ Authorization = "Bearer $VertexKey" } -TimeoutSec 8
      if ($r.data -and @($r.data).Count -gt 0) { $credsOk = $true }
      break
    } catch { Start-Sleep -Seconds 3 }
  }
  if (-not $credsOk) {
    $saPath = Join-Path $SaCredHostDir $SaCredFile
    if (Test-Path $saPath) {
      Write-Host '      SA credentials not loaded; copying in + restarting...'
      # Stream the file into the container via stdin. (docker cp with a Windows
      # drive-letter source path gets mis-parsed at the colon and silently no-ops.)
      Get-Content $saPath -Raw | docker exec -i vertex2openai sh -c 'cat > /app/credentials/sa.json'
      docker restart vertex2openai *>$null
      Start-Sleep -Seconds 5
      Write-Host '      credentials loaded.' -ForegroundColor Green
    } else {
      Write-Host "      WARN: SA file missing ($saPath); KB term extraction will fail." -ForegroundColor Red
    }
  } else {
    Write-Host '      credentials OK.' -ForegroundColor Green
  }
} else {
  Write-Host '      WARN: container vertex2openai not found; KB term extraction unavailable.' -ForegroundColor Red
}

# --- 4. backend (8080) ---
Write-Host '[4/6] backend (8080)...' -ForegroundColor Yellow
if (Test-Port 8080) {
  Write-Host '      already running, skipped.' -ForegroundColor Green
} else {
  Start-Process powershell -ArgumentList '-NoExit','-ExecutionPolicy','Bypass','-File',(Join-Path $root 'run-backend.ps1')
  Write-Host '      launching in a new window.' -ForegroundColor Green
}

# --- 5. ai-service (8001) ---
Write-Host '[5/6] ai-service (8001)...' -ForegroundColor Yellow
if (Test-Port 8001) {
  Write-Host '      already running, skipped.' -ForegroundColor Green
} else {
  Start-Process powershell -ArgumentList '-NoExit','-ExecutionPolicy','Bypass','-File',(Join-Path $root 'run-ai-service.ps1')
  Write-Host '      launching in a new window.' -ForegroundColor Green
}

# --- 6. frontend (5173) ---
Write-Host '[6/6] frontend (5173)...' -ForegroundColor Yellow
if (Test-Port 5173) {
  Write-Host '      already running, skipped.' -ForegroundColor Green
} else {
  Start-Process powershell -ArgumentList '-NoExit','-ExecutionPolicy','Bypass','-File',(Join-Path $root 'run-frontend.ps1')
  Write-Host '      launching in a new window.' -ForegroundColor Green
}

# --- wait for backend + frontend, then open browser ---
Write-Host ''
Write-Host 'Waiting for backend + frontend (backend first-compile can take ~30-60s)...' -ForegroundColor Yellow
$beOk = Wait-Port 8080 150
$feOk = Wait-Port 5173 60
Write-Host ("  backend 8080 : {0}" -f $(if ($beOk) { 'UP' } else { 'NOT YET' }))
Write-Host ("  frontend 5173: {0}" -f $(if ($feOk) { 'UP' } else { 'NOT YET' }))
if ($feOk) { Start-Process 'http://localhost:5173' }

Write-Host ''
Write-Host '==== done -> http://localhost:5173  (demo / demo123) ====' -ForegroundColor Cyan
Write-Host 'The 3 new windows are backend / ai-service / frontend logs. Close them or run stop.bat to stop.'
Start-Sleep -Seconds 4
