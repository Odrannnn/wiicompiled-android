param([string]$CheckoutRoot = (Join-Path $PSScriptRoot '../upstream'))
$ErrorActionPreference = 'Stop'
$portRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$checkoutBase = [IO.Path]::GetFullPath($CheckoutRoot)
$lock = Get-Content (Join-Path $portRoot 'upstream.lock.json') -Raw | ConvertFrom-Json
New-Item -ItemType Directory -Force $checkoutBase | Out-Null
foreach ($entry in $lock.PSObject.Properties) {
    $destination = Join-Path $checkoutBase $entry.Name
    $safeDestination = $destination.Replace('\', '/')
    if (-not (Test-Path (Join-Path $destination '.git'))) {
        if (Test-Path $destination) { throw "Refusing to overwrite existing directory: $destination" }
        & git clone --no-checkout $entry.Value.url $destination
        if ($LASTEXITCODE -ne 0) { throw "Clone failed for $($entry.Name)" }
        & git -c "safe.directory=$safeDestination" -C $destination checkout --detach $entry.Value.commit
        if ($LASTEXITCODE -ne 0) { throw "Pinned checkout failed for $($entry.Name)" }
    }
    $actual = (& git -c "safe.directory=$safeDestination" -C $destination rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $actual -ne $entry.Value.commit) {
        throw "Expected $($entry.Value.commit) in $destination; found $actual. Existing work was left untouched."
    }
    if ($entry.Value.PSObject.Properties.Name -contains 'submodules' -and $entry.Value.submodules) {
        & git -c "safe.directory=$safeDestination" -C $destination submodule update --init --recursive
        if ($LASTEXITCODE -ne 0) { throw "Submodule initialization failed for $($entry.Name)" }
    }
    $patchFile = Join-Path $portRoot "patches/$($entry.Name)-android.patch"
    if (Test-Path $patchFile) {
        & git -c "safe.directory=$safeDestination" -C $destination apply --reverse --check $patchFile 2>$null
        if ($LASTEXITCODE -ne 0) {
            & git -c "safe.directory=$safeDestination" -C $destination apply --check $patchFile
            if ($LASTEXITCODE -ne 0) { throw "Patch conflicts in $destination. Existing work was left untouched." }
            & git -c "safe.directory=$safeDestination" -C $destination apply $patchFile
            if ($LASTEXITCODE -ne 0) { throw "Patch failed for $($entry.Name)" }
        }
    }
    Write-Host "$($entry.Name): pinned revision ready"
}
