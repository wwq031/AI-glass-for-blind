<#
.SYNOPSIS
  给荣耀手机 MI_02「ADB Interface」补 Android ADB 设备接口 GUID，让 adb 能枚举到它。

.DESCRIPTION
  ## 问题
  MI_02 在 PnP 里名为 "ADB Interface"，但绑定的是通用 WINUSB (winusb.inf)，
  因此只注册通用 USB 设备接口 GUID，没有注册 Android ADB 接口 GUID
  {F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}。adb 的 Windows 后端按该 GUID 枚举
  设备，所以扫不到手机。（对照：眼镜注册在该 GUID 下，adb devices 看得到。）

  ## 做法
  WinUSB 在设备启动时读取 Device Parameters 键下的 `DeviceInterfaceGUIDs`
  (REG_MULTI_SZ)，把每个 GUID 注册为设备接口。因此：新增该值 -> 只重启 MI_02。

  ## 回滚语义（重点，v2 修正）
  旧版用 `reg import` 回滚是**错的**：import 只会覆盖/写回键里已有的值，
  **不会删除**原本不存在、由本脚本新增的 `DeviceInterfaceGUIDs`。
  现在改为按元数据做**值级还原**：

    - 原本不存在 -> DeleteValue 删掉本脚本新增的那个值
    - 原本存在   -> SetValue 写回原始的 MultiString 数据（含原始类型）

  执行前落两份备份：
    - `<stamp>.json`  结构化元数据（实例 ID / 键路径 / 值名 / 是否存在 / 原类型 / 原数据）
    - `<stamp>.reg`   整键原始导出，仅作取证与人工兜底，**不作为回滚依据**

  ## 重启语义
  只重启**同一个已验证的 MI_02 实例**，绝不碰父设备、控制器、别的 USB 口。
  优先 `pnputil /restart-device <实例ID>`（原子操作，不存在"禁用窗口"）；
  失败才回退 disable/enable，且 `Enable-PnpDevice` 放在 `finally` 里保证一定执行，
  之后再轮询确认设备回到 OK。任何一步失败都会触发失败恢复：还原值状态 + 重启 + 报错。

  ## 明确不做
  不装/不换驱动（尤其不装未签名驱动）、不改证书、不改安全设置、不改全局策略、
  不动 Enum 键 ACL、不重启父设备或机器、不动手机端开关。

  ## 默认演练
  **不加 -Execute 只读不写**。写注册表必须显式 -Execute。

.PARAMETER Execute
  执行：备份元数据 -> 写值 -> 只重启 MI_02 -> 复验。不加则纯演练。

.PARAMETER Rollback
  按元数据做值级还原，并只重启同一个 MI_02。需 -MetadataFile 或自动找最新。

.PARAMETER MetadataFile
  回滚依据的元数据 JSON。

.PARAMETER ArtifactDir
  备份与日志目录。默认仓库 artifacts/gates。

.PARAMETER AdbPath
  adb.exe 路径。默认本机配置的 SDK platform-tools。

.EXAMPLE
  pwsh tools/repair-honor-adb-interface.ps1                 # 演练（默认，安全）
  pwsh tools/repair-honor-adb-interface.ps1 -Execute        # root 审阅后执行
  pwsh tools/repair-honor-adb-interface.ps1 -Rollback       # 回滚
#>
[CmdletBinding()]
param(
    [switch]$Execute,
    [switch]$Rollback,
    [string]$MetadataFile,
    [string]$ArtifactDir,
    [string]$AdbPath = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)

$ErrorActionPreference = 'Stop'

$ADB_GUID       = '{F72FE0D4-CBCB-407D-8814-9ED673D0DD6B}'
$MI02_PREFIX    = 'USB\VID_339B&PID_107D&MI_02\'
$MI02_FRAGMENT  = 'VID_339B&PID_107D&MI_02'      # DeviceClasses 符号链接里出现的片段
$PHONE_SERIAL   = 'ACPR024C13000639'             # 真机序列号，判定 adb 成功只认它
$EXPECT_SERVICE = 'WINUSB'
$EXPECT_PROVIDER= 'Microsoft'
$VALUE_NAME     = 'DeviceInterfaceGUIDs'         # 复数 REG_MULTI_SZ，WinUSB 启动时读取

if (-not $ArtifactDir) { $ArtifactDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'artifacts\gates' }
New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null

function Say($m, $c = 'Gray') { Write-Host $m -ForegroundColor $c }
function Head($m) { Write-Host "`n=== $m ===" -ForegroundColor Cyan }

# =====================================================================
# 解析 + 校验（执行与回滚共用；回滚时实例可能已变，必须重新解析）
# =====================================================================
function Resolve-Mi02 {
    $found = @(Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like "$MI02_PREFIX*" })
    if ($found.Count -eq 0) { throw "找不到 MI_02 实例（前缀 $MI02_PREFIX）。手机没插好或换口了。" }
    if ($found.Count -gt 1) {
        throw ("匹配到 $($found.Count) 个 MI_02 实例，不敢乱选：`n" +
               (($found | ForEach-Object { '  ' + $_.InstanceId }) -join "`n"))
    }
    return $found[0]
}

function Assert-HealthyWinUsb($dev) {
    $props = Get-PnpDeviceProperty -InstanceId $dev.InstanceId
    $svc   = (($props | Where-Object KeyName -eq 'DEVPKEY_Device_Service').Data)         -join ''
    $prov  = (($props | Where-Object KeyName -eq 'DEVPKEY_Device_DriverProvider').Data)  -join ''
    $pcode = (($props | Where-Object KeyName -eq 'DEVPKEY_Device_ProblemCode').Data)     -join ''
    if ($dev.Status -ne 'OK' -or $pcode -ne '0') { throw "设备非 OK（ProblemCode=$pcode），脚本不适用。" }
    if ($svc  -ne $EXPECT_SERVICE)   { throw "Service='$svc' 不是 $EXPECT_SERVICE，拒绝继续。" }
    if ($prov -ne $EXPECT_PROVIDER)  { throw "Provider='$prov' 不是 $EXPECT_PROVIDER，拒绝继续。" }
    return @{ Service = $svc; Provider = $prov; ProblemCode = $pcode }
}

# =====================================================================
# 只重启同一个 MI_02。绝不接收父设备/控制器 ID。
# =====================================================================
function Restart-TargetDevice {
    param([Parameter(Mandatory)][string]$InstanceId)

    if ($InstanceId -notlike "$MI02_PREFIX*") {
        throw "拒绝重启：'$InstanceId' 不是已验证的 MI_02 实例。"
    }

    Head '重启 MI_02（只此一个 devnode）'
    Say "  目标: $InstanceId" 'White'

    # 主路径：pnputil /restart-device —— 原子，不存在"禁用窗口"，不会留下禁用设备
    $pnputil = Join-Path $env:SystemRoot 'System32\pnputil.exe'
    $method = $null
    if (Test-Path $pnputil) {
        $pout = & $pnputil /restart-device "$InstanceId" 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0) {
            $method = 'pnputil /restart-device'
            Say "  已重启（$method）" 'Green'
        } else {
            Say "  pnputil 失败（exit=$LASTEXITCODE），回退 disable/enable：" 'Yellow'
            Say ("    " + ($pout.Trim() -replace "`r?`n", "`n    ")) 'DarkGray'
        }
    }

    # 回退路径：disable/enable，Enable 放 finally 保证一定执行
    if (-not $method) {
        $disabled = $false
        try {
            Disable-PnpDevice -InstanceId $InstanceId -Confirm:$false -ErrorAction Stop
            $disabled = $true
            Start-Sleep -Seconds 2
        } finally {
            if ($disabled) {
                Enable-PnpDevice -InstanceId $InstanceId -Confirm:$false -ErrorAction SilentlyContinue
            }
        }
        $method = 'disable/enable (finally 保护)'
        Say "  已重启（$method）" 'Green'
    }

    # 轮询确认设备回到 OK，避免"以为重启成功其实留在禁用态"
    $ok = $false
    foreach ($i in 1..15) {
        Start-Sleep -Seconds 1
        $d = Get-PnpDevice -InstanceId $InstanceId -ErrorAction SilentlyContinue
        if ($d -and $d.Status -eq 'OK') { $ok = $true; break }
    }
    if ($ok) { Say '  设备已回到 OK 状态。' 'Green' }
    else {
        Say '  ！设备未在 15 秒内回到 OK —— 需要人工检查，可能停在禁用态。' 'Red'
        Say "  手工恢复: Enable-PnpDevice -InstanceId `"$InstanceId`" -Confirm:`$false" 'Red'
    }
    return $method
}

# =====================================================================
# 复验
# =====================================================================
function Test-DeviceClassesRegistration {
    $base = "HKLM:\SYSTEM\CurrentControlSet\Control\DeviceClasses\$ADB_GUID"
    if (-not (Test-Path $base)) { return @{ Found = $false; Note = 'ADB 接口 GUID 键不存在' } }
    # 递归到任意深度再匹配，避免把"嵌套层级不对"误判成"没注册"
    $all = @(Get-ChildItem $base -Recurse -ErrorAction SilentlyContinue)
    $hit = @($all | Where-Object { $_.PSChildName -like "*$MI02_FRAGMENT*" })
    return @{
        Found = ($hit.Count -gt 0)
        Note  = "递归扫描 $($all.Count) 个子键（任意深度），匹配片段 '$MI02_FRAGMENT'"
        Names = @($hit | ForEach-Object { $_.PSChildName })
    }
}

function Test-AdbSeesPhone {
    & $AdbPath kill-server  2>&1 | Out-Null
    & $AdbPath start-server 2>&1 | Out-Null
    $list = (& $AdbPath devices -l 2>&1 | Out-String)
    # 只认真机序列号，不用 "Honor"/"Magic" 之类子串，避免误判
    $seen = $list -match [regex]::Escape($PHONE_SERIAL)
    return @{ Seen = $seen; Output = $list.TrimEnd(); Serial = $PHONE_SERIAL }
}

# =====================================================================
# 打开键 + 快照
# =====================================================================
function Get-KeySnapshot($subPath) {
    $k = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($subPath, $false)
    if (-not $k) { throw "打不开 HKLM\$subPath —— 键不存在。" }
    $snap = [ordered]@{}
    foreach ($vn in $k.GetValueNames()) {
        $snap[$vn] = @{
            Kind = [string]$k.GetValueKind($vn)
            Data = $k.GetValue($vn, $null, [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        }
    }
    $k.Close()
    return $snap
}

# =====================================================================
# 值级还原（回滚与失败恢复共用）
# =====================================================================
function Restore-ValueState {
    param(
        [Parameter(Mandatory)][string]$SubPath,
        [Parameter(Mandatory)][string]$ValueName,
        [Parameter(Mandatory)][bool]$Existed,
        [string]$Kind,
        $Data
    )
    $k = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($SubPath, $true)
    if (-not $k) { throw "还原失败：打不开 HKLM\$SubPath（需要写权限）。" }
    try {
        if (-not $Existed) {
            # 原本不存在 -> 必须显式删除，reg import 做不到这件事
            $k.DeleteValue($ValueName, $false)
            Say "  已删除新增值 '$ValueName'（原本不存在）" 'Green'
        } else {
            $kindEnum = [Microsoft.Win32.RegistryValueKind]::$Kind
            $k.SetValue($ValueName, $Data, $kindEnum)
            $d = if ($Data -is [array]) { '[' + ($Data -join ' | ') + ']' } else { $Data }
            Say "  已还原 '$ValueName' 为原值 $d（类型 $Kind）" 'Green'
        }
    } finally { $k.Close() }
}

# =====================================================================
# 0. 解析目标
# =====================================================================
Head '0. 定位 MI_02'
$dev = Resolve-Mi02
$id  = $dev.InstanceId
Say "  实例: $id" 'White'
Say "  名称: $($dev.FriendlyName)   状态: $($dev.Status)   类: $($dev.Class)"
$health = Assert-HealthyWinUsb $dev
Say "  Service=$($health.Service)  Provider=$($health.Provider)  ProblemCode=$($health.ProblemCode) -> 健康 WINUSB" 'Green'

$subPath  = "SYSTEM\CurrentControlSet\Enum\$id\Device Parameters"
$hivePath = "HKLM\$subPath"
Say "  键: $hivePath" 'White'

# =====================================================================
# 回滚分支
# =====================================================================
if ($Rollback) {
    Head '回滚（值级还原）'
    if (-not $MetadataFile) {
        $MetadataFile = Get-ChildItem $ArtifactDir -Filter 'honor-mi02-adb-repair-*.json' -ErrorAction SilentlyContinue |
                        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $MetadataFile -or -not (Test-Path $MetadataFile)) {
        throw '找不到元数据 JSON，无法可靠回滚（不知道原值是否存在）。用 -MetadataFile 指定。'
    }
    Say "  元数据: $MetadataFile" 'White'
    $meta = Get-Content $MetadataFile -Raw | ConvertFrom-Json
    Say "  记录实例: $($meta.instanceId)"
    Say "  记录原始状态: 存在=$($meta.valueExisted)  类型=$($meta.valueKind)  数据=$(if($meta.valueData){'[' + ($meta.valueData -join ' | ') + ']'}else{'<无>'})"

    if (-not $Execute) {
        Say '  [演练] 未加 -Execute，不做还原、不重启。' 'Yellow'
        return
    }

    $usedPath = "SYSTEM\CurrentControlSet\Enum\$id\Device Parameters"
    if ($usedPath -ne $meta.keyPath) {
        Say "  ！注意：当前键路径与记录不同。" 'Yellow'
        Say "    记录: $($meta.keyPath)" 'Yellow'
        Say "    当前: $usedPath" 'Yellow'
    }
    Restore-ValueState -SubPath $usedPath -ValueName $meta.valueName `
        -Existed ([bool]$meta.valueExisted) -Kind $meta.valueKind -Data $meta.valueData
    [void](Restart-TargetDevice -InstanceId $id)

    Head '回滚复验'
    $dc = Test-DeviceClassesRegistration
    Say "  DeviceClasses: $($dc.Note) -> 注册=$(if($dc.Found){'是'}else{'否'})" $(if ($dc.Found) { 'Yellow' } else { 'Green' })
    Say '  （回滚后期望"否"；若仍为"是"，说明还有别的来源注册了该 GUID）' 'DarkGray'
    $adb = Test-AdbSeesPhone
    Say $adb.Output 'White'
    Say "  序列号 $PHONE_SERIAL 可见 = $($adb.Seen)" $(if ($adb.Seen) { 'Yellow' } else { 'Green' })
    return
}

# =====================================================================
# 1. 快照 + 计算目标值
# =====================================================================
Head '1. Device Parameters 键现状'
$snap = Get-KeySnapshot $subPath
if ($snap.Count -eq 0) { Say '  （键下没有任何值）' }
foreach ($vn in $snap.Keys) {
    $d = $snap[$vn].Data
    $r = if ($d -is [array]) { '[' + ($d -join ' | ') + ']' } else { $d }
    $mark = if ($vn -eq $VALUE_NAME) { '   <-- 本脚本要改的' } else { '' }
    Say ("    {0,-24} {1,-9} = {2}{3}" -f $vn, $snap[$vn].Kind, $r, $mark)
}

$valueExisted = $snap.Contains($VALUE_NAME)
$valueKind    = $null
$valueData    = @()
if ($valueExisted) {
    $valueKind = $snap[$VALUE_NAME].Kind
    if ($valueKind -ne 'MultiString') {
        throw "$VALUE_NAME 已存在但类型是 $valueKind，不是 MultiString —— 情况特殊，停下来人工看。"
    }
    $valueData = @($snap[$VALUE_NAME].Data) | Where-Object { $_ }
}

$target = @($valueData + $ADB_GUID | Select-Object -Unique)

Head '2. 将要写入的内容（其它值一律不动）'
Say "  值名: $VALUE_NAME   类型: REG_MULTI_SZ" 'White'
Say "  原状态: $(if($valueExisted){'已存在，原值 [' + ($valueData -join ' | ') + ']'}else{'不存在，将新建'})"
Say "  新内容: [$($target -join ' | ')]" 'White'
Say '  保持不变:' 'DarkGray'
foreach ($vn in $snap.Keys) { if ($vn -ne $VALUE_NAME) { Say "    $vn ($($snap[$vn].Kind))" 'DarkGray' } }
if ($valueExisted -and ($valueData -contains $ADB_GUID)) { Say '  ADB GUID 已在列表中，无需写入。' 'Yellow' }

# =====================================================================
# 3. 写权限
# =====================================================================
Head '3. 写权限探测'
$writable = $false
try {
    $kw = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($subPath, $true)
    if ($kw) { $writable = $true; $kw.Close() }
} catch { $writable = $false }
Say "  可写 = $writable" $(if ($writable) { 'Green' } else { 'Red' })
if (-not $writable) {
    Say '  不可写则需要改 Enum 键 ACL —— 那属于安全设置变更，本脚本明确不做，到此为止。' 'Red'
    return
}

# =====================================================================
# 4. 执行 / 演练
# =====================================================================
if (-not $Execute) {
    Head '演练结束'
    Say '  未加 -Execute：注册表一个字节都没改，设备未被重启。' 'Yellow'
    Say '  root 审阅通过后加 -Execute 执行。' 'Yellow'
    return
}

Head '4. 备份元数据'
$stamp  = Get-Date -Format 'yyyyMMdd-HHmmss'
$metaPath = Join-Path $ArtifactDir "honor-mi02-adb-repair-$stamp.json"
$regPath  = Join-Path $ArtifactDir "honor-mi02-device-parameters-$stamp.reg"

@{
    stamp        = $stamp
    createdAt    = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    instanceId   = $id
    keyPath      = $subPath
    hivePath     = $hivePath
    valueName    = $VALUE_NAME
    valueExisted = $valueExisted
    valueKind    = $valueKind
    valueData    = @($valueData)
    addedGuid    = $ADB_GUID
    writeTarget  = @($target)
    otherValues  = @($snap.Keys | Where-Object { $_ -ne $VALUE_NAME } | ForEach-Object {
                        @{ name = $_; kind = $snap[$_].Kind } })
} | ConvertTo-Json -Depth 6 | Set-Content $metaPath -Encoding UTF8
Say "  元数据: $metaPath" 'Green'
Say "    （回滚按 valueExisted 决定删值还是还原原值）" 'DarkGray'

& reg.exe export $hivePath $regPath /y | Out-Null
if ($LASTEXITCODE -ne 0) {
    Say '  ！reg export 失败，但元数据已落盘，回滚依据仍然充分。' 'Yellow'
} else { Say "  整键快照（取证用）: $regPath" 'Green' }

Head '5. 写入'
try {
    $kw = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey($subPath, $true)
    try { $kw.SetValue($VALUE_NAME, [string[]]$target, [Microsoft.Win32.RegistryValueKind]::MultiString) }
    finally { $kw.Close() }
    Say '  写入完成。' 'Green'
} catch {
    Say "  写入失败: $($_.Exception.Message)" 'Red'
    Say '  未发生设备重启，注册表可能处于半写状态，尝试还原...' 'Yellow'
    Restore-ValueState -SubPath $subPath -ValueName $VALUE_NAME -Existed $valueExisted -Kind $valueKind -Data $valueData
    throw
}

Head '6. 重启 + 复验'
try {
    [void](Restart-TargetDevice -InstanceId $id)
} catch {
    Say "  重启阶段异常: $($_.Exception.Message)" 'Red'
    Say '  失败恢复：还原值状态并再次尝试重启...' 'Yellow'
    Restore-ValueState -SubPath $subPath -ValueName $VALUE_NAME -Existed $valueExisted -Kind $valueKind -Data $valueData
    try { [void](Restart-TargetDevice -InstanceId $id) } catch { Say "  恢复期重启也失败: $($_.Exception.Message)" 'Red' }
    throw
}

Head '7. 复验'
$dc = Test-DeviceClassesRegistration
Say "  [a] Android ADB 接口 GUID: $($dc.Note)"
if ($dc.Found) { $dc.Names | ForEach-Object { Say "      $_" 'Green' } }
Say "      -> 注册 = $(if($dc.Found){'是'}else{'否'})" $(if ($dc.Found) { 'Green' } else { 'Red' })

Say '  [b] adb devices -l：'
$adb = Test-AdbSeesPhone
Say $adb.Output 'White'
Say "      真机序列号 $PHONE_SERIAL 可见 = $($adb.Seen)" $(if ($adb.Seen) { 'Green' } else { 'Yellow' })

Head '收尾'
if ($adb.Seen) {
    Say '  手机已可见。' 'Green'
} elseif (-not $dc.Found) {
    Say '  GUID 未注册且手机不可见 —— 建议回滚。' 'Yellow'
} else {
    Say '  GUID 已注册但手机仍不可见 —— 该接口可能不跑 ADB 协议（例如走 HDB）。建议回滚。' 'Yellow'
}
Say '  回滚命令:' 'White'
Say "    pwsh tools/repair-honor-adb-interface.ps1 -Rollback -MetadataFile `"$metaPath`" -Execute" 'Gray'
