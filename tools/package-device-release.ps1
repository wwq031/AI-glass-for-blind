<#
.SYNOPSIS
  打包 device-experiment 双 APK 交付件到发布目录。

.DESCRIPTION
  三件事：可选先跑构建 -> 校验产物不是陈旧件 -> 复制 APK 与源码归档并写清单。

  核心目的之一是**拒绝把未验收的东西标成已发布**：

  1. 陈旧件拦截 —— 比对 APK 修改时间与源码最新修改时间。APK 比任何构建输入还旧
     就失败。2026-09-30 那次 glasses-bt APK 就是 UP-TO-DATE 的旧件，
     和修复后的源码并非内容等价，正是这个检查要拦的。
  2. 验收标注 —— 不给 -AcceptanceNote 不写 "released" 字样；给了也必须指向真实文件。
  3. 归档 —— 复制 APK + 源码 zip（排除密钥/模型/媒体），并写 SHA256 清单。

  ## 两种复制模式
  - `-Stage`：APK 带 `PENDING-TS-ACCEPTANCE-` 前缀复制，明确不是最终件。
  - `-AcceptanceNote <文件>`：APK 用正式文件名复制，清单标 ACCEPTED。
  不跑脚本时发布目录保持现状，不会凭空出现"看起来像最终件"的东西。

  构建配置里 Kotlin jvmTarget 已显式对齐 Java 17
  （见 apps/{phone-companion,glasses-agent}/android/build.gradle），不要改回默认。

.PARAMETER Rebuild
  打包前先跑一次双模块 assembleDebug。

.PARAMETER Stage
  以 PENDING 前缀复制 APK（未验收但要有实物时用）。

.PARAMETER ReleaseDir
  发布目录。默认当天日期目录。

.PARAMETER AcceptanceNote
  指向 TS 验收结论文件。存在且给出时标 ACCEPTED 并用正式文件名。

.PARAMETER Force
  跳过陈旧件拦截。只在明确知道自己在干什么时用。

.EXAMPLE
  # 未验收，先放实物（带 PENDING 前缀）
  pwsh tools/package-device-release.ps1 -Rebuild -Stage

.EXAMPLE
  # 验收通过后出正式件
  pwsh tools/package-device-release.ps1 -Rebuild `
    -AcceptanceNote 'artifacts/claude-handoff/ts-acceptance.md'
#>
[CmdletBinding()]
param(
    [switch]$Rebuild,
    [switch]$Stage,
    [string]$ReleaseDir = 'D:\leqi-device-experiment\releases\2026-09-30-agent-integrated',
    [string]$AcceptanceNote,
    [switch]$Force,
    [string]$BuildRoot = 'D:\leqi-device-experiment\build'
)

$ErrorActionPreference = 'Stop'
$repoRoot   = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $repoRoot 'apps\device-experiment\android'

$modules = [ordered]@{
    'phone-bt'   = Join-Path $BuildRoot 'phone-bt\outputs\apk\debug\phone-bt-debug.apk'
    'glasses-bt' = Join-Path $BuildRoot 'glasses-bt\outputs\apk\debug\glasses-bt-debug.apk'
}

# ---------------------------------------------------------------- 构建
if ($Rebuild) {
    Write-Host '[1/5] 构建双模块 APK ...' -ForegroundColor Cyan
    $env:LEQI_BUILD_ROOT = 'D:/leqi-device-experiment/build'
    $env:ANDROID_HOME    = 'C:/Users/Administrator/AppData/Local/Android/Sdk'
    $env:JAVA_HOME       = 'C:\Program Files\Android\Android Studio\jbr'
    $gradle = 'D:\leqi-device-experiment\gradle-dist\gradle-8.13\bin\gradle.bat'
    Push-Location $androidDir
    try {
        & $gradle :phone-bt:assembleDebug :glasses-bt:assembleDebug --offline --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "构建失败，exit=$LASTEXITCODE" }
    } finally { Pop-Location }
} else {
    Write-Host '[1/5] 跳过构建（未给 -Rebuild）' -ForegroundColor DarkGray
}

# --------------------------------------------------- 陈旧件拦截
Write-Host '[2/5] 陈旧件检查 ...' -ForegroundColor Cyan
$inputs = @(
    (Join-Path $repoRoot 'apps\phone-companion\src'),
    (Join-Path $repoRoot 'apps\phone-companion\android\src'),
    (Join-Path $repoRoot 'apps\glasses-agent\android\src'),
    (Join-Path $repoRoot 'apps\device-experiment\android\common\src'),
    (Join-Path $repoRoot 'packages\domain'),
    (Join-Path $repoRoot 'packages\contracts')
) | Where-Object { Test-Path $_ }

$newest = $inputs |
    ForEach-Object { Get-ChildItem $_ -Recurse -File -ErrorAction SilentlyContinue } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($newest) {
    Write-Host ("      最新构建输入: {0}  ({1})" -f $newest.FullName.Replace($repoRoot, '.'), $newest.LastWriteTime)
}
foreach ($m in $modules.Keys) {
    $apk = $modules[$m]
    if (-not (Test-Path $apk)) { throw "缺少 APK: $apk（先跑 -Rebuild）" }
    $apkTime = (Get-Item $apk).LastWriteTime
    if ($newest -and $apkTime -lt $newest.LastWriteTime -and -not $Force) {
        $msg = "陈旧件: {0} 的 APK ({1}) 早于构建输入 ({2})。" -f $m, $apkTime, $newest.LastWriteTime
        throw ($msg + "说明它是改动前的产物，拒绝打包。确认要强行继续就加 -Force。")
    }
    Write-Host ("      {0}: APK {1} >= 输入 {2}  OK" -f $m, $apkTime, $newest.LastWriteTime)
}

# --------------------------------------------------- 验收判定
Write-Host '[3/5] 验收判定 ...' -ForegroundColor Cyan
$accepted = $false
if ($AcceptanceNote) {
    $notePath = if (Test-Path $AcceptanceNote) { $AcceptanceNote } else { Join-Path $repoRoot $AcceptanceNote }
    if (-not (Test-Path $notePath)) { throw "给了 -AcceptanceNote 但文件不存在: $AcceptanceNote —— 不接受口头验收。" }
    $accepted = $true
    Write-Host "      验收依据: $notePath" -ForegroundColor Green
} else {
    Write-Host '      未给 -AcceptanceNote -> PENDING-TS-ACCEPTANCE' -ForegroundColor Yellow
}
$status = if ($accepted) { 'ACCEPTED' } else { 'PENDING-TS-ACCEPTANCE' }
$prefix = if ($accepted) { '' } else { 'PENDING-TS-ACCEPTANCE-' }

# --------------------------------------------------- 哈希与签名
Write-Host '[4/5] 计算哈希与签名 ...' -ForegroundColor Cyan
$bt = Get-ChildItem 'C:\Users\Administrator\AppData\Local\Android\Sdk\build-tools' -Directory |
      Sort-Object Name -Descending | Select-Object -First 1
$apksigner = Join-Path $bt.FullName 'apksigner.bat'

New-Item -ItemType Directory -Force -Path $ReleaseDir | Out-Null
$entries = foreach ($m in $modules.Keys) {
    $apk = $modules[$m]
    $f = Get-Item $apk
    $certDigest = $null
    if (Test-Path $apksigner) {
        $certDigest = (& $apksigner verify --print-certs $apk 2>&1 |
            Select-String 'certificate SHA-256 digest' |
            Select-Object -First 1) -replace '.*digest:\s*', ''
    }
    $destName = "$prefix$($f.Name)"
    Copy-Item $apk (Join-Path $ReleaseDir $destName) -Force
    [pscustomobject]@{
        module     = $m
        file       = $destName
        source     = $f.FullName
        bytes      = $f.Length
        builtAt    = $f.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')
        sha256     = (Get-FileHash $apk -Algorithm SHA256).Hash
        signerCert = $certDigest
        signedWith = 'Android debug keystore (CN=Android Debug) - NOT for distribution'
    }
}

# --------------------------------------------------- 源码归档
Write-Host '[5/5] 源码归档 + 清单 ...' -ForegroundColor Cyan
$stage = Join-Path $env:TEMP ("leqi-src-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# 只收真实源码，排除密钥/模型/媒体/构建产物。
$includeDirs = @(
    'apps/device-experiment', 'apps/phone-companion/src', 'apps/phone-companion/android/src',
    'apps/glasses-agent/src', 'apps/glasses-agent/android/src',
    'packages/domain', 'packages/contracts',
    'tools', 'tests', 'docs/superpowers'
)
$excludeGlobs = @(
    '*\node_modules\*', '*\.git\*', '*\build\*', '*\.gradle\*', '*\.kotlin\*', '*\dist\*',
    # 密钥 / 凭据
    '*\local.properties', '*.jks', '*.keystore', '*.p12', '*.pem', '*.key',
    '*\google-services.json', '*.env', '*.env.*', '*\secrets*',
    # 模型 / 原生库 / 媒体
    '*.tar.bz2', '*.onnx', '*.tflite', '*.bin', '*.so', '*.aar', '*.dll', '*.exe',
    '*.apk', '*.aab', '*.wav', '*.mp3', '*.mp4', '*.png', '*.jpg', '*.jpeg', '*.webp'
)
foreach ($d in $includeDirs) {
    $src = Join-Path $repoRoot $d
    if (-not (Test-Path $src)) { continue }
    $dest = Join-Path $stage $d
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Get-ChildItem $src -Recurse -File -ErrorAction SilentlyContinue |
        Where-Object {
            $rel = $_.FullName
            -not ($excludeGlobs | Where-Object { $rel -like $_ })
        } | ForEach-Object {
            $target = Join-Path $dest $_.FullName.Substring($src.Length).TrimStart('\')
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            Copy-Item $_.FullName $target -Force
        }
}
$zipPath = Join-Path $ReleaseDir 'source-baseline.zip'
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Add-Type -AssemblyName System.IO.Compression.FileSystem
# 用 .NET 而不是 Compress-Archive：后者在本机对临时路径会抛参数绑定异常。
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $stage, $zipPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)
Remove-Item $stage -Recurse -Force

@{
    status      = $status
    generatedAt = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    gitBranch   = (git -C $repoRoot rev-parse --abbrev-ref HEAD 2>$null)
    gitHead     = (git -C $repoRoot rev-parse HEAD 2>$null)
    acceptance  = if ($accepted) { $AcceptanceNote } else { $null }
    artifacts   = $entries
    sourceZip   = 'source-baseline.zip'
    note        = if ($accepted) { 'TS acceptance recorded' }
                  else { 'PENDING TS acceptance - APKs carry PENDING- prefix and are NOT final' }
} | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $ReleaseDir 'apk-manifest.json') -Encoding UTF8

($entries | ForEach-Object { "{0}  {1}" -f $_.sha256, $_.file }) -join "`n" |
    Set-Content (Join-Path $ReleaseDir 'SHA256SUMS.txt') -Encoding UTF8

$entries | Format-Table module, file, bytes, builtAt, sha256 -AutoSize | Out-String | Write-Host
Write-Host "状态: $status" -ForegroundColor $(if ($accepted) { 'Green' } else { 'Yellow' })
Write-Host "输出: $ReleaseDir"
if (-not $accepted) {
    Write-Host '注意: APK 带 PENDING-TS-ACCEPTANCE- 前缀，是未验收实物，不要当最终件分发。' -ForegroundColor Yellow
}
