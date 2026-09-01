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
Write-Host 'Workspace-local .NET SDK and nodtool are ready.'
