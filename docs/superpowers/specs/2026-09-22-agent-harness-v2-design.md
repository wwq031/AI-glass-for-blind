# Agent Harness P0 设计规格 v2

状态：待评审
日期：2026-09-22

## 1. 设计结论

乐奇 AI 眼镜的 Agent 是一个以 LLM 为认知和规划中心的任务运行时。它负责理解用户目标、结合当前上下文选择或组合 Skill、规划下一步、持续吸收工具结果，并以自然语言完成交互。

Harness 不替代 LLM 做业务规划。Harness 的作用是提供会话状态、统一执行入口、资源与授权检查、结果记录和恢复能力。

系统的唯一主数据流是：

~~~text
Event → Plan → Result → Effect
~~~

其中：

- Event 是进入 Agent 的外部变化；
- Plan 是 LLM 为当前目标生成的下一步行动计划；
- Result 是 Tool 或 Provider 执行计划后返回的事实与状态；
- Effect 是系统最终对用户、设备或导航产生的外部效果。

SessionState、PolicyDecision、资源锁和审计记录都是 Harness 内部对象，不与四种主对象混称。

## 2. 四种主对象

### 2.1 Event：进入 Agent 的输入

所有外部变化先归一化为 Event，包括：

~~~text
UserEvent       语音、按键、取消、重播、确认
SystemEvent     计时器、会话恢复、设备断开
NavigationEvent 路线、转向、接近路口、到达
MotionEvent     朝向、姿态、行走状态变化
ProviderEvent   视觉、OCR、地图、语音识别、网络任务的完成或失败
~~~

每个 Event 需要有稳定的：

~~~text
event_id、session_id、sequence、source、occurred_at、type、payload、confidence
~~~

原始设备协议、原始图像和原始传感器数据不得直接进入 LLM 上下文；它们先由边缘适配器归一化为 Event 或 Provider Fact。

### 2.2 Plan：LLM 的行动计划

LLM 每次处理 Event 后，生成一个结构化 AgentPlan。它可以包含：

~~~text
目标更新
选择或组合的 Skill
逻辑 ToolCall
需要用户澄清的问题
对当前 Result 的解释
建议的语音回复
等待、结束、暂停或恢复动作
~~~

示例：

~~~json
{
  "goal": "help_user_reach_destination",
  "actions": [
    {
      "skill": "navigate_to",
      "arguments": { "target": "人民医院" }
    },
    {
      "wait_for": "navigation.approaching_intersection",
      "then": "crossing_advisory"
    }
  ],
  "response_draft": "正在为你查找人民医院。"
}
~~~

Plan 是 LLM 的输出，不是设备指令，也不是已经发生的事实。它可以被拒绝、要求补参或等待下一 Event。

### 2.3 Result：执行后的反馈

Result 是 Tool、Provider 或策略执行后的反馈，包含成功、部分成功、事实、错误与可恢复信息。

~~~text
DestinationCandidates
NavigationState
ObservationResult
TextReadingResult
ToolError
PlanRejection
~~~

Result 必须携带来源、时间、置信度、有效期和必要的上下文。Harness 会将 Result 包装为下一个 ProviderEvent 或 SystemEvent 后重新交给 LLM。

LLM 可以解释 Result、据此修改 Plan 或对用户作答；LLM 不能把自己的猜测写回为 Provider 产生的事实。PolicyDecision 只在 Harness 内部使用；若拒绝计划或动作，Harness 把可解释的原因包装为 PlanRejection 或 ToolError。

### 2.4 Effect：离开 Agent 的输出

执行通过后的外部输出统一为 Effect：

~~~text
SpeechEffect        播报、确认问题、进度提示
HapticEffect        震动或提示音
DeviceCommand       已批准的拍摄、音频播放等设备指令
NavigationEffect    启动、停止或恢复导航
SessionEffect       结束、暂停、保存或恢复会话
~~~

Effect 必须经过执行器实际送达；实际送达或失败会再次成为新的 Event。

## 3. 运行时角色和权威边界

~~~text
LLM Agent
  理解目标、组合 Skill、生成 Plan、解释 Result、主持对话

Agent Harness
  维护 SessionState、构造上下文、调度 Plan、记录和恢复

Skill Registry
  描述可组合的任务能力、参数、Tool 需求和适用条件

ToolGateway
  校验逻辑 ToolCall，形成可执行请求

ProviderRouter
  选择本地、云端、MCP 适配器或测试替身

Provider / DeviceTransport
  提供现实世界事实，或实际执行设备、地图和模型操作

Policy
  对授权、资源和高风险建议进行裁决
~~~

权威不是“是否相信 LLM”的二元问题：

| 问题 | 主导者 |
|---|---|
| 用户想完成什么 | LLM，根据用户输入和上下文理解 |
| 任务应如何组合 | LLM，在已注册 Skill 中规划 |
| 现实中发生了什么 | Provider 和设备事件 |
| 当前动作能否执行 | ToolGateway、授权和资源规则 |
| 高风险事实能否形成安全建议 | 对应领域 Policy |
| 如何向用户说明 | LLM，基于 Result 和允许公开的策略原因 |

Harness 不应把普通业务流程重写成硬编码状态分支；它只维护执行正确性。

## 4. Skill 与动态组合

### 4.1 Skill 是可调用任务能力

每个 Skill 提供给 LLM 的是能力描述，而不是一条强制流程。Skill 至少声明：

~~~text
skill_id
目标和参数 Schema
可使用的逻辑 Tool
适用事件和前置条件
期望 Result Schema
资源与延迟要求
高风险时需要的 Policy
失败后的可选处理方式
~~~

### 4.2 通用 Skill 覆盖长尾

~~~text
navigate_to       前往任意目标
inspect_scene     观察和描述当前场景
read_text         读取当前文字
find_target       寻找指定物体、入口或标志
follow_up         利用当前会话结果回答追问、重播或纠正
~~~

地点、对象、文字和用户目标均是运行时参数，不需要为医院、餐馆、商场或学校分别建立永久 Skill。

### 4.3 特化 Skill 守住高风险或高结构任务

~~~text
crossing_advisory  路口和信号灯辅助
obstacle_advisory  行走障碍辅助
menu_structuring   菜单等结构化文本整理
~~~

特化不意味着固定一条完整用户旅程；它只意味着该类任务需要特定事实、置信度规则或领域 Policy。

### 4.4 LLM 组合的边界

LLM 可以在一次 Plan 中组合通用 Skill 和特化 Skill，例如：

~~~text
navigate_to(target=医院)
  + 到达后 find_target(target=入口)
  + 用户请求时 read_text(mode=sign_or_form)
~~~

组合是一次性的运行时任务计划，不会被写成“医院流程”。对于低风险任务，LLM 可以灵活组合已注册 Skill；对于路口、障碍物等高风险任务，LLM 只能请求对应特化 Skill，不能自行替代其事实要求和 Policy。

## 5. 完整生命周期

### 5.1 唤起

实体按键、语音、用户回答或系统事件产生 Event。固定的取消、重播、暂停和紧急打断走本地快速处理，不为了“路由”额外增加模型回合。

### 5.2 上下文构造

Harness 为 LLM 构造当前 turn 的上下文：

~~~text
当前 Event
当前目标与未完成 Plan
最近 Result 和事实有效期
导航、位置、朝向和设备状态
用户授权、隐私范围和资源占用
可用 Skill、Tool 和 Provider 健康状态
最近播报和等待用户回答的问题
~~~

### 5.3 LLM 规划

LLM 根据 Event 和上下文输出 AgentPlan。它可以直接回复，也可以发出一个或多个逻辑 ToolCall，也可以等待后续 Event。普通输入只需一次“理解 + 规划”模型回合，不拆成独立意图路由回合和规划回合。

### 5.4 Plan 验证和执行

Harness 对 Plan 做最小检查：

- Skill 和 Tool 是否存在；
- 参数是否符合 Schema；
- 是否具备用户授权；
- 是否满足资源、并发、截止时间和幂等要求；
- 高风险任务是否进入指定 Policy。

通过后，TaskRunner 调用 ToolGateway。拒绝或缺参时，Harness 以结构化 Result 返回给 LLM，由 LLM 修正 Plan 或向用户澄清。

### 5.5 Result 回流

Tool/Provider 的同步返回、异步回调、设备确认、模型失败都成为 Result，再包装为下一 Event。LLM 依据 Result 继续任务、重试、追问、切换 Skill 或生成最终回复。

### 5.6 输出和收尾

LLM 产生的回复草稿与批准后的动作编译为 Effect。会话在完成、取消、超时、设备断开或用户求助时收尾；Harness 释放资源、保存状态并记录可恢复点。

## 6. 导航、路口和主动观察

navigate_to 的职责是启动并维持导航，不直接拍摄，也不直接判断通行。

导航 Provider 返回：

~~~text
navigation.approaching_intersection
  intersection_id
  distance_m
  travel_heading
  crossing_context
  route_segment
  occurred_at
~~~

该 Event 会建立一个“路口检查”任务上下文，唤起 crossing_advisory。LLM 在这个上下文中规划下一步，且只能调用符合该 Skill 要求的逻辑观察能力。

~~~text
NavigationEvent
  → crossing_advisory context
  → LLM Plan
  → consent/resource check
  → observation.request
  → ObservationResult
  → CrossingPolicy
  → SpeechEffect
~~~

用户主动问“前面能不能过”时，LLM 也可以请求 crossing_advisory；若缺少位置、方向或路口上下文，必须向用户澄清、等待导航 Event，或输出保守结果。普通 inspect_scene 不能代替路口安全建议。

主动观察不由 LLM 随意发起。是否可以拍摄由会话授权、当前任务、冷却时间、资源和隐私策略共同决定。P0 支持按次确认，也支持显式的会话预授权；两者都能随时取消。

## 7. Tool、Provider 与本地/云端

Agent 只调用逻辑 Tool，例如：

~~~text
navigation.search_destination
navigation.start
observation.request
facts.query
speech.ask_user
session.cancel
~~~

ToolGateway 之后才进入 ProviderRouter：

~~~text
ToolGateway
  → ProviderRouter
      ├─ LocalProvider
      ├─ RemoteProvider
      ├─ McpProviderAdapter
      └─ RecordedProvider
~~~

本地同步调用和云端异步调用统一为 Result 生命周期：

~~~text
requested → running → partial → succeeded
                              ├─ needs_retake
                              ├─ cannot_determine
                              └─ failed
~~~

调用上下文至少包括：

~~~text
session_id、request_id、idempotency_key、deadline、consent、privacy_policy、priority
~~~

Provider Result 至少包括：

~~~text
status、provider_id、execution_location、provider_version、created_at、expires_at、retryable
~~~

设备能力的形态不同：

~~~text
摄像头：经授权的 observation.request
语音：SpeechInput Event 与 SpeechEffect
陀螺仪：本地 MotionEvent 流
蓝牙：本地 DeviceTransport 传输层
地图：NavigationProvider
~~~

MCP 可以作为某个远程 Provider 的传输适配，不作为 Agent、眼镜或手机之间的核心协议。

## 8. 高风险策略

高风险策略只处理明确需要领域裁决的问题，不接管 LLM 的一般规划。

例如 crossing_advisory 的输入必须是带来源和有效期的交通事实；其输出只能是：

~~~text
wait
recheck
proceed_with_caution
cannot_determine
~~~

LLM 可以把结果表达为适合用户理解的语言，但不能把 unknown、过期、方向不匹配或低置信度事实改写成“可以安全通行”。

低风险通用 Skill 的不确定结果可由 LLM 决定重试、换角度、追问或直接说明看不清。高风险不确定结果进入保守处置，并在需要时建议用户停下、等待确认或寻求人工协助。

## 9. P0 验收

P0 在模拟器中验证以下闭环：

1. 用户按键或语音唤起，输入任意目的地；
2. LLM 生成参数化导航 Plan，不依赖手机屏幕；
3. 导航 Event 唤起路口检查上下文；
4. LLM 组合已注册 Skill，Harness 只做约束检查；
5. 观察 Result 支持成功、重拍、超时、低置信度和无法确认；
6. 到达任何目的地后，用户可按需求寻找入口、目标物或读取文字；
7. 菜单是 read_text 的结构化模式，不是餐馆固定流程；
8. 用户可追问、重播、取消、打断和继续；
9. 本地、远程替身和回放 Provider 采用同一 Tool/Result 合同；
10. Event、Plan、Result、Effect、授权判定和资源状态均可回放。

P0 不要求真实 CXR、真实 Android 后台服务、真实云模型或连续自主监测，但必须保留对应接缝。

## 10. 合同与目录调整

~~~text
packages/contracts/agent/
├─ event.schema.json
├─ agent-plan.schema.json
├─ tool-result.schema.json
├─ effect.schema.json
├─ session-state.schema.json
└─ skill-manifest.schema.json

packages/domain/agent/
├─ agent-harness.ts
├─ session-orchestrator.ts
├─ context-builder.ts
├─ llm-agent.ts
├─ plan-validator.ts
├─ task-runner.ts
├─ resource-manager.ts
├─ policy-guard.ts
├─ effect-compiler.ts
└─ replay-runtime.ts

packages/domain/skills/
├─ generic/
├─ navigation/
├─ crossing-advisory/
├─ obstacle-advisory/
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
~~~

“去餐馆”只保留为 tests/scenarios 的回放样例，而不是永久组合 Skill。
