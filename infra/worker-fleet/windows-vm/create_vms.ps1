[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string]$WindowsIso,
    [Parameter(Mandatory)] [string]$PayloadIso,
    [Parameter(Mandatory)] [string]$AnswerIsoA,
    [Parameter(Mandatory)] [string]$AnswerIsoB,
    [string]$VmRoot = 'F:\testforge-vm\machines',
    [string]$SwitchName = 'Default Switch'
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = [IO.Path]::GetFullPath($VmRoot)
if ($resolvedRoot -ne 'F:\testforge-vm\machines') {
    throw "Unexpected VM root: $resolvedRoot"
}
foreach ($path in $WindowsIso, $PayloadIso, $AnswerIsoA, $AnswerIsoB) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "ISO missing: $path" }
}
New-Item -ItemType Directory -Force -Path $resolvedRoot | Out-Null

$definitions = @(
    @{ Name = 'TF-WIN-A'; Answer = $AnswerIsoA },
    @{ Name = 'TF-WIN-B'; Answer = $AnswerIsoB }
)
foreach ($definition in $definitions) {
    if (Get-VM -Name $definition.Name -ErrorAction SilentlyContinue) {
        throw "VM already exists; refusing to overwrite: $($definition.Name)"
    }
    $path = Join-Path $resolvedRoot $definition.Name
    New-Item -ItemType Directory -Force -Path $path | Out-Null
    $vhd = Join-Path $path "$($definition.Name).vhdx"
    $vm = New-VM -Name $definition.Name -Generation 2 -Path $path `
        -NewVHDPath $vhd -NewVHDSizeBytes 80GB -MemoryStartupBytes 8GB `
        -SwitchName $SwitchName
    Set-VMProcessor -VM $vm -Count 4
    Set-VMMemory -VM $vm -DynamicMemoryEnabled $true -MinimumBytes 4GB `
        -StartupBytes 8GB -MaximumBytes 12GB
    Set-VM -VM $vm -AutomaticStartAction Nothing -AutomaticStopAction ShutDown `
        -CheckpointType Production
    Set-VMFirmware -VM $vm -EnableSecureBoot On `
        -SecureBootTemplate MicrosoftWindows
    Set-VMKeyProtector -VM $vm -NewLocalKeyProtector
    Enable-VMTPM -VM $vm
    $windowsDrive = Add-VMDvdDrive -VM $vm -Path $WindowsIso -Passthru
    Add-VMDvdDrive -VM $vm -Path $definition.Answer | Out-Null
    Add-VMDvdDrive -VM $vm -Path $PayloadIso | Out-Null
    Set-VMFirmware -VM $vm -FirstBootDevice $windowsDrive
    Set-VMVideo -VM $vm -HorizontalResolution 1600 -VerticalResolution 900 `
        -ResolutionType Single
}

Start-VM -Name ($definitions.Name)
Get-VM -Name ($definitions.Name) | Select-Object Name, State, Status, CPUUsage, MemoryAssigned
