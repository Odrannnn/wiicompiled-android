param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$SdkPath = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
$apk = Join-Path $portRoot 'artifacts/WiiCompiled-PortLab-debug.apk'
if (-not (Test-Path $apk)) { throw 'Run scripts/Build-Android.ps1 first.' }
# Require an explicit serial: never install on the first of multiple devices.
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'APK install failed.' }
& $adb -s $Serial shell am start -n org.wiicompiled.portlab/.MainActivity
if ($LASTEXITCODE -ne 0) { throw 'Activity launch failed.' }
Write-Host 'Tap Run device checks. Results are displayed and can be exported from the app.'
