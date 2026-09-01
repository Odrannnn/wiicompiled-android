param([string]$SdkPath = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolRoot = Join-Path $portRoot '.tools'
$source = Join-Path $portRoot 'upstream/nod'
$lock = Get-Content (Join-Path $portRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
$vendorArchive = Join-Path $toolRoot "vendored-crates-$($lock.nodVendor.version).tar.zst"
foreach ($required in @($vendorArchive, (Join-Path $source 'nod-ffi/Cargo.toml'),
        (Join-Path $portRoot 'docker/nod-android/Dockerfile'))) {
    if (-not (Test-Path -LiteralPath $required)) {
        throw "Missing $required; run scripts/Bootstrap.ps1 and scripts/Setup-HostTools.ps1 first."
    }
}
if ((Get-FileHash $vendorArchive -Algorithm SHA256).Hash -ne $lock.nodVendor.sha256) {
    throw 'NOD vendored-crates checksum mismatch.'
}
if (-not (Test-Path (Join-Path $source 'vendor'))) {
    & tar -xf $vendorArchive -C $source
    if ($LASTEXITCODE -ne 0) { throw 'Could not unpack NOD vendored crates.' }
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker Desktop is required for the pinned Linux-hosted NOD Android build.'
}
$image = "wiicompiled-nod-android:rust$($lock.rust.version)-ndk$($lock.android.ndk)"
& docker image inspect $image *> $null
if ($LASTEXITCODE -ne 0) {
    & docker build --build-arg "RUST_TOOLCHAIN=$($lock.rust.version)" `
        --build-arg "ANDROID_NDK_VERSION=$($lock.android.ndk)" -t $image `
        (Join-Path $portRoot 'docker/nod-android')
    if ($LASTEXITCODE -ne 0) { throw 'Pinned NOD Android builder image failed.' }
}
$mount = "$($portRoot.Replace('\', '/')):/src"
$command = @"
set -eu
export NDK=/opt/android-studio/ndk/$($lock.android.ndk)
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=`$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang
export CC_aarch64_linux_android=`$CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
export AR_aarch64_linux_android=`$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar
export CMAKE_TOOLCHAIN_FILE=`$NDK/build/cmake/android.toolchain.cmake
export CMAKE_GENERATOR=Ninja
export ANDROID_ABI=arm64-v8a
export ANDROID_PLATFORM=android-30
cargo +$($lock.rust.version) build --locked --offline --release --target aarch64-linux-android \
  --target-dir /src/.tools/nod-target-linux --manifest-path nod-ffi/Cargo.toml --no-default-features \
  --features compress-bzip2-vendored,compress-lzma-vendored,compress-zlib-vendored,compress-zstd-vendored,threading
"@
& docker run --rm -v $mount -w /src/upstream/nod $image sh -lc $command
if ($LASTEXITCODE -ne 0) { throw 'NOD ARM64 Android build failed.' }
$output = Join-Path $toolRoot 'nod-android'
New-Item -ItemType Directory -Force (Join-Path $output 'include'), (Join-Path $output 'lib/arm64-v8a') | Out-Null
Copy-Item (Join-Path $source 'nod-ffi/include/nod.h') (Join-Path $output 'include/nod.h') -Force
Copy-Item (Join-Path $toolRoot 'nod-target-linux/aarch64-linux-android/release/libnod.a') `
    (Join-Path $output 'lib/arm64-v8a/libnod.a') -Force
Write-Host 'Built the pinned NOD RVZ/WBFS/WIA/ISO reader for Android arm64-v8a.'
