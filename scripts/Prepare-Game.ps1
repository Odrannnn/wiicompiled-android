param(
    [Parameter(Mandatory=$true)][string]$GameImage,
    [ValidateRange(1,64)][int]$Threads = 8
)
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$workspace = Join-Path $portRoot 'upstream/wiicompiled'
$private = Join-Path $portRoot 'private'
$disc = Join-Path $private 'disc'
$nod = Join-Path $portRoot '.tools/nodtool.exe'
$dotnet = Join-Path $portRoot '.tools/dotnet/dotnet.exe'
foreach ($required in @($GameImage, $nod, $dotnet, (Join-Path $workspace 'projects/mkwii/recomp.yml'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing $required; run Bootstrap.ps1 and Setup-HostTools.ps1 first." }
}
New-Item -ItemType Directory -Force $private | Out-Null
$inputHash = (Get-FileHash -LiteralPath $GameImage -Algorithm SHA256).Hash
$stamp = Join-Path $private 'disc-source-sha256.txt'
if (Test-Path $disc) {
    if (-not (Test-Path $stamp) -or (Get-Content $stamp -Raw).Trim() -ne $inputHash) {
        throw 'Existing private/disc is not recorded as an extraction of this image. Preserve or relocate it before running; nothing was overwritten.'
    }
} else {
    & $nod --no-color extract --validate --quiet $GameImage $disc
    if ($LASTEXITCODE -ne 0) { throw 'Disc extraction/validation failed. Partial output was retained for diagnosis.' }
    Set-Content $stamp $inputHash
}
$pins = @{
    'sys/main.dol' = '80d18895b39c63bd80f457398bfcbb91b7d16ac116a41a88967e954080155b05'
    'files/rel/StaticR.rel' = '16d9d146112541fefea701ecb5bc1a496f9d50e4a752fbb5b6778e7c6399f67d'
}
foreach ($entry in $pins.GetEnumerator()) {
    if ((Get-FileHash (Join-Path $disc $entry.Key) -Algorithm SHA256).Hash -ne $entry.Value) {
        throw "Wrong revision or modified binary: $($entry.Key)"
    }
}
$assets = Join-Path $workspace 'Assets'
New-Item -ItemType Directory -Force $assets | Out-Null
Copy-Item (Join-Path $disc 'sys/main.dol') (Join-Path $assets 'main.dol')
Copy-Item (Join-Path $disc 'files/rel/StaticR.rel') (Join-Path $assets 'StaticR.rel')
$env:DOTNET_CLI_HOME = Join-Path $portRoot '.tools/dotnet-home'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'
$env:DOTNET_GENERATE_ASPNET_CERTIFICATE = 'false'
& $dotnet build (Join-Path $workspace 'translator/src/Translator.Cli/Translator.Cli.csproj') -c Release --nologo
if ($LASTEXITCODE -ne 0) { throw 'Translator build failed.' }
$cli = Join-Path $workspace 'translator/src/Translator.Cli/bin/Release/net8.0/Translator.Cli.dll'
$manifest = Join-Path $workspace 'projects/mkwii/recomp.yml'
$generated = Join-Path $workspace 'generated'
$metadata = Join-Path $generated 'base_translation_metadata.json'
$functions = Join-Path $generated 'functions'
function Invoke-Translator([string[]]$Arguments) {
    & $dotnet $cli @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Translator failed: $($Arguments[0])" }
}
Invoke-Translator -Arguments @('translate-recursive', '0x800060A4', '--project', $manifest, '--outdir', $functions,
    '--output-metadata', $metadata, '--production-source-bundle', (Join-Path $generated 'base_translation_sources.bin'),
    '--no-function-files', '--prune-stale', '--threads', $Threads)
Invoke-Translator -Arguments @('emit-base-manifest', '--project', $manifest, '--out', (Join-Path $workspace 'build/base'),
    '--functions-dir', $functions, '--translation-output-metadata', $metadata, '--region', 'P')
Invoke-Translator -Arguments @('generate-data-init', '--project', $manifest)
Invoke-Translator -Arguments @('emit-build-shards', '--project', $manifest, '--base-metadata', $metadata,
    '--base-functions-dir', $functions, '--native-source-dir', (Join-Path $workspace 'runtime/src'),
    '--out', (Join-Path $generated 'build_shards'))
Write-Host 'Local base-game translation is ready. It is not an Android APK. Keep private/ and upstream/generated data out of distributions.'

