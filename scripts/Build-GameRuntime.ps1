param(
    [ValidateRange(1,64)][int]$Jobs = 8,
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$SigningDirectory = (Join-Path $PSScriptRoot '../private/signing'),
    [string]$ExpectedCertificateSha256 = 'A6B1924D07EFDE502F72F25D32A3A83DF3D97B6B79829B107BC057963C213C39',
    [switch]$BaseOnly
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolchain = Get-Content (Join-Path $portRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$workspace = Join-Path $portRoot 'upstream/wiicompiled'
if (-not (Test-Path (Join-Path $workspace 'generated/build_shards/shards.cmake'))) {
    throw 'Run Prepare-Game.ps1 first.'
}
$cmake = Join-Path $SdkPath "cmake/$($toolchain.android.cmake)/bin/cmake.exe"
$ninja = Join-Path $SdkPath "cmake/$($toolchain.android.cmake)/bin/ninja.exe"
$ndk = Join-Path $SdkPath "ndk/$($toolchain.android.ndk)"
$apksigner = Join-Path $SdkPath 'build-tools/36.0.0/apksigner.bat'
$build = Join-Path $portRoot 'private/android-runtime'
$signingKey = Join-Path $SigningDirectory 'debug.keystore'
if (-not (Test-Path -LiteralPath $signingKey -PathType Leaf)) {
    throw "Required tablet-compatible signing key was not found: $signingKey"
}
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) {
    throw "Android APK signer was not found: $apksigner"
}
& (Join-Path $PSScriptRoot 'Build-NodAndroid.ps1') -SdkPath $SdkPath
if ($LASTEXITCODE -ne 0) { throw 'Android disc extractor dependency build failed.' }

$runtimeTargets = @('WiiCompiled')
if (-not $BaseOnly) {
    $dotnet = Join-Path $portRoot '.tools/dotnet/dotnet.exe'
    $translatorProject = Join-Path $workspace 'translator/src/Translator.Cli/Translator.Cli.csproj'
    $translator = Join-Path $workspace 'translator/src/Translator.Cli/bin/Release/net8.0/Translator.Cli.dll'
    $project = Join-Path $workspace 'projects/mkwii/recomp.yml'
    $modRoot = Join-Path $portRoot 'private/retro-rewind-6.12.4/RetroRewind6'
    $codePul = Join-Path $modRoot 'Binaries/Code.pul'
    $loaderPul = Join-Path $modRoot 'Binaries/Loader.pul'
    $retroXml = Join-Path $modRoot 'xml/RetroRewind6.xml'
    $expectedCodeHash = 'EA93F9B8BF6D7696A807C1DA5BE724F1B0EC3EEA563C1FDC1ADFAB10CB6C98E2'
    $expectedLoaderHash = 'EF396B3116219EF6C5A5C96E73AD138F40505FD958417DE671D11A06049D7167'
    if (-not (Test-Path -LiteralPath $dotnet -PathType Leaf)) { throw 'Run Setup-HostTools.ps1 first.' }
    foreach ($requiredRetroFile in @($codePul, $loaderPul, $retroXml)) {
        if (-not (Test-Path -LiteralPath $requiredRetroFile -PathType Leaf)) {
            throw "Pinned Retro Rewind 6.12.4 input is unavailable: $requiredRetroFile"
        }
    }
    if ((Get-FileHash -LiteralPath $codePul -Algorithm SHA256).Hash -ne $expectedCodeHash) {
        throw 'Pinned Retro Rewind 6.12.4 Code.pul hash changed.'
    }
    if ((Get-FileHash -LiteralPath $loaderPul -Algorithm SHA256).Hash -ne $expectedLoaderHash) {
        throw 'Pinned Retro Rewind 6.12.4 Loader.pul hash changed.'
    }
    & $dotnet build $translatorProject -c Release --nologo
    if ($LASTEXITCODE -ne 0) { throw 'WiiCompiled translator build failed.' }

    $stagedBinaries = Join-Path $workspace 'PulsarPacks/completed/RetroRewind/RetroRewind6/Binaries'
    $stagedXml = Join-Path $workspace 'PulsarPacks/completed/RetroRewind/RetroRewind6/xml'
    [IO.Directory]::CreateDirectory($stagedBinaries) | Out-Null
    [IO.Directory]::CreateDirectory($stagedXml) | Out-Null
    Copy-Item -LiteralPath $codePul -Destination (Join-Path $stagedBinaries 'Code.pul') -Force
    Copy-Item -LiteralPath $loaderPul -Destination (Join-Path $stagedBinaries 'Loader.pul') -Force
    Copy-Item -LiteralPath $retroXml -Destination (Join-Path $stagedXml 'RetroRewind6.xml') -Force
    Push-Location $workspace
    try {
        & $dotnet $translator translate-recursive 0x800060A4 --project $project `
            --outdir generated/functions --output-metadata generated/base_translation_metadata.json `
            --production-source-bundle generated/base_translation_sources.bin --no-function-files `
            --prune-stale --threads $Jobs
        if ($LASTEXITCODE -ne 0) { throw 'Mod-aware base translation failed.' }
        & $dotnet $translator emit-base-manifest --project $project --out build/base `
            --functions-dir generated/functions --translation-output-metadata generated/base_translation_metadata.json `
            --region P
        if ($LASTEXITCODE -ne 0) { throw 'Base manifest generation failed.' }
        & $dotnet $translator translate-mod --project $project --profile retro-rewind `
            --base-manifest build/base/mkwii_base_manifest.json `
            --base-translation-output-metadata generated/base_translation_metadata.json `
            --code-pul $codePul --mod-root $modRoot --mod-name 'Retro Rewind' --region P `
            --out build/mods/retro_rewind_full_cpp --prefer-cached-inputs --emit-cpp `
            --threads $Jobs --skip-retro-wfc
        if ($LASTEXITCODE -ne 0) { throw 'Retro Rewind translation failed.' }
        & $dotnet $translator emit-build-shards --project $project `
            --base-metadata generated/base_translation_metadata.json --base-functions-dir generated/functions `
            --native-source-dir runtime/src --out generated/build_shards `
            --resolved-profile build/mods/retro_rewind_full_cpp/resolved_dispatch_profile.json `
            --retro-cpp-dir build/mods/retro_rewind_full_cpp/cpp
        if ($LASTEXITCODE -ne 0) { throw 'Translated build-shard generation failed.' }
    } finally { Pop-Location }
    $runtimeTargets += 'RetroRewind'
}

& $cmake -S (Join-Path $workspace 'runtime') -B $build -G Ninja `
    "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" `
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_shared `
    -DCMAKE_BUILD_TYPE=Release -DMKW_TRANSLATED_COMPILE_JOBS=4 "-DCMAKE_MAKE_PROGRAM=$ninja"
if ($LASTEXITCODE -ne 0) { throw 'Android runtime configure failed.' }
$nativeTargets = $runtimeTargets + @('main_hook', 'hook_impl')
& $cmake --build $build --target $nativeTargets -j $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Android runtime build failed.' }
$jni = Join-Path $portRoot 'private/android-apk-libs/arm64-v8a'
New-Item -ItemType Directory -Force $jni | Out-Null
Copy-Item (Join-Path $build 'libWiiCompiled.so') $jni
if (-not $BaseOnly) {
    Copy-Item (Join-Path $build 'libRetroRewind.so') $jni
} else {
    $staleRetro = Join-Path $jni 'libRetroRewind.so'
    if (Test-Path $staleRetro) { Remove-Item -LiteralPath $staleRetro }
}
Copy-Item (Join-Path $build 'adrenotools/src/hook/libmain_hook.so') $jni
Copy-Item (Join-Path $build 'adrenotools/src/hook/libhook_impl.so') $jni
Copy-Item (Join-Path $build '_deps/png-build/libpng16.so') $jni
$staleSdl = Join-Path $jni 'libSDL3.so'
if (Test-Path $staleSdl) { Remove-Item -LiteralPath $staleSdl }
Copy-Item (Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so') $jni
Push-Location (Join-Path $portRoot 'android')
$previousAndroidUserHome = $env:ANDROID_USER_HOME
try {
    # Gradle's debug signing config reads debug.keystore from ANDROID_USER_HOME.
    $env:ANDROID_USER_HOME = $SigningDirectory
    & .\gradlew.bat --console=plain -PwithGame assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Local game APK build failed.' }
} finally {
    $env:ANDROID_USER_HOME = $previousAndroidUserHome
    Pop-Location
}
$outputApk = Join-Path $portRoot 'private/WiiCompiled-local-game.apk'
Copy-Item (Join-Path $portRoot 'android/app/build/outputs/apk/debug/app-debug.apk') $outputApk -Force
$certificateOutput = (& $apksigner verify --print-certs $outputApk 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Built APK signature verification failed.`n$certificateOutput" }
$certificateMatch = [regex]::Match($certificateOutput, 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)')
if (-not $certificateMatch.Success) { throw "Could not read the built APK certificate.`n$certificateOutput" }
$actualCertificate = $certificateMatch.Groups[1].Value.ToUpperInvariant()
if ($actualCertificate -ne $ExpectedCertificateSha256.ToUpperInvariant()) {
    throw "Built APK certificate $actualCertificate does not match the tablet certificate $ExpectedCertificateSha256. Installation was not attempted."
}
$apkHash = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash
Write-Host "Built private/WiiCompiled-local-game.apk (SHA-256 $apkHash; certificate $actualCertificate)."
Write-Host 'This private artifact embeds translated game code: do not redistribute it.'


