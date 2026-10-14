# CXR-L 独立诊断探针

对应 [#20](https://github.com/wwq031/AI-glass-for-blind/issues/20) 和 [#21](https://github.com/wwq031/AI-glass-for-blind/issues/21)。在现有模块恢复 `PhoneLActivity`，检查官方 App 安装/兼容性，提供授权与 CUSTOM_VIEW Session 诊断；不初始化 Agent、模型、TTS 或地图。

## SDK 来源与版本

显式从仓库残留的 `client-l:1.0.1` 迁移到 `1.1.2`，依据 [Rokid 官方 CxrlSample.zip](https://rokid-ota.oss-cn-hangzhou.aliyuncs.com/toB/Document/CXR-L/1.1.2/CxrlSample.zip) 的 `SetupViewModel.kt` 与 Manifest 核对安装、兼容检查 API 及三个官方 App 包名。原入口在可见 Git 历史与交接附件中均未找到，因此补最小入口，而非复制第二套业务应用。Sample ZIP SHA-256 见[离线来源记录](../../../../docs/handoff/2026-10-14-cxrl-offline-baseline.md)。

探针使用 `CxrSessionManager`，不混用历史 `CXRLink`；版本兼容以 SDK 的 `RokidAppStatus.Compatible` 为准，不硬编码旧版 App 阈值。Token、异常正文和设备编号均不展示或记录。

## 构建

使用 JDK 17/21，配置完整 Android SDK（platform 36.1、Build Tools 35.0.0）与 Gradle。在仓库根目录执行：

```powershell
$env:JAVA_HOME = '<JDK 17 或 21 路径>'
$env:ANDROID_HOME = '<Android SDK 路径>'
& '<Gradle 路径>\bin\gradle.bat' -p apps/device-experiment/android -PcxrlProbeOnly=true :phone-l:assembleDebug
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

#21 的离线代码完成不代表用户授权、Started 或可见显示通过；#21 仍开放。#22–#25 依赖实际 Session/图片/闭环/稳定性证据，当前没有这些前置验收结果，不以模拟状态替代。
