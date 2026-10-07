# 手机 APK 分析

原始材料位于开发机工作区的 `apk_unpacked.zip`，不复制进仓库。当前解包显示包名为 `com.rokid.sprite.aiapp`，Native 层包含 CXR bridge 和 socket protocol 实现。

本目录只保存可复核的符号、组件和协议推断，不保存 APK 或从中提取的完整二进制。

## 2026-09-29 再核对

直接列举 `apk_unpacked.zip` 的条目：存在 `libcxr-bridge-jni.so`、`libcxr-sock-proto-jni.so`，主 `classes.dex` 约 77 KB，并有 `assets/C1.enc`、`L1.enc`、`L2.enc`。未见独立命名的 `.lc` 授权文件。资源表中可见 CXR Link 的用户授权文案，但未见名为 `clientSecret` 或 `license` 的资源项。这些观察不能排除授权数据被加密打包、运行时生成或从服务端获取。

眼镜固件中的 `com.rokid.cxrservice` 是系统 UID 的 CXR 服务；本机 APK 文件未包含单独的 `.lc` 资源。安装在眼镜上的 AIX/Ink 样例是 JSUI 页面与按键资料，不是手机 CXR-M 的授权材料。

官方 Maven 的 `client-m:1.2.2` 在手机端 `connectBluetooth` 要求传入授权字节和 clientSecret；其手机侧字节码含 SN 解密校验和 `SN_CHECK_FAILED` 分支。我们的实机诊断用空授权材料收到该错误，同时眼镜短暂报告连接，表明失败发生在手机 SDK 的授权校验阶段。不能从官方应用的解包资料推断我们的 APK 已获得授权。
