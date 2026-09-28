param(
    [Parameter(Mandatory = $true)][string]$ControllerUrl,
    [Parameter(Mandatory = $true)][string]$NodeName,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [Parameter(Mandatory = $true)][string]$JavaExe,
    [string]$NodeHome
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$controller = $ControllerUrl.TrimEnd('/')
$resolvedWorkDir = [System.IO.Path]::GetFullPath($WorkDir)
New-Item -ItemType Directory -Force -Path $resolvedWorkDir | Out-Null
if ($NodeHome) {
    $resolvedNodeHome = [System.IO.Path]::GetFullPath($NodeHome)
    $env:Path = "$resolvedNodeHome;$env:Path"
}

$agentJar = Join-Path $resolvedWorkDir 'agent.jar'
Invoke-WebRequest -Uri "$controller/jnlpJars/agent.jar" -UseBasicParsing -OutFile $agentJar
$jnlpBytes = (Invoke-WebRequest -Uri "$controller/computer/$NodeName/jenkins-agent.jnlp" -UseBasicParsing).Content
$jnlp = [xml][Text.Encoding]::UTF8.GetString($jnlpBytes)
$agentSecret = [string]$jnlp.jnlp.'application-desc'.argument[0]
if (-not $agentSecret) {
    throw "Jenkins did not return an inbound secret for node $NodeName"
}

& $JavaExe -jar $agentJar `
    -url $controller `
    -secret $agentSecret `
    -name $NodeName `
    -webSocket `
    -workDir $resolvedWorkDir
exit $LASTEXITCODE
