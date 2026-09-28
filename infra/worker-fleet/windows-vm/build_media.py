#!/usr/bin/env python3
"""Build unattended and payload ISO files for isolated Windows UI workers."""

from __future__ import annotations

import argparse
import tempfile
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

import pycdlib


def add_iso_file(iso: pycdlib.PyCdlib, source: Path, name: str) -> None:
    iso.add_file(
        str(source),
        iso_path=f"/{name.upper()[:8]};1",
        joliet_path=f"/{name}",
        udf_path=f"/{name}",
    )


def build_payload(staging: Path, bootstrap: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    required = (
        "python-3.11.9-amd64.exe",
        "vc_redist.x64.exe",
        "start-environment.ps1",
        "register-environment-task.ps1",
        "airtest-assets/layout-profiles.json",
        "airtest-assets/normal_hit.air/normal_hit.py",
    )
    missing = [name for name in required if not (staging / name).is_file()]
    if missing:
        raise FileNotFoundError(f"payload staging is missing required files: {', '.join(missing)}")
    if not list(staging.glob("testforge_worker-*.whl")):
        raise FileNotFoundError("payload staging is missing testforge_worker wheel")
    with tempfile.TemporaryDirectory() as temporary:
        temporary_root = Path(temporary)
        archive = temporary_root / "payload.zip"
        with zipfile.ZipFile(
            archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True
        ) as bundle:
            for path in sorted(staging.rglob("*")):
                if path.is_file():
                    bundle.write(path, path.relative_to(staging))
        marker = temporary_root / "testforge-payload.marker"
        marker.write_text("TFP-032\n", encoding="utf-8")
        iso = pycdlib.PyCdlib()
        iso.new(interchange_level=3, joliet=3, udf="2.60", vol_ident="TESTFORGE")
        add_iso_file(iso, archive, "payload.zip")
        add_iso_file(iso, bootstrap, "bootstrap.ps1")
        add_iso_file(iso, marker, "testforge-payload.marker")
        iso.write(str(output))
        iso.close()


def answer_xml(vm_name: str, password: str) -> str:
    name = escape(vm_name)
    secret = escape(password)
    first_logon = escape(
        "$v=(Get-CimInstance Win32_LogicalDisk | Where-Object { "
        "$_.DriveType -eq 5 -and (Test-Path ($_.DeviceID+'\\testforge-payload.marker')) "
        "} | Select-Object -First 1).DeviceID; "
        "if (-not $v) { throw 'TestForge payload media not found' }; "
        "& ($v+'\\bootstrap.ps1')"
    )
    return f'''<?xml version="1.0" encoding="utf-8"?>
<unattend xmlns="urn:schemas-microsoft-com:unattend">
  <settings pass="windowsPE">
    <component name="Microsoft-Windows-International-Core-WinPE" processorArchitecture="amd64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">
      <SetupUILanguage><UILanguage>zh-CN</UILanguage></SetupUILanguage>
      <InputLocale>0804:00000804</InputLocale><SystemLocale>zh-CN</SystemLocale><UILanguage>zh-CN</UILanguage><UserLocale>zh-CN</UserLocale>
    </component>
    <component name="Microsoft-Windows-Setup" processorArchitecture="amd64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">
      <DiskConfiguration>
        <Disk wcm:action="add"><DiskID>0</DiskID><WillWipeDisk>true</WillWipeDisk>
          <CreatePartitions>
            <CreatePartition wcm:action="add"><Order>1</Order><Type>EFI</Type><Size>260</Size></CreatePartition>
            <CreatePartition wcm:action="add"><Order>2</Order><Type>MSR</Type><Size>16</Size></CreatePartition>
            <CreatePartition wcm:action="add"><Order>3</Order><Type>Primary</Type><Extend>true</Extend></CreatePartition>
          </CreatePartitions>
          <ModifyPartitions>
            <ModifyPartition wcm:action="add"><Order>1</Order><PartitionID>1</PartitionID><Format>FAT32</Format><Label>System</Label></ModifyPartition>
            <ModifyPartition wcm:action="add"><Order>2</Order><PartitionID>3</PartitionID><Format>NTFS</Format><Label>Windows</Label><Letter>C</Letter></ModifyPartition>
          </ModifyPartitions>
        </Disk><WillShowUI>OnError</WillShowUI>
      </DiskConfiguration>
      <ImageInstall><OSImage><InstallFrom><MetaData wcm:action="add"><Key>/IMAGE/INDEX</Key><Value>1</Value></MetaData></InstallFrom><InstallTo><DiskID>0</DiskID><PartitionID>3</PartitionID></InstallTo><WillShowUI>OnError</WillShowUI></OSImage></ImageInstall>
      <UserData><AcceptEula>true</AcceptEula><FullName>TestForge</FullName><Organization>TestForge</Organization></UserData>
    </component>
  </settings>
  <settings pass="specialize">
    <component name="Microsoft-Windows-Shell-Setup" processorArchitecture="amd64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS"><ComputerName>{name}</ComputerName><TimeZone>China Standard Time</TimeZone></component>
  </settings>
  <settings pass="oobeSystem">
    <component name="Microsoft-Windows-International-Core" processorArchitecture="amd64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS"><InputLocale>0804:00000804</InputLocale><SystemLocale>zh-CN</SystemLocale><UILanguage>zh-CN</UILanguage><UserLocale>zh-CN</UserLocale></component>
    <component name="Microsoft-Windows-Shell-Setup" processorArchitecture="amd64" publicKeyToken="31bf3856ad364e35" language="neutral" versionScope="nonSxS" xmlns:wcm="http://schemas.microsoft.com/WMIConfig/2002/State">
      <OOBE><HideEULAPage>true</HideEULAPage><HideLocalAccountScreen>true</HideLocalAccountScreen><HideOnlineAccountScreens>true</HideOnlineAccountScreens><HideWirelessSetupInOOBE>true</HideWirelessSetupInOOBE><NetworkLocation>Work</NetworkLocation><ProtectYourPC>3</ProtectYourPC></OOBE>
      <UserAccounts><LocalAccounts><LocalAccount wcm:action="add"><Name>TestForge</Name><DisplayName>TestForge</DisplayName><Group>Administrators</Group><Password><Value>{secret}</Value><PlainText>true</PlainText></Password></LocalAccount></LocalAccounts></UserAccounts>
      <AutoLogon><Enabled>true</Enabled><LogonCount>999</LogonCount><Username>TestForge</Username><Password><Value>{secret}</Value><PlainText>true</PlainText></Password></AutoLogon>
      <FirstLogonCommands><SynchronousCommand wcm:action="add"><Order>1</Order><Description>Bootstrap TestForge</Description><RequiresUserInput>false</RequiresUserInput><CommandLine>powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "{first_logon}"</CommandLine></SynchronousCommand></FirstLogonCommands>
    </component>
  </settings>
</unattend>
'''


def build_answer(vm_name: str, password: str, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as temporary:
        answer = Path(temporary) / "Autounattend.xml"
        answer.write_text(answer_xml(vm_name, password), encoding="utf-8")
        iso = pycdlib.PyCdlib()
        iso.new(interchange_level=3, joliet=3, udf="2.60", vol_ident="AUTOUNATTEND")
        add_iso_file(iso, answer, "Autounattend.xml")
        iso.write(str(output))
        iso.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    payload = subparsers.add_parser("payload")
    payload.add_argument("--staging", type=Path, required=True)
    payload.add_argument("--bootstrap", type=Path, required=True)
    payload.add_argument("--output", type=Path, required=True)
    answer = subparsers.add_parser("answer")
    answer.add_argument("--vm-name", required=True)
    answer.add_argument("--password-file", type=Path, required=True)
    answer.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "payload":
        build_payload(args.staging.resolve(), args.bootstrap.resolve(), args.output.resolve())
    else:
        build_answer(
            args.vm_name,
            args.password_file.read_text(encoding="utf-8").strip(),
            args.output.resolve(),
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
