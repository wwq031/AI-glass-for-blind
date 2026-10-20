param([string]$ReleaseDirectory = $PSScriptRoot)
$ErrorActionPreference = 'Stop'
$releaseRoot = (Resolve-Path -LiteralPath $ReleaseDirectory).Path
$manifest = Get-Content -LiteralPath (Join-Path $releaseRoot 'manifest-v0.4.1.json') -Raw | ConvertFrom-Json
if ($manifest.apk_name -notmatch '^phone-l-speech-v[0-9]+\.[0-9]+\.[0-9]+\.apk$' -or
    $manifest.apk_sha256 -notmatch '^[0-9a-f]{64}$' -or $manifest.apk_bytes -le 0) {
    throw 'Invalid APK manifest'
}
$parts = @($manifest.parts)
if ($parts.Count -lt 1 -or $parts.Count -gt 16) { throw 'Invalid part count' }
$seen = @{}
$total = 0L
foreach ($part in $parts) {
    if ($part.name -notmatch '^phone-l-speech-v[0-9]+\.[0-9]+\.[0-9]+\.apk\.part[0-9]{2}$' -or
        $seen.ContainsKey($part.name) -or $part.sha256 -notmatch '^[0-9a-f]{64}$' -or $part.bytes -le 0) {
        throw 'Invalid part metadata'
    }
    $seen[$part.name] = $true
    $path = Join-Path $releaseRoot $part.name
    if (!(Test-Path -LiteralPath $path -PathType Leaf) -or
        (Get-Item -LiteralPath $path).Length -ne $part.bytes) { throw "Missing or incomplete part: $($part.name)" }
    Write-Output "Checking $($part.name)"
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $part.sha256) {
        throw "Part checksum mismatch: $($part.name)"
    }
    $total += $part.bytes
}
if ($total -ne $manifest.apk_bytes) { throw 'Total size mismatch' }
$destination = Join-Path $releaseRoot $manifest.apk_name
if (Test-Path -LiteralPath $destination) {
    if ((Get-Item -LiteralPath $destination).Length -eq $manifest.apk_bytes -and
        (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -eq $manifest.apk_sha256) {
        Write-Output "Already verified: $destination"
        exit 0
    }
    throw 'Existing APK differs; move it elsewhere before restoring'
}
$temporary = Join-Path $releaseRoot ('.restore-' + [Guid]::NewGuid().ToString('N') + '.partial')
try {
    $output = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        foreach ($part in $parts) {
            Write-Output "Merging $($part.name)"
            $inputStream = [IO.File]::OpenRead((Join-Path $releaseRoot $part.name))
            try { $inputStream.CopyTo($output, 1048576) } finally { $inputStream.Dispose() }
        }
        $output.Flush($true)
    } finally { $output.Dispose() }
    if ((Get-Item -LiteralPath $temporary).Length -ne $manifest.apk_bytes -or
        (Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $manifest.apk_sha256) {
        throw 'Restored APK checksum mismatch'
    }
    Move-Item -LiteralPath $temporary -Destination $destination
    Write-Output "Verified APK ready: $destination"
    Write-Output 'Copy this single APK to the phone and install. Do not install the .part files.'
} finally {
    if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary }
}
