# 文件级实现与交付矩阵

本文件把架构落实到“哪个文件负责什么、由谁实现、怎样验收”。它是组员领取任务和提交合并请求时的执行清单。

以下路径兼有已存在的 TypeScript 文件和后续建议布局。表中“已有”只说明文件存在，不代表真实设备或 SDK 已集成；“规划”表示该路径尚不存在。`packages/domain/agent/`、对应的 `tests/domain/`、合同及离线回放已有实现；真实设备、定位、语音与视觉服务的端到端接入仍待验证。文件名可以调整，但职责和接口不能绕开。离线测试通过不等于真机可用。

## 总体调用链

```text
语音输入 / 眼镜按键 / 摄像头
  → DeviceTransport
  → Agent SessionOrchestrator.handle(AgentEvent)
  → LlmAgent 的 Plan → PlanValidator/Policy → ToolGateway/ProviderRouter 的 Result
  → Effect / followUpEvents（由调用方显式回放）
  → SpeechOutput
  → 眼镜播报
```

协议负责人实现“设备事件如何进来、命令如何发出去”；导航和识图负责人实现“事实如何产生”；Agent 核心负责人维护“如何规划、校验、执行和回放”；系统集成负责人负责适配器装配、会话序号、语音排队及降级。导航提醒不会自动授权拍摄；路口安全建议只能由策略根据有效视觉事实产生。

语音是所有语义交互的默认入口。实体键不替代语音输入，只负责唤起、拍摄确认、重拍、暂停/打断和紧急取消。

## 负责人 A：协议与设备传输

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/providers/device/device-transport.ts`（已有） | 设备通信抽象 | 保持 `connect`、`disconnect`、`sendCommand`、`sendBinary`、`subscribe` 的统一合同 |
| `packages/testkit/simulator-transport.ts`（已有） | 无真机时的可重复替身 | 可注入按键、拍摄、连接断开和播报确认事件 |
| `apps/phone-companion/src/transport/rokid-cxr-adapter.ts`（规划） | Rokid CXR 适配 | 把 CXR 连接、认证、请求、响应映射为统一接口；不泄漏 CXR 类型 |
| `apps/glasses-agent/src/device-event-mapper.ts`（已有） | 设备事件映射 | 将已取得的设备输入归一化；不代表按键协议已接通 |
| `apps/glasses-agent/src/input/button-handler.ts`（规划） | JSUI 实体键映射 | 短按、长按、重复按的语义和去抖 |
| `apps/glasses-agent/src/capture/capture-controller.ts`（规划） | 用户确认后的拍摄 | 只响应合法的观察请求，不自行启动连续拍摄 |
| `apps/glasses-agent/src/audio/speech-bridge.ts`（规划） | 眼镜端音频反馈 | 接收统一 `SpeechEffect` 并播报，返回成功/失败事件 |
| `tools/protocol-inspector/`（已有说明文档） | 协议研究和回放 | 连接日志、脱敏载荷、重放标记和故障记录仍待实现 |

验收标准：真实 CXR 未完成时，`SimulatorTransport` 也能让黄金场景运行；连接断开可产生统一事件；业务代码不出现临时蓝牙调用；不提交令牌、原始图像和密钥。

## 负责人 B：导航

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/providers/navigation/navigation-provider.ts`（已有） | 地图能力抽象 | 保持供应商无关的搜索、步行路线和导航事件合同 |
| `apps/phone-companion/src/navigation/map-adapter.ts`（已有 Web API 适配） | 高德 POI 搜索与步行路线 | 已有 HTTP 适配，不等于 Android SDK、实时定位、偏航重规划或真机导航完成 |
| `apps/phone-companion/src/navigation/destination-service.ts`（规划） | 语音目的地服务 | 将 `SpeechInput` 转为 POI 搜索、候选播报和语音确认 |
| `apps/phone-companion/src/navigation/location-service.ts`（规划） | 定位输入 | 位置更新、定位质量、权限失败和暂时失联状态 |
| `packages/testkit/fake-navigation-provider.ts`（已有） | 导航模拟 | 可按脚本产生开始、转向、偏航、到达和 GPS 弱事件 |

验收标准：用户只通过语音即可完成目的地输入和候选确认，不需要手机屏幕；地图适配器只产生导航事实，不直接播放音频、不调用视觉模型；定位或地图不可用时有明确降级语音。

## 负责人 C：基础识图与 OCR

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/providers/observation/observation-provider.ts`（已有） | 视觉能力抽象 | 按 `capability_id` 路由，统一返回 `ObservationResult.facts[]` |
| `apps/gateway/src/observation/observation-gateway.ts`（已有） | 视觉能力路由 | 从能力注册表选择分析器，不在路由器里堆场景分支 |
| `apps/gateway/src/observation/ocr-adapter.ts`（已有） | OCR 结果归一化 | 已有文字行事实输出；菜单菜名、价格等结构提取仍需验证 |
| `apps/gateway/src/observation/vlm-adapter.ts`（已有） | 通用视觉分析适配 | 接入实际模型、入口及可见表情能力仍需单独验收 |
| `packages/contracts/capabilities/registry.json`（已有） | 能力注册表 | 声明能力 ID、Provider、结果 Schema、策略和语音模板 |
| `packages/testkit/observation-fixtures/`（规划） | 视觉测试夹具 | 脱敏图片、期望结构、低置信度和超时样例 |

验收标准：能力都返回合同规定的状态、摘要、置信度、事实、重拍建议和限制；新增能力不要求修改 `SessionOrchestrator`；低置信度不会伪装成确定事实；模型超时、图像模糊和服务不可用都可被上层处理。

## 负责人 D：Agent 核心

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/domain/agent/`（已有） | P0 Agent 入口、上下文、LLM 端口、计划校验、任务执行、ToolGateway 逻辑适配与导航触发 | 保持 `Event → Plan → Result → Effect`，Skill 可组合，不增加目的地硬编码流程；限制路口模型语音和高风险结论 |
| `packages/domain/policies/`、`packages/domain/reminder/navigation-reminder-policy.ts`、`packages/domain/policies/speech-priority-policy.ts`（已有） | 确定性领域与播报策略 | 高风险建议、导航提醒及语音优先级不交给 Provider 或模型直接决定 |
| `tests/domain/`（已有） | Agent、Skill、Tool 与策略的离线合同及行为测试 | 覆盖授权/拒绝、结果反馈、取消、重复 Event、路口低置信度和失败保守处理 |
| `tests/scenarios/navigation-crossing-replay.test.ts`（已有） | 录制的导航路口回放 | 证明导航 Event 建立路口上下文、经授权观察后由策略播报；不得调用真机、网络或真实模型 |

验收标准：模型按语音目标组合已注册 Skill；无授权观察被拒绝；`navigation.start` 不由模型直接调用；跨端只交换结构化 Event、Plan、Result、Effect；Tool 结果通过规范 `followUpEvents` 回放；紧急取消可打断待处理流程。Agent 核心负责人不接管设备、地图、语音、视觉 SDK 适配器。

## 负责人 E：手机运行时与系统集成

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/domain/session/session-orchestrator.ts`（已有旧版） | 早期固定场景兼容实现 | 含餐厅/菜单专用状态，不是新 Agent 核心入口；停止扩展新场景，迁移时仅作为兼容层或删除 |
| `packages/domain/agent/session-orchestrator.ts`（已有） | 新 Agent 核心入口 | 手机运行时只向其提交 `AgentEvent` 并回放 `followUpEvents`；不得另建固定餐厅状态机 |
| `packages/providers/registry/tool-registry.json`（已有） | Tool 注册表 | 区分模型、策略和内部工具，声明 Schema、风险、超时、重试和事件 |
| `apps/phone-companion/src/session/session-runtime.ts`（规划） | 运行时组装 | 注入真实或模拟适配器，维护 `session_id` 和 `sequence`；作为薄适配层调用 Agent 核心 |
| `tests/scenarios/golden-path-session.test.ts`（已有旧版） | 早期固定场景回归 | 保留兼容验证，不作为可组合 Agent 流程的验收证明 |
| `tests/scenarios/` 中的参数化跨端回放（规划） | 集成验收 | 以任意 `target` 组合导航、可选入口、用户请求的菜单/表情辅助与故障降级 |

验收标准：集成后的完整流程无需手机屏幕；目的地、确认、菜单追问和表情请求均可用语音完成；系统提醒不会自动触发拍摄；Agent 不能直接调用设备或供应商 SDK；导航播报和识图播报不会互相覆盖；所有事件、事实、计划和拒绝原因可以按 `session_id + sequence` 回放。这些集成验收项不因 P0 离线核心完成而自动达成。

## 负责人 F：语音输入与输出适配

| 文件 | 功能 | 必须交付 |
|---|---|---|
| `packages/providers/speech/speech-input-provider.ts`（已有） | 语音输入端口 | 保持统一输入 Event 和错误语义，不耦合某家 ASR SDK |
| `apps/phone-companion/src/speech/speech-input-adapter.ts`（规划） | ASR 适配 | 把平台识别结果和超时/取消映射为统一输入，支持语音目的地和追问 |
| `apps/phone-companion/src/speech/speech-output-adapter.ts`（规划） | 播报适配 | 只消费经过优先级策略的 `SpeechEffect`，回报完成/失败 |
| `packages/contracts/examples/` 中的语音夹具（已有部分） | 合同样例 | 补齐正常输入、低置信度、打断及播报失败样例 |

验收标准：无屏幕语音输入可发起目标、确认候选和追问；策略生成的播报按优先级排队、可被紧急事件打断。语音负责人只维护注入的适配器和对应合同夹具，不修改 Agent 决策逻辑。

协议、地图、视觉负责人同样只维护各自注入的适配器、模拟实现和对应合同夹具；跨领域策略及 `packages/domain/agent/` 由 Agent 核心负责人维护。合同 Schema 的跨组变更需共同评审。

## 共享合同和测试文件

| 文件 | 作用 | 变更规则 |
|---|---|---|
| `packages/contracts/schemas/*.schema.json` | 跨端数据格式 | 先改 Schema，再改实现；破坏性变更提升主版本 |
| `packages/contracts/examples/` | P0 正常/失败样例 | 作为 Fake、回放和合同测试的共同夹具 |
| `packages/contracts/interfaces.md` | 跨模块接口形状 | 不把供应商 SDK 类型写入接口 |
| `packages/testkit/` | Fake、fixture、回放工具 | 每个外部依赖都要有替身 |
| `packages/testkit/tool-loop-fixtures/`（已有说明文档） | Tool Loop 回放夹具 | 验证授权拒绝、幂等、超时、事实过期和失败降级；具体夹具仍须核对 |
| `tests/contracts/` | Schema 和兼容性检查 | 每次合同变更必须运行 |
| `tests/scenarios/` | 场景验收和回归 | 以黄金场景为最小端到端标准 |

## 通用完成定义（Definition of Done）

每个模块合并前必须满足：

1. README 或模块文档写清输入、输出、错误和本地验证方式。
2. 至少有一个模拟实现或离线夹具，不依赖真机、地图网络或云模型才能运行单元测试。
3. 跨模块数据通过 `packages/contracts`，不直接传递供应商对象。
4. 覆盖一个正常路径和一个失败路径，并能看到 `session_id`、事件序号和错误原因。
5. 不提交账号令牌、原始设备 dump、APK、用户图像或音频。

P0 合同闭合标准：设备命令/事件、媒体引用、语音输入、目的地查询/候选、导航路口事件、通用观察请求/结果、能力注册表、命名事实、路口辅助建议、会话快照和统一错误均有 Schema，并至少有一条正常样例和一条失败样例。新增普通观察能力不得要求修改 `SessionOrchestrator` 或基础观察结果结构。

## 依赖顺序

```text
合同 Schema
  → FakeTransport / FakeNavigation / ObservationFixture
  → Agent 核心与确定性策略
  → 各真实 Provider 适配器
  → 手机运行时组装
  → 真机黄金场景
```

任何真实 SDK 或 CXR 研究受阻时，不得阻塞其他负责人；先用替身完成合同、状态机和场景回放。
