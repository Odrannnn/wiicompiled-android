param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [ValidateRange(1,64)][int]$Jobs = 8
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock = Get-Content (Join-Path $portRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
$archive = Join-Path $portRoot ".tools/llvm-project-$($lock.llvmAndroid.version).src.tar.xz"
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    New-Item -ItemType Directory -Force (Split-Path $archive) | Out-Null
    Invoke-WebRequest -Uri $lock.llvmAndroid.url -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $lock.llvmAndroid.sha256) {
    throw 'Pinned LLVM source archive checksum mismatch.'
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Desktop is required to cross-build the app-specific compiler.' }
$image = "wiicompiled-llvm-android:$($lock.llvmAndroid.version)-ndk$($lock.android.ndk)"
& docker build --build-arg "ANDROID_NDK_VERSION=$($lock.android.ndk)" -t $image `
    (Join-Path $portRoot 'docker/llvm-android')
if ($LASTEXITCODE -ne 0) { throw 'Pinned Android LLVM builder image failed.' }
$mount = "$($portRoot.Replace('\', '/')):/src:ro"
$output = Join-Path $portRoot '.tools/android-builder'
New-Item -ItemType Directory -Force $output | Out-Null
$outputMount = "$($output.Replace('\', '/')):/out"
$volume = "wiicompiled-llvm-$($lock.llvmAndroid.version)"
& docker volume inspect $volume *> $null
if ($LASTEXITCODE -ne 0) { & docker volume create $volume | Out-Null }
& docker run --rm -e "LLVM_VERSION=$($lock.llvmAndroid.version)" `
    -e "ANDROID_NDK_VERSION=$($lock.android.ndk)" -e "BUILD_JOBS=$Jobs" `
    -v $mount -v "${volume}:/work" -v $outputMount -w /src $image sh /usr/local/bin/build-wiicompiled-llvm
if ($LASTEXITCODE -ne 0) { throw 'App-specific ARM64 Clang/LLD build failed.' }
$bin = Join-Path $portRoot '.tools/android-builder/toolchain/bin'
foreach ($name in @('clang','lld','llvm-ar')) {
    $file = Join-Path $bin $name
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Compiler build did not produce $file" }
}
$jni = Join-Path $portRoot '.tools/android-builder/jniLibs/arm64-v8a'
foreach ($name in @('libwiicompiled_clang.so','libwiicompiled_lld.so','libwiicompiled_llvm_ar.so')) {
    $file = Join-Path $jni $name
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Compiler packaging did not produce $file" }
}
$sdkArchive = Join-Path $portRoot '.tools/android-builder/assets/compiler-sdk.zip'
if (-not (Test-Path -LiteralPath $sdkArchive -PathType Leaf)) { throw "Compiler SDK did not produce $sdkArchive" }
Add-Type -AssemblyName System.IO.Compression
$zip = [IO.Compression.ZipFile]::OpenRead($sdkArchive)
try {
    $entries = @{}; foreach ($entry in $zip.Entries) { $entries[$entry.FullName] = $true }
    foreach ($required in @(
        'toolchain/sysroot/usr/include/stdio.h',
        'toolchain/sysroot/usr/include/c++/v1/vector',
        "toolchain/lib/clang/$($lock.llvmAndroid.version.Split('.')[0])/lib/linux/libclang_rt.builtins-aarch64-android.a",
        "toolchain/lib/clang/$($lock.llvmAndroid.version.Split('.')[0])/lib/linux/aarch64/libatomic.a",
        "toolchain/lib/clang/$($lock.llvmAndroid.version.Split('.')[0])/lib/linux/aarch64/libunwind.a"
    )) {
        if (-not $entries.ContainsKey($required)) { throw "Compiler SDK archive is missing $required" }
    }
} finally { $zip.Dispose() }
Write-Host 'Built relocatable Android ARM64 Clang, LLD, and llvm-ar for the Builder APK.'
