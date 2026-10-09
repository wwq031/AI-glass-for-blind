# MVP-01 / 操作与手机构建基线（2026-10-09）

## 目的与证据边界

本文对应 GitHub Issue [#19：核对设备、官方 App、ADB 与 Android 工具链基线](https://github.com/wwq031/AI-glass-for-blind/issues/19)，记录本次可复核的主机环境、仓库手机配置和本地依赖状态。

本文是**静态与本机配置基线**，不是设备连接、APK 安装、CXR 通信或完整黄金流程的验收报告。未在本文中出现的设备型号、固件、官方 App 版本、序列号、Token、Key 和日志，均保持为待核对或本地私有资料。

源码参考：

- GitHub `main` 当前已合入的 Agent Harness P0 提交：`b58c0d1d88344f7abae3b00f39fba9bf501dfa18`。
- 本交接分支：`agent-harness-p0`，提交 `7a5707e865cf321f17d7845c970955dfc6e6f25b`。

## 1. 当前主机环境

采集日期：2026-10-09（Asia/Shanghai）。

| 项目 | 当前观测 | 说明 |
| --- | --- | --- |
| 操作系统 | Windows NT 10.0.26200.0 | 本机系统版本字符串 |
| Node.js | v24.15.0 | 与仓库 `package.json` 的开发依赖兼容性仍需按项目命令复核 |
| pnpm | 11.25.0 | 仓库声明 `packageManager: pnpm@12.4.2`，当前存在版本偏差 |
| JAVA_HOME | `C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\` | 当前采用 JDK 17 |
| Java | OpenJDK 17.0.19 LTS | 与 Kotlin/Java 17 编译目标一致 |
| Android SDK | `C:\Users\Administrator\AppData\Local\Android\Sdk` 存在 | SDK 目录已发现，具体组件版本尚未逐项记录 |
| platform-tools | SDK 下的 `platform-tools` 目录存在 | `adb` 当前不在 PATH，需另行确认直接调用是否可用 |
| Gradle | PATH 中未发现 `gradle`/`gradlew` | 仓库没有提交 Gradle Wrapper |
| sdkmanager | PATH 中未发现 | 不能仅凭 PATH 结论判定 SDK 组件缺失 |

## 2. 手机端正式模块配置

来源：`apps/phone-companion/android/`。

- 应用 ID：`com.leqi.experiment.phonebt`。
- namespace：`com.leqi.experiment.phonebt`。
- `minSdk = 31`，`targetSdk = 35`，compile SDK 固定为 Android 36.1。
- Java 源码/目标版本为 17；Kotlin JVM target 显式对齐为 17。
- 当前构建只包含 `arm64-v8a`，原因是本轮联调手机为 arm64；其他 ABI 需要单独调整并重新验证。
- 高德依赖固定为 `navi-3dmap-location-search:11.3.100_3dmap11.3.100_loc11.3.000_sea9.8.1`，不使用动态版本。
- LiteRT-LM 固定为 `0.17.1`。
- 离线 TTS 依赖为本地 `libs/sherpa-onnx-1.13.8.aar`；该 AAR 不是业务源码，不能从 Git 忽略规则中推断为可公开上传的依赖。
- 高德 Android Key 通过 `local.properties` 的 `AMAP_ANDROID_KEY` 注入 Manifest；Key 值、签名 SHA-1 和 `local.properties` 不提交。
- Android Manifest 声明了蓝牙、粗/精定位、网络、网络状态、唤醒锁、通知和前台服务权限；服务类型为 `connectedDevice|location`。
- `AgentHostService` 承载 Agent WebView、本地模型、离线语音、导航和眼镜传输；界面销毁不应成为这些运行层的生命周期依据。

## 3. 双端实验与手机探针配置

来源：`apps/device-experiment/android/`。

- 工程名为 `LeqiDeviceExperiment`，当前 settings 文件配置 `:phone-bt` 与 `:glasses-bt` 两个正式实验模块。
- 手机端实验模块 `phone-bt`：`minSdk = 28`、`targetSdk = 35`，依赖 Rokid CXR-M `1.2.2`。
- 手机端 CXR-L 探针 `phone-l`：`minSdk = 31`、`targetSdk = 35`，依赖 Rokid CXR-L `1.0.1`。
- 眼镜端实验模块使用 `minSdk = 32`、`targetSdk = 32`，依赖 Rokid CXR Service Bridge `1.4`。
- Rokid Maven 仓库只在 Android 工程的依赖解析配置中声明；实际下载、授权与设备访问仍需独立验证。
- CXR 授权文件、客户端密钥、APK、模型、原始照片/录音、设备 dump 和未脱敏日志不进入 Git。

## 4. 当前本地私有项

以下项目在本次本地工作树中存在或由构建配置引用，但不上传其内容：

- `apps/device-experiment/android/local.properties`：本地 SDK 路径和可能的本机 Key 配置。
- `apps/phone-companion/android/libs/sherpa-onnx-1.13.8.aar`：本地构建依赖。
- `.env`：本地环境变量文件。
- CXR 授权材料、模型与 TTS 目录：只允许放在应用私有目录或本机受控目录。

本文件只记录“存在/需要提供”的状态，不记录上述文件的值、路径细节之外的个人或设备标识，也不把它们加入提交。

## 5. 当前未确认项与下一步

- 眼镜准确型号、固件、系统/API 版本和官方 App 包名/版本仍需在设备与官方 App 内记录。
- 手机准确型号、Android/厂商系统版本、USB 调试授权、蓝牙与定位权限状态仍需现场核对。
- `adb version`、`adb devices -l` 以及脱敏后的 `getprop` 结果尚未作为本次提交的一部分上传。
- Android SDK 组件、Gradle 8.13、Android Studio JDK 兼容组合需要在当前机器上逐项复核；不能把“目录存在”当成“工具链可构建”。
- CXR-L 授权、Session、拍照、Mock AI 和眼镜显示的完整链路未因本文件而变为已验收。

## 6. 安全与上传规则

- 只提交可复现的配置约束、版本号、模块入口和脱敏状态。
- 不提交 Token、API Key、密钥、序列号、账号、精确设备标识、原始媒体和未脱敏日志。
- 本基线不能替代物理验收；任何“已连接”“已安装”“已通信”结论都必须附对应设备证据。
