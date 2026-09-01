param([string]$SdkPath = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path $SdkPath)) { throw 'Pass -SdkPath with the installed Android SDK directory.' }
if (-not (Test-Path (Join-Path $portRoot 'upstream/wiicompiled/runtime/cmake/GuestCoroutines.cmake'))) {
    throw 'Run scripts/Bootstrap.ps1 first.'
}
$propertyPath = [IO.Path]::GetFullPath($SdkPath).Replace('\', '/').Replace(':', '\:')
[IO.File]::WriteAllText((Join-Path $portRoot 'android/local.properties'), "sdk.dir=$propertyPath`n")
Push-Location (Join-Path $portRoot 'android')
try {
    & .\gradlew.bat --console=plain assembleDebug testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build or verification failed.' }
} finally { Pop-Location }
$artifactsPath = Join-Path $portRoot 'artifacts'
New-Item -ItemType Directory -Force $artifactsPath | Out-Null
Copy-Item (Join-Path $portRoot 'android/app/build/outputs/apk/debug/app-debug.apk') (Join-Path $artifactsPath 'WiiCompiled-PortLab-debug.apk')
Write-Host 'Built artifacts/WiiCompiled-PortLab-debug.apk (diagnostics only; not a playable game).'
