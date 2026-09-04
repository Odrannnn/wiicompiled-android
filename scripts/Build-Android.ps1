param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '../private/signing'),
    [string]$ExpectedCertificateSha256 = 'A6B1924D07EFDE502F72F25D32A3A83DF3D97B6B79829B107BC057963C213C39'
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock = Get-Content (Join-Path $portRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path $SdkPath)) { throw 'Pass -SdkPath with the installed Android SDK directory.' }
foreach ($required in @(
        (Join-Path $SdkPath "ndk/$($lock.android.ndk)"),
        (Join-Path $SdkPath "cmake/$($lock.android.cmake)/bin/cmake.exe"),
        (Join-Path $SdkPath 'platforms/android-36'))) {
    if (-not (Test-Path $required)) { throw "Pinned Android toolchain component is missing: $required" }
}
if (-not (Test-Path (Join-Path $portRoot 'upstream/wiicompiled/runtime/cmake/GuestCoroutines.cmake'))) {
    throw 'Run scripts/Bootstrap.ps1 first.'
}
$propertyPath = [IO.Path]::GetFullPath($SdkPath).Replace('\', '/').Replace(':', '\:')
[IO.File]::WriteAllText((Join-Path $portRoot 'android/local.properties'), "sdk.dir=$propertyPath`n")
& (Join-Path $PSScriptRoot 'Build-NodAndroid.ps1') -SdkPath $SdkPath
if ($LASTEXITCODE -ne 0) { throw 'Android disc extractor dependency build failed.' }
Push-Location (Join-Path $portRoot 'android')
$previousGradleHome = $env:GRADLE_USER_HOME
$previousAndroidUserHome = $env:ANDROID_USER_HOME
try {
    $env:GRADLE_USER_HOME = Join-Path $portRoot '.gradle-user'
    $env:ANDROID_USER_HOME = Join-Path $portRoot '.android-user'
    New-Item -ItemType Directory -Force $env:GRADLE_USER_HOME, $env:ANDROID_USER_HOME | Out-Null
    $privateSigningKey = Join-Path $SigningDirectory 'debug.keystore'
    if (Test-Path -LiteralPath $privateSigningKey -PathType Leaf) {
        Copy-Item -LiteralPath $privateSigningKey -Destination (Join-Path $env:ANDROID_USER_HOME 'debug.keystore') -Force
    } else {
        Write-Warning "Canonical signing key is unavailable at $privateSigningKey; this build cannot update the target tablet."
    }
    # The Builder uses the same debug variant name with configuration-time source sets.
    # Cleaning prevents Gradle from reusing a Builder package for the public Port Lab.
    & .\gradlew.bat --console=plain clean assembleDebug testDebugUnitTest lintDebug
    if ($LASTEXITCODE -ne 0) { throw 'Android build or verification failed.' }
} finally {
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:ANDROID_USER_HOME = $previousAndroidUserHome
    Pop-Location
}
$artifactsPath = Join-Path $portRoot 'artifacts'
New-Item -ItemType Directory -Force $artifactsPath | Out-Null
Copy-Item (Join-Path $portRoot 'android/app/build/outputs/apk/debug/app-debug.apk') (Join-Path $artifactsPath 'WiiCompiled-PortLab-debug.apk')
$builtApk = Join-Path $artifactsPath 'WiiCompiled-PortLab-debug.apk'
Add-Type -AssemblyName System.IO.Compression
$publicZip = [IO.Compression.ZipFile]::OpenRead($builtApk)
try {
    foreach ($entry in $publicZip.Entries) {
        if ($entry.FullName -match '(?i)(compiler-sdk\.zip|runtime-sdk\.zip|libwiicompiled_(translator|clang|lld|llvm_ar)\.so|main\.dol|StaticR\.rel|libWiiCompiled\.so|libRetroRewind\.so|\.(rvz|iso|wbfs)$)') {
            throw "Builder or private game material entered the public Port Lab APK: $($entry.FullName)"
        }
    }
} finally { $publicZip.Dispose() }
if (Test-Path -LiteralPath (Join-Path $SigningDirectory 'debug.keystore') -PathType Leaf) {
    $apksigner = Join-Path $SdkPath 'build-tools/36.0.0/apksigner.bat'
    $certificateOutput = (& $apksigner verify --print-certs $builtApk 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw "Built APK signature verification failed.`n$certificateOutput" }
    $certificateMatch = [regex]::Match($certificateOutput, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
    if (-not $certificateMatch.Success) { throw "Could not read the built APK certificate.`n$certificateOutput" }
    if ($certificateMatch.Groups[1].Value.ToUpperInvariant() -ne $ExpectedCertificateSha256.ToUpperInvariant()) {
        throw "Built APK certificate does not match the recorded tablet certificate $ExpectedCertificateSha256."
    }
}
Write-Host 'Built artifacts/WiiCompiled-PortLab-debug.apk (diagnostics only; not a playable game).'
