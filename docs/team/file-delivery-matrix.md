# 文件级实现与交付矩阵

本文件把架构落实到“哪个文件负责什么、由谁实现、怎样验收”。它是组员领取任务和提交合并请求时的执行清单。

以下路径是建议的源码布局；当前仓库先提供文档和合同，`src/` 文件由组员按选择的 TypeScript、Kotlin 或其他实现语言创建。文件名可以调整，但职责和接口不能绕开。

## 总体调用链

```text
语音输入 / 眼镜按键 / 摄像头
  → DeviceTransport
  → SessionOrchestrator
  → NavigationProvider / ObservationProvider
  → DomainEvent / DomainEffect
  → SpeechOutput
  → 眼镜播报
```

协议负责人实现“设备事件如何进来、命令如何发出去”；导航和识图负责人实现“事实如何产生”；系统集成负责人实现“何时调用、如何排队、如何降级”。

语音是所有语义交互的默认入口。实体键不替代语音输入，只负责唤起、拍摄确认、重拍、暂停/打断和紧急取消。

## 负责人 A：协议与设备传输

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/providers/transport/device-transport.ts` | 设备通信抽象 | `connect`、`disconnect`、`sendCommand`、`sendBinary`、`subscribe` 接口实现 |
| `packages/providers/transport/simulator-transport.ts` | 无真机时的可重复替身 | 可注入按键、拍摄、连接断开和播报确认事件 |
| `apps/phone-companion/src/transport/rokid-cxr-adapter.ts` | Rokid CXR 适配 | 把 CXR 连接、认证、请求、响应映射为统一接口；不泄漏 CXR 类型 |
| `apps/glasses-agent/src/input/button-handler.ts` | JSUI 实体键映射 | 短按、长按、重复按的语义和去抖 |
| `apps/glasses-agent/src/capture/capture-controller.ts` | 用户确认后的拍摄 | 只响应合法的观察请求，不自行启动连续拍摄 |
| `apps/glasses-agent/src/audio/speech-bridge.ts` | 眼镜端音频反馈 | 接收统一 `SpeechEffect` 并播报，返回成功/失败事件 |
| `tools/protocol-inspector/` | 协议研究和回放 | 连接日志、脱敏载荷、重放标记和故障记录 |

验收标准：真实 CXR 未完成时，`SimulatorTransport` 也能让黄金场景运行；连接断开可产生统一事件；业务代码不出现临时蓝牙调用；不提交令牌、原始图像和密钥。

## 负责人 B：导航

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/providers/navigation/navigation-provider.ts` | 地图能力抽象 | 目的地确认、启动、停止、当前状态、导航事件订阅 |
| `apps/phone-companion/src/navigation/map-adapter.ts` | 地图 SDK 适配 | POI 搜索、候选确认、路线启动、偏航重规划、到达事件 |
| `apps/phone-companion/src/navigation/destination-service.ts` | 语音目的地服务 | 将 `SpeechInput` 转为 POI 搜索、候选播报和语音确认 |
| `apps/phone-companion/src/navigation/location-service.ts` | 定位输入 | 位置更新、定位质量、权限失败和暂时失联状态 |
| `packages/domain/navigation-reminder-policy.ts` | 确定性提醒策略 | 接近转向、偏航、重规划、到达等事件生成 `SpeechEffect` |
| `packages/testkit/fake-navigation-provider.ts` | 导航模拟 | 可按脚本产生开始、转向、偏航、到达和 GPS 弱事件 |

验收标准：用户只通过语音即可完成目的地输入和候选确认，不需要手机屏幕；地图适配器只产生导航事实，不直接播放音频、不调用视觉模型；定位或地图不可用时有明确降级语音。

## 负责人 C：基础识图与 OCR

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/providers/vision/observation-provider.ts` | 视觉能力抽象 | 统一接收 `ObservationRequest`，返回 `ObservationResult` |
| `apps/gateway/src/vision/vision-router.ts` | 视觉任务路由 | 按 `scene`、`entrance`、`menu`、`expression` 选择模型或 OCR |
| `apps/gateway/src/vision/ocr-adapter.ts` | 菜单文字识别 | 输出菜名、价格、限制条件和识别置信度 |
| `apps/gateway/src/vision/scene-adapter.ts` | 场景/入口识别 | 输出招牌、入口方向、可见风险和未知项 |
| `apps/gateway/src/vision/expression-adapter.ts` | 可见表情辅助 | 仅处理用户明确请求的单帧观察，禁止身份识别和真实情绪推断 |
| `packages/testkit/observation-fixtures/` | 视觉测试夹具 | 脱敏图片、期望结构、低置信度和超时样例 |

验收标准：四类任务都返回合同规定的摘要、置信度、重拍建议和限制；低置信度不会伪装成确定事实；模型超时、图像模糊和服务不可用都可被上层处理。

## 负责人 D：系统集成与会话编排

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/domain/session-state.ts` | 会话状态 | `idle`、导航、入口观察、菜单阅读、追问和完成状态 |
| `packages/domain/session-orchestrator.ts` | 唯一业务入口 | 将设备、导航和识图事件转换为领域效果；不依赖具体 SDK |
| `packages/domain/speech-priority-policy.ts` | 播报仲裁 | 导航、风险、识图、用户追问按优先级排队和打断 |
| `packages/providers/speech/speech-input.ts` | 语音输入抽象 | 统一手机/眼镜麦克风、ASR 结果和意图提示 |
| `apps/phone-companion/src/speech/speech-input-adapter.ts` | ASR 适配 | 将平台语音识别结果转换为 `SpeechInput`，处理超时、低置信度和取消 |
| `apps/phone-companion/src/session/session-runtime.ts` | 运行时组装 | 注入真实或模拟适配器，维护 `session_id` 和 `sequence` |
| `tests/scenarios/golden-path-navigation-restaurant.test.*` | 端到端回放 | 覆盖导航、入口、菜单、追问、表情辅助和故障降级 |

验收标准：完整流程无需手机屏幕；目的地、确认、菜单追问和表情请求均可用语音完成；系统提醒不会自动触发拍摄；导航播报和识图播报不会互相覆盖；所有事件可以按 `session_id + sequence` 回放。

## 共享合同和测试文件

| 文件 | 作用 | 变更规则 |
|---|---|---|
| `packages/contracts/schemas/*.schema.json` | 跨端数据格式 | 先改 Schema，再改实现；破坏性变更提升主版本 |
| `packages/contracts/examples/` | P0 正常/失败样例 | 作为 Fake、回放和合同测试的共同夹具 |
| `packages/contracts/interfaces.md` | 跨模块接口形状 | 不把供应商 SDK 类型写入接口 |
| `packages/testkit/` | Fake、fixture、回放工具 | 每个外部依赖都要有替身 |
| `tests/contracts/` | Schema 和兼容性检查 | 每次合同变更必须运行 |
| `tests/scenarios/` | 场景验收和回归 | 以黄金场景为最小端到端标准 |

## 通用完成定义（Definition of Done）

每个模块合并前必须满足：

1. README 或模块文档写清输入、输出、错误和本地验证方式。
2. 至少有一个模拟实现或离线夹具，不依赖真机、地图网络或云模型才能运行单元测试。
3. 跨模块数据通过 `packages/contracts`，不直接传递供应商对象。
4. 覆盖一个正常路径和一个失败路径，并能看到 `session_id`、事件序号和错误原因。
5. 不提交账号令牌、原始设备 dump、APK、用户图像或音频。

P0 合同闭合标准：设备命令/事件、媒体引用、语音输入、目的地查询/候选、导航路口事件、观察结果、路口辅助建议、会话快照和统一错误均有 Schema，并至少有一条正常样例和一条失败样例。

## 依赖顺序

```text
合同 Schema
  → FakeTransport / FakeNavigation / ObservationFixture
  → 领域状态机与策略
  → 各真实 Provider 适配器
  → 手机运行时组装
  → 真机黄金场景
```

任何真实 SDK 或 CXR 研究受阻时，不得阻塞其他负责人；先用替身完成合同、状态机和场景回放。
