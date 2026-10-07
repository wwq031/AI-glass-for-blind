<#
.SYNOPSIS
    Publish the 2026-09-30 device handoff snapshot as a GitHub pre-release.

.DESCRIPTION
    Uploads the eight release assets of artifacts/team-handoff-20260930 to
    https://api.github.com/repos/<Repo>/releases as a PRE-RELEASE tagged
    device-handoff-20260930.

    The uploaded snapshot is NOT device-accepted. See the package README.md.

    Credential handling:
      * The token is obtained ONLY through `git credential fill` for host
        github.com (Git Credential Manager). No environment variable, no
        prompt, no file.
      * The token is held in a local variable for the lifetime of this
        process, never written to disk, never echoed, and cleared before exit.
      * Redirects are never followed: any redirect response aborts this
        script, so the Authorization header cannot be replayed elsewhere.
      * The Authorization header is attached per request and only when the
        target host is api.github.com or uploads.github.com.

    This script does NOT commit, does NOT push, and does NOT create tags in
    the local repository.

.PARAMETER Repo
    owner/name of the GitHub repository.

.PARAMETER Tag
    Release tag to create or reuse.

.PARAMETER PackageDir
    Directory holding the seven release assets (defaults to the packaged
    handoff snapshot next to this script).

.PARAMETER Commitish
    target_commitish for a newly created release. Defaults to the current
    git HEAD of the working tree (the snapshot's recorded base commit).

.PARAMETER DryRun
    Verify local files and resolve the release, but upload nothing.

.EXAMPLE
    pwsh -File tools/publish-team-handoff.ps1 -DryRun
    pwsh -File tools/publish-team-handoff.ps1
#>
[CmdletBinding()]
param(
    [string] $Repo       = 'wwq031/AI-glass-for-blind',
    [string] $Tag        = 'device-handoff-20260930',
    [string] $PackageDir = (Join-Path $PSScriptRoot '..\artifacts\team-handoff-20260930'),
    [string] $Commitish,
    [switch] $DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http | Out-Null

# Assets are uploaded in this order (largest last so small failures surface fast).
$Assets = @(
    'README.md'
    'manifest.json'
    'SHA256SUMS.txt'
    'sherpa-onnx-1.13.8.aar'
    'glasses-bt-debug.apk'
    'phone-bt-debug.apk'
    'source-baseline.zip'
    'evidence.zip'
)

$AllowedHosts = @('api.github.com', 'uploads.github.com', 'github.com', 'objects.githubusercontent.com')
$ApiBase      = 'https://api.github.com'

# ---------------------------------------------------------------- helpers
function Write-Step { param([string]$Message) Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Ok   { param([string]$Message) Write-Host "    $Message" -ForegroundColor Green }
function Write-Warn { param([string]$Message) Write-Host "    $Message" -ForegroundColor Yellow }

function Get-GitHubToken {
    # Token enters this process only. Never logged, never returned to the caller's output stream.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = 'git'
    $psi.Arguments              = 'credential fill'
    $psi.RedirectStandardInput  = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true

    $proc = [System.Diagnostics.Process]::Start($psi)
    try {
        $proc.StandardInput.Write("protocol=https`nhost=github.com`n`n")
        $proc.StandardInput.Close()
        $raw = $proc.StandardOutput.ReadToEnd()
        $proc.WaitForExit(30000) | Out-Null
    } finally {
        if (-not $proc.HasExited) { $proc.Kill() }
    }

    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match '^password=(.+)$') { return $Matches[1].Trim() }
    }
    return $null
}

function New-Client {
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false      # redirects are never followed; a 3xx aborts the run
    $client  = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromMinutes(30)
    $client.DefaultRequestHeaders.UserAgent.ParseAdd('leqi-handoff-publisher/1.0')
    $client.DefaultRequestHeaders.Accept.ParseAdd('application/vnd.github+json')
    return $client
}

function Assert-SafeUri {
    param([string]$Uri)
    $u = [Uri]$Uri
    if ($u.Scheme -ne 'https') { throw "Refusing non-HTTPS URL: $Uri" }
    if ($AllowedHosts -notcontains $u.Host) { throw "Refusing unexpected host '$($u.Host)' in URL: $Uri" }
    return $u
}

function New-GitHubRequest {
    # Builds a request and attaches the token only for the two GitHub API hosts.
    param(
        [System.Net.Http.HttpMethod]$Method,
        [string]$Uri
    )
    $u   = Assert-SafeUri $Uri
    $req = [System.Net.Http.HttpRequestMessage]::new($Method, $u)
    if ($u.Host -eq 'api.github.com' -or $u.Host -eq 'uploads.github.com') {
        $req.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    }
    return $req
}

function Send-GitHubRequest {
    # Sends exactly one request. Redirects are never followed: a 3xx is a failure,
    # so the Authorization header cannot be replayed against another host.
    param(
        [System.Net.Http.HttpClient]$Client,
        [System.Net.Http.HttpRequestMessage]$Request
    )
    $resp = $Client.SendAsync($Request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    $code = [int]$resp.StatusCode
    if ($code -in 301, 302, 303, 307, 308) {
        $loc = $resp.Headers.Location
        $resp.Dispose()
        throw "Refusing redirect ($code) to '$loc' for $($Request.RequestUri)"
    }
    return $resp
}

# ---------------------------------------------------------------- pre-flight
Write-Step "Handoff package: $PackageDir"
if (-not (Test-Path -LiteralPath $PackageDir)) { throw "Package directory not found: $PackageDir" }
$PackageDir = (Resolve-Path -LiteralPath $PackageDir).Path

# local manifest + checksums are the source of truth for what gets uploaded
$manifestPath = Join-Path $PackageDir 'manifest.json'
$sumsPath     = Join-Path $PackageDir 'SHA256SUMS.txt'
if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'manifest.json missing - refusing to publish.' }
if (-not (Test-Path -LiteralPath $sumsPath))     { throw 'SHA256SUMS.txt missing - refusing to publish.' }

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.acceptance.deviceAccepted -ne $false) {
    throw 'manifest.json does not declare deviceAccepted=false. Refusing to publish an unlabelled snapshot.'
}

$expected = @{}
foreach ($line in (Get-Content -LiteralPath $sumsPath)) {
    if ($line -match '^\s*([0-9A-Fa-f]{64})\s+\*?(.+?)\s*$') {
        $expected[$Matches[2]] = $Matches[1].ToUpperInvariant()
    }
}

Write-Step 'Verifying local assets against SHA256SUMS.txt'
foreach ($name in $Assets) {
    $path = Join-Path $PackageDir $name
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing asset: $name" }
    $size = (Get-Item -LiteralPath $path).Length
    if ($name -eq 'SHA256SUMS.txt') {
        # Only the checksum file itself is not self-hashed; README.md and
        # manifest.json must appear in SHA256SUMS.txt and match.
        Write-Ok ("{0,-26} {1,12:N0} bytes" -f $name, $size)
        continue
    }
    if (-not $expected.ContainsKey($name)) { throw "No checksum recorded for $name - refusing to publish." }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actual -ne $expected[$name]) {
        throw "Checksum mismatch for $name`n  expected $($expected[$name])`n  actual   $actual"
    }
    Write-Ok ("{0,-26} {1,12:N0} bytes  sha256 OK" -f $name, $size)
}

if (-not $Commitish) {
    $Commitish = (& git -C $PackageDir rev-parse HEAD 2>$null)
    if (-not $Commitish) { $Commitish = (& git rev-parse HEAD) }
    $Commitish = $Commitish.Trim()
}
Write-Step "target_commitish: $Commitish"

# ---------------------------------------------------------------- credentials
$token = Get-GitHubToken
if (-not $token) { throw 'No GitHub credential available from git credential fill for host github.com.' }

$client = New-Client
try {
    # ------------------------------------------------------------ get-or-create (idempotent)
    $releaseUri = "$ApiBase/repos/$Repo/releases/tags/$Tag"
    Write-Step "Looking up existing release '$Tag'"
    $release = $null
    $getReq  = New-GitHubRequest -Method ([System.Net.Http.HttpMethod]::Get) -Uri $releaseUri
    $getResp = Send-GitHubRequest -Client $client -Request $getReq
    try {
        if ([int]$getResp.StatusCode -eq 200) {
            $release = ($getResp.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
            Write-Ok "Reusing existing release id=$($release.id) (idempotent re-run)"
        } elseif ([int]$getResp.StatusCode -eq 404) {
            Write-Ok 'No existing release; one will be created.'
        } else {
            throw "Unexpected status $([int]$getResp.StatusCode) looking up release."
        }
    } finally { $getResp.Dispose() }

    if (-not $release) {
        if ($DryRun) {
            Write-Warn "[DryRun] would create pre-release '$Tag' at $Commitish"
        } else {
            Write-Step "Creating pre-release '$Tag'"
            $payload = @{
                tag_name         = $Tag
                target_commitish = $Commitish
                name             = "Device handoff snapshot $Tag (NOT device-accepted)"
                body             = "Development handoff snapshot. NOT device-accepted - see README.md.`n`nKnown blocker: glasses background lifecycle (no manifest-registered background Service)."
                draft            = $false
                prerelease       = $true
            } | ConvertTo-Json -Depth 5
            $postReq = New-GitHubRequest -Method ([System.Net.Http.HttpMethod]::Post) -Uri "$ApiBase/repos/$Repo/releases"
            $postReq.Content = New-Object System.Net.Http.StringContent($payload, [System.Text.Encoding]::UTF8, 'application/json')
            $postResp = Send-GitHubRequest -Client $client -Request $postReq
            try {
                if ([int]$postResp.StatusCode -notin 200,201) {
                    throw "Create release failed: $([int]$postResp.StatusCode) $($postResp.ReasonPhrase)"
                }
                $release = ($postResp.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
                Write-Ok "Created release id=$($release.id)"
            } finally { $postResp.Dispose() }
        }
    }

    # ------------------------------------------------------------ upload
    if ($DryRun) {
        Write-Step '[DryRun] upload plan'
        foreach ($name in $Assets) {
            $size = (Get-Item -LiteralPath (Join-Path $PackageDir $name)).Length
            Write-Ok ("{0,-26} {1,12:N0} bytes" -f $name, $size)
        }
    } else {
        $uploadBase = $release.upload_url -replace '\{\?name,label\}$', ''
        Assert-SafeUri $uploadBase | Out-Null

        $existing = @{}
        if ($release.assets) { foreach ($a in $release.assets) { $existing[$a.name] = $a } }

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        foreach ($name in $Assets) {
            $path = Join-Path $PackageDir $name
            $size = (Get-Item -LiteralPath $path).Length

            if ($existing.ContainsKey($name) -and [long]$existing[$name].size -eq $size) {
                Write-Warn "$name already uploaded with identical size ($size bytes) - skipping"
                continue
            }

            Write-Step "Uploading $name ($([math]::Round($size/1MB,1)) MB)"
            $enc  = [Uri]::EscapeDataString($name)
            $uri  = "$uploadBase`?name=$enc"
            Assert-SafeUri $uri | Out-Null

            $fs      = [System.IO.File]::OpenRead($path)
            $content = [System.Net.Http.StreamContent]::new($fs)
            $content.Headers.ContentType   = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
            $content.Headers.ContentLength = $size

            $putReq = New-GitHubRequest -Method ([System.Net.Http.HttpMethod]::Post) -Uri $uri
            $putReq.Content = $content

            $t0 = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $putResp = Send-GitHubRequest -Client $client -Request $putReq
                try {
                    if ([int]$putResp.StatusCode -notin 200,201) {
                        $detail = $putResp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                        throw "Upload of $name failed: $([int]$putResp.StatusCode) $($putResp.ReasonPhrase) $detail"
                    }
                    $t0.Stop()
                    $mbps = if ($t0.Elapsed.TotalSeconds -gt 0) { [math]::Round(($size/1MB)/$t0.Elapsed.TotalSeconds, 1) } else { 0 }
                    Write-Ok ("done in {0:N1}s ({1} MB/s)" -f $t0.Elapsed.TotalSeconds, $mbps)
                } finally { $putResp.Dispose() }
            } finally {
                $putReq.Dispose()
                $content.Dispose()
            }
        }
        $sw.Stop()
        Write-Step "All uploads finished in $([math]::Round($sw.Elapsed.TotalMinutes,1)) min"
    }

    Write-Step 'Result'
    if ($release) {
        Write-Ok "Release: $($release.html_url)"
        Write-Ok "Prerelease: $($release.prerelease)   Tag: $($release.tag_name)"
        Write-Ok 'Reminder: this snapshot is NOT device-accepted.'
    }
} finally {
    $client.Dispose()
    $token = $null          # drop the credential from this process
    [System.GC]::Collect()
}
