[CmdletBinding()]
param([switch]$SkipInstall)

$ErrorActionPreference = 'Stop'
$platformRoot = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $platformRoot 'build/local-runtime'

function Import-LocalEnvironment {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -eq 2) {
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process')
        }
    }
}

Import-LocalEnvironment (Join-Path $platformRoot '.env.local')
if (-not $env:TESTFORGE_SERVER_PORT) { $env:TESTFORGE_SERVER_PORT = '8081' }
if (-not $env:TESTFORGE_CLIENT_PORT) { $env:TESTFORGE_CLIENT_PORT = '5174' }
if (-not $env:TESTFORGE_DB_URL) {
    $dbPort = if ($env:MYSQL_PORT) { $env:MYSQL_PORT } else { '3306' }
    $dbName = if ($env:MYSQL_DATABASE) { $env:MYSQL_DATABASE } else { 'testforge' }
    $env:TESTFORGE_DB_URL = "jdbc:mysql://127.0.0.1:$dbPort/${dbName}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
}
$healthUrl = "http://127.0.0.1:$env:TESTFORGE_SERVER_PORT/actuator/health"
New-Item -ItemType Directory -Force -Path $runtimeRoot | Out-Null

& docker compose -f (Join-Path $platformRoot 'infra/compose.yaml') --env-file (Join-Path $platformRoot 'infra/env/dev.env') up -d --wait mysql redis minio
if ($LASTEXITCODE -ne 0) { throw '基础设施启动失败' }
& docker compose -f (Join-Path $platformRoot 'infra/compose.yaml') --env-file (Join-Path $platformRoot 'infra/env/dev.env') run --rm minio-init
if ($LASTEXITCODE -ne 0) { throw '对象存储初始化失败' }

if (-not $SkipInstall) {
    Push-Location (Join-Path $platformRoot 'testforge-client')
    try { & npm.cmd ci; if ($LASTEXITCODE -ne 0) { throw 'npm ci 失败' } } finally { Pop-Location }
}

$backend = Start-Process -FilePath (Join-Path $platformRoot 'testforge-app/gradlew.bat') -ArgumentList ':app:bootRun' -WorkingDirectory (Join-Path $platformRoot 'testforge-app') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeRoot 'backend.out.log') -RedirectStandardError (Join-Path $runtimeRoot 'backend.err.log') -PassThru
$client = Start-Process -FilePath 'npm.cmd' -ArgumentList 'run','dev','--','--host','127.0.0.1','--port',$env:TESTFORGE_CLIENT_PORT -WorkingDirectory (Join-Path $platformRoot 'testforge-client') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeRoot 'client.out.log') -RedirectStandardError (Join-Path $runtimeRoot 'client.err.log') -PassThru
@{ backend = $backend.Id; client = $client.Id; backendStarted = $backend.StartTime.ToUniversalTime().ToString('o'); clientStarted = $client.StartTime.ToUniversalTime().ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeRoot 'pids.json') -Encoding UTF8

$deadline = (Get-Date).AddMinutes(2)
do {
    try { $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 3 } catch { $health = $null }
    if ($health.status -eq 'UP') { break }
    Start-Sleep -Seconds 2
} while ((Get-Date) -lt $deadline)
if ($null -eq $health -or $health.status -ne 'UP') { throw "后端未就绪，检查 $runtimeRoot" }

Write-Host "TestForge 已启动：Console http://127.0.0.1:$env:TESTFORGE_CLIENT_PORT，API http://127.0.0.1:$env:TESTFORGE_SERVER_PORT"
