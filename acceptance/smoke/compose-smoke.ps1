[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$platformRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$logRoot = Join-Path $platformRoot 'build/ci-smoke'
$projectName = 'testforge-smoke'
$composeFiles = @('-f', (Join-Path $platformRoot 'infra/compose.yaml'), '-f', (Join-Path $platformRoot 'infra/compose.test.yaml'))
$appProcess = $null
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null

$env:MYSQL_PORT = '13306'
$env:REDIS_PORT = '16379'
$env:MINIO_API_PORT = '19000'
$env:MINIO_CONSOLE_PORT = '19001'
if (Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Smoke port 18081 is already in use; stop the existing self-test instance before running smoke.'
}
try {
    & docker compose -p $projectName @composeFiles up -d --wait mysql redis minio
    if ($LASTEXITCODE -ne 0) { throw 'Compose dependencies failed to become healthy' }
    Push-Location (Join-Path $platformRoot 'testforge-app')
    try { & .\gradlew.bat :app:bootJar --no-daemon; if ($LASTEXITCODE -ne 0) { throw 'bootJar failed' } } finally { Pop-Location }

    $env:TESTFORGE_DB_URL = 'jdbc:mysql://127.0.0.1:13306/testforge?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
    $env:MYSQL_USER = 'testforge'
    $env:MYSQL_PASSWORD = 'testforge_mysql'
    $env:REDIS_HOST = '127.0.0.1'
    $env:REDIS_PORT = '16379'
    $env:REDIS_PASSWORD = 'testforge_redis'
    $env:TESTFORGE_DISPATCH_REDIS_ENABLED = 'false'
    $env:TESTFORGE_DISPATCH_RELAY_ENABLED = 'false'
    $env:TESTFORGE_RELIABILITY_ENABLED = 'false'
    $appProcess = Start-Process -FilePath (Join-Path $platformRoot 'testforge-app/gradlew.bat') -ArgumentList ':app:bootRun','--args=--server.port=18081','--no-daemon' -WorkingDirectory (Join-Path $platformRoot 'testforge-app') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logRoot 'app.log') -RedirectStandardError (Join-Path $logRoot 'app.err.log') -PassThru
    $deadline = (Get-Date).AddMinutes(2); $health = $null
    do {
        try { $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18081/actuator/health' -TimeoutSec 3 } catch { $health = $null }
        if ($health.status -eq 'UP') { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $health -or $health.status -ne 'UP') { throw 'Smoke application did not become healthy' }
    $resources = Invoke-RestMethod -Uri 'http://127.0.0.1:18081/api/v1/monitoring/resources' -TimeoutSec 5
    $metrics = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:18081/actuator/prometheus' -TimeoutSec 5
    @{ executedAt = (Get-Date).ToUniversalTime().ToString('o'); result = 'PASS'; health = $health.status; resources = $resources.data; prometheusStatus = $metrics.StatusCode } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $logRoot 'result.json') -Encoding UTF8
    Write-Host '[CI] Compose smoke passed on isolated ports.'
}
finally {
    $listener = Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($appProcess -and $listener) { Stop-Process -Id $listener.OwningProcess -Force -ErrorAction SilentlyContinue }
    if ($appProcess -and -not $appProcess.HasExited) { Stop-Process -Id $appProcess.Id -Force }
    & docker compose -p $projectName @composeFiles logs --no-color *> (Join-Path $logRoot 'compose.log')
    & docker compose -p $projectName @composeFiles down -v --remove-orphans | Out-Null
}
