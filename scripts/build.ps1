[CmdletBinding()]
param(
    [ValidateSet('All', 'Backend', 'Client', 'Worker', 'Mcp')]
    [string]$Component = 'All',
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$platformRoot = Split-Path -Parent $PSScriptRoot

function Invoke-InDirectory {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [scriptblock]$Action
    )

    Push-Location -LiteralPath $Path
    try {
        & $Action
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code $LASTEXITCODE in $Path"
        }
    }
    finally {
        Pop-Location
    }
}

function Build-Backend {
    Invoke-InDirectory (Join-Path $platformRoot 'testforge-app') {
        & .\gradlew.bat :app:bootJar --no-daemon
    }
}

function Build-Client {
    Invoke-InDirectory (Join-Path $platformRoot 'testforge-client') {
        if (-not $SkipInstall) {
            & npm.cmd ci
            if ($LASTEXITCODE -ne 0) { return }
        }
        & npm.cmd run build
    }
}

function Build-PythonProject {
    param([Parameter(Mandatory)] [string]$Project)

    Invoke-InDirectory (Join-Path $platformRoot $Project) {
        $venvRoot = Join-Path (Get-Location) '.venv'
        $venvPython = Join-Path $venvRoot 'Scripts\python.exe'
        if (-not $SkipInstall -and -not (Test-Path -LiteralPath $venvPython)) {
            & python -m venv $venvRoot
            if ($LASTEXITCODE -ne 0) { return }
        }
        $python = if (Test-Path -LiteralPath $venvPython) { $venvPython } else { (Get-Command python -ErrorAction Stop).Source }
        $previousPycachePrefix = $env:PYTHONPYCACHEPREFIX
        $env:PYTHONPYCACHEPREFIX = (Join-Path (Get-Location) 'build/pycache')
        try {
            if (-not $SkipInstall) {
                & $python -m pip install -e .
                if ($LASTEXITCODE -ne 0) { return }
            }
            & $python -m pip wheel . --no-deps --wheel-dir build/wheels
        }
        finally {
            $env:PYTHONPYCACHEPREFIX = $previousPycachePrefix
        }
    }
}

function Build-Mcp {
    Invoke-InDirectory (Join-Path $platformRoot 'testforge-mcp') {
        New-Item -ItemType Directory -Path 'build' -Force | Out-Null
        & go build -trimpath -o build/testforge-mcp.exe ./cmd/testforge-mcp
    }
}

switch ($Component) {
    'Backend' { Build-Backend }
    'Client' { Build-Client }
    'Worker' { Build-PythonProject 'testforge-worker' }
    'Mcp' { Build-Mcp }
    'All' {
        Build-Backend
        Build-Client
        Build-PythonProject 'testforge-worker'
        Build-Mcp
    }
}
