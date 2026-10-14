# CXR-L 离线基线与模拟验证（2026-10-14）

对应 [#19](https://github.com/wwq031/AI-glass-for-blind/issues/19)，后续依赖为 [#20](https://github.com/wwq031/AI-glass-for-blind/issues/20)、[#21](https://github.com/wwq031/AI-glass-for-blind/issues/21)。本轮无开放的重复 PR；#19 已分配给 swimming001。用户明确说明设备暂时不在，先做模拟。

**状态：完成本轮可执行的只读环境核验和已有软件测试；#19 整体验收未完成。** 未修改业务代码、SDK 依赖或通信路线，未执行真机授权、安装、连接、拍照或显示。下文的历史记录、用户截图、当前命令结果和官方规范分别注明来源。

## 设备与官方 App

| 项目 | 已知事实 | 来源与限制 |
| --- | --- | --- |
| 眼镜型号 | RV101，中国大陆版 | 用户本轮提供的法规信息、眼镜设置截图 |
| 眼镜固件 | YodaOS-Sprite，1.25.015-20260903-150201 | 同上；不是本轮 ADB 读数 |
| 眼镜 Android | RG_glasses / Android 12 | 2026-09-29 仓库历史记录；当前 API 等待设备复测 |
| 手机 | 荣耀 BVL-AN16 / Android 16 | `docs/superpowers/specs/2026-09-29-device-navigation-loop-design.md`；厂商系统版本尚无当前证据 |
| 手机官方 App | 包名、versionName、versionCode、地区版本待实机确认 | 截图没有展示荣耀手机 App 版本，不能从截图状态栏推断开发手机型号 |
| 官方 App 连接、拍照 | 本轮未执行 | 用户说明设备暂时不在 |
| 当前 ADB 设备 | 空列表 | 本轮 `adb devices -l` 实际结果 |

截图中的序列号、MAC、账号等不写入报告。

## 本机工具链

| 工具 | 当前结果 | 位置 / 说明 |
| --- | --- | --- |
| Node | v26.2.0 | `E:\npm\node.exe` |
| pnpm | 12.4.2 | `C:\Users\XWM\AppData\Roaming\npm\pnpm.ps1`；普通沙箱曾拒绝路径访问，授权执行后版本检查成功 |
| 默认 Java | JDK 25.0.2 | `D:\soft\OracleJdk\jdk25\jdk-25.0.2`；未修改系统 JAVA_HOME |
| 可用 JDK 21 | JBR 21.0.10，已运行 `java -version` | `C:\Program Files\JetBrains\IntelliJ IDEA 2025.3.3\jbr` |
| ADB | 1.0.41 / 36.0.0-13206524，版本及设备枚举成功 | `E:\my_download\android\MuMuPlayer\nx_main\adb.exe`；这是 MuMu 附带工具，不代表完整 Android SDK 已就绪 |
| Gradle 8.9 | 使用上述 JDK 21，`--version` 成功 | `%USERPROFILE%\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat` |
| Gradle 9.2.0 | 使用上述 JDK 21，`--version` 成功 | `%USERPROFILE%\.gradle\wrapper\dists\gradle-9.2.0-bin\11i5gvueggl8a5cioxuftxrik\gradle-9.2.0\bin\gradle.bat` |
| Gradle 8.13 | 尚未定位 | 交接记录使用该版本；本轮没有下载或安装 |
| Android SDK / Android Studio | 在本轮搜索范围未定位完整 SDK、sdkmanager 或 studio64.exe | 检查了 D/E 盘可访问目录、Program Files、用户 AppData/Local 和 Gradle 缓存；不等于所有目录均不存在安装 |
| 本项目 SDK 配置 | 无 Gradle Wrapper、无 local.properties；未发现 CXR-L AAR 缓存 | 依赖解析及 Android 构建未执行 |
| 完整手机模块的 TTS AAR | 初查缺少，后续已从上游 Release 恢复并校验，见文末补充 | 该资源属于 phone-bt，不能要求 phone-l 的独立诊断依赖它 |

当前根工程声明 AGP 8.13.2、Kotlin 插件 2.4.20，手机工程 compileSdk 36.1、Java/Kotlin 目标 17。Gradle 命令能启动不代表这些项目插件和 Android 平台已可用。

[AGP 8.13 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)列出 Gradle 最低 8.13、JDK 最低 17、最大 Android API 36.1。因此本机 Gradle 8.9 不满足当前工程要求。[Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)表明 Java 21 可运行 Gradle 8.5+，Java 25 需要 Gradle 9.1.0+。后续优先用 JDK 21 配置交接版本 Gradle 8.13；不因发现 9.2.0 就假定整个工程与它兼容。Kotlin 插件与 AGP 的实际解析、编译兼容性仍需构建确认。

## 探针源码与官方来源

当前 `apps/device-experiment/android/settings.gradle` 只纳入 `:phone-bt` 和 `:glasses-bt`。`phone-l` 仅有 `build.gradle`、`AndroidManifest.xml`，依赖 `client-l:1.0.1`，Manifest 引用的 `PhoneLActivity` 缺失。

本轮检查本地全部 Git 引用中的 `PhoneLActivity.kt` 路径历史，未找到实现；交接提交 `b755548` 的该模块也仅有上述两个文件。此结论限于当前可见 Git 历史，不代表外部交接附件一定不存在源码。

从 [Rokid 官方 SDK 页](https://developerdoc.rokid.com/sdk?lang=cn)的文档入口，已定位并读取 [CXR-L 1.1.2 正式文档](https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/cn/fa92e9303bc848e6b4a20c062f2c792b.html)。官方页面通过公开内容接口加载章节，不以社区转录替代原文。

已下载并解压官方 [CxrlSample.zip](https://rokid-ota.oss-cn-hangzhou.aliyuncs.com/toB/Document/CXR-L/1.1.2/CxrlSample.zip)，仅用于源码核对，没有运行或构建 Sample。

- ZIP SHA-256：`B0F615F8526C66E5E962ED317AA3585B724B9FADA15438B271F0E5326B7E6EFB`。
- 本机临时位置：`%LOCALAPPDATA%\Temp\leqi-cxrl-112-reference\CxrlSample`；不依赖该临时目录作为长期构建输入。
- `app/build.gradle.kts` 实际声明 `client-l:1.1.2`，compileSdk 35、minSdk 28、targetSdk 34、Java/Kotlin 目标 17。Sample 自身 versionName 为 1.1.0，不能把它误作 SDK 版本。
- `viewmodel/SetupViewModel.kt` 实际调用 `isRokidAppInstalled`、`checkRokidAppCompatibility`、`requestAuthorization`、`parseAuthorizationResult`，授权 token 保存在内存中。
- 该新版本 Sample 是 #20 的候选参考，**不等于已经获得仓库 1.0.1 的匹配 Sample**。正式实现前须明确保留旧版本还是独立迁移，不能仅改 Maven 版本号套用旧 API。

## 会影响后续 Issue 的官方规范

1. CXR-L 的链路是手机应用 → CXR-L → Rokid AI App → 眼镜。眼镜须先在官方 App 内连接；当前 RV101 固件与实际手机 App 的组合尚未通过本轮实测。
2. [1.1.2 简介](https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/cn/fa92e9303bc848e6b4a20c062f2c792b.html?documentId=dc5233055b774e898c2bdc228bcfe904)说明官方 App 兼容检查阈值为 1.11.0，并指出 SDK 内部 1.9.0 显示字符串瑕疵。实现应使用 `checkRokidAppCompatibility` 结果，不能照搬旧版本的 1.7.14 要求。
3. [连接与会话](https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/cn/fa92e9303bc848e6b4a20c062f2c792b.html?documentId=c457f9cdf8f44acebf5044b0d7e57087)使用 `CxrSessionManager.create(config)` → 注册回调 → `session.connect(token)`；能力调用等待 `onSessionStarted()`。这与仓库旧记录中的 `CXRLink` 不可混用。
4. CUSTOM_VIEW 不需要自有眼镜 APK，可用于初始文字显示；自定义指令属于 CUSTOM_APP，需要对应眼镜应用。SDK 的 Started/Paused 等状态与仓库业务 session_id 是两个层次。
5. 官方 1.1.2 简介规定拍照返回 **WebP**。#22 当前写有 JPEG 验收；如采用 1.1.2，必须明确区分原始 WebP 与手机转换的 JPEG，不能直接复用 RFCOMM 的 JPEG 文件头判断并据此报告 CXR-L 失败。
6. 历史授权成功、`connect(token)=true`、随后 `binder=null` 的记录仍是未解释现象。本轮没有复现，不能宣称升级版本必然修复，也不能把 CXR-M 的授权失败解释为 CXR-L 的根因。

## 本轮验证

源码基线 `32b683d948ffae75616aa3b0f9690a6a25004b86`，工作区已有用户对 `tests/scenarios/mobile-agent-bundle.test.ts` 的未提交修改。本轮测试包含该修改；不能将结果宣称为纯净 main 的测试结果。本轮没有修改该文件。

| 分级 | 操作 | 实际结果 |
| --- | --- | --- |
| static | `node node_modules/typescript/bin/tsc --noEmit` | 通过 |
| static | `node tools/validate-contracts.mjs` | 33 schemas、10 examples、5 capabilities、8 skills、7 tools，通过 |
| mock / static | `node --test tests/**/*.test.ts` | 183/183，通过；0 失败、0 跳过；含 bundle 回放、模拟传输及源码结构检查 |
| host | JDK 21、ADB、Gradle 8.9/9.2.0 版本检查 | 通过；ADB 设备列表为空 |
| build | phone-l APK、官方 Sample APK | 未执行：缺完整 Android 构建环境；当前 phone-l 也缺入口源码与模块接线 |
| physical | 官方 App 连接/拍照、授权、SDK Session、眼镜显示 | 未执行：设备暂时不在 |
| full-demo | 授权 → 真实照片 → Mock → 眼镜显示 | 未执行 |

模拟测试验证的是仓库软件的消息合同、确认约束、取消、断连和旧结果拒收。它没有执行 Android Binder、Rokid AI App 或 CXR-L 原生 SDK，不能证明它们可连接。没有生成模拟 token 或伪造设备成功日志。

## 交接到下一步

- #19 保留未完成：设备回到手边后，补手机厂商系统/API、眼镜 API、官方 App 包名与版本，以及官方连接/拍照结果。
- #20 的离线准备已获得 JDK 21、ADB 及官方 Sample 来源；仍需配置完整 Android SDK 与匹配 Gradle，并确认 SDK 版本策略后，在现有 phone-l 中恢复入口。不要将官方 Sample 整体复制为第二套业务应用。
- #21 的模拟状态和真机状态必须分别标记；创建会话、请求被接受和真正 Started 不可合并为一个“连接成功”。
- 此处记录的是最初调查阶段；发布进展见文末补充。本报告不声称上述 Issue 已解决。

## 上游分支复核补充（同日）

用户提示检查其他分支后执行 `git ls-remote --heads origin` 和 `git fetch origin`。上游当前有 `main`、`agent-harness-p0`、`feat/local-tesseract-ocr` 三个分支；本地其他 `origin/*` 引用不代表远端分支仍然存在。

- `origin/main` 已更新至 `ce1aa82`（PR #27），`origin/agent-harness-p0` 已更新至 `0440d21`。此前调查使用的远端引用较旧，漏读了新增的[2026-10-09 环境记录](https://github.com/wwq031/AI-glass-for-blind/blob/ce1aa82bab72e4fb73b98d37160daedd7ab6e468/docs/handoff/2026-10-09-mvp-01-ops-baseline.md)。本轮只 fetch，没有 merge 或 checkout，当前源码和用户修改保持原状。
- 新记录确实说明当时 `C:\Users\Administrator\AppData\Local\Android\Sdk` 存在，包含 Android 36.1、Build Tools 35.0.0/36.1.0/37.0.0。它记录的是另一个用户环境的本地安装路径，分支没有提交完整 Android SDK。
- 本轮在当前主机以授权的只读检查确认：上述 SDK 路径、`C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot`、`D:\leqi-device-experiment\gradle-dist\gradle-8.13\bin\gradle.bat` 均不存在。
- 新记录第 3 节将 CXR-M 的 `minSdk 28/client-m:1.2.2` 配置称为 `phone-bt`；当前源码实际属于历史 `phone` 模块。判断模块能力须继续以 Gradle 源文件为准。
- 已检查三个远端分支的文件树：没有完整 SDK、Gradle Wrapper 或 `PhoneLActivity.kt`。两个 Android 相关分支的 `phone-l` 仍只有配置与 Manifest；OCR 分支未提供这套 Android 工程。
- [交接 Release](https://github.com/wwq031/AI-glass-for-blind/releases/tag/device-handoff-20260930)确实提供 `sherpa-onnx-1.13.8.aar`、APK、源码 ZIP、清单及验证证据。完整 Android SDK 不在附件列表中。已下载源码 ZIP 并核对 SHA-256 `F3C971DF6A156478A6689FC8FED829D9EC823B2A3F8EEA2A02FEFF03A20340BB`；ZIP 内同样没有 `PhoneLActivity.kt`、`android.jar` 或完整 SDK。

因此，已找到上游 SDK 的安装记录和可恢复的 TTS 构建依赖来源；当前主机完整 Android SDK 的缺口仍存在。

已将 Release 的 `sherpa-onnx-1.13.8.aar` 下载并放回 `apps/phone-companion/android/libs/sherpa-onnx-1.13.8.aar`。文件大小 50,129,134 字节；实际 SHA-256 与 Release 清单一致：`633C24321E06B1FE79FEAFA03EA16CBC0F8A286641E2DA3559BAC91BDB13BD96`。`git check-ignore` 确认该二进制仍被忽略。此项补齐本地 TTS 编译依赖，不代表 Android SDK、模型权重或 APK 构建已完成。

## 逐项交付复核（同日）

用户要求逐项 commit、push、merge，并再次确认设备暂时不在，先完成可做的离线工作。

- 分支基线更新为 `origin/main` 的 `ce1aa82`。已有测试修改仍保留在工作区，不纳入本基线文档提交。
- 再次执行 `adb devices -l`，结果为空列表；当前主机两个历史 SDK 路径均不存在。
- 在更新后的代码及已有测试修改上执行类型检查、合同校验与完整 Node 测试：全部通过，183/183，无失败或跳过。
- 本文作为 #19 的离线成果发布；#19 保持开放，设备与官方 App 验收尚缺实际证据。
- #20 可以依据已核对的 1.1.2 官方 Sample 准备入口与构建；后续真机操作仍等待前置关口通过，不能用离线结果关闭 #19–#26。
