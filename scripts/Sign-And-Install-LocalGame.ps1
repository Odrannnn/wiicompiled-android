param(
    [string]$Serial = 'adb-HA25JV53-ljn454._adb-tls-connect._tcp',
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$Apk,
    [string]$ExpectedCertificateSha256 = 'A6B1924D07EFDE502F72F25D32A3A83DF3D97B6B79829B107BC057963C213C39',
    [switch]$Launch
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $Apk) { $Apk = Join-Path $portRoot 'private/WiiCompiled-local-game.apk' }
$adb = Join-Path $SdkPath 'platform-tools/adb.exe'
$apksigner = Join-Path $SdkPath 'build-tools/36.0.0/apksigner.bat'
$packageName = 'org.wiicompiled.portlab'

foreach ($requiredFile in @($adb, $apksigner, $Apk)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw "Required file was not found: $requiredFile" }
}

function Get-ApkCertificate([string]$Path) {
    $output = (& $apksigner verify --print-certs $Path 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed for $Path.`n$output" }
    $match = [regex]::Match($output, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    if (-not $match.Success) { throw "Could not read the APK certificate for $Path.`n$output" }
    return $match.Groups[1].Value.ToUpperInvariant()
}

$expectedCertificate = $ExpectedCertificateSha256.ToUpperInvariant()
$candidateCertificate = Get-ApkCertificate $Apk
if ($candidateCertificate -ne $expectedCertificate) {
    throw "Refusing installation: candidate certificate $candidateCertificate does not match tablet certificate $expectedCertificate."
}

$deviceState = (& $adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $deviceState -ne 'device') {
    throw "ADB endpoint '$Serial' is not ready (state: '$deviceState')."
}

$installedPathOutput = (& $adb -s $Serial shell pm path $packageName 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -eq 0 -and $installedPathOutput -match '(?m)^package:(.+base\.apk)\s*$') {
    $installedRemotePath = $Matches[1].Trim()
    $installedBackup = Join-Path $portRoot 'private/device-installed-before-update.apk'
    & $adb -s $Serial pull $installedRemotePath $installedBackup | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Could not pull the installed APK for certificate verification.' }
    $installedCertificate = Get-ApkCertificate $installedBackup
    if ($installedCertificate -ne $expectedCertificate) {
        throw "Refusing installation: installed certificate $installedCertificate does not match expected tablet certificate $expectedCertificate. No uninstall was attempted."
    }
    Write-Host "Installed certificate verified: $installedCertificate"
} elseif ($installedPathOutput) {
    Write-Host "Package is not currently installed or has no readable base APK: $installedPathOutput"
}

$candidateHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash
Write-Host "Installing $Apk"
Write-Host "APK SHA-256: $candidateHash"
& $adb -s $Serial install -r $Apk | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'ADB package update failed. No uninstall or data clear was attempted.' }

$packageStatus = (& $adb -s $Serial shell dumpsys package $packageName 2>&1 | Select-String -Pattern 'versionName=|lastUpdateTime=' | ForEach-Object { $_.Line.Trim() })
if ($LASTEXITCODE -ne 0 -or -not $packageStatus) { throw 'Package update completed, but post-install package verification failed.' }
$packageStatus | ForEach-Object { Write-Host $_ }

if ($Launch) {
    & $adb -s $Serial shell am start -n "$packageName/.MainActivity" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Package installed, but MainActivity did not launch.' }
}
Write-Host 'Guarded update complete; application and game data were preserved.'
