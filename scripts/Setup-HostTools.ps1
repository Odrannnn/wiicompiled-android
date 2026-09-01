$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolRoot = Join-Path $portRoot '.tools'
$lock = Get-Content (Join-Path $portRoot 'toolchain.lock.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force $toolRoot | Out-Null
$nod = Join-Path $toolRoot 'nodtool.exe'
if (-not (Test-Path $nod)) { Invoke-WebRequest $lock.nodtool.url -OutFile $nod }
if ((Get-FileHash $nod -Algorithm SHA256).Hash -ne $lock.nodtool.sha256) { throw 'nodtool checksum mismatch.' }
$sdkArchive = Join-Path $toolRoot 'dotnet-sdk.zip'
if (-not (Test-Path $sdkArchive)) { Invoke-WebRequest $lock.dotnet.url -OutFile $sdkArchive }
if ((Get-FileHash $sdkArchive -Algorithm SHA512).Hash -ne $lock.dotnet.sha512) { throw '.NET SDK checksum mismatch.' }
$sdkDirectory = Join-Path $toolRoot 'dotnet'
if (-not (Test-Path (Join-Path $sdkDirectory 'dotnet.exe'))) { Expand-Archive $sdkArchive $sdkDirectory }
$vendorArchive = Join-Path $toolRoot "vendored-crates-$($lock.nodVendor.version).tar.zst"
if (-not (Test-Path $vendorArchive)) { Invoke-WebRequest $lock.nodVendor.url -OutFile $vendorArchive }
if ((Get-FileHash $vendorArchive -Algorithm SHA256).Hash -ne $lock.nodVendor.sha256) {
    throw 'NOD vendored-crates checksum mismatch.'
}
Write-Host 'Workspace-local .NET SDK, nodtool, and NOD offline vendor archive are ready.'
