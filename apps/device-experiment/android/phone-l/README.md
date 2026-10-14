# CXR-L 独立诊断探针

对应 [#20](https://github.com/wwq031/AI-glass-for-blind/issues/20)。在现有模块恢复 `PhoneLActivity`，只检查官方 App 安装/兼容性、打开官方 App；不初始化 Agent、模型、TTS 或地图。

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
