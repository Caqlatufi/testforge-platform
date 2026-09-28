param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(Mandatory = $true)][string]$ClientDist,
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$CommitSha,
    [Parameter(Mandatory = $true)][string]$EnvironmentKey
)

$ErrorActionPreference = 'Stop'
if ($EnvironmentKey -ne 'testforge-selftest') { throw "Unsupported environment: $EnvironmentKey" }

function Resolve-Java21Executable {
    $candidates = @()
    if ($env:TESTFORGE_JAVA_EXECUTABLE) { $candidates += $env:TESTFORGE_JAVA_EXECUTABLE }
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    foreach ($root in @($env:GRADLE_USER_HOME, (Join-Path $env:USERPROFILE '.gradle'))) {
        if (-not $root) { continue }
        $jdkRoot = Join-Path $root 'jdks'
        if (Test-Path -LiteralPath $jdkRoot) {
            $candidates += Get-ChildItem -LiteralPath $jdkRoot -Recurse -Filter java.exe -File -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -match '[\\/]bin[\\/]java\.exe$' } |
                Select-Object -ExpandProperty FullName
        }
    }
    foreach ($candidate in $candidates | Select-Object -Unique) {
        if (-not (Test-Path -LiteralPath $candidate)) { continue }
        $versionCommand = '"{0}" -version 2>&1' -f $candidate
        $version = (& $env:ComSpec /d /s /c $versionCommand | Out-String)
        if ($version -match 'version "(?:1\.)?(?<major>\d+)') {
            if ([int]$Matches.major -ge 21) { return $candidate }
        }
    }
    throw 'Java 21 runtime not found. Set TESTFORGE_JAVA_EXECUTABLE or install the Gradle Java 21 toolchain.'
}

$javaExecutable = Resolve-Java21Executable
$target = if ($env:TESTFORGE_SELFTEST_ROOT) { $env:TESTFORGE_SELFTEST_ROOT } else { Join-Path $env:ProgramData 'TestForge/platform-selftest' }
$release = Join-Path $target 'release'
$logs = Join-Path $target 'logs'
New-Item -ItemType Directory -Force -Path $release, $logs | Out-Null
foreach ($name in @('backend', 'web')) {
    $pidFile = Join-Path $target "$name.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $processId = [int](Get-Content -LiteralPath $pidFile -Raw)
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $pidFile -Force
    }
}
$scheduledTasks = @(
    'TestForge Platform Selftest Backend',
    'TestForge Platform Selftest Web'
)
foreach ($taskName in $scheduledTasks) {
    $endCommand = 'schtasks.exe /End /TN "{0}" >nul 2>&1' -f $taskName
    & $env:ComSpec /d /s /c $endCommand | Out-Null
}
Copy-Item -LiteralPath $JarPath -Destination (Join-Path $release 'testforge-app.jar') -Force
$clientTarget = Join-Path $release 'client'
if (Test-Path -LiteralPath $clientTarget) { Remove-Item -LiteralPath $clientTarget -Recurse -Force }
Copy-Item -LiteralPath $ClientDist -Destination $clientTarget -Recurse -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'selftest_web_server.py') -Destination (Join-Path $release 'selftest_web_server.py') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'run_selftest_backend.ps1') -Destination (Join-Path $release 'run_selftest_backend.ps1') -Force
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'run_selftest_web.ps1') -Destination (Join-Path $release 'run_selftest_web.ps1') -Force
Set-Content -LiteralPath (Join-Path $release 'commit.txt') -Value $CommitSha -Encoding ascii

$startAt = (Get-Date).AddMinutes(5).ToString('HH:mm')
$backendLauncher = Join-Path $release 'start-backend.cmd'
$webLauncher = Join-Path $release 'start-web.cmd'
$backendCommand = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "{0}" -JavaExecutable "{1}" -JarPath "{2}" -Target "{3}"' -f `
    (Join-Path $release 'run_selftest_backend.ps1'), $javaExecutable, (Join-Path $release 'testforge-app.jar'), $target
$webCommand = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "{0}" -PythonExecutable "{1}" -ServerScript "{2}" -ClientRoot "{3}" -Target "{4}"' -f `
    (Join-Path $release 'run_selftest_web.ps1'), (Get-Command python.exe).Source, (Join-Path $release 'selftest_web_server.py'), $clientTarget, $target
Set-Content -LiteralPath $backendLauncher -Value @('@echo off', $backendCommand) -Encoding ascii
Set-Content -LiteralPath $webLauncher -Value @('@echo off', $webCommand) -Encoding ascii
$backendAction = $backendLauncher
$webAction = $webLauncher
& schtasks.exe /Create /TN $scheduledTasks[0] /SC ONCE /ST $startAt /TR $backendAction /F | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to create backend scheduled task' }
& schtasks.exe /Create /TN $scheduledTasks[1] /SC ONCE /ST $startAt /TR $webAction /F | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to create web scheduled task' }
& schtasks.exe /Run /TN $scheduledTasks[0] | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to start backend scheduled task' }
& schtasks.exe /Run /TN $scheduledTasks[1] | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Failed to start web scheduled task' }
$deadline = (Get-Date).AddSeconds(90)
do {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18081/actuator/health' -TimeoutSec 3
        $page = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:15174/' -TimeoutSec 3
        if ($health.status -eq 'UP' -and $page.StatusCode -eq 200) { Write-Host 'TestForge self-test environment READY'; exit 0 }
    } catch { Start-Sleep -Seconds 2 }
} while ((Get-Date) -lt $deadline)
throw 'TestForge self-test environment did not become ready within 90 seconds'
