# Agent Harness P0 设计规格 v2

状态：待评审
日期：2026-09-22

## 1. 核心结论

本项目的 Agent 是一个**由 LLM 主导任务理解和规划、由 Harness 管理执行边界、由 Provider 提供现实世界事实、由策略层守住高风险边界**的助盲任务运行时。

它不是：

- 只会提出候选意见的固定状态机；
- 每个地点或每个用户目标都预设一条流程；
- 可以直接操作眼镜硬件的聊天模型；
- 把普通视觉描述当作安全结论的万能助手。

它是：

```text
用户目标 / 系统事件
  → LLM 理解和任务规划
  → Harness 验证计划的最小约束
  → ToolGateway 执行逻辑能力
  → Provider 返回结构化事实或效果
  → LLM 根据结果继续规划或表达
  → 高风险策略对最终建议进行裁决
```

LLM 可以组合已经注册的能力，并在运行时生成一次性的参数化任务计划；它不能凭空创造能力、事实、安全规则或底层设备命令。

## 2. 信任模型

不能笼统地说“信任”或“不信任”LLM，必须按输出类型确定权威来源。

| 输出类型 | 主要来源 | LLM 权限 |
|---|---|---|
| 用户意图 | 语音输入和会话上下文 | 理解、补全、澄清 |
| 任务计划 | LLM 规划 | 生成，经过 Schema 和状态校验 |
| 物理世界事实 | 摄像头、陀螺仪、定位、地图、OCR/VLM Provider | 解释和引用，不能伪造 |
| 普通描述 | Provider 事实 + LLM | 可以总结和自然语言表达 |
| 高风险建议 | 事实 + 领域策略 | LLM 可解释，不能单独裁决 |
| 设备副作用 | ToolGateway / DeviceTransport | 不能直接执行底层命令 |

因此 LLM 不是被禁止做决策，而是它的决策必须落在正确的权威边界内：

```text
LLM 决定“要完成什么任务、下一步需要什么能力”
Provider 决定“现实世界检测到了什么”
Policy 决定“高风险事实能否形成安全建议”
Gateway 决定“动作是否可以真正执行”
```

## 3. 四类运行时对象

### 3.1 Domain Skill

Domain Skill 是一个可注册、可发现、可组合的领域能力。它不是针对某个地点的完整流程，而是一个可复用的任务能力。

```text
通用 Skill：navigate_to、inspect_scene、read_text、find_object、follow_up
特化 Skill：crossing_advisory、obstacle_warning、entrance_finding、menu_structuring
```

Skill 声明：

- 目标和输入参数；
- 前置条件和适用事件；
- 结果 Schema；
- 需要的逻辑 Tool；
- 资源、延迟和并发要求；
- 风险等级和必须经过的策略；
- 失败、重试和澄清方式。

Skill 不绑定某一个本地模型、云端模型或硬件实现。

### 3.2 参数化任务计划

用户目标在运行时转换成 `TaskPlan`，而不是为每个目标注册新能力：

```json
{
  "goal": "help_user_reach_destination",
  "steps": [
    { "skill": "navigate_to", "arguments": { "target": "地铁站" } },
    { "on_event": "navigation.approaching_intersection", "skill": "crossing_advisory" },
    { "on_arrival": { "skill": "find_object", "arguments": { "target": "入口" } } }
  ]
}
```

这是一次性的运行时计划，不会被永久注册成“地铁站流程”。

LLM 可以生成或调整这个计划，但必须经过 `PlanValidator` 和 `PolicyGuard`。计划只能引用已注册 Skill 和逻辑 Tool，必须有步骤上限、截止时间、资源声明和终止条件。

### 3.3 Provider Fact

Provider 是现实世界事实或设备效果的来源：

```text
NavigationProvider   路线、距离、方向、接近路口事件
ObservationProvider  视觉观察和结构化事实
MotionProvider       姿态、转向、行走和运动事件
SpeechProvider       语音输入和输出
DeviceTransport      眼镜连接、按键、摄像请求和设备反馈
```

事实必须带来源、时间、置信度、有效期和方向/上下文信息。LLM 可以解释事实，但不能把自身猜测写成 Provider Fact。

### 3.4 Effect

Effect 是经过策略和执行入口批准后的外部效果：

```text
SpeechEffect
DeviceCommand
NavigationEffect
ObservationRequest
SessionEffect
```

LLM 生成的是计划或逻辑 ToolCall，不是未经检查的 Effect。

## 4. 完整 Agent 生命周期

### 4.1 待机和唤起

唤起来源包括：

- 实体按键；
- 唤醒词或语音输入；
- 当前任务的用户回答；
- 导航、姿态、连接或设备事件。

唤起阶段只做本地快速处理：取消、重播、打断、接受回答、恢复会话。明显的固定命令不得为了路由再额外调用一次 LLM。

### 4.2 事件标准化

所有来源进入统一事件流：

```text
user.speech_final
button.short_press
button.long_press
navigation.started
navigation.approaching_intersection
navigation.arrived
motion.heading_changed
observation.completed
device.disconnected
timer.expired
```

事件至少包含：

```text
event_id、source、session_id、sequence、occurred_at、payload、confidence
```

陀螺仪、姿态和行走状态优先以本地 `MotionEvent` 进入事件流，不设计为让 LLM 反复读取原始传感器的 Tool。

### 4.3 会话和上下文

Agent 为每轮构造上下文快照：

- 当前任务目标和阶段；
- 已确认的事实及有效期；
- 未完成的计划步骤；
- 用户授权和隐私策略；
- 当前导航、方向和位置状态；
- 可用 Skill、Tool 和 Provider 健康状态；
- 最近播报、用户追问和错误；
- 摄像头、麦克风、播报等资源占用。

LLM 看到的是经过筛选的结构化上下文，而不是任意原始设备数据。

### 4.4 LLM 规划

对于新目标或模糊输入，LLM 作为主要任务规划器：

1. 识别目标、对象、地点、条件和缺失参数；
2. 从 Skill Registry 选择和组合通用/特化能力；
3. 生成参数化 `TaskPlan`；
4. 决定是否需要澄清、观察、等待或继续；
5. 根据结构化结果决定下一步计划或播报。

LLM 不需要先经过一个独立的“意图路由模型”再调用一次 LLM；普通请求可以一次完成理解和初步计划。

### 4.5 计划校验和最小约束

`PlanValidator` 不负责把所有场景硬编码成状态分支，而只检查计划是否满足基础不变量：

- Skill 和 Tool 是否已注册；
- 输入参数是否符合 Schema；
- 当前状态是否满足前置条件；
- 是否存在越权的原始设备调用；
- 是否缺少用户授权；
- 是否违反资源锁、步骤数或截止时间；
- 高风险结果是否经过要求的领域策略。

校验通过后，LLM 生成的计划可以由 `TaskRunner` 执行；校验失败时返回结构化错误，允许 LLM 修正或向用户澄清。

### 4.6 导航事件与红绿灯能力

导航是事件来源，不能让 LLM凭空决定“现在应该拍摄”。标准路径为：

```text
用户请求 navigate_to
  → NavigationProvider 启动路线
  → NavigationProvider 返回 navigation.approaching_intersection
  → TriggerEngine 创建 crossing_advisory 任务上下文
  → LLM 可补充任务表达或选择已允许的观察步骤
  → PolicyGuard 检查授权、方向、时效和资源
  → observation.request
  → ObservationProvider 返回交通事实
  → CrossingAdvisoryPolicy 生成 wait / recheck / proceed_with_caution / cannot_determine
  → SpeechEffect
```

`navigate_to` 本身不直接拍摄，也不直接输出是否可以通行的结论。`navigation.approaching_intersection` 至少携带：

```text
intersection_id、distance_m、travel_heading、crossing_context、route_segment、occurred_at
```

用户主动问“前面能不能过”时，Agent 仍然先读取当前导航上下文；缺少必要上下文时，必须补齐、追问或保守拒绝，不能退回普通场景描述。

### 4.7 执行和结果反馈

执行统一经过：

```text
TaskRunner
  → ToolGateway
  → ProviderRouter
  → LocalProvider / RemoteProvider / RecordedProvider / McpProviderAdapter
  → ResultNormalizer
  → EventLog
```

结果统一为：

```text
succeeded
partial
needs_retake
needs_confirmation
cannot_determine
failed
```

LLM 可以基于结构化结果继续规划、总结或追问，但不可以修改事实来源和置信度。

### 4.8 通用兜底和安全处置

能力匹配顺序不是“所有场景都特化”，而是：

```text
特化 Skill 适用 → 使用特化 Skill
没有特化 Skill 且低风险 → 使用通用 Skill
普通结果不清楚 → 重试、调整方向或追问
高风险事实不清楚 → 保守处置，不输出安全保证
```

例如：

- “看看那边有什么”可以使用 `inspect_scene`；
- “读一下这张纸”可以使用 `read_text`；
- “前面能不能过马路”不能用普通场景描述替代 `crossing_advisory`。

保守处置不是系统崩溃，而是合法结果：

```text
请先停下，我无法确认当前是否安全。
```

必要时可以重试、请求身边的人确认或进入预设人工协助流程。

### 4.9 追问、中断和收尾

Agent 支持：

- 取消当前任务；
- 中断播报；
- 重播上次结果；
- 回答待确认问题；
- 继续当前计划；
- 结束会话。

收尾原因统一为：

```text
completed、cancelled、timeout、failed、device_disconnected、user_requested_help
```

收尾时释放设备资源、停止主动观察、保存计划和事实摘要，并记录未完成步骤。恢复时必须先对账最后一个已确认事件，不能把未知状态当作成功。

## 5. Tool、Provider 和本地/云端

### 5.1 统一逻辑调用

Agent 只调用逻辑 Tool，不知道实际执行位置：

```text
CoreAgent / TaskRunner
  → ToolGateway
  → ProviderRouter
      ├─ LocalProvider
      ├─ RemoteProvider
      ├─ RecordedProvider
      └─ McpProviderAdapter
```

MCP 只是远程 Provider 的一种协议适配，不是 Agent 核心协议。模型不直接看到原始蓝牙、CXR、摄像头或陀螺仪命令。

### 5.2 Provider 选择

ProviderRouter 根据以下因素选择实现：

- 隐私策略；
- 允许的网络位置；
- 延迟截止时间；
- 本地模型健康状态；
- 电量和设备资源；
- 云端可用性；
- 失败回退策略。

本地同步和云端异步统一为：

```text
requested → running → partial → succeeded / needs_retake / cannot_determine / failed
```

调用上下文至少包含：

```text
session_id、request_id、idempotency_key、deadline、consent、privacy_policy、priority
```

结果至少包含：

```text
status、provider_id、execution_location、provider_version、created_at、expires_at、retryable
```

### 5.3 不同设备能力的调用形态

```text
摄像头：受授权的 observation.request
语音：SpeechInput / SpeechEffect
陀螺仪：本地 MotionEvent 事件流
蓝牙：本地 DeviceTransport 传输层
地图：NavigationProvider
```

这些能力在启动时全局注册并维护健康状态，但敏感采集和模型推理只能由任务计划和策略按需启动。

## 6. P0 目录和实现边界

建议核心模块为：

```text
packages/domain/agent/
├─ agent-harness.ts
├─ session-orchestrator.ts
├─ context-builder.ts
├─ core-agent.ts
├─ plan-validator.ts
├─ task-runner.ts
├─ resource-manager.ts
├─ policy-guard.ts
├─ decision-engine.ts
├─ effect-compiler.ts
└─ replay-runtime.ts

packages/domain/skills/
├─ generic/
├─ navigation/
├─ crossing-advisory/
├─ obstacle-warning/
├─ entrance-finding/
└─ text-reading/

packages/providers/
├─ registry/
├─ gateway/
├─ router/
├─ local/
├─ remote/
├─ mcp-adapter/
├─ device/
├─ motion/
├─ speech/
├─ navigation/
├─ observation/
└─ storage/
```

场景样例“去餐厅”只放在 `tests/scenarios`，作为参数化任务计划的回放案例，不建立 `restaurant_visit` 这种针对地点的永久能力。

## 7. P0 验收

模拟器必须验证：

1. 用户唤起后，LLM 能从语音提取目的地和附加需求；
2. Agent 能生成 `navigate_to` 参数化任务计划；
3. 导航 Provider 产生接近路口事件后，自动创建路口能力上下文；
4. LLM/TaskRunner 能在已注册能力中组合合法步骤，但不能绕过策略；
5. 路口观察成功、低置信度、过期和无法确认均有正确结果；
6. 到达任意目标后可以根据用户需求寻找入口、目标物或文字；
7. 通用观察和文字读取覆盖未特化的低风险请求；
8. 菜单只是文字读取的结构化模式之一，不绑定餐厅流程；
9. 用户可以追问、重播、取消、打断和继续；
10. 本地、远程替身和回放 Provider 使用同一合同；
11. 所有事件、计划、Provider 事实、策略判定、Tool 结果和 Effect 可回放。

P0 不要求真实 CXR、真实 Android 后台服务、真实云模型或所有主动监测策略，但必须保留这些适配位置。

## 8. 明确不做的事情

- 不为每个地点、职业、建筑或用户目标创建永久 Skill；
- 不让 LLM 直接读写原始陀螺仪、蓝牙或 CXR 协议；
- 不把导航启动动作和视觉观察动作强行绑定；
- 不让普通视觉描述替代红绿灯或障碍物安全策略；
- 不为每一个逻辑判断增加额外的 LLM 回合；
- 不把 MCP 当作设备侧核心通信协议。
