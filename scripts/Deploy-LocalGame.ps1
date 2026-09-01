param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$SdkPath = $env:ANDROID_HOME,
    [switch]$ReplaceGameData
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
$apk = Join-Path $portRoot 'private/WiiCompiled-local-game.apk'
$disc = Join-Path $portRoot 'private/disc'
if (-not (Test-Path $apk) -or -not (Test-Path (Join-Path $disc 'sys/main.dol'))) {
    throw 'Build the local game APK and prepare the disc first.'
}
& (Join-Path $PSScriptRoot 'Sign-And-Install-LocalGame.ps1') -Serial $Serial -SdkPath $SdkPath -Apk $apk
if (-not $ReplaceGameData) {
    Write-Host 'APK update complete. Existing app and game data were preserved.'
    Write-Host 'Pass -ReplaceGameData only when an intentional full disc-data redeployment is required.'
    exit 0
}
# Replace only this package's staging directory; the receiver recreates it with app ownership.
$remoteRoot = '/sdcard/Android/data/org.wiicompiled.portlab/files/game'
& $adb -s $Serial shell rm -rf $remoteRoot
$port = 38997
& $adb -s $Serial forward "tcp:$port" localabstract:wiicompiled_import
if ($LASTEXITCODE -ne 0) { throw 'Could not create the local ADB forwarding channel.' }
$token = [Guid]::NewGuid().ToString('N')
& $adb -s $Serial shell am start -n org.wiicompiled.portlab/.ImportActivity --es token $token
if ($LASTEXITCODE -ne 0) { throw 'Could not start the app-owned data importer.' }
try {
    & python (Join-Path $portRoot 'scripts/DeployData.py') --port $port --token $token `
        "$disc=disc" `
        "$(Join-Path $portRoot 'private/android-runtime/wii_bootstrap')=wii_bootstrap" `
        "$(Join-Path $portRoot 'private/android-runtime/dsp_coef.bin')=dsp_coef.bin" `
        "$(Join-Path $portRoot 'private/android-runtime/initial_pipeline_cache.db')=initial_pipeline_cache.db"
    if ($LASTEXITCODE -ne 0) { throw 'App-owned data streaming failed.' }
} finally { & $adb -s $Serial forward --remove "tcp:$port" | Out-Null }
Write-Host 'Deployment complete. The app-owned importer launches the experimental game after verification.'


