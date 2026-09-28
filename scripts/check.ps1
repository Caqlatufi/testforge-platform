[CmdletBinding()]
param([switch]$SkipInstall, [switch]$ComposeSmoke)

$ErrorActionPreference = 'Stop'
$platformRoot = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
    param([string]$Name, [string]$Path, [scriptblock]$Action)
    Write-Host "[Check] $Name"
    Push-Location -LiteralPath $Path
    try {
        $global:LASTEXITCODE = 0
        & $Action
        if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
}

Invoke-Checked 'Java tests and module boundaries' (Join-Path $platformRoot 'testforge-app') {
    & .\gradlew.bat test verifyModuleBoundaries --no-daemon
}
Invoke-Checked 'Python worker tests' (Join-Path $platformRoot 'testforge-worker') {
    $python = if (Test-Path '.venv/Scripts/python.exe') { (Resolve-Path '.venv/Scripts/python.exe').Path } else { (Get-Command python).Source }
    if (-not $SkipInstall) {
        & $python -m pip install -e .
        if ($LASTEXITCODE -ne 0) { return }
    }
    & $python -m pytest -q
}
Invoke-Checked 'TestForge MCP tests and vet' (Join-Path $platformRoot 'testforge-mcp') {
    & go test ./...
    if ($LASTEXITCODE -ne 0) { return }
    & go vet ./...
}
Invoke-Checked 'Vue tests' (Join-Path $platformRoot 'testforge-client') {
    if (-not $SkipInstall) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { return }
    }
    & npm.cmd test -- --run
}
Invoke-Checked 'OpenAPI and JSON schemas' (Join-Path $platformRoot 'contracts') {
    if (-not $SkipInstall) {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { return }
    }
    & npm.cmd run validate
}
Invoke-Checked 'Acceptance scaffold and evidence manifest' $platformRoot {
    & python acceptance/validate.py
    if ($LASTEXITCODE -ne 0) { return }
    & python acceptance/evidence/build_index.py --check
}
if ($ComposeSmoke) {
    & (Join-Path $platformRoot 'acceptance/smoke/compose-smoke.ps1')
    if ($LASTEXITCODE -ne 0) { throw "Compose smoke failed with exit code $LASTEXITCODE" }
}
Write-Host '[Check] All selected checks passed.'
