[CmdletBinding()]
param(
    [string]$Root = 'C:\TestForge',
    [switch]$StartIfStopped
)

$ErrorActionPreference = 'Stop'
$startScript = Join-Path $Root 'start-environment.ps1'
if (-not (Test-Path -LiteralPath $startScript -PathType Leaf)) {
    throw "Environment start script missing: $startScript"
}

$startup = [Environment]::GetFolderPath('Startup')
Remove-Item -LiteralPath (Join-Path $startup 'TestForge-Environment.cmd') `
    -Force -ErrorAction SilentlyContinue

$taskName = 'TestForge Environment'
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument (
    "-NoProfile -ExecutionPolicy Bypass -File `"$startScript`""
)
$trigger = New-ScheduledTaskTrigger -AtLogOn -User 'TestForge'
$principal = New-ScheduledTaskPrincipal -UserId 'TestForge' -LogonType Interactive `
    -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
    -Principal $principal -Settings $settings -Force | Out-Null

if ($StartIfStopped) {
    $workerRunning = @(Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -match 'testforge_worker\s+--serve'
    }).Count -gt 0
    if (-not $workerRunning) {
        Start-ScheduledTask -TaskName $taskName
    }
}

Get-ScheduledTask -TaskName $taskName
