$ErrorActionPreference = 'Stop'
$target = 'C:\TestForge'
$log = 'C:\TestForge-bootstrap.log'
Start-Transcript -Path $log -Append | Out-Null
try {
    $media = Split-Path -Parent $PSCommandPath
    $archive = Join-Path $media 'payload.zip'
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        throw "Payload archive missing: $archive"
    }
    New-Item -ItemType Directory -Force -Path $target | Out-Null
    Expand-Archive -LiteralPath $archive -DestinationPath $target -Force

    $vcRuntime = Join-Path $target 'vc_redist.x64.exe'
    $vcRuntimeState = Get-ItemProperty `
        'HKLM:\SOFTWARE\Microsoft\VisualStudio\14.0\VC\Runtimes\x64' `
        -ErrorAction SilentlyContinue
    if ($vcRuntimeState.Installed -ne 1) {
        if (-not (Test-Path -LiteralPath $vcRuntime -PathType Leaf)) {
            throw "Visual C++ runtime installer missing: $vcRuntime"
        }
        $process = Start-Process -FilePath $vcRuntime `
            -ArgumentList @('/install', '/quiet', '/norestart') -Wait -PassThru
        if ($process.ExitCode -notin 0, 3010) {
            throw "Visual C++ runtime installer failed: $($process.ExitCode)"
        }
    }

    $installer = Join-Path $target 'python-3.11.9-amd64.exe'
    $python = 'C:\Program Files\Python311\python.exe'
    if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
        $process = Start-Process -FilePath $installer -ArgumentList @(
            '/quiet', 'InstallAllUsers=1', 'PrependPath=1', 'Include_test=0',
            'Include_launcher=1', 'InstallLauncherAllUsers=1'
        ) -Wait -PassThru
        if ($process.ExitCode -ne 0) {
            throw "Python installer failed: $($process.ExitCode)"
        }
    }
    & $python -m pip install --disable-pip-version-check --upgrade pip
    & $python -m pip install --disable-pip-version-check (Join-Path $target 'testforge_worker-0.1.0-py3-none-any.whl') 'airtest==1.3.6'
    if ($LASTEXITCODE -ne 0) { throw "Python package installation failed: $LASTEXITCODE" }

    & (Join-Path $target 'register-environment-task.ps1') -Root $target -StartIfStopped
} finally {
    Stop-Transcript | Out-Null
}
