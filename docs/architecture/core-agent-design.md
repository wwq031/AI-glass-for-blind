# 核心 Agent 设计

## 定义

核心 Agent 不是一个可以直接调用摄像头、地图和播报的大模型，而是一个事件驱动的决策层：读取带证据的事件和事实，在能力注册表与安全策略允许的范围内生成结构化行动计划。

`SessionOrchestrator` 是运行时外壳，负责事件顺序、状态持久化和执行边界；`CoreAgent` 是其中的决策引擎，负责判断下一步需要询问、观察、导航还是播报。两者都不能绕过 Provider 和 `DeviceTransport`。

## 核心循环

```text
外部事件
  → EventNormalizer
  → SessionMemory / State
  → TriggerEngine
  → CoreAgent.Decision
  → SafetyGuard + ConsentGate
  → Typed Action Plan
  → Provider / DeviceTransport 执行
  → 结果和事实校验
  → SpeechEffect / 新状态
  → AuditLog / Replay
```

Agent 每轮只处理一个有序事件，并输出可回放的结构化计划。计划执行失败必须产生 `ContractError`，不能由模型自行重试或伪造成功。

## 建议模块

| 文件 | 责任 |
|---|---|
| `packages/domain/agent/agent-loop.ts` | 驱动一次事件-决策-执行循环 |
| `packages/domain/agent/event-normalizer.ts` | 把语音、导航、设备和观察结果归一化为领域事件 |
| `packages/domain/agent/session-memory.ts` | 保存当前任务、最近事实、授权、过期时间和未完成动作 |
| `packages/domain/agent/trigger-engine.ts` | 根据事件和注册能力触发提醒、等待确认或观察请求 |
| `packages/domain/agent/capability-planner.ts` | 从能力注册表选择能力，不写场景分支 |
| `packages/domain/agent/policy-engine.ts` | 按声明式策略把事实转换为领域效果 |
| `packages/domain/agent/safety-guard.ts` | 检查授权、时效、置信度、冲突和不可覆盖的安全规则 |
| `packages/domain/agent/dialogue-manager.ts` | 管理语音轮次、澄清、重复、取消和追问 |
| `packages/domain/agent/effect-compiler.ts` | 将结构化动作编译为 `SpeechEffect`、`DeviceCommand` 或 Provider 调用 |
| `packages/domain/agent/audit-log.ts` | 记录事件、事实、计划、拒绝原因和执行结果 |
| `packages/domain/agent/model-advisor.ts` | 可选模型顾问；只能提出计划候选，不能直接执行工具 |

## Agent 输入与输出

```text
AgentInput = {
  event: DomainEvent,
  snapshot: SessionSnapshot,
  recentFacts: Fact[],
  capabilities: CapabilityDefinition[],
  pendingActions: PendingAction[]
}

AgentPlan = {
  intent: "speak" | "ask_confirmation" | "request_observation" |
          "search_destination" | "confirm_destination" | "start_navigation" |
          "answer_query" | "wait" | "cancel",
  capability_id?: string,
  reason_code: string,
  parameters: object,
  expires_at?: string
}
```

`AgentPlan` 不是设备命令。只有通过 `SafetyGuard` 和授权检查后，`effect-compiler` 才能把它转换为具体的 Provider 或 `DeviceCommand` 调用。

## 主动与被动协作

### 被动导航事件

```text
navigation.intersection_approaching
  → TriggerEngine 发现需要路口检查
  → SpeechEffect：“前方路口，需要检查时请按键或说检查”
  → 等待用户确认
```

此时 Agent 只申请用户确认，不自动打开摄像头。

### 用户确认后的主动观察

```text
button.pressed / SpeechInput("检查")
  → ConsentGate 通过
  → request_observation(capability_id=vision.traffic_signal)
  → capture.requested
  → observation.result_received
  → crossing-advisory policy
  → wait / recheck / proceed_with_caution / cannot_determine
```

### 未来预授权主动观察

未来可以增加 `future_authorized_policy`，但必须同时满足预授权、能力冷却时间、媒体生命周期和可撤销开关。它仍然经过同一条 `SafetyGuard`，不能绕过用户隐私策略。

## 模型顾问的边界

模型可以：

- 将自然语言归一化为目的地、查询或确认意图；
- 从已注册能力中提出候选能力；
- 根据已有事实生成追问或摘要；
- 提出 `AgentPlan` 候选。

模型不能：

- 直接调用摄像头、地图、蓝牙、TTS 或网络；
- 伪造设备事件、观察事实或导航状态；
- 绕过拍摄授权和隐私规则；
- 覆盖过期、低置信度、方向未知或车辆风险的保守策略；
- 把“无法确认”改写成“可以安全通行”。

## 路口协作策略

路口策略消费命名事实，而不是识别模型的自由文本：

```text
traffic_signal.state = red
  → wait

traffic_signal.state = unknown
或 traffic_signal.direction_match = unknown
或 fact 已过期
  → recheck / cannot_determine

traffic_signal.state = green
且 direction_match = yes
且 vehicle.activity != approaching
  → proceed_with_caution
```

即使输出 `proceed_with_caution`，播报也必须说明这不是安全通行保证，并要求用户继续留意周围环境。

## 可回放要求

每次 Agent 决策必须记录：

- 输入事件和 `session_id + sequence`；
- 使用的能力注册版本；
- 参与决策的事实、置信度和有效期；
- Agent 提出的计划；
- SafetyGuard 接受或拒绝的原因；
- 实际执行结果和最终播报。

回放时禁止再次调用实时模型；使用记录的事实和 Provider 替身重建决策。
