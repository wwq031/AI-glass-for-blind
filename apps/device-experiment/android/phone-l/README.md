# CXR-L 独立诊断探针

对应 [#20](https://github.com/wwq031/AI-glass-for-blind/issues/20) 和 [#21](https://github.com/wwq031/AI-glass-for-blind/issues/21)。在现有模块恢复 `PhoneLActivity`，检查官方 App 安装/兼容性，提供授权与 CUSTOM_VIEW Session 诊断；不初始化 Agent、模型、TTS 或地图。

## SDK 来源与版本

显式从仓库残留的 `client-l:1.0.1` 迁移到 `1.1.2`，依据 [Rokid 官方 CxrlSample.zip](https://rokid-ota.oss-cn-hangzhou.aliyuncs.com/toB/Document/CXR-L/1.1.2/CxrlSample.zip) 的 `SetupViewModel.kt` 与 Manifest 核对安装、兼容检查 API 及三个官方 App 包名。原入口在可见 Git 历史与交接附件中均未找到，因此补最小入口，而非复制第二套业务应用。Sample ZIP SHA-256 见[离线来源记录](../../../../docs/handoff/2026-10-14-cxrl-offline-baseline.md)。

探针使用 `CxrSessionManager`，不混用历史 `CXRLink`；版本兼容以 SDK 的 `RokidAppStatus.Compatible` 为准，不硬编码旧版 App 阈值。Token、异常正文和设备编号均不展示或记录。

## 构建

使用 JDK 17/21，配置完整 Android SDK（platform 36.1、Build Tools 35.0.0）与 Gradle。在仓库根目录执行：

```powershell
$env:JAVA_HOME = '<完整 JDK 17 或 21 路径，包含 jlink>'
$env:ANDROID_HOME = '<Android SDK 路径>'
& '<Gradle 路径>\bin\gradle.bat' -p apps/device-experiment/android -PcxrlProbeOnly=true -PleqiSourceRevision=$(git rev-parse HEAD) :phone-l:assembleDebug
```

`cxrlProbeOnly=true` 仅配置 phone-l，避免完整 phone-bt 的模型/TTS 依赖影响独立探针。默认工程仍配置 phone-bt、glasses-bt，并新增 phone-l。Java 与 Kotlin 字节码目标均为 17。

APK：`build/outputs/apk/debug/phone-l-debug.apk`（相对此目录）。APK、SDK、Gradle 和本地配置均不提交 Git。

## 安装与验收

设备到位、#19 的官方 App 基础链路检查通过后，指定自己的本地设备编号执行（不要上传编号）：

```powershell
adb -s <手机编号> install -r apps/device-experiment/android/phone-l/build/outputs/apk/debug/phone-l-debug.apk
adb -s <手机编号> shell am start -n com.leqi.experiment.phonel/.PhoneLActivity
```

检查无入口类缺失异常；官方 App 缺失/不兼容时状态应明确，返回探针后可刷新。不把 APK 构建、App 安装或环境兼容当成 SDK Session、拍照或眼镜显示成功。手机按钮只用于诊断，不是盲人主流程。

## 2026-10-14 离线结果

- build：JBR/JDK 21.0.10、Gradle 9.2.0、AGP 8.13.2、Kotlin 2.4.20、Android platform 36.1、Build Tools 35.0.0，`:phone-l:assembleDebug` 成功；真实解析并编译 `client-l:1.1.2`。
- 官方 AGP 8.13 兼容表最低 Gradle 8.13。下载 8.13 因网络重置/超时失败，本轮实际验证的是已有 9.2.0，不宣称已验证 8.13 或全部 Android 模块。
- physical：设备暂时不在；安装、启动、官方连接、授权和 Session 均未验收。#20 保持开放。

## Session 诊断（#21 离线准备）

只有设备到位且 #19 / #20 的真机关口通过后，执行下列诊断。此界面不会自动授权、连接、拍照或恢复旧请求。

1. 允许 Android 蓝牙连接权限后，再点击“请求官方授权”。只请求 CAMERA / MEDIA，不采集录音，也不会调用拍照 API。
2. 官方 App 返回授权结果后，点击“创建并连接 CUSTOM_VIEW Session”。Token 只在内存中；不显示、不持久化、不记录其部分内容。
3. 依次核对连接回调、官方 SDK 蓝牙状态和 `onSessionStarted`。1.1.2 的 `connect` 返回 Unit，调用本身不是连接证据。
4. 眼镜应该显示 `CXR-L probe #<代号>`；佩戴者必须现场核对，与 trace 的 `g=<代号>` 对应。SDK Started 不自动计作“文本已显示”。
5. 手动关闭或退出 Activity，确认清理状态；取消后需重新授权，旧 SDK 回调不能恢复会话。授权超时 60 秒，连接/Started 超时 30 秒，暂停后未恢复超时 60 秒。授权界面晚到返回也按原尝试代号拒收；上次授权界面返回前不开始新授权。

trace 使用本机单调时钟、尝试代号、枚举错误码和异常类名，最多保留 100 行。它不记录 SDK 返回的任意错误正文、Token、设备信息或媒体。此处的时间只用于诊断手机阶段，不能当作跨设备单向延迟。

先评估 CUSTOM_VIEW，因为它不要求自有眼镜 APK，且适合初始文字反馈。CUSTOM_APP 用于自有眼镜 APK/自定义指令，依赖匹配的硬件与 App 配置；本轮没有切换到该路线，也没有以 RFCOMM 成功代替 CXR-L。

官方 Sample 中的 `SessionHubViewModel.kt` 用于核对创建、注册回调、connect 和 close 顺序；真实下载的 1.1.2 AAR 也已核对接口签名。历史 `binder=null` 的根因尚未复现或证实。若设备仍失败，用同版本官方 Sample 对照并记录最早失败阶段；不要绕过授权或修改官方系统组件。

本轮最终代码的 `assembleDebug` 与 `lintDebug` 通过（lint：0 errors）；默认工程的 `projects` 确认保留三个模块。类型检查、合同校验及 Node 测试 183/183 通过。Android 生命周期、授权 UI、超时和实际设备回调尚未进行真机验证，不能由编译结果推导它们已经通过。

#21 的离线代码完成不代表用户授权、Started 或可见显示通过；#21 仍开放。真机操作依赖实际前置证据，不以模拟状态替代。

## 图片接收（#22 离线实现）

用户已明确同意先用手机确认按钮完成通信调试；眼镜实体键仍待真机确认接入，不将调试按钮计作实体键验收。

Session Started 后点击“拍照：打开本次确认窗口”，再在对话框确认，才调用 `takePhoto(640,480,80)`。确认窗口与图片接收/解码分别有 30 秒超时。取消、暂停或退出撤销本次请求；没有确认、旧回调、重复图片不会产生有效结果。

SDK `IImageCallback` 没有请求 ID，因此每个 SDK Session 只允许一次相机调用。失败、超时或取消已确认的拍照时，关闭旧 SDK Session；重新授权和连接后才能发起全新请求。成功后亦须关闭并重建 Session 才能再次拍照。不能仅换一个手机 request_id 就宣称旧图片不会串入下一次请求。

原始 WebP/JPEG 在有界内存内解码和预览，不写相册、不输出图片正文。检查完整容器、最大 12 MiB、支持 MIME、尺寸及实际 Bitmap 解码；最大边长 4096、最大 1200 万像素。官方 1.1.2 原始 WebP 保持原格式，不伪称已收到 JPEG；只有后续合同要求 JPEG 时才另行转换。

软件测试包含授权窗口、取消、暂停、重复/晚到图片、单 Session 单拍照约束，以及 Robolectric 原生 JPEG/WebP 解码、损坏/截断/超限/非支持格式。它们不执行真实摄像头或官方 App；现场内容、设备图片回调和真机预览仍需验收。

## 独立 Mock 闭环（#23 离线实现）

真实有效图片解码完成后，自动调用确定性的 Mock 观察替身，然后通过同一 SDK Session 的 `customViewUpdate` 更新 `title_text`。增量更新使用官方 Sample 的 JSON 数组格式并正确转义文本。手机只播报脱敏状态，不调用 Gemma、TTS、地图、云端或第二套业务 Agent。

Mock 复用仓库 ObservationResult 字段，保留 session_id/request_id/capability_id（vision.scene），输出明确的 `Mock：图片已收到 … #<请求代号>`，confidence=unknown、status=partial、facts 为空。它证明通信路径，不进行真实视觉识别、安全判断或模型性能验收。

无有效图片、损坏、取消、断连或过期回调不会进入 Mock；显示更新失败如实记录并结束旧 Session。SDK 接受更新与眼镜实际显示分别记录。只有佩戴者现场核对相同请求代号后，才能点“人工确认眼镜看见本次 Mock 文本”，该记录明确属于人工确认，不伪称 SDK 显示回执。

尚未采集真机闭环证据。断开互联网后的本地链路、实际图片和眼镜显示须在当前官方 App/固件组合上验证；本 APK 没有新增远程模型调用。

## 生命周期与恢复（#24 离线实现）

当前是前台通信调试 APK，约定手机宿主后台/灭屏、眼镜灭屏/返回桌面/应用暂停时关闭 Session，撤销采集与结果；返回后不自动重连或恢复旧确认。跳转官方授权界面时尚未创建 Session，不会因 Activity onStop 误取消授权。它不依靠常亮屏幕运行，也不承诺后台持续摄像。

官方助手唤醒/中断和摘下眼镜时撤销本次拍照确认。拍照中发生 SDK Paused/断连则关闭旧 Session；晚到 Resumed、图片和显示结果按代号拒收。手动恢复入口清理旧 Session 并重新请求授权，之后需手动连接和全新拍照确认；没有无限重试或旧授权自动复活。

connect、takePhoto、customViewUpdate 和资源释放在独立的串行 SDK 工作线程执行，图片解码另用工作线程，手机界面与超时计时不被 SDK 请求阻塞。SDK 显示请求有 20 秒超时；发送完成前不能人工确认成功。取消/退出先同步作废本地代号，再排队解除回调和 close。已经进入厂商 SDK 的调用无法由本程序保证立即中断；若 Binder 长期卡住，排队 close 的完成仍需真机观察，不能以本地已取消替代原生资源已释放。

CUSTOM_VIEW 不提供眼镜实体键回调，本轮不把屏幕事件映射成实体键。用户同意的手机确认仅用于调试；眼镜实体键与官方助手共存仍须真机确认支持路线，必要时另接 CUSTOM_APP。已有 RFCOMM 运行层不改动。

## 重复验收与延迟记录（#25 离线实现）

每次确认拍照记录 request_id/session_id、测试者指定的 cold/warm、原始图片 MIME/尺寸/大小，以及手机 `elapsedRealtime` 阶段：确认、手机处理完整图片回调、解码校验、Mock 开始/结束、显示 API 开始/返回、人工确认可见。SDK 未公开曝光时间、首分片时间或显示回执；这些值保持未知，不能补造眼镜拍照/上传单向延迟。

手机图片回调时间不是曝光时刻；SDK API 返回时间不是可见时刻；人工确认包含反应时间。只能计算同一手机单调时钟的阶段差值，不将两台设备的时钟直接相减。精确眼镜拍照/传输或首次可见时间需要外部录像等额外证据。cold/warm 指手机进程冷启动/已运行，不表示 SDK Session 被复用（本实现每次使用全新 Session）。

在拍照前选冷/热类型并填写眼镜固件版本。每次确认时快照记录 App/SDK/手机/API/固件/官方 App 版本；导出补已安装 APK SHA-256 和编译时 source_revision。未设置或无法读取的版本保持 unknown/unrecorded，需要补齐后才能完成验收。记录最多保留 100 次，不保存原始图片、Token、账号、序列号、位置或错误正文。导出 Android 文档选择器会触发后台停止；建议在可见确认或失败已记录后导出。

点击“导出脱敏验收记录 JSON”，选择自己的受控目录，然后在仓库根目录执行：

```powershell
node tools/summarize-cxrl-mvp.mjs <记录.json> > <报告.md>
```

汇总工具按每次记录的版本和 cold/warm 分组，不混合 APK 或启动口径；输出确认拍照样本数、人工确认率、仅发送/失败/未完成数、失败阶段及各手机阶段 P50/P95/最大值。分位数采用 nearest-rank；缺失阶段保持空，不填 0；重复请求、跨时钟类型、时间逆序和缺少对应可见记录会报错。多个 JSON 必须选择不重叠的样本。

记录只保留在当前 Activity 的内存中，销毁或进程退出会丢失；冷启动测试每次完成后应先导出，各 JSON 的请求不能重叠。导出证据级别默认 unverified，用户人工确认也不自动升级为完整真机验收。Mock 耗时不能代表 Gemma 推理性能，Mock 文本不代表识别或安全辅助能力。重复真机样本、现场一致性和可见结果仍需人工检查。

本轮 v0.2.0 最终本机构建采用完整 Oracle JDK 25.0.2 + Gradle 9.2.0（Java/Kotlin 目标 17），APK、原生测试与 lint 通过。此前 JBR 21 没有 jlink，启用 BuildConfig 生成 Java 后不能用于完整构建；CI 使用完整 Temurin JDK 21。未修改系统 JAVA_HOME。
