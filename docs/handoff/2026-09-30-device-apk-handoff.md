# 双端 APK 当前快照与组员交接（2026-09-30）

## 状态

这是开发交接快照，**未通过完整实机验收**。用户要求暂停继续修复，上传现有源码、工具与安装包供组员接手。不要将此快照当作可独立展示的正式版本。

正式业务直接运行仓库 TypeScript Agent、合同、工具、策略与会话代码；Kotlin 用于设备适配。正式 Gradle 工程只包含 `:phone-bt`、`:glasses-bt`。历史实验模块不是正式入口。

## 已生成、已安装的 APK

| 文件 | 构建时间 | SHA256 |
| --- | --- | --- |
| phone-bt-debug.apk | 2026-09-30 12:03:53 | 50BA1A85FBC257EA155869BBF714A4708E5FF70F2ACAEAF3891FA5F37C40CF74 |
| glasses-bt-debug.apk | 2026-09-30 11:36:00 | 676D039798F8FEA89CC34784BB2183BE6137139D2A0F408FEE0928B5191A1B87 |

两包均已在本轮手机/眼镜上安装并启动。手机已读取本地 Gemma/TTS 配置，仓库 bundle 报 ready，自有蓝牙通道曾实际连接。启动自检不能证明模型推理、语音识别或完整导航任务已经成功。

## 验证证据

- 双模块 Gradle 构建成功，见本次交接 evidence/final-dual-build.log。
- 最新有记录的定向 TypeScript 测试为 **43/43，通过，exit 0**：dual-apk-golden-loop（15）、adapter-quality-fix（11）、navigation-crossing-replay（17）。见 evidence/final-integrated-gates.log。
- 上述测试运行仓库实际打包代码，但模型和设备为模拟依赖；没有最终整仓回归结果，不代表实机导航验收。
- 语音解析测试曾 38/38；具体证据随交接包保留。
- 最新用户实测：短按一次镜腿功能键，听到“连接 Rokid App”；没有得到本项目 Agent 的真实回复。

## 当前第一阻塞：眼镜后台生命周期

ADB 系统退出记录：2026-09-30 12:40:01，眼镜应用 PID3177 被 LOW_MEMORY / TOO MANY EMPTY PROCS 回收，importance400、state empty。

现有 `GlassesBtActivity.kt` 同时承载动态按键接收器、蓝牙服务、录音/拍照/播放。Manifest 未注册后台 Service。进程回收后没有按键监听和蓝牙服务，手机自动重连尝试5次后停止。已人工恢复原应用进程并重新连通，但后台生命周期修复尚未完成，也没有新修复 APK。

接手优先级：将现有 Kotlin 设备运行层迁至合规前台服务，独立于 Activity 存活，保留原传输协议、授权窗口、代号隔离、取消和资源释放。复用现有手机前台 Service 的组织方式；不要新增 Agent/FSM 或意图关键字实现。不要把保持屏幕亮着作为正式解决方案。

## 入口与后续实机检查

- 当前短按：有语音窗口时确认并录音；有拍照窗口时确认并拍照；没有窗口时发送按键事件。首次事件先播放开场帮助，再打开语音窗口，所以当前实现不能把“一次短按后立即说话”视为已经采集。
- 当前长按映射取消；DOWN/UP/DOUBLE 已注册但未处理。触控入口尚未得到真实事件验收。不要臆造映射或修改系统助手权限。
- 断链清理曾使手机标题显示 `device.send failed ... entrance.disarm`；恢复连接后自检可再次通过，仍应核对取消/断链错误提示和状态准确性。
- 下一轮应先验证后台生命周期与真实按键到达，再进行真实录音→Gemma→仓库 Agent→TTS→眼镜播报，随后持续交互、真实定位导航、到达与入口确认。
- 自检展示的“本地模型已加载”不能代替成功创建推理会话和实际输出；语音、视觉、自由交互、真实导航和入口闭环均仍需实测。

## 构建与源码入口

1. 安装 Node，按 packageManager 配置安装项目依赖，运行 `pnpm typecheck`、`pnpm build:android-agent`。
2. JDK17兼容工具链；本机 Android Studio JBR 可用。Gradle8.13，Android SDK36.1。工程在 `apps/device-experiment/android`。
3. 构建 `:phone-bt:assembleDebug :glasses-bt:assembleDebug`。本机使用 `LEQI_BUILD_ROOT=D:\leqi-device-experiment\build` 避免C盘空间不足；组员改为自己的路径。
4. `apps/device-experiment/android/local.properties` 配置 `sdk.dir`、`AMAP_ANDROID_KEY`，文件不进入 Git。AMap 调试 Key 须绑定本机签名 SHA1 和包名 `com.leqi.experiment.phonebt`；原 APK 使用现有签名与配置。
5. `apps/phone-companion/android/libs/sherpa-onnx-1.13.8.aar` 是本地构建依赖，随附件提供，放回相同路径；非业务源码。
6. Gemma 与 TTS **不包含在 APK 中**。本机手机已有模型；其他手机须另行提供 `gemma-4-E2B-it.litertlm` 与完整 TTS 目录。适配代码读取应用私有 `files/models/` 下对应路径，具体以 `GemmaLocalBridge.kt` / `OfflineTtsBridge.kt` 为准。安装 APK 本身不会自动下载或部署模型。

主要入口：`apps/phone-companion/src/android/agent-browser.ts`；`packages/domain/agent/session-orchestrator.ts`；`apps/phone-companion/android/.../AgentHostService.kt`；`apps/glasses-agent/android/.../GlassesBtActivity.kt`。

工具包括 bundle/合同生成、打包、语音解析测试、荣耀 ADB 接口修复。`repair-honor-adb-interface.ps1` 是特定本机 MI_02 诊断修复工具，默认先只读检查，不能对其他组员机器直接套用本机标识。修改有备份、值级回滚；不要关闭签名验证或整体重装驱动。

## 历史资料

之前桌面审计文档记录的是凌晨旧实验版本，作为历史问题材料保留；不能用它的旧结论替代当前源码与最新证据。旧 release README/manifest/source-baseline.zip 存在过期验收标注和不完整源码归档，以本交接说明与新快照为准。

密钥、原始录音照片、设备私有注册表备份、模型权重和官方应用解包不在公开交接包中。
