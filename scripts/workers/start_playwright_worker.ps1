[CmdletBinding()]
param(
    [int]$Concurrency = 2,
    [string]$GatewayUrl = $env:TESTFORGE_GATEWAY_URL,
    [string]$RedisUrl = $env:TESTFORGE_REDIS_URL
)

$ErrorActionPreference = 'Stop'
$platformRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtime = Join-Path $platformRoot 'build\local-runtime'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
$env:TESTFORGE_RUNNER = 'playwright-web'
$env:TESTFORGE_STREAM = 'testforge:tasks:playwright-web'
$env:TESTFORGE_WORKER_ID = 'playwright-web-local'
$env:TESTFORGE_WORKER_CAPABILITIES = 'RUNNER_PLAYWRIGHT_WEB,WEB_UI,PLATFORM_WINDOWS'
$env:TESTFORGE_WORKER_CONCURRENCY = [string]$Concurrency
if (-not $GatewayUrl) { $GatewayUrl = 'http://127.0.0.1:8081' }
if (-not $RedisUrl) { $RedisUrl = 'redis://:testforge_redis@127.0.0.1:6379/0' }
$env:TESTFORGE_GATEWAY_URL = $GatewayUrl
$env:TESTFORGE_REDIS_URL = $RedisUrl
if (-not $env:TESTFORGE_ARTIFACT_BACKEND) {
    # Local development has no OSS upload policy. Keep evidence on disk and
    # still return immutable artifact keys in the Attempt callback.
    $env:TESTFORGE_ARTIFACT_BACKEND = 'local'
}
$worker = Start-Process -FilePath 'python' -ArgumentList '-m','testforge_worker','--serve' -WorkingDirectory (Join-Path $platformRoot 'testforge-worker') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtime 'playwright-worker.out.log') -RedirectStandardError (Join-Path $runtime 'playwright-worker.err.log') -PassThru
Set-Content -LiteralPath (Join-Path $runtime 'playwright-worker.pid') -Value $worker.Id -Encoding ascii
Write-Host "Playwright Worker started: $($worker.Id)"
