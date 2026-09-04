param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '../private/signing'),
    [string]$ExpectedCertificateSha256 = 'A6B1924D07EFDE502F72F25D32A3A83DF3D97B6B79829B107BC057963C213C39',
    [ValidateRange(1,64)][int]$Jobs = 8,
    [switch]$SkipPayloadBuild
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock = Get-Content (Join-Path $portRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$signingKey = Join-Path $SigningDirectory 'debug.keystore'
if (-not (Test-Path -LiteralPath $signingKey -PathType Leaf)) { throw "Canonical signing key is missing: $signingKey" }
if (-not $SkipPayloadBuild) {
    & (Join-Path $PSScriptRoot 'Build-NodAndroid.ps1') -SdkPath $SdkPath
    & (Join-Path $PSScriptRoot 'Build-AndroidTranslator.ps1') -SdkPath $SdkPath
    & (Join-Path $PSScriptRoot 'Build-AndroidCompiler.ps1') -SdkPath $SdkPath -Jobs $Jobs
    & (Join-Path $PSScriptRoot 'Build-AndroidRuntimeSdk.ps1') -SdkPath $SdkPath -Jobs $Jobs
}
$requiredPayload = @(
    '.tools/android-builder/assets/compiler-sdk.zip', '.tools/android-builder/assets/runtime-sdk.zip',
    '.tools/android-builder/jniLibs/arm64-v8a/libwiicompiled_translator.so',
    '.tools/android-builder/jniLibs/arm64-v8a/libwiicompiled_clang.so',
    '.tools/android-builder/jniLibs/arm64-v8a/libwiicompiled_lld.so'
)
foreach ($relative in $requiredPayload) {
    if (-not (Test-Path -LiteralPath (Join-Path $portRoot $relative) -PathType Leaf)) { throw "Builder payload is missing: $relative" }
}
$propertyPath = [IO.Path]::GetFullPath($SdkPath).Replace('\','/').Replace(':','\:')
[IO.File]::WriteAllText((Join-Path $portRoot 'android/local.properties'),"sdk.dir=$propertyPath`n")
$oldGradle = $env:GRADLE_USER_HOME; $oldAndroid = $env:ANDROID_USER_HOME
try {
    $env:GRADLE_USER_HOME = Join-Path $portRoot '.gradle-user'
    $env:ANDROID_USER_HOME = Join-Path $portRoot '.android-user'
    New-Item -ItemType Directory -Force $env:GRADLE_USER_HOME,$env:ANDROID_USER_HOME | Out-Null
    Copy-Item -LiteralPath $signingKey -Destination (Join-Path $env:ANDROID_USER_HOME 'debug.keystore') -Force
    Push-Location (Join-Path $portRoot 'android')
    # withBuilder changes source sets and packaging, but Gradle does not treat a project
    # property used during configuration as an assembleDebug task input. Clean first so a
    # prior Port Lab build can never be reused as the Builder artifact.
    try { & .\gradlew.bat --console=plain -PwithBuilder clean assembleDebug }
    finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw 'Builder APK build failed.' }
} finally { $env:GRADLE_USER_HOME=$oldGradle; $env:ANDROID_USER_HOME=$oldAndroid }
$artifacts = Join-Path $portRoot 'artifacts'; New-Item -ItemType Directory -Force $artifacts | Out-Null
$apk = Join-Path $artifacts 'WiiCompiled-Builder-debug.apk'
Copy-Item -LiteralPath (Join-Path $portRoot 'android/app/build/outputs/apk/debug/app-debug.apk') -Destination $apk -Force
$apksigner = Join-Path $SdkPath 'build-tools/36.0.0/apksigner.bat'
$certificateOutput = (& $apksigner verify --verbose --print-certs $apk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Builder APK signature verification failed.`n$certificateOutput" }
$certificate = [regex]::Match($certificateOutput,'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
if (-not $certificate.Success -or $certificate.Groups[1].Value.ToUpperInvariant() -ne $ExpectedCertificateSha256) {
    throw 'Builder APK certificate does not match the recorded tablet certificate.'
}
Add-Type -AssemblyName System.IO.Compression
$zip = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    $entries = @{}
    foreach ($entry in $zip.Entries) {
        $entries[$entry.FullName] = $true
        if ($entry.FullName -match '(?i)(main\.dol|StaticR\.rel|libWiiCompiled\.so|libRetroRewind\.so|\.(rvz|iso|wbfs)$)') {
            throw "Private game material entered the Builder APK: $($entry.FullName)"
        }
    }
    foreach ($requiredEntry in @(
            'assets/compiler-sdk.zip', 'assets/runtime-sdk.zip',
            'lib/arm64-v8a/libwiicompiled_translator.so',
            'lib/arm64-v8a/libwiicompiled_clang.so',
            'lib/arm64-v8a/libwiicompiled_lld.so')) {
        if (-not $entries.ContainsKey($requiredEntry)) {
            throw "Builder APK is missing its required payload: $requiredEntry"
        }
    }
} finally { $zip.Dispose() }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
Write-Host ("Built {0} ({1:N1} MiB; SHA-256 {2})." -f $apk,((Get-Item $apk).Length/1MB),$hash)
