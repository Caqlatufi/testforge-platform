param(
    [Parameter(Mandatory = $true)][string]$PythonExecutable,
    [Parameter(Mandatory = $true)][string]$ServerScript,
    [Parameter(Mandatory = $true)][string]$ClientRoot,
    [Parameter(Mandatory = $true)][string]$Target
)

$ErrorActionPreference = 'Stop'
$logs = Join-Path $Target 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$process = Start-Process -FilePath $PythonExecutable `
    -ArgumentList $ServerScript, '--root', $ClientRoot, '--backend', 'http://127.0.0.1:18081', '--port', '15174' `
    -RedirectStandardOutput (Join-Path $logs 'web.log') `
    -RedirectStandardError (Join-Path $logs 'web-error.log') `
    -WindowStyle Hidden -PassThru
Set-Content -LiteralPath (Join-Path $Target 'web.pid') -Value $process.Id -Encoding ascii
$process.WaitForExit()
exit $process.ExitCode
