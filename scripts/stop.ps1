[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$platformRoot = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $platformRoot '.env.local'
if (Test-Path -LiteralPath $environmentFile) {
    foreach ($line in Get-Content -LiteralPath $environmentFile -Encoding UTF8) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        $parts = $text.Split('=', 2)
        if ($parts.Count -eq 2) { [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process') }
    }
}
$pidFile = Join-Path $platformRoot 'build/local-runtime/pids.json'
if (Test-Path -LiteralPath $pidFile) {
    $runtimeProcesses = Get-Content -Raw -LiteralPath $pidFile -Encoding UTF8 | ConvertFrom-Json
    foreach ($name in @('backend', 'client')) {
        $processId = $runtimeProcesses.$name
        if (-not $processId) { continue }
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $process) { continue }
        $started = $runtimeProcesses."${name}Started"
        if (-not $started -or $process.StartTime.ToUniversalTime().ToString('o') -ne $started) {
            Write-Warning "Skip $name process: start-time ownership could not be verified."
            continue
        }
        & taskkill.exe /PID $processId /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Unable to stop $name process tree" }
    }
    Remove-Item -LiteralPath $pidFile -Force
}
& docker compose -f (Join-Path $platformRoot 'infra/compose.yaml') --env-file (Join-Path $platformRoot 'infra/env/dev.env') stop mysql redis minio
if ($LASTEXITCODE -ne 0) { throw 'Unable to stop local infrastructure' }
Write-Host 'TestForge local processes stopped; data volumes retained.'
