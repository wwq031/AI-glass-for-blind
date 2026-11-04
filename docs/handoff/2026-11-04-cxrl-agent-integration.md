# CXR-L Agent 集成交付（2026-11-04）

## 当前状态

方案 A：手机应用保留 CXR-L，改用 `CUSTOM_APP` 会话，连接轻量眼镜应用。任务决策、目的地确认、观察授权、工具执行和追问继续使用仓库 TypeScript Agent。没有在 Kotlin 中新增另一套业务 Agent。

软件编译和自动化检查与真机验收分开记录。当前没有连接 ADB 真机；本文件不宣称新 CUSTOM_APP 通道已经完成实机验收。此前 CUSTOM_VIEW 通讯验证不能替代本次新增按键与音频路径的验收。

导航需要与 `com.leqi.experiment.phonel` 及当前安装包签名匹配的高德 Android Key。没有配置时，搜索返回 `navigation_not_configured`，不会伪造地点或导航成功。

已从仓库 `device-handoff-20260930` Release 的 `phone-bt-debug.apk` 恢复既有 Android Key，写入被忽略的根目录 `.env` 并重新打包；不在文档或源码中保存 Key。原发布包使用 `com.leqi.experiment.phonebt`，因此当前状态是“Key 已接入、控制台绑定待核对”，不是“Key 缺失”或“导航鉴权已通过”。

请在高德控制台核对 Package 为 `com.leqi.experiment.phonel`、调试版 SHA-1 为 `96:5A:EF:49:13:CD:E3:1E:61:30:E2:A9:D9:7C:76:D2:27:8A:6E:85`。修改既有 Key 的绑定可能影响旧应用，请按需要选择更新旧绑定或单独注册新 Key；本轮未修改控制台。[高德官方绑定说明](https://lbs.amap.com/faq/android/map-sdk/create-project/43260)

## 安装包与交互

- 手机：`apps/device-experiment/android/phone-l/build/outputs/apk/debug/phone-l-debug.apk`
- 眼镜：`apps/device-experiment/android/glasses/build/outputs/apk/debug/glasses-debug.apk`
- 手机包名：`com.leqi.experiment.phonel`；眼镜包名：`com.leqi.experiment.glasses`。
- 当前调试证书 SHA-1：`965aef4913cde31e6130e2a9d97c76d2278a6e85`，配置高德 Android Key 时须匹配此证书及手机包名。
- 当前证书与工作区 `0.4.2` APK 的证书不同，不能覆盖安装旧版本。若需保留旧应用及其中数据，应取得原签名并重签新包；本轮未卸载任何应用、未删除任何旧数据。
- 手机首次打开：授予蓝牙、定位、通知权限，阅读导航服务告知，经官方 App 授予眼镜相机、麦克风与媒体权限。
- 两端应用先安装。手机点击“授权并连接眼镜”，由官方应用启动眼镜端；等待眼镜显示模型和连接就绪。
- 眼镜短按开始会话，提示播放完后短按开始说话，录音中再短按结束；最长 15 秒。语音识别、Agent 与语音合成均在手机本地运行。
- 眼镜提示拍照时，另一次短按只确认当前观察能力；到达导航目的地本身不会拍照。
- 眼镜长按取消。手机通知中的“停止任务”可关闭整个服务。断开后需要重新连接和按键，不恢复旧授权。
- 新眼镜端直接使用本机 `AudioTrack` 播放 PCM；不存在回退到手机扬声器的成功路径。播放 API 完成仍需佩戴者确认实际听见。

## 构建

环境变量仅写入根目录 `.env`，该文件已被 `.gitignore` 忽略。需要 `ANDROID_HOME`、`JAVA_HOME`、`GRADLE_USER_HOME`、`GRADLE_EXECUTABLE`；导航 Key 使用 `AMAP_ANDROID_KEY`，不要写进提交、文档或聊天。

```powershell
node tools/build-cxrl-agent.mjs
```

默认从 `工作区/phone-l-speech-v0.4.2.apk` 提取 Gemma、SenseVoice、MeloTTS 与许可资源，先根据 `工作区/manifest-v0.4.2.json` 核验整个源 APK 大小和 SHA-256。资源只进入 `build/generated/modelAssets`，不进入源码目录。运行时仍逐项核验模型文件。指定其他来源：

```powershell
node tools/build-cxrl-agent.mjs --model-apk <已验证APK路径> --model-manifest <对应清单路径>
```

仅检查代码、无需打包权重：`node tools/build-cxrl-agent.mjs --code-only`。这个变体不能被当作可直接演示的完整软件。完整构建之后再次做无权重构建，应使用另一处构建输出目录，脚本拒绝复用已有模型资源生成虚假的无权重包。

构建前须恢复被忽略的 `apps/phone-companion/android/libs/sherpa-onnx-1.13.8.aar`，上游来源为 k2-fsa/sherpa-onnx 的 v1.13.8 Release；SHA-256 应为 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`。不要提交 AAR 或权重。

## 接线与边界

- `CxrlAgentActivity`：首次权限、官方授权与人工恢复，不判断任务意图。
- `CxrlAgentService`：前台服务拥有 Agent WebView、本地模型、ASR/TTS、地图设备端口和临时媒体。
- `CxrlAgentDevice`：绑定当前连接代号与眼镜握手随机值；过滤旧连接、重复事件和错误通道。会话暂停直接撤销本次任务。
- `GlassesActivity`：按键授权窗口、播放校验、真实播放回执。订阅 `rk_custom_client`，回复 `rk_custom_key`，依据官方 CXR-L 1.1.2 Sample 的 CUSTOM_APP 通道约定。
- `CxrlInputGate`：互斥、一次消费、过期和撤销的设备授权窗口，不保存业务会话。
- `build-mobile-agent.mjs --outdir`：从同一份 TypeScript 源码构建两个宿主的 Agent，不维护两份业务副本。
- Gemma 模型端口、`BundledModel`、`SpeechModels` 复用上游 `b9cbdada9e09485df287797e152ed9c59b146822` 的实现；本次接线是尚未提交的本地修改，`SOURCE_REVISION` 是基础提交号，不代表这些新增文件已存在于该提交。

眼镜上行音频目前按 CXR-L 16kHz、单声道、16bit PCM 接入；必须在目标官方 App/固件上核对实际音频格式和连续两轮回收。停止麦克风必须等待停止回执；超时关闭连接。相机没有请求 ID，拍照超时或处理中撤销均关闭连接，拒绝把旧图片归入下一次确认。

## 自动化与真机验收

本轮检查结果：根目录 189 项 Node 测试通过，TypeScript 类型检查及 33 个 Schema / 10 个示例 / 5 个能力 / 8 个 Skill / 7 个工具的协议校验通过。手机端 29 项 Android 单元测试通过，眼镜端运行同一组 4 项授权窗口测试通过。双端完整 APK 构建通过。完整手机 APK 内 2,588,147,712 字节的 Gemma 权重，以及 19 个语音资源文件均逐项校验通过；Agent JS 及 HTML 入口已实际进入 APK。

构建脚本执行手机与眼镜单元测试及双端 APK 构建；根目录另执行：

```powershell
node --test tests/**/*.test.ts
node node_modules/typescript/bin/tsc --noEmit
node tools/validate-contracts.mjs
```

重点真机顺序：握手 → 眼镜短按 → 提示从眼镜播放 → 再短按录音 → 结束录音 → 本地识别及眼镜回答 → 地点候选确认 → 真实步行导航 → 到达提醒 → 另一次按键拍照 → 图片理解与追问。

异常验收覆盖：长按取消正在推理/播放的任务、麦克风停止回执丢失、拍照超时、摘下眼镜/返回桌面、断连后重连；均不得恢复旧录音或拍摄授权。需要另外核对眼镜实体键广播在目标固件上不被官方默认拍照动作抢占。

官方 SDK/Sample 来源：<https://developerdoc.rokid.com/sdk?lang=en>；<https://rokid-ota.oss-cn-hangzhou.aliyuncs.com/toB/Document/CXR-L/1.1.2/CxrlSample.zip>。
