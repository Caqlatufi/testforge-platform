[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet('Backend', 'Client', 'Worker')]
    [string]$Component
)

$ErrorActionPreference = 'Stop'
$platformRoot = Split-Path -Parent $PSScriptRoot

function Import-LocalEnvironment {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $parts = $trimmed.Split('=', 2)
        if ($parts.Count -eq 2) {
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1], 'Process')
        }
    }
}

switch ($Component) {
    'Backend' {
        Import-LocalEnvironment (Join-Path $platformRoot '.env.local')
        Push-Location (Join-Path $platformRoot 'testforge-app')
        try { & .\gradlew.bat :app:bootRun } finally { Pop-Location }
    }
    'Client' {
        Push-Location (Join-Path $platformRoot 'testforge-client')
        try {
            if (-not (Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci }
            if ($LASTEXITCODE -eq 0) { & npm.cmd run dev }
        }
        finally { Pop-Location }
    }
    'Worker' {
        Push-Location (Join-Path $platformRoot 'testforge-worker')
        try { & python -m testforge_worker --serve } finally { Pop-Location }
    }
}
