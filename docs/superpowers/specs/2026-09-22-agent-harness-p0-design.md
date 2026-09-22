# Agent Harness P0 设计规格

状态：待评审
日期：2026-09-22

## 1. 目的

本规格定义“乐奇 AI 眼镜”助盲 Agent 的完整任务生命周期：从用户或系统唤起，到会话判断、能力路由、计划执行、结果决策、语音反馈，再到任务收尾和回放。

P0 的目标不是接入真实 CXR、Android 服务或真实模型，而是在模拟器中验证一条可回放的纵向闭环：

```text
唤起 → 语音目的地 → 地点确认 → 开始导航
     → 接近路口提醒 → 观察申请/观察执行
     → 到达餐厅 → 入口观察 → 菜单读取
     → 追问、重播、取消 → 会话收尾
```

## 2. 术语和职责边界

### 2.1 Agent Harness

Agent Harness 是长期运行的受控运行时，负责事件、状态、能力、策略、资源、执行和审计的闭环。

在现有命名中：

- `SessionOrchestrator` 是 Harness 内的会话生命周期编排器；
- `CoreAgent` 是提出结构化决策/计划候选的决策组件；
- `PolicyGuard` 是不可绕过的授权和安全裁决层。

### 2.2 Domain Skill

Domain Skill 是解决一类领域任务的可执行能力包，不是提示词，也不是底层 Tool。

能力包包含：

- 触发条件和前置条件；
- 任务步骤和结果 Schema；
- 所需资源和并发规则；
- 置信度、不确定性和失败处理；
- 语音反馈规则；
- 使用的通用 Tool 能力需求；
- 回放样例和验收标准。

能力包分为三层：

```text
特化 Skill：traffic_signal_advisory、obstacle_warning、entrance_finding、menu_reading
通用 Skill：generic_observation、generic_text_reading、generic_object_finding、generic_followup
组合 Skill：navigation_to_restaurant、restaurant_visit
```

### 2.3 Tool 和 Provider

Tool 和 Provider 是全局基础设施，不按每个场景复制一套实现。

```text
Domain Skill
  → 通用 Tool 合同
  → Provider Gateway
  → 当前可用 Provider
```

Provider 在 Agent 启动时统一注册并维护健康状态，但摄像头采集、OCR/VLM 推理、原始音频上传等高成本或敏感行为只能在会话授权和策略许可后按需执行。

本项目不把 MCP 作为 Agent 核心调用协议。MCP 可以作为远程 Provider 的一种适配协议，但必须隐藏在 `McpProviderAdapter` 后面；Agent 看到的仍然是项目自己的 Tool 和领域结果合同。

## 3. 总体运行时结构

```text
事件源
  → EventNormalizer
  → SessionOrchestrator
  → ContextBuilder
  → FastPathRouter / CoreAgent
  → PolicyGuard / ConsentGate
  → PlanExecutor / ResourceManager
  → ToolGateway / ProviderRouter / ProviderGateway
  → ResultNormalizer
  → DecisionEngine
  → EffectCompiler
  → EventLog / Replay
```

核心原则：

1. 路由是 Harness 内的本地步骤，不额外增加固定的模型回合。
2. 清晰的按键、取消、重播、导航事件和已知会话转换走确定性快速路径。
3. LLM 只处理自然语言理解、模糊能力匹配、参数补全、结果摘要和追问候选。
4. LLM 不能直接访问摄像头、地图、设备、网络，也不能修改安全状态。
5. 每个副作用动作都必须经过 PolicyGuard 和 ToolGateway。

### 3.1 统一调用与部署路由

Agent 只调用逻辑 Tool，不感知执行位置。`ProviderRouter` 根据隐私、延迟、网络、电量、资源占用、Provider 健康状态和产品策略选择实现：

```text
ToolGateway
  → ProviderRouter
      ├─ LocalProvider       手机本地模型或眼镜设备
      ├─ RemoteProvider      HTTP/WebSocket/云端任务
      ├─ McpProviderAdapter  远程 MCP 服务的受限适配器
      └─ RecordedProvider    测试和场景回放
```

本地同步和云端异步必须归一化为同一任务生命周期：

```text
requested → running → partial → succeeded
                              ├─ needs_retake
                              ├─ cannot_determine
                              └─ failed
```

每次调用携带：

```text
session_id、request_id、idempotency_key、deadline、consent、privacy_policy、priority
```

每个结果携带：

```text
status、provider_id、execution_location、provider_version、created_at、expires_at、retryable
```

`ProviderRouter` 的选择不由 LLM 临时决定。LLM 可以提出逻辑能力请求，但不能指定绕过策略的本地函数、云端 URL、MCP Server 或设备协议。

不同硬件能力采用不同的输入形态：

- 摄像头是受授权的 `observation.request`；
- 语音是 `SpeechInput` 和 `SpeechEffect`；
- 蓝牙只是本地 `DeviceTransport`，不暴露给模型；
- 陀螺仪、姿态和行走状态优先作为本地 `MotionEvent` 事件流进入 `EventNormalizer`，不作为模型按需读取的原始 Tool。

## 4. Agent 生命周期

### 4.1 待机和唤起

唤起源包括：

- 实体按键；
- 唤醒词或语音入口；
- 当前任务的用户回答；
- 导航接近路口、到达目的地等系统事件；
- 设备连接、断开或错误事件。

唤起阶段只做本地判断：是否已有活动会话、是否正在播报、是否需要中断、是否允许主动观察。唤起不应触发通用 LLM 调用。

### 4.2 输入采集和事件标准化

所有输入转换为统一事件：

```text
user.speech_final
button.short_press
button.long_press
navigation.approaching_intersection
navigation.arrived
observation.completed
device.disconnected
timer.expired
```

事件至少包含：

```text
event_id、source、timestamp、session_id、sequence、payload、confidence
```

事件必须可去重、可排序、可回放；设备和 Provider 的原始事件不得直接进入策略层。

### 4.3 会话创建和分类

Agent 对输入分类为：

```text
new_task       新任务
continue       继续当前任务
interrupt      打断当前播报/动作
repeat         重播上一结果
cancel         取消当前任务
clarification  回答待确认问题
system_alert   系统主动事件
```

会话至少保存：

- 当前阶段和活动任务；
- 当前组合 Skill 及子任务；
- 最近确认事实及有效期；
- 用户主动观察授权和可撤销状态；
- 待确认问题；
- 当前资源锁和未完成动作；
- 最近播报和可重播结果。

### 4.4 能力路由

路由采用风险优先的候选选择：

```text
高优先级安全事件
  → 当前会话上下文
  → 特化 Domain Skill
  → 通用 Domain Skill
  → 澄清或重试
  → 高风险时采取保守处置
```

这不是所有请求都经过的线性流水线：

- 明确的“取消”“重播”“读菜单”走确定性路径；
- “看看那里怎么了”才需要结合上下文或由 LLM 在候选能力中选择；
- “前面能不能过马路”不能因为没有特化能力就退回普通场景描述。

### 4.5 任务计划

能力路由产生结构化 `TaskPlan`，而不是直接发设备命令：

```text
TaskPlan
├─ goal
├─ skill_id
├─ steps
├─ required_tools
├─ required_resources
├─ max_rounds
├─ timeout
├─ interrupt_rules
├─ confirmation_rules
└─ fallback
```

组合 Skill 通过有限任务图表达：

```text
navigation_to_restaurant
  ├─ navigation
  ├─ crossing_advisory (事件触发)
  ├─ entrance_finding (到达触发)
  └─ menu_reading (用户请求触发)
```

支持四类关系：

- 顺序：导航 → 找入口 → 读菜单；
- 并行：导航状态监听 + 设备连接监测；
- 抢占：障碍物或危险提醒中断普通播报；
- 回退：特化识别失败后重试、澄清或请求协助。

任务图必须有最大轮数、截止时间、资源锁和终止条件，禁止无限 Tool Loop。

### 4.6 策略和安全检查

PolicyGuard 在每个副作用动作前检查：

- 当前会话和 Skill 是否允许该动作；
- 用户授权是否存在且未撤销；
- 摄像头、麦克风、定位和网络权限；
- 事实是否新鲜、方向是否匹配、置信度是否足够；
- 是否与其他任务争用摄像头或播报资源；
- 是否需要用户确认；
- 是否超过超时、重试或冷却限制。

P0 支持两种观察授权模式：

```text
explicit_per_request：每次观察前请求用户确认
session_preapproved：用户在会话开始时预授权，仍受冷却、资源和策略限制
```

具体产品默认模式另行确定；两种模式都必须经过同一 PolicyGuard。

### 4.7 Tool/Provider 执行

ToolGateway 是唯一的执行入口：

```text
校验 Tool ID 和 Schema
  → 检查授权、状态、幂等键和截止时间
  → ResourceManager 获取资源
  → ProviderRouter 选择本地/远程/回放 Provider
  → 调用 Provider
  → 归一化 ToolResult / DomainEvent / Fact
  → 写入审计记录
```

只读 Tool 可在资源不冲突时并行；副作用 Tool 默认串行。摄像头、麦克风、播报通道等资源必须有显式锁和释放路径。远程 Provider 的网络重试、异步回调和断线恢复由 Provider Adapter 负责，不泄漏到 Domain Skill。

### 4.8 结果决策

Provider 结果先经过 ResultNormalizer，再由 DecisionEngine 分类：

```text
succeeded
partial
needs_retake
needs_confirmation
cannot_determine
failed
```

`cannot_determine` 不是系统崩溃，而是一个合法的安全结果。

通用能力不确定时，可以：

- 请求重新拍摄或调整方向；
- 澄清用户目标；
- 说明当前看不清。

高风险能力不确定时，不得用普通描述冒充安全结论，必须采取保守处置：

- 提示用户停下或等待确认；
- 重试特化观察；
- 必要时请求身边的人或预设协助人确认。

### 4.9 反馈和继续

EffectCompiler 将结构化结果转换为：

- 语音播报；
- 提示音或震动；
- 眼镜设备命令；
- 等待用户的下一事件。

支持即时反馈、完整结果、重播、打断和追问。远程识别耗时较长时，先提供等待反馈，不让用户静默等待。

### 4.10 收尾和恢复

会话结束原因统一为：

```text
completed
cancelled
timeout
failed
device_disconnected
user_requested_help
```

收尾必须：

- 停止当前采集和推理；
- 释放资源锁；
- 关闭或暂停主动监测；
- 保存结构化摘要和最终播报；
- 记录未完成动作和恢复可能性。

设备断开或进程重启后，恢复流程必须先读取最后一个已确认状态，不能把未知状态当成成功，也不能无条件重复副作用动作。

## 5. LLM 边界

### LLM 可以做

- 将语音归一化为目的地、查询、确认、取消或追问意图；
- 在已注册 Skill 候选中提出选择；
- 补全结构化参数；
- 根据结构化事实生成澄清问题和自然语言摘要；
- 提出有限的 `TaskPlan` 候选。

### LLM 不可以做

- 创建未注册的 Skill 或 Tool；
- 直接调用摄像头、蓝牙、地图、网络或 TTS；
- 伪造事实、设备事件或导航状态；
- 绕过用户授权、资源锁和 PolicyGuard；
- 把未知、过期或低置信度结果改写成确定结论；
- 输出“可以安全通行”等不可验证的绝对保证。

## 6. P0 场景验收

模拟器必须覆盖：

1. 实体按键唤起并开始目的地语音输入；
2. 地点候选不唯一时澄清并确认；
3. 创建导航会话并接收接近路口事件；
4. 在显式确认或会话预授权下启动一次交通观察；
5. 红绿灯结果成功、低置信度、重拍和无法确认四种分支；
6. 到达餐厅后切换到入口寻找；
7. 用户请求菜单读取并进行一次追问；
8. 用户重播、取消和打断当前播报；
9. 设备断开、Provider 超时和会话恢复；
10. 完整事件、计划、策略判定、Tool 结果和播报可回放。
11. 同一观察合同分别由 `RecordedProvider`、本地替身和远程异步替身执行时，Agent 行为和领域结果保持一致；回放不访问真实网络或 MCP 服务。

P0 不要求：

- 真实 Rokid CXR/JSUI 运行时；
- 真实 Android 后台服务；
- 真实地图账号和在线 VLM；
- 无限开放式主动监测；
- 将通用观察当作安全通行判断。

## 7. 代码和合同调整方向

建议新增或收敛为：

```text
packages/contracts/agent/
├─ event.schema.json
├─ session-state.schema.json
├─ skill-manifest.schema.json
├─ task-plan.schema.json
├─ decision.schema.json
└─ effect.schema.json

packages/domain/agent/
├─ agent-harness.ts
├─ session-orchestrator.ts
├─ event-normalizer.ts
├─ context-builder.ts
├─ fast-path-router.ts
├─ core-agent.ts
├─ plan-builder.ts
├─ policy-guard.ts
├─ resource-manager.ts
├─ task-runner.ts
├─ decision-engine.ts
├─ effect-compiler.ts
└─ session-closer.ts

packages/domain/skills/
├─ generic/
├─ traffic-signal-advisory/
├─ obstacle-warning/
├─ entrance-finding/
├─ menu-reading/
└─ composite/

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
├─ ocr/
├─ vlm/
└─ storage/
```

现有 `core-agent-design.md` 中的 `capability_id`、ToolGateway、SafetyGuard 和回放原则继续保留，但需要把“能力注册”明确为 Domain Skill 注册，把 Provider 绑定改为全局 ProviderRouter/ProviderGateway 的能力需求解析。现有 `tool-system.md` 需要补充本地、远程、MCP 适配器和统一调用上下文的说明。

## 8. 未在本规格中决定的事项

- 真实设备上采用按次确认还是会话预授权作为默认模式；
- 具体 Android 后台生命周期和系统权限；
- 云端、手机端和眼镜端的 Provider 部署位置；
- 各模型 Provider 的具体延迟和成本预算；
- 人工协助服务的具体通道。

这些事项不应阻塞模拟器中的 Agent Harness P0 验证。
