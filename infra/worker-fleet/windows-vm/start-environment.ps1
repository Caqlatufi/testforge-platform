$ErrorActionPreference = 'Stop'
$root = 'C:\TestForge'
$settings = @{}
Get-Content -LiteralPath (Join-Path $root 'vm.env') -Encoding UTF8 | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $key, $value = $line -split '=', 2
        $settings[$key.Trim()] = $value.Trim()
    }
}
foreach ($entry in $settings.GetEnumerator()) {
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
}

$identity = $env:COMPUTERNAME.ToLowerInvariant()
$env:TESTFORGE_WORKER_ID = "airtest-$identity"
$env:TESTFORGE_WORKER_CONCURRENCY = '1'
$env:TESTFORGE_WORKER_CAPABILITIES = 'RUNNER_AIRTEST,PLATFORM_WINDOWS,WINDOWS_UI,ISOLATED_DESKTOP'
$env:TESTFORGE_RUNNER = 'airtest'
$env:TESTFORGE_PLATFORM = 'windows'
$env:TESTFORGE_DEVICE_ID = "skill-sandbox-$identity"
$env:TESTFORGE_DEVICE_URI = 'Windows:///?title_re=TestForge%20Skill%20Sandbox'
$env:TESTFORGE_DEVICE_PLATFORM = 'WINDOWS'
$env:TESTFORGE_DEVICE_FEATURES = 'WINDOWS_UI,ISOLATED_DESKTOP'
$env:TESTFORGE_DEVICE_RESOLUTION = '1280x800'
$env:TESTFORGE_ENVIRONMENT_ID = $identity
$env:TESTFORGE_SANDBOX_CWD = Join-Path $root 'sandbox-app'
$env:TESTFORGE_SANDBOX_STARTUP_TIMEOUT_SECONDS = '90'
$env:TESTFORGE_SANDBOX_COMMAND_JSON = ConvertTo-Json @(
    (Join-Path $root 'electron\electron.exe'),
    (Join-Path $root 'sandbox-app')
) -Compress
$env:TESTFORGE_ARTIFACT_ROOT = Join-Path $root 'artifacts'

$python = 'C:\Program Files\Python311\python.exe'
Set-Location $root
& $python -m testforge_worker --serve *>> (Join-Path $root 'worker.log')
