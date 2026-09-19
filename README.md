# 乐奇 AI 眼镜

面向盲人及低视力用户的多端助盲辅助设备项目。项目以 Rokid 眼镜作为第一视角输入和语音输出终端，以手机作为无屏交互、定位、地图导航和会话协调中心，以可替换的视觉模型和 Agent 完成环境、入口、菜单及用户主动请求的表情辅助。

当前仓库是“可并行实现的工程骨架”，不是已经完成的产品实现。第一阶段先固定协议、状态机、场景和模块接缝，再由组员分别实现眼镜端、手机端、CXR 适配器、导航和视觉模型。

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
- `packages/domain/`：设备无关的会话状态机和策略。
- `packages/providers/`：地图、视觉、OCR、播报等适配器接口。
- `research/`：ADB、JSUI 和手机 APK 逆向证据，不作为生产源码。
- `docs/`：需求、架构、协议、场景和团队协作约定。
- `tests/`：合同、状态机、场景回放和真机测试入口。

## 当前边界

第一阶段不承诺连续视频安全控制、完全自主导航、实时高可靠避障、红绿灯通行决策、身份识别或真实情绪判断。系统提醒可以自动播报，但摄像观察仍由用户按键确认；主动观察策略作为后续能力接入。

## 开始贡献

先阅读 [`CONTEXT.md`](CONTEXT.md)、[`docs/architecture/system-architecture.md`](docs/architecture/system-architecture.md)、[`packages/contracts/interfaces.md`](packages/contracts/interfaces.md)、[`docs/team/file-delivery-matrix.md`](docs/team/file-delivery-matrix.md) 和 [`docs/scenarios/golden-path-navigation-restaurant.md`](docs/scenarios/golden-path-navigation-restaurant.md)。实现前先遵守合同文件，不把具体 SDK 类型泄漏到 `packages/domain`。
