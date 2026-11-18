# 乐奇 AI 眼镜

## 2026-11-18 纯云端演示版交接

手机端 0.8.3 搭配眼镜端 0.5.2。接手导航中断、原生助手抢占或菜单错读，请从[本轮测试与问题交接](docs/handoff/2026-11-18-deepseek-demo.md)开始。里面有真机反馈、双端版本指纹、回归结果、菜单对照实验和验收步骤。

## 2026-09-30 双端 APK 交接快照

当前安装包、构建工具、源码接线与实机已知问题见[组员交接说明](docs/handoff/2026-09-30-device-apk-handoff.md)。此版本未通过完整实机验收；APK 下载见项目 Releases 中的 `device-handoff-20260930`。

面向盲人及低视力用户的多端助盲辅助设备项目。项目以 Rokid 眼镜作为第一视角输入和语音输出终端，以手机作为无屏交互、定位、地图导航和会话协调中心，以可替换的视觉模型和 Agent 完成环境、入口、菜单及用户主动请求的表情辅助。

当前仓库是“可并行实现的工程骨架”，不是已经完成的产品实现。第一阶段先固定协议、状态机、场景和模块接缝，再由组员分别实现眼镜端、手机端、CXR 适配器、导航和视觉模型。

## RGR06 戒指连接不上时

适用于戒指有电、指示灯闪烁，但 Rokid App 搜不到 `RGR06` 或无法连接的情况。

1. 确认手机 Rokid App 已连接眼镜，进入“设置 → 蓝牙外设”（部分版本称“外设连接”）。
2. **同时按住戒指滚轮两侧的左键和右键约 3 秒，直到指示灯快闪后松开。** 此操作会断开戒指连接并重新进入配对流程。
3. 等待外设列表出现 `RGR06`，点击它完成连接。

单独长按滚轮约 2 秒用于开机并使蓝灯快闪；连接异常时使用上述左右键组合。操作依据：[RGR06 产品说明书图片（销售商展示）](https://img.pchome.com.tw/cs/items/DSBE20A900K51K1/i010007_1782185060.jpg)。

**2026-11-12 实测：** 戒指有电、蓝灯闪烁，重启眼镜后仍搜不到；同时长按左右键后重新连接成功。ADB 随后确认眼镜重新出现 `RGR06`、`RGR06 Keyboard`、`RGR06 Consumer Control` 三个输入设备。按键与跨端诊断结果见 [RGR06 实测记录](rgr06-adb-events.md)。

## 手机独立运行与 DeepSeek 对话

当前分支的 `phone-l` 使用纯云端模式。在手机的「对话模型与费用设置」中填写 DeepSeek API Key，再点「授权并连接眼镜」。手机直接通过网络调用 DeepSeek，默认对话模型为 `deepseek-flash`，也可选择 `deepseek-v4-pro`。云服务按用量计费。[DeepSeek 官方价格](https://api-docs.deepseek.com/quick_start/pricing/)

语音识别、对话、图片识别、OCR 和语音合成使用 DeepSeek。录音、确认拍摄的照片、对话上下文和播报文字按对应功能发送。手机用 Android Keystore 保护凭据，可在设置页清除。每条回答整段合成，眼镜播完后回传完成状态。合成失败时，配套眼镜端用提示音提醒，并显示原因。

步行导航使用高德 Android SDK。首次使用时在「高德导航设置」中授予定位权限，可以用地点名称自检。自检页显示本机 APK 的包名和签名 SHA-1。新旧包可在被 Git 忽略的根目录 `.env` 中分别配置 `AMAP_ANDROID_KEY_PHONEL` 和 `AMAP_ANDROID_KEY_PHONEBT`；只构建一个包时也可沿用 `AMAP_ANDROID_KEY`。配置后重新打包。高德地图客户端可通过自检页单独打开搜索，眼镜内导航以 SDK 搜索、用户确认和算路成功为准。

## 第一条完整流程

```text
用户短按/长按实体键
  → 语音输入目的地
  → 音频确认餐厅 POI
  → 手机地图导航
  → 系统语音提醒接近目的地
  → 用户按键观察入口
  → 进入餐厅
  → 用户按键拍摄菜单
  → 菜单识别与语音追问
  → 用户明确请求后观察对话人物可见表情
```

手机屏幕不是必经交互。手机界面只用于首次配置、家属协助和故障排查。

## 总体架构

```text
┌──────────────────┐
│  glasses-agent   │  JSUI：实体键、摄像、设备反馈、播报
└────────┬─────────┘
         │ DeviceTransport
┌────────▼─────────┐
│ phone-companion  │  语音、地图、定位、提醒、会话
└────────┬─────────┘
         │ contracts
┌────────▼─────────┐
│ domain + providers│  状态机、策略、模型/地图适配器
└────────┬─────────┘
         │ optional gateway
┌────────▼─────────┐
│ Coze / VLM / OCR  │
└──────────────────┘
```

眼镜 JSUI、Rokid CXR、地图供应商和视觉模型都位于可替换接缝之后。核心会话不直接导入 Android、JSUI、CXR 或供应商 SDK。

## 仓库导航

- `apps/`：可运行端的目录和实现入口。
- `packages/contracts/`：跨端事件、命令和结果的唯一事实来源。
- `packages/domain/`：设备无关的会话状态机、核心 Agent 和策略。
- `packages/providers/`：地图、视觉、OCR、播报等适配器接口。
- `research/`：ADB、JSUI 和手机 APK 逆向证据，不作为生产源码。
- `docs/`：需求、架构、协议、场景和团队协作约定。
- `tests/`：合同、状态机、场景回放和真机测试入口。

## 当前边界

第一阶段不承诺连续视频安全控制、完全自主导航、实时高可靠避障、红绿灯通行决策、身份识别或真实情绪判断。系统提醒可以自动播报，但摄像观察仍由用户按键确认；主动观察策略作为后续能力接入。

## 开始贡献

先阅读 [`CONTEXT.md`](CONTEXT.md)、[`docs/architecture/system-architecture.md`](docs/architecture/system-architecture.md)、[`docs/architecture/core-agent-design.md`](docs/architecture/core-agent-design.md)、[`docs/architecture/tool-system.md`](docs/architecture/tool-system.md)、[`packages/contracts/interfaces.md`](packages/contracts/interfaces.md)、[`docs/team/file-delivery-matrix.md`](docs/team/file-delivery-matrix.md) 和 [`docs/scenarios/golden-path-navigation-restaurant.md`](docs/scenarios/golden-path-navigation-restaurant.md)。实现前先遵守合同文件，不把具体 SDK 类型泄漏到 `packages/domain`。
