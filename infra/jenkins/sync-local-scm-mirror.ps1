param(
    [Parameter(Mandatory = $false)]
    [string]$SourceRepository,
    [Parameter(Mandatory = $false)]
    [string]$MirrorRepository,
    [Parameter(Mandatory = $false)]
    [string]$Commit
)

$ErrorActionPreference = 'Stop'
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($SourceRepository)) {
    $SourceRepository = (Resolve-Path -LiteralPath (Join-Path $scriptDirectory '..\..')).Path
} else {
    $SourceRepository = (Resolve-Path -LiteralPath $SourceRepository).Path
}

if ([string]::IsNullOrWhiteSpace($MirrorRepository)) {
    $workspaceDirectory = Split-Path -Parent $SourceRepository
    $MirrorRepository = Join-Path $workspaceDirectory '.runtime\git\testforge-platform.git'
}

if ([string]::IsNullOrWhiteSpace($Commit)) {
    $Commit = (& git -C $SourceRepository rev-parse HEAD).Trim()
}
if ($LASTEXITCODE -ne 0 -or $Commit -notmatch '^[0-9a-fA-F]{40}$') {
    throw "Cannot resolve a full commit SHA: $Commit"
}

$mirrorParent = Split-Path -Parent $MirrorRepository
New-Item -ItemType Directory -Force -Path $mirrorParent | Out-Null
$mutex = [System.Threading.Mutex]::new($false, 'Local\TestForgeScmMirrorSync')
$locked = $false
try {
    $locked = $mutex.WaitOne([TimeSpan]::FromSeconds(60))
    if (-not $locked) { throw 'Timed out waiting for the local SCM mirror lock' }

    if (-not (Test-Path -LiteralPath $MirrorRepository)) {
        & git clone --mirror $SourceRepository $MirrorRepository
    } else {
        & git -C $MirrorRepository remote set-url origin $SourceRepository
        if ($LASTEXITCODE -eq 0) {
            $headRefspec = '+refs/heads/*:refs/heads/*'
            $tagRefspec = '+refs/tags/*:refs/tags/*'
            & git -C $MirrorRepository fetch --prune origin $headRefspec $tagRefspec
        }
    }
    if ($LASTEXITCODE -ne 0) { throw 'Failed to synchronize the local SCM mirror' }

    & git -C $MirrorRepository cat-file -e "$Commit`^{commit}"
    if ($LASTEXITCODE -ne 0) { throw "SCM mirror does not contain the frozen commit: $Commit" }
    Write-Host "TestForge SCM mirror ready: $Commit"
} finally {
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
