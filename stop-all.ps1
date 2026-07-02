# Stop aifanyi app servers (backend 8080 / ai-service 8001 / frontend 5173).
# Docker containers (MySQL/Redis/vertex2openai) are left running on purpose,
# so the vertex SA credentials do not get wiped.
$ErrorActionPreference = 'Continue'
foreach ($port in 8080, 8001, 5173) {
  $conns = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
  if ($conns) {
    foreach ($procId in @($conns.OwningProcess | Sort-Object -Unique)) {
      try {
        $name = (Get-Process -Id $procId -ErrorAction SilentlyContinue).ProcessName
        Stop-Process -Id $procId -Force -ErrorAction Stop
        Write-Host "stopped $name (PID $procId) on port $port"
      } catch {
        Write-Host "could not stop PID $procId on port $port"
      }
    }
  } else {
    Write-Host "port $port : nothing listening"
  }
}
Write-Host 'Done. Docker containers left running.'
Start-Sleep -Seconds 2
