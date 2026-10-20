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

SDK `IImageCallback` 没有请求 ID，因此每个 SDK Session 只允许一次相机调用。失败、超时或取消已确认的拍照时，关闭旧 SDK Session；重新授权和连接后才能发起全新请求。成功后再次点击拍照会先回收旧 Session；确认 SDK 状态为 Idle 后重新授权、创建并连接新 Session，再弹出全新的拍照确认窗口。原有确认不会带到新 Session，相机仍只在本次明确确认后调用。不能仅换一个手机 request_id 就宣称旧图片不会串入下一次请求。

原始 WebP/JPEG 在有界内存内解码和预览，不写相册、不输出图片正文。检查完整容器、最大 12 MiB、支持 MIME、尺寸及实际 Bitmap 解码；最大边长 4096、最大 1200 万像素。官方 1.1.2 原始 WebP 保持原格式，不伪称已收到 JPEG；只有后续合同要求 JPEG 时才另行转换。

软件测试包含授权窗口、取消、暂停、重复/晚到图片、单 Session 单拍照约束，以及 Robolectric 原生 JPEG/WebP 解码、损坏/截断/超限/非支持格式。它们不执行真实摄像头或官方 App；现场内容、设备图片回调和真机预览仍需验收。

## 独立 Mock 闭环（#23 离线实现）

真实有效图片解码完成后，自动调用确定性的 Mock 观察替身，然后通过同一 SDK Session 的 `customViewUpdate` 更新 `title_text`。增量更新使用官方 Sample 的 JSON 数组格式并正确转义文本。手机只播报脱敏状态，不调用 Gemma、TTS、地图、云端或第二套业务 Agent。

Mock 复用仓库 ObservationResult 字段，保留 session_id/request_id/capability_id（vision.scene），输出明确的 `Mock：图片已收到 … #<请求代号>`，confidence=unknown、status=partial、facts 为空。它证明通信路径，不进行真实视觉识别、安全判断或模型性能验收。

无有效图片、损坏、取消、断连或过期回调不会进入 Mock；显示更新失败如实记录并结束旧 Session。SDK 接受更新与眼镜实际显示分别记录。只有佩戴者现场核对相同请求代号后，才能点“人工确认眼镜看见本次 Mock 文本”，该记录明确属于人工确认，不伪称 SDK 显示回执。

尚未采集真机闭环证据。断开互联网后的本地链路、实际图片和眼镜显示须在当前官方 App/固件组合上验证；本 APK 没有新增远程模型调用。

## 生命周期与恢复（#24 离线实现）

当前是前台通信调试 APK，约定手机宿主后台/灭屏、眼镜灭屏/返回桌面/应用暂停时关闭 Session，撤销采集与结果；返回后不自动重连或恢复旧确认。跳转官方授权界面时尚未创建 Session，不会因 Activity onStop 误取消授权。它不依靠常亮屏幕运行，也不承诺后台持续摄像。

SDK 在 Idle 也可能发出眼镜事件；尚未第一次 Started 时，桌面/灭屏/眼镜应用暂停仅记录初始状态，不误中止启动。Started 后同类事件按运行中断处理。该顺序有独立回放测试覆盖，实际固件回调顺序仍需真机验证。

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

## v0.2.1 真机反馈修复

用户实机反馈和脱敏日志已显示授权成功、Session Started、真实 WebP 图片解码（480×640、24262 bytes）、Mock 生成及 SDK 接受显示更新；用户反馈 Mock 信息已出现。本轮按用户要求只修复授权恢复与 Session 回收，不加入额外文字探针。当前设备组合的重复拍照和生命周期验收仍须继续。

核对真实 1.1.2 AAR，官方 App 可直接返回缓存授权，不启动 Activity。v0.2.1 在授权回调完成后释放等待标志；确实启动授权页面时捕获 SDK 的动态 requestCode，拒收旧返回，避免手动恢复后一直等待不存在的页面。

首次授权和连接步骤保持不变。一次 Mock 显示更新被 SDK 接受后，再次点击“拍照：打开本次确认窗口”会撤销旧请求、解除回调并关闭旧 Session，在 SDK 工作线程检查旧状态。只有确认 Idle 才会再次请求授权、创建并连接新 Session，Started 后显示新的拍照确认对话框；相机不会自动调用。SDK 回收等待最长 10 秒，未到 Idle 或清理异常导致未释放时停止本次自动准备，不创建冲突 Session；需点手动恢复后重试。重复点击不会重复关闭或创建。

确认窗口取消不占用新 Session 的拍照机会；已经调用过相机的 Session 永不复用。当前拍照/解码/显示尚未结束时拒绝开始下一次，用户仍可主动取消。回收期间取消或手机进入后台会撤销待弹窗的请求；旧图片、旧确认和旧生命周期回调不能进入新请求。确实跳转官方授权 Activity 时保留本次准备，以便用户授权后继续连接，仍须重新确认拍照。

回归测试覆盖缓存授权、实际 Activity 请求码、旧授权拒收、连续两次真实解码输入对应不同请求、异步回收未 Idle 时不授权/创建、取消和后台撤销、回收失败/超时、重复点击和旧图片隔离。测试使用 SDK 接口替身和 Robolectric，不替代实际固件上的连续拍照验证。


## v0.3.0 内置 Gemma 视觉识别

用户选择将完整模型权重随 APK 提供，首次安装后不导入、不下载。`-PcxrlGemma=true` 构建启用真实 Gemma 图片推理；默认构建继续是 Mock 调试，运行时不自动从 Gemma 失败回退到 Mock。

模型固定为官方 LiteRT 社区的 `gemma-4-E2B-it-gpu.litertlm`，版本 `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`，2008432640 bytes，SHA-256 `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5`。GPU 权重不是此前约 2.59GB 的 CPU/GPU 通用文件；本版使用 GPU 文本与视觉后端，固定 LiteRT-LM 0.17.1。复用原有 GemmaLocalBridge，通过独立 library 只编译模型桥接和输出解析，不引入旧 Bluetooth/Agent/TTS 宿主。旧宿主的默认 CPU 配置保留。

下载与完整构建（需完整 JDK、SDK 36.1 / build-tools 35.0.0）：

```powershell
./tools/download-cxrl-gemma.ps1
$revision = git rev-parse HEAD
# 使用已安装的 gradle 9.2.0 或对应完整路径
gradle -p apps/device-experiment/android -PcxrlProbeOnly=true -PcxrlGemma=true "-PgemmaModelPath=$PWD/artifacts/models/gemma-4-E2B-it-gpu.litertlm" "-PleqiSourceRevision=$revision" '-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8' :phone-l:assembleDebug :phone-l:testDebugUnitTest :phone-l:lintDebug
```

下载脚本先访问原站，网络不可达时使用镜像；固定 revision、大小和原站 SHA-256 校验。权重不进入 Git。完整构建先校验模型，再分成有界、不压缩的 APK assets；首次运行在工作线程流式复制到本应用私有目录，校验完整 SHA-256 后原子提交。再次启动校验已有文件后复用。缺少、截断、错误权重、空间不足不会产生“模型已就绪”。APK 含 Apache 2.0 文本与模型来源 NOTICE。

CI 使用 `-PcxrlGemma=true -PgemmaCodeOnly=true` 只编译和测试代码，免去每次下载 2GB；这种 APK 不含权重，不能作为完整版本发布。完整构建缺少模型路径或校验不符时直接失败，发布前还必须从 APK 各分片重新计算模型 SHA-256。

安装与真机检查：

1. 覆盖安装 v0.3.0（arm64-v8a）。首次解包、模型和 GPU 缓存需要额外空间，建议安装前至少预留 8GB。保持应用前台，等待“Gemma 已就绪”。GPU 驱动兼容性仍需在实际手机验证；初始化失败会显示失败，不能以编译成功替代推理成功。
2. 授权并连接眼镜，Started 后点拍照并明确确认。原始 WebP/JPEG 先按现有边界校验、解码，工作线程将同一帧转为 JPEG 交给 Gemma；记录保留原始 MIME/尺寸和字节数。
3. 图片只留在本机内存，当前模型端口不上传图片。每次调用建立并关闭新的模型 Conversation，避免上一张图污染下一张。输出仅接受有界、单字段 JSON summary；无内容、异常格式和模型错误不会伪装成成功。
4. 眼镜应显示 `Gemma：<实际画面描述> #<本次代号>`。核对内容与现场，再点人工确认。此版本完成的是图片识别接入，没有新增语音、TTS 或完整导航流程。
5. 再次拍照仍先回收旧 SDK Session 至 Idle，再授权/连接和确认。连续检查至少三张不同场景。取消、后台、断连及 120 秒推理超时作废当前结果；旧模型调用可能继续至原生返回，期间不排队发起新的推理，返回后丢弃旧输出。原生推理返回前不会并发释放引擎。

导出 `mode=gemma`，阶段为 `inference_start` / `inference_end`，并记录模型 SHA-256、GPU 后端和运行库版本；不记录图片、提示词或原始模型输出。汇总工具将 Mock 与 Gemma、不同模型版本分组，禁止混用 Mock 时间作为模型耗时。软件测试使用模型端口替身验证异步隔离，真实模型推理、GPU 性能和现场识别准确性仍需用户真机验收。


## v0.4.0 内置离线语音识别、对话和播报

用户选择 SenseVoiceSmall INT8 → Gemma 4 E2B → MeloTTS zh_en INT8 的手机本地方案。完整 APK 同时内置三套权重，安装后无需导入语音模型。语音输入为手机内置麦克风；输出默认手机扬声器，也可选择跟随 Android 系统媒体路由，通过已经配对的眼镜蓝牙播放。输出路由类型与人工实际听见分别记录，不将普通蓝牙设备自动认定为眼镜。

本版实现前台、明确启动、一问一答：点开始并确认后，最多录制 30 秒的 16kHz/单声道/PCM16；点结束或达到上限后完整收音，再依次执行 ASR、Gemma 文本回答、MeloTTS 和播放。播放时禁止开始新录音，避免将本机播报再次识别。录音、CPU 语音模型、GPU Gemma 和播放各有独立工作队列，界面不会等待原生推理。取消、音频焦点丢失、进入后台、销毁及超时撤销当前代号；晚到结果不识别为新一轮、不播报旧回答。原生任务若已执行，允许结束后丢弃结果，再串行释放资源。

语音对话本身可以使用手机麦克风和扬声器独立运行，不需要 Rokid 官方授权。眼镜图片采集和文字显示仍走官方 App / CXR-L；本版没有宣称与官方 App 完全解耦。真实 1.1.2 接口存在 startAudioStream/addAudioCallback，但缺少回传音频格式元数据，未将未知格式硬编码送入 ASR；眼镜麦克风与眼镜实体键不在本版已验收范围内。公开 CxrSession 没有发送任意 TTS PCM 的播放方法，蓝牙播报使用 Android 媒体路由。

Gemma 文本与图片共享原有引擎，在同一工作线程串行执行；每次文本生成建立新 Conversation，调用方提供最多四组且按字符预算裁剪的历史。回答必须为单字段 JSON answer，最多 140 字符；不执行拍照、导航或联网操作。询问画面时可以引用最近一次已成功返回的图片描述，明确标作历史图片，不能假装是当前实时画面。拍照仍使用原有明确确认窗口；最近图片结果可通过“朗读最近一次图片识别结果”播报。取消不会伪造识别、回答或静音占位音频。

SenseVoice/Melo 权重、词典、规则和许可共 19 个文件，原始共 313860895 bytes。模型与 Android AAR 的版本、来源、大小、SHA-256 固定在 `tools/cxrl-speech-models.lock.json`。MeloTTS 模型为 MIT；SenseVoice 遵循其所链接的 FunASR 模型许可，随包保留原许可引用、模型名称、来源、作者及协议全文。完整构建逐文件校验；首次运行流式校验并原子写入应用私有目录，缺失、损坏、空间不足时拒绝初始化。语音版本对 APK assets 启用无损 ZIP 压缩，使完整单 APK 保持在 2GiB 以下；解压后权重不改变。

构建：

```powershell
./tools/download-cxrl-gemma.ps1
python tools/prepare-cxrl-speech.py
$revision = git rev-parse HEAD
gradle -p apps/device-experiment/android -PcxrlProbeOnly=true -PcxrlGemma=true -PcxrlSpeech=true "-PgemmaModelPath=$PWD/artifacts/models/gemma-4-E2B-it-gpu.litertlm" "-PspeechModelRoot=$PWD/artifacts/models/speech" "-PleqiSourceRevision=$revision" '-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8' :phone-l:assembleDebug :phone-l:testDebugUnitTest :phone-l:lintDebug
python tools/verify-cxrl-gemma-apk.py apps/device-experiment/android/phone-l/build/outputs/apk/debug/phone-l-debug.apk
```

CI 下载固定哈希的 Sherpa Android AAR，以 `-PcxrlSpeech=true -PgemmaCodeOnly=true` 验证真实代码路径，不发布没有权重的 CI APK。可额外在桌面安装 `sherpa-onnx==1.13.8` 和 numpy 后执行 `python tools/test-cxrl-speech-native.py`：测试官方中文 WAV 的真实识别、Melo 的非静音合成及 TTS→ASR 回环。此验证已在 Windows x64 CPU 上运行通过，不能替代 Android 麦克风、GPU 和眼镜蓝牙实测；回环并非逐字无误，例如“离线”被识别为“一线”，仍需实际语音质量验收。

安装与测试：

1. 覆盖安装 v0.4.0（arm64-v8a）；建议安装前预留 10GB 存储。保持应用前台，分别等待 Gemma 和离线语音就绪。
2. 首先调好媒体音量，点击“测试离线语音播报”，确认手机实际听见，再点“人工确认听见本次播报”。
3. 点开始说话，允许 Android 麦克风权限后再次点击并确认开始；权限返回不会自动录音。说“你好，请简单介绍一下你自己”，点结束录音并发送，依次核对识别文本、回答和实际播报。
4. 连续问三轮不同问题，检查每轮只录制/播报一次。再验证取消录音、取消推理、取消播放，以及切后台后没有旧结果复活或自动开始录音。
5. 按既有授权/连接/确认拍照流程完成图片识别，点击朗读最近一次图片结果。询问最近图片时核对模型回答确实来自该帧，不能将历史描述当作当前安全判断。
6. 需要眼镜播放时，先在 Android 系统媒体输出选眼镜，再选应用的系统媒体路由模式并测试实际听见；回调类型 Bluetooth 不等于已证明眼镜播放，必须人工确认。
7. 完成后导出 JSON。语音记录位于 speech 字段，包含请求代号、阶段时间、失败阶段、输出路由和人工听见确认，不包含录音、转写、回答或对话历史。历史只存内存，进入后台/清空/退出时撤销；播放头完成不等于实际听见。

当前不是唤醒词、自动 VAD、持续监听、全双工可打断对话或完整导航系统。实际手机的麦克风、Gemma GPU、语音延迟/准确率、扬声器及眼镜蓝牙需要真机验收；代码、构建、桌面原生测试不替代这些证据。

## v0.4.1 / v0.3.1 视觉权重修复（2026-10-20）

上文 v0.3.0 / v0.4.0 的“GPU 权重支持图片识别”结论有误，现予以更正。旧版 `gemma-4-E2B-it-gpu.litertlm` 只有 metadata、tokenizer 和 `tf_lite_artisan_text_decoder` 三个 section，没有视觉编码器。引擎 initialize 成功不会加载延迟创建的视觉资源，直到 createConversation 才报 `TF_LITE_VISION_ENCODER not found`；不能把旧版安装、引擎加载和替身测试通过当作真实视觉推理通过。用户的小米 15 Pro 真机日志显示照片接收与校验正常，失败发生在此处。

新版固定使用同一官方 revision 的完整 `gemma-4-E2B-it.litertlm`：2588147712 bytes，SHA-256 `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`。包含原始视觉编码器、适配器和文本模型，不做量化修改。下载脚本、构建校验、模型 assets manifest、手机导出 metadata、NOTICE 和 APK 验证器已同步。完整语音版 versionCode=6 / versionName=0.4.1；视觉版 versionName=0.3.1。

启动在模型 initialize 后创建并关闭一次无输入 Conversation，验证延迟加载的视觉资源，成功后才显示 Gemma 已就绪；该检查不调用相机或生成回答。初始化失败会显示阶段码；实际图片推理失败在脱敏 JSON 记录 `failure_code`，不导出原生错误正文、图片或提示词，不回退 Mock。

新的完整构建仍按前文命令执行，但 `-PgemmaModelPath` 必须改为 `artifacts/models/gemma-4-E2B-it.litertlm`。发布前执行 APK 验证器，检查完整 hash 和必需视觉 section。桌面真实回归命令如下（需要固定版本 `litert-lm==0.17.1`）：

```powershell
python tools/test-cxrl-gemma-native.py --model artifacts/models/gemma-4-E2B-it.litertlm --legacy-model artifacts/models/gemma-4-E2B-it-gpu.litertlm
```

该脚本解析真实容器、复现旧权重失败，并使用官方公开苹果测试图，对完整模型执行三次新的 Conversation，校验真实中文 JSON 结果包含苹果。Windows GPU 实测通过；这不是小米手机或眼镜真机通过证据。现场仍需按交付说明完成三张不同物体的拍照与显示复测。

完整 APK 超过 GitHub 单附件 2GiB 限制，使用 `tools/package-cxrl-release.py` 分片分发，`restore-cxrl-apk.ps1` 校验每片和最终 APK 后恢复为单文件；不改变 APK 签名或模型。详见仓库 `docs/handoff/2026-10-20-cxrl-vision-fix.md`。原始权重约 2.59GB，下载大小和安装占用应据新版实测，不再沿用旧版 2.08GB 数字。
