param([string]$OutputDirectory = (Join-Path $PSScriptRoot '../artifacts/models'))
$ErrorActionPreference = 'Stop'
$revision = 'b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1'
$filename = 'gemma-4-E2B-it.litertlm'
$expected = '181938105E0EEFD105961417E8DA75903EACDA102C4FCE9CE90F50B97139A63C'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$destination = Join-Path (Resolve-Path -LiteralPath $OutputDirectory).Path $filename
function Test-Weights([string]$Path) {
    (Test-Path -LiteralPath $Path -PathType Leaf) -and
    ((Get-Item -LiteralPath $Path).Length -eq 2588147712) -and
    ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $expected)
}
if (Test-Weights $destination) { Write-Output "Verified: $destination"; exit 0 }
if (Test-Path -LiteralPath $destination) { throw 'Existing model is not the pinned model; choose another output directory.' }
$partial = "$destination.part"
foreach ($hostName in @('huggingface.co', 'hf-mirror.com')) {
    $url = "https://$hostName/litert-community/gemma-4-E2B-it-litert-lm/resolve/$revision/$filename"
    & curl.exe --disable -4 --http1.1 --location --fail --show-error --retry 2 --connect-timeout 20 --max-time 7200 --continue-at - --output $partial $url
    if ($LASTEXITCODE -eq 0) { break }
}
if (!(Test-Weights $partial)) { throw 'Download incomplete or SHA-256 mismatch; no model published.' }
Move-Item -LiteralPath $partial -Destination $destination
Write-Output "Verified: $destination"
