# v0.4.1 图片理解修复与真机复测

## 修复内容

小米 15 Pro 报告中的三张照片都已正常到达手机、通过 WebP 解码并转为 JPEG。故障发生在 Gemma 推理，而非眼镜相机。

旧版 v0.3.0 / v0.4.0 误选纯文本 GPU 模型，文件缺少视觉编码器。桌面使用相同旧权重和 LiteRT-LM 0.17.1 已复现 `TF_LITE_VISION_ENCODER not found in the model`。新版换成完整官方多模态权重，并在启动时验证真实视觉资源后才显示就绪。

- 模型：`gemma-4-E2B-it.litertlm`，原始大小 2,588,147,712 字节。
- 官方 revision：`b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1`。
- SHA-256：`181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`。
- SenseVoice 和 MeloTTS 权重仍全部内置。
- 错误日志增加 `failure_code`，可区分视觉初始化、视觉生成或输出格式失败。

## 下载与安装

[发布页：cxrl-speech-v0.4.1](https://github.com/wwq031/AI-glass-for-blind/releases/tag/cxrl-speech-v0.4.1)

完整 APK 大于 GitHub 单附件上限，所以下载时分成两片；合并后仍是一个包含所有权重的 APK，手机不用另行导入模型。

1. 在 Windows 电脑从发布页下载以下四个文件，放在**同一个文件夹**：
   - `phone-l-speech-v0.4.1.apk.part01`
   - `phone-l-speech-v0.4.1.apk.part02`
   - `manifest-v0.4.1.json`
   - `restore-cxrl-apk.ps1`
2. 在该文件夹打开 PowerShell，执行：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\restore-cxrl-apk.ps1
   ```

3. 等待脚本显示 `Verified APK ready`。它会检查分片和最终文件 SHA-256；不需要管理员权限，也不修改系统的执行策略。
4. 将生成的 `phone-l-speech-v0.4.1.apk` 通过 USB 复制到手机，点击覆盖安装。不要安装或改名 `.part` 文件。
5. 建议手机预留至少 12 GB 空间；电脑也需容纳分片与合并后的 APK 两份。安装后旧模型缓存可能仍占空间，请先保留已有测试记录。
6. 打开应用，核对版本 `0.4.1`，等待 Gemma 视觉自检及语音模型就绪。首次新权重准备与 GPU 初始化可能比旧版耗时更长。

若分片校验失败，重新下载提示的那一片；若覆盖安装失败，截图系统提示，不要先卸载丢失已有材料。

## 本次优先复测

1. **启动**：确认两类模型就绪，尤其不能停在 `vision_initialization` 失败。
2. **第一张图片**：在官方 App 确认眼镜连接，回本应用授权并创建 Session，Started 后打开拍照确认窗口，再点确认拍照。
3. **结果一致性**：手机出现现场照片；眼镜由 CXR-PROBE 更新为 `Gemma：<实际描述> #<代号>`，代号等于本次 `request=` 的前 8 位。实际看见后再点人工确认。
4. **连续三张**：分别拍杯子、书、水瓶等明显不同物体，核对旧 Session 回收、新的拍照确认、照片与描述没有串轮。
5. **语音回归**：测试离线播报、一轮手机语音问答、朗读最近一次图片结果，实际听见后再点人工确认。
6. **取消与恢复**：图片处理中取消或进入后台，不发送旧结果；重新授权、连接后，新拍照可以完成。
7. **交付证据**：每组结束后导出 JSON，并附版本与就绪截图、三轮 request/照片/描述截图、佩戴者看见的眼镜原文，以及失败时的完整最后几行日志。导出会触发后台取消，请先完成人工确认。

详细操作与结果模板继续使用 [测试交付说明](2026-10-15-cxrl-speech-v0.4.0-test-guide.md)，安装包以本页新版为准。

## 验证范围

修复已通过 Android 回归测试和 lint；桌面真实 GPU 测试复现旧权重失败，并以新权重对公开苹果图片连续完成三次中文 JSON 图片理解。APK 发布前还需校验完整模型及全部语音文件、签名和分片恢复结果。以上不能代替小米 15 Pro 的 GPU、眼镜显示与手机声音真机复测。
