param(
    [Parameter(Mandatory = $true)][string]$JavaExecutable,
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(Mandatory = $true)][string]$Target,
    [string]$ServerAddress = $env:TESTFORGE_SELFTEST_SERVER_ADDRESS,
    [string]$CallbackBaseUrl = $env:TESTFORGE_SELFTEST_CALLBACK_BASE_URL,
    [string]$RedisHost = $env:TESTFORGE_SELFTEST_REDIS_HOST
)

$ErrorActionPreference = 'Stop'
$logs = Join-Path $Target 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
if (-not $ServerAddress) { $ServerAddress = '127.0.0.1' }
if (-not $CallbackBaseUrl) { $CallbackBaseUrl = 'http://127.0.0.1:18081' }
if (-not $RedisHost) { $RedisHost = '127.0.0.1' }
$env:TESTFORGE_SERVER_ADDRESS = $ServerAddress
$env:TESTFORGE_SERVER_PORT = '18081'
$env:TESTFORGE_DB_URL = 'jdbc:mysql://127.0.0.1:3306/testforge_selftest?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:TESTFORGE_CICD_CALLBACK_BASE_URL = $CallbackBaseUrl
$env:TESTFORGE_DISPATCH_STREAM_PREFIX = 'testforge:selftest:tasks'
$env:REDIS_HOST = $RedisHost
$process = Start-Process -FilePath $JavaExecutable -ArgumentList '-jar', $JarPath `
    -RedirectStandardOutput (Join-Path $logs 'backend.log') `
    -RedirectStandardError (Join-Path $logs 'backend-error.log') `
    -WindowStyle Hidden -PassThru
Set-Content -LiteralPath (Join-Path $Target 'backend.pid') -Value $process.Id -Encoding ascii
$process.WaitForExit()
exit $process.ExitCode
