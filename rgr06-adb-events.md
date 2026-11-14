# RGR06 指环在 RG_glasses 上的 ADB 输入记录

## 2026-11-11 跨端真机诊断结果

已实测打通：戒指 → Android KeyEvent → 前台眼镜应用 → CXR-L → 手机诊断入口。四组共 84 条原始事件（序号 3–86），眼镜发送与手机接收逐条字段一致，无漏传、重复或乱序。以下方向均以用户本轮握持方向为准。

| 用户操作 | 实际组数 | 每组按下键码顺序 | 跨端事件数（含 DOWN/UP） |
| --- | --- | --- | --- |
| 左单击 | 3 | `83 → 66` | 12/12 |
| 中单击 | 4（用户确认多按） | `83 → 85` | 16/16 |
| 上滚 | 3 | `83,22,22,20`；`83,22,22,20`；`83,21,22,22,20` | 26/26 |
| 下滚 | 3 | `83 → 21 × N → 19`，N 为 2、4、3 | 30/30 |

逐条核对 action、keyCode、scanCode、repeat、downTime、eventTime、heldMs 和 canceled；DOWN/UP 配对完整。所有 repeat 为 0、canceled 为 false。滚动会产生多组独立按键，上滚第三组混有 LEFT，该事件在眼镜端已存在，并非 CXR-L 额外生成。两端时钟未同步，本轮不估计单程延迟。原始事件持续时间反映系统生成的按键脉冲，不能当作手指按住时长。

左长按已在前一阶段三次触发官方助手并抢走焦点，后续出现官方相机；右键误触与系统拍照事件时间相符，但未完成三次独立确认。因此没有继续重复这两类系统功能操作，也没有新增拍照、确认、取消等业务映射。取消语义、滚动合并、后台、断连重连仍待验证。

用户授权扩展后，在 `GlassesActivity.kt` 与 `CxrlAgentActivity.kt` 增加 debug-only 跨端输入诊断。手机“戒指输入诊断（只记录）”入口直接复用 `CxrlAgentDevice`，只申请 MEDIA 会话授权；不启动业务服务或模型。眼镜收到同一连接的 `rgr06.diagnostics` 指令后清除授权窗口，回复 `rgr06.ready`，以 `rgr06.input` 转发原始 KeyEvent，以 `rgr06.broadcast` 转发系统按键广播。手机只显示并记录这些诊断消息。普通启动入口保持原有业务流程。

跨端诊断沿用 `rk_custom_client` / `rk_custom_key` 通道、连接标记与单调序号。眼镜 `Rgr06Cxrl` 日志记录发送序号与返回值，手机同标签记录接收序号和输入字段；两台设备的运行时间不同，应按消息序号与眼镜携带的 eventTime 对齐。

验证：9 项 Node 设备测试、71 项手机 Android 测试、4 项眼镜 Android 测试通过；双端完整 debug APK 构建成功。眼镜端 `com.leqi.experiment.glasses` 已安装。手机原 0.4.2 与本机构建签名不同，首次检查后停止覆盖；用户随后自行卸载旧版并授权安装新版。

手机新版 APK 的 v2 签名和包结构校验通过，但流式安装、完整传输后的安装均被手机 USB 安装器拒绝：`INSTALL_FAILED_USER_RESTRICTED: Invalid apk`。手机 `AdbInstallActivity` 日志显示 `Unknown authority guard`；查询 `com.miui.guardprovider` 显示当前用户 `installed=false`。随后用户从“下载”目录手动安装成功，双端均为 0.5.0。用户已授权附近设备和官方 SDK 的 MEDIA 权限；诊断代码没有读取媒体。手机安全组件未调整。

首次连接出现眼镜端握手超时；唤醒眼镜、将诊断应用置于前台并重新连接后，两端均记录 `rgr06.ready`，随后完成上表采样。首次超时原因尚未隔离。采样前后手机业务服务均未运行，原始事件只进入诊断入口，业务事件链路尚未验收。完成后手机显示“已停止”，眼镜诊断应用已退出，双端日志采集进程已停止。

完整手机 APK：`apps/device-experiment/android/phone-l/build/outputs/apk/debug/phone-l-debug.apk`；眼镜 APK：`apps/device-experiment/android/glasses/build/outputs/apk/debug/glasses-debug.apk`。本轮跨端日志目录：`E:/cache/tmp/rgr06-cxrl-20261111-232347/`。

## 2026-11-11 本地真机复测结果

基线 `e443ef4`，分支 `eagle_codex/cxrl-agent-runtime`。Android 12，系统版本 `SKQ1.240613.001 release-keys`。本轮运行 `com.leqi.experiment.glassesbt` 的 debug 输入诊断模式；左右、上下以用户当时握持方向为准，与下方历史记录的方向名称不同。

| 用户操作 | 本轮证据 | 应用接收结果 |
| --- | --- | --- |
| 左单击 × 3 | `NOTIFICATION(83, scanCode=59) → ENTER(66, scanCode=28)` | 3/3 完整 DOWN/UP；两键间隔 370、391、393 ms |
| 左长按 × 3 | 系统 `F1 → F2`；F2 持续 1191、1507、1058 ms | F2 映射为 `PROG_BLUE`，三次触发 `launchRokidAI`；应用只收到第一次前缀，随后焦点归官方助手 |
| 中单击 | `NOTIFICATION(83,59) → MEDIA_PLAY_PAUSE(85,62)` | 首轮第二次混入方向键，另补一次；累计三次独立样本完整到达应用 |
| 右单击误触一次 | 与系统 F3 / `SPRITE_FUNCTION` 的时间位置相符 | 应用收到 `ACTION_SPRITE_BUTTON_DOWN/UP/CLICK` 广播，官方助手同时打开相机；未做三次独立复测，物理映射仍待确认 |
| 上滚 × 3 | 每组 `83 → RIGHT(22,106) × 3 → DOWN(20,108)`；第二组额外有 `LEFT(21,105)` | 三组完整到达应用 |
| 下滚 × 3 | `83 → LEFT(21,105) × N → UP(19,103)`，N 分别为 1、1、3 | 三组完整到达应用 |

本轮应用收到的事件均为 `repeat=0`、`canceled=false`，DOWN/UP 配对完整，单对持续 0–2 ms。滚动产生的是多组独立按键，不能靠 `repeatCount` 去重；这些持续时间也不等于手指实际按住时间。触控坐标曾在混合操作中出现，本补丁只记录 KeyEvent。

系统实际加载 `/system/usr/keylayout/Vendor_248a_Product_045b.kl`：扫描码 59 → NOTIFICATION，60 → PROG_BLUE，61 → SPRITE_FUNCTION，62 → MEDIA_PLAY_PAUSE。键名以设备配置和实际日志为证据。系统日志在误触样本记录官方助手打开相机，在长按样本记录三次助手唤起及后续相机打开；这来自系统处理。停止采样前已退出助手，查询活动相机客户端为空。

### 本轮改动与验证

- 应用补丁中的被动 `dispatchKeyEvent` 日志，保持 `super.dispatchKeyEvent(event)`。
- 修复构建错误：当前 SDK 未公开 `KeyEvent.actionToString`，改用公开动作常量生成名称，同时记录原始 `actionCode`。
- 增加 debug-only 启动参数 `rgr06InputOnly=true`：跳过手机蓝牙服务及权限申请，在诊断 Activity 前台保持亮屏。解决真机权限窗口占焦点和采样超时休眠；普通启动流程保持原样。
- `.env` 中已有的 JDK 17、Gradle 9.3.1、Android SDK 36.1 完成 debug APK 构建与覆盖安装；7 项设备测试通过，`git diff --check` 通过。合同测试检查源码约束；上表来自真机日志。
- 本轮未新增拍照、确认、取消等业务映射。日志采集已停止，诊断应用已退出。

### 本地阶段的链路结论（跨端结果见上文）

已实测：戒指 HID → Android 键位映射 → 前台 `GlassesBtActivity`；系统功能键另以广播进入该应用。本轮输入模式跳过自有 RFCOMM 服务，手机转发未采样。

源码中还存在独立的 CXR-L 应用 `com.leqi.experiment.glasses`：`GlassesActivity.event()` 经 `CXRServiceBridge.sendMessage("rk_custom_key", …)` 发送，手机 `CxrlAgentDevice.onCustomCmdResult()` 接收并检查连接标记和序号，正常业务入口再交给 `CxrlAgentService`。后续跨端阶段已验证原始事件到达手机独立诊断入口，未经过业务服务。`CxrlSessionProbe` 的通用生命周期监听不能替代这条业务通道验收。

该 CXR-L 应用已有通用 ENTER/DPAD_CENTER → `click()` 处理：绑定连接后，在有效 `voice.arm` / `entrance.arm` 窗口中可发送录音／拍照确认。跨端阶段使用独立诊断模式关闭授权窗口并跳过业务按键处理，原始输入经 `rgr06.input` 转发。

手机 USB 双端采集已经完成，详见上文。后续业务适配需要先确定可靠取消操作，并验证滚动合并、后台和断连重连；右键独立复测需先处理已观察到的系统拍照副作用。

本机原始证据：`E:/cache/tmp/rgr06-diagnostics-20261111-225306/` 中的 `app.log`、`input.log`、`system-live.log`、键位配置、各组 JSON 结果及补充系统日志。日志时间为设备运行时秒数，KeyEvent 时间单位为毫秒；按各组 `Rgr06Session` 标记对齐，ADB 唤醒／返回操作有独立标记。

## 本地诊断复现方法

这次先查“戒指按键有没有到应用”，**不是安装后就能控制业务**。不用连接手机端，不打开拍照或录音任务；保持眼镜上的测试应用在前台。

1. 检查当前分支和已有修改，确认诊断补丁已安全合并。本轮直接在当前工作分支构建。
2. 在已配置 Android 构建环境的电脑上，仓库根目录运行：
   ```sh
   gradle -p apps/device-experiment/android :glasses-bt:assembleDebug
   ```
   本轮使用 JDK 17、Gradle 9.3.1、Android SDK 36.1 构建成功。默认 APK 位于 `apps/glasses-agent/android/build/outputs/apk/debug/glasses-bt-debug.apk`；设置过 `LEQI_BUILD_ROOT` 时，在该目录下的 `glasses-bt/outputs/apk/debug/` 找。
3. USB 连接眼镜，运行 `adb devices`。把下面的 `SERIAL` 换成眼镜那一行的序列号，然后安装并打开应用：
   ```sh
   adb -s SERIAL install -r apps/glasses-agent/android/build/outputs/apk/debug/glasses-bt-debug.apk
   adb -s SERIAL shell am start -n com.leqi.experiment.glassesbt/.GlassesBtActivity --ez rgr06InputOnly true
   ```
   若提示签名不一致，先停止，不要直接卸载旧应用。若使用了自定义输出目录，替换第一条命令的 APK 路径。
4. 开始录日志：
   ```sh
   adb -s SERIAL logcat -v monotonic Rgr06Input:D "*:S" > rgr06-log.txt
   ```
   依次做：**左单击、中单击、上滚、下滚**。每项做 3 次，项与项之间停 3 秒，并记下动作顺序。长按已触发官方助手，右键误触与官方相机开启时间相符，这两项先暂停复测。完成后按 Ctrl+C 停止日志。
5. 发回 `rgr06-log.txt` 和动作顺序。正常应看到 `ACTION_DOWN` / `ACTION_UP`、`keyCode`、`heldMs` 等字段。如果文件没有这些行，直接告诉我“没有 Rgr06Input 日志”，不要猜按键映射。

下面保留补丁基线的历史证据与排查计划，其方向名称和待验证状态对应当时记录；当前验收结果以上方两轮复测为准。

2026-11-11 在眼镜上用 `adb shell getevent -pl` 枚举设备，用 `adb shell getevent -lt` 记录实际输入。以下节点编号只对应本次连接，重新连接后可能变化。

| 输入设备名 | 本次节点 | 实际观察到的事件 |
| --- | --- | --- |
| `RGR06 Keyboard` | `/dev/input/event2` | `EV_KEY` 的 `KEY_F1` 至 `KEY_F8`、`KEY_ENTER`、`KEY_LEFT`、`KEY_RIGHT`、`KEY_UP`、`KEY_DOWN` |
| `RGR06 Consumer Control` | `/dev/input/event3` | `EV_KEY KEY_BACK`（`MSC_SCAN 0x000c0224`） |
| `RGR06` | `/dev/input/event4` | `EV_KEY BTN_TOUCH`、`EV_KEY BTN_DIGI`、`EV_ABS ABS_X`、`EV_ABS ABS_Y` |

## 按操作顺序对照

下表对应这一轮操作的时间顺序。按键名省略 `KEY_` 前缀。各次短按包含 `DOWN` 和 `UP`。

| 操作 | 观察到的输入 |
| --- | --- |
| 左单击 | `F3`（`MSC_SCAN 0x0007003c`） |
| 左长按，两次 | 都是 `F3 DOWN → UP`，分别约 1.38 秒和 1.43 秒 |
| 滚轮上滚 | `F1 → LEFT → UP` |
| 滚轮下滚 | `F1 → RIGHT → DOWN`，同期还有触控坐标 |
| 中长按 | `F1 → F6` |
| 中单击 | `F1 → F4` |
| 中双击 | `F1 × 2 → F5` |
| 中三击 | `F1 × 3 → F8` |
| 右单击 | `F1 → ENTER` |
| 右双击 | 键盘的 `F1 × 2`，随后是消费控制设备的 `BACK` |
| 右长按 | `F1 → F2`，`F2` 持续按下约 0.78 秒 |
| 右三击 | `F1 × 3 → F7` |
| 触摸板移动 | `BTN_DIGI` 和 `ABS_X / ABS_Y` 坐标变化 |
| 触摸板单击 | `BTN_TOUCH` 与 `BTN_DIGI` 按下、松开 |

`F1` 出现在多类操作中，单独出现时不足以区分动作。滚轮的组合键和触控重叠应以单项复测确认后再做业务映射。

这些名称来自眼镜系统的输入设备层。证据支持 HID 输入形式。蓝牙链路类型（BLE 或经典蓝牙）、Android 应用层键码和 CXR-L 是否转发，需要分别验证。

## 2026-11-11 源码排查与下一步复测

基线：`87c81d6`（本次提交只新增本记录，未接入戒指业务；该提交 CI 通过）。

### 已确认的软件缺口

- 自有 RFCOMM 眼镜端 `GlassesBtActivity.onKeyUp` 只发送 `probe.glasses` / `keycode-N`。它不是 `button.pressed`，也不会直接调用确认或取消。仅有 UP 的记录不足以区分本次同为 F3 的左短按和左长按。
- 该 Activity 的业务入口目前是 `ACTION_SPRITE_BUTTON_CLICK` / `ACTION_SPRITE_BUTTON_LONG_PRESS` 广播。不能从 HID 已枚举推断戒指会生成这些广播。
- Android 控件可能先处理 ENTER、方向键或 BACK；因此 Activity 的 `onKeyUp` 未记录，不等于 Android 没收到键。
- `phone-l` 的 `CxrlSessionProbe` 注册生命周期、图片及 `IGlassesEventListener`（包括助手打断处理）回调，没有 RGR06 输入适配。眼镜内核事件不会因此自动变成手机的业务事件。自有 RFCOMM 探针与 CXR-L 两条路线必须分别验收。

### 本次诊断补丁

`GlassesBtActivity.dispatchKeyEvent` 在控件分发之前，对名称为 `RGR06`、`RGR06 Keyboard` 或 `RGR06 Consumer Control` 的设备输出 debug-only `Rgr06Input` 日志：Android keyCode/名称、scanCode、source、设备运行时 ID、DOWN/UP、重复次数、downTime/eventTime、持续时间和 canceled。

这只是被动诊断，仍调用系统正常分发，不消费按键、不改系统权限、不接管全局输入、不发送新的业务命令，也不把设备名称当成可信身份。它只覆盖前台 Activity 收到的 KeyEvent，不覆盖触控 MotionEvent、后台、系统截获的按键或 CXR-L 转发。已有控件的默认 ENTER/BACK 行为仍然存在。

### 真机复测步骤（未在本轮执行）

1. 使用本补丁的 `glasses-bt` debug APK，在眼镜上打开 Activity。先确保没有语音/拍摄授权窗口，避免正常控件或已有按钮广播触发采集。不要修改官方助手或无障碍权限来抢占输入。
2. 同时保留原有 `adb shell getevent -lt` 输入记录和应用日志：`adb logcat -v monotonic Rgr06Input:D '*:S'`。如连接多台设备，用自己选择的 `adb -s` 目标；共享日志前脱敏设备序列号和蓝牙地址。
3. 左单击、左长按、中单击/双击/三击/长按、右单击/双击/三击/长按分别独立重复。每项标注真实动作，与日志中的 DOWN/UP、持续时间、重复次数及 canceled 对齐。F1 前缀不作为独立动作；Android keyCode 数值不得由 Linux KEY_* 数值直接替代。
4. 上滚、下滚、触摸板分开复测。当前诊断不记录 MotionEvent；只有坐标输入而无 KeyEvent 时，记录为需要单独验证的触控路径，不猜测映射。
5. 记录 Activity 前台、探针按钮有/无焦点、切到后台、断开/重连后的差异。无日志可能是系统截获、设备名不同、窗口没有焦点或 release APK；先定位，不据此断言蓝牙失败。`deviceId` 与 `/dev/input/eventN` 都不能持久硬编码。
6. 对 CXR-L 单独确认 SDK 支持的输入回调和手机实际收到的内容。只有建立 Android 回调及跨端传输证据后，再选择确认/取消/重播映射、按下抬起配对、重复去重和断连清理策略。取消事件不得触发确认，重连不得恢复旧授权。

### 验证边界

本补丁配套 Node 源码合同检查覆盖 debug/device 过滤、诊断字段及不新增业务调用；它不执行 Android 分发。类型与合同检查、Node 测试不能替代 APK 构建和真实戒指验证。没有实际 Android KeyEvent 记录前，#44 的业务接入及真机验收仍未完成。
