param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$BuildDirectory,
    [ValidateRange(1,64)][int]$Jobs = 8,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspace = Join-Path $portRoot 'upstream/wiicompiled'
$lock = Get-Content (Join-Path $portRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $BuildDirectory) { $BuildDirectory = Join-Path $portRoot '.tools/android-runtime-sdk-build' }
$BuildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
$cmake = Join-Path $SdkPath "cmake/$($lock.android.cmake)/bin/cmake.exe"
$ninja = Join-Path $SdkPath "cmake/$($lock.android.cmake)/bin/ninja.exe"
$ndk = Join-Path $SdkPath "ndk/$($lock.android.ndk)"
foreach ($required in @($cmake,$ninja,(Join-Path $ndk 'build/cmake/android.toolchain.cmake'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing pinned Android tool: $required" }
}
if (-not $SkipBuild) {
    & $cmake -S (Join-Path $workspace 'runtime') -B $BuildDirectory -G Ninja `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" `
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_shared `
        -DCMAKE_BUILD_TYPE=Release -DMKW_BUILD_ANDROID_RUNTIME_SDK=ON "-DCMAKE_MAKE_PROGRAM=$ninja"
    if ($LASTEXITCODE -ne 0) { throw 'Android runtime SDK configure failed.' }
    & $cmake --build $BuildDirectory --target mkw_android_runtime_support main_hook hook_impl -j $Jobs
    if ($LASTEXITCODE -ne 0) { throw 'Android runtime SDK build failed.' }
}

$ninjaFile = Join-Path $BuildDirectory 'build.ninja'
$support = Join-Path $BuildDirectory 'libwiicompiled_runtime_support.a'
foreach ($required in @($ninjaFile,$support)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Runtime SDK output is missing: $required" }
}
$lines = Get-Content -LiteralPath $ninjaFile
$probe = [Array]::FindIndex($lines, [Predicate[string]]{ param($line) $line.StartsWith('build libmkw_android_runtime_link_probe.so:') })
if ($probe -lt 0) { throw 'Runtime SDK link probe was not generated.' }
$linkLine = $null
for ($index = $probe + 1; $index -lt [Math]::Min($probe + 12, $lines.Count); $index++) {
    if ($lines[$index].StartsWith('  LINK_LIBRARIES = ')) { $linkLine = $lines[$index].Substring(19); break }
}
if (-not $linkLine) { throw 'Runtime SDK link closure was not found in build.ninja.' }

$output = Join-Path $portRoot '.tools/android-builder'
$stage = Join-Path $output 'runtime-sdk-stage'
if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
$libStage = Join-Path $stage 'runtime-sdk/lib'
$kit = Join-Path $stage 'kit'
New-Item -ItemType Directory -Force $libStage,$kit | Out-Null
Copy-Item -LiteralPath (Join-Path $workspace 'projects') -Destination $kit -Recurse
Copy-Item -LiteralPath (Join-Path $workspace 'runtime/include') -Destination (Join-Path $kit 'runtime/include') -Recurse
Copy-Item -LiteralPath (Join-Path $workspace 'runtime/src') -Destination (Join-Path $kit 'runtime/src') -Recurse
Copy-Item -LiteralPath (Join-Path $workspace 'runtime/assets') -Destination (Join-Path $kit 'runtime/assets') -Recurse

$arguments = [Collections.Generic.List[string]]::new()
foreach ($token in ($linkLine -split '\s{2,}')) {
    $token = $token.Trim()
    if (-not $token) { continue }
    if ($token -match '[/\\]libz\.so$') { $arguments.Add('-lz'); continue }
    if ($token.EndsWith('.a')) {
        $source = if ([IO.Path]::IsPathRooted($token)) { $token } else { Join-Path $BuildDirectory $token }
        $source = [IO.Path]::GetFullPath($source)
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Link archive is missing: $source" }
        $relative = [IO.Path]::GetRelativePath($BuildDirectory,$source).Replace('..','external').Replace('\','/')
        $destination = Join-Path $libStage $relative
        New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Force
        $arguments.Add("runtime-sdk/lib/$relative")
    } elseif ($token.EndsWith('libpng16.so')) {
        $png = Join-Path $BuildDirectory $token
        $jni = Join-Path $output 'jniLibs/arm64-v8a'
        New-Item -ItemType Directory -Force $jni | Out-Null
        Copy-Item -LiteralPath $png -Destination (Join-Path $jni 'libpng16.so') -Force
        $arguments.Add('@native/libpng16.so')
    } elseif ($token.StartsWith('-')) {
        foreach ($flag in ($token -split '\s+')) { if ($flag) { $arguments.Add($flag) } }
    } else { $arguments.Add($token) }
}
[IO.File]::WriteAllLines((Join-Path $stage 'runtime-sdk/link-arguments.txt'),$arguments)
[IO.File]::WriteAllText((Join-Path $stage 'runtime-sdk/version.txt'),"1`n")

$jni = Join-Path $output 'jniLibs/arm64-v8a'
New-Item -ItemType Directory -Force $jni | Out-Null
$runtimeFiles = @{
    (Join-Path $BuildDirectory 'adrenotools/src/hook/libmain_hook.so') = 'libmain_hook.so'
    (Join-Path $BuildDirectory 'adrenotools/src/hook/libhook_impl.so') = 'libhook_impl.so'
    (Join-Path $ndk 'toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so') = 'libc++_shared.so'
}
foreach ($entry in $runtimeFiles.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Key -PathType Leaf)) { throw "Runtime dependency is missing: $($entry.Key)" }
    Copy-Item -LiteralPath $entry.Key -Destination (Join-Path $jni $entry.Value) -Force
}

$assets = Join-Path $output 'assets'
New-Item -ItemType Directory -Force $assets | Out-Null
$archive = Join-Path $assets 'runtime-sdk.zip'
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($stage,$archive,[IO.Compression.CompressionLevel]::Optimal,$false)
$size = (Get-Item -LiteralPath $archive).Length / 1MB
Write-Host ("Built game-free Android runtime SDK ({0:N1} MiB)." -f $size)
