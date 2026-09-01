param([string]$SdkPath = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$lock = Get-Content (Join-Path $portRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$dotnet = Join-Path $portRoot '.tools/dotnet/dotnet.exe'
$project = Join-Path $portRoot 'upstream/wiicompiled/translator/src/Translator.Cli/Translator.Cli.csproj'
$ndkBin = Join-Path $SdkPath "ndk/$($lock.android.nativeAotNdk)/toolchains/llvm/prebuilt/windows-x86_64/bin"
foreach ($required in @($dotnet, $project, (Join-Path $ndkBin 'clang.exe'))) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing pinned Android translator input: $required" }
}
$runtimeLine = (& $dotnet --list-runtimes | Select-String '^Microsoft.NETCore.App ' | Select-Object -First 1).Line
if ($runtimeLine -notmatch "^Microsoft\.NETCore\.App $([regex]::Escape($lock.android.nativeAotRuntime)) ") {
    throw "Pinned .NET Native AOT runtime $($lock.android.nativeAotRuntime) is unavailable. Run scripts/Setup-HostTools.ps1."
}
$oldPath = $env:PATH
try {
    $env:PATH = "$ndkBin;$oldPath"
    & $dotnet publish $project -c Release -r linux-bionic-arm64 `
        -p:PublishAot=true -p:DisableUnsupportedError=true -p:PublishAotUsingRuntimePack=true `
        -p:JsonSerializerIsReflectionEnabledByDefault=true `
        -p:StripSymbols=true -p:TreatWarningsAsErrors=false -p:IlcTreatWarningsAsErrors=false `
        -p:SuppressTrimAnalysisWarnings=true -p:IlcGenerateCompleteTypeMetadata=true --nologo
    if ($LASTEXITCODE -ne 0) { throw 'Android Native AOT translator build failed.' }
} finally { $env:PATH = $oldPath }
$published = Join-Path (Split-Path $project) 'bin/Release/net8.0/linux-bionic-arm64/publish/Translator.Cli'
$destination = Join-Path $portRoot '.tools/android-builder/jniLibs/arm64-v8a/libwiicompiled_translator.so'
New-Item -ItemType Directory -Force (Split-Path $destination) | Out-Null
Copy-Item -LiteralPath $published -Destination $destination -Force
$header = [IO.File]::ReadAllBytes($destination)[0..19]
$machine = $header[18] -bor ($header[19] -shl 8)
if ($header[0] -ne 0x7f -or $header[1] -ne 0x45 -or $header[2] -ne 0x4c -or $header[3] -ne 0x46 `
        -or $header[4] -ne 2 -or $header[5] -ne 1 -or $machine -ne 183) {
    throw 'Native AOT output is not an ARM64 ELF binary.'
}
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
Write-Host "Built private Android translator ($((Get-Item $destination).Length) bytes; SHA-256 $hash)."
