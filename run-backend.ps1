# Load .env from project root and start the Spring Boot backend.
# Usage:  powershell -ExecutionPolicy Bypass -File run-backend.ps1
$root = $PSScriptRoot
$envFile = Join-Path $root ".env"
if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
      $idx = $line.IndexOf("=")
      $k = $line.Substring(0, $idx).Trim()
      $v = $line.Substring($idx + 1).Trim()
      [Environment]::SetEnvironmentVariable($k, $v)
      Write-Host "set $k"
    }
  }
}
Set-Location (Join-Path $root "backend")
& .\mvnw.cmd -q -DskipTests spring-boot:run
