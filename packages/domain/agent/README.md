# Agent Harness P0

## 状态与范围

P0 的设备无关 Agent 核心已实现，并通过录制计划、模拟 Tool 结果和场景回放完成离线验证。它还不是可直接部署的完整助盲产品：真实 Rokid/CXR、Android 手机服务、地图 SDK、语音服务，以及本地或云端模型 Provider 尚未完成端到端接入。

本目录负责把一次输入事件转成受校验的下一步行动，不负责直接操作 Android、JSUI、CXR、地图或模型 SDK。设备与服务适配器通过稳定接口接入，不能把厂商 SDK 类型传入领域核心。

## 核心处理流程

```text
AgentEvent + 会话视图 + Skill 注册表
  → ContextBuilder 脱敏并构造 LLM 上下文
  → LlmAgent 生成 AgentPlan
  → PlanValidator 校验计划、Skill、Tool、授权与风险边界
  → TaskRunner 通过 ToolGateway 按序执行逻辑调用
  → 返回 ToolResult、Effect 和可选 followUpEvents
  → 调用方显式提交 followUpEvents，再次调用 handle()
```

`SessionOrchestrator.handle(event, permissions?)` 是唯一 Agent 核心入口。一次 `handle` 只处理一个 Event；Tool 结果不会在核心内部自动触发下一轮模型调用。调用方负责维护会话序号，并将返回的 `followUpEvents` 原样提交为后续输入。

LLM 根据当前目标组合已注册的 Skill，而不是选择固定餐厅流程。Tool 和 Provider 是稳定的可注入能力；普通场景扩展优先通过注册数据和 Provider 完成，不在 Agent 核心里按场景增加分支。

## 核心文件

| 文件 | 职责 |
| --- | --- |
| `session-orchestrator.ts` | 协调单次 Agent 回合、会话序号、待处理反馈、中断和路口上下文。 |
| `context-builder.ts` | 构造传给模型的上下文，并移除二进制媒体和敏感字段。 |
| `llm-agent.ts` | 定义模型规划端口和模型可见的 Agent 输入。 |
| `plan-validator.ts` | 验证计划结构、Skill/Tool 权限、能力兼容性和观察授权。 |
| `task-runner.ts` | 按序调用逻辑 Tool，并把执行结果作为 ToolResult 返回。 |
| `tool-gateway.ts` | Agent 核心使用的逻辑 ToolGateway 接口。 |
| `contract-codecs.ts` | 在跨进程 snake_case JSON 合同和运行时 camelCase 类型之间编解码。 |
| `navigation-trigger.ts` | 验证导航路口触发事件并建立临时路口上下文。 |
| `../../policies/crossing-advisory.ts` | 仅基于有效视觉事实生成保守的路口辅助提示。 |
| `../../tools/tool-gateway.ts` | 将逻辑调用映射到注册的 Tool 和执行网关。 |
| `../../skills/skill-registry.ts` | 提供运行时 Skill 注册表。 |

回放测试位于 `tests/domain/`、`tests/contracts/` 和 `tests/scenarios/`；录制模型、录制 ToolGateway 与设备模拟器位于 `packages/testkit/`。

## 安全与授权边界

- 模型输出的是候选 AgentPlan，不是事实或设备命令。计划只能请求允许的逻辑 Tool、播报、等待或结束；模型不能直接下发原始设备命令，也不能直接启动导航。
- `observation.request` 只是观察请求。执行需要明确或预先授权；执行来源和 consent 由 Harness 提供，不能由模型参数自行提升权限。
- `navigation.intersection_approaching` / `navigation.crosswalk_approaching` 只建立路口任务上下文，不等于拍摄许可，也不表示可以通行。默认只接受最近 15 秒内、时间戳与路口 ID/朝向/距离均有效的触发事件；无效事件不会进入模型或请求摄像头。
- 路口提示由 CrossingAdvisoryPolicy 根据同一次观察的有效 Provider 事实生成。缺少事实、事实过期、方向不匹配或风险不明时必须保守处理。所有结论都只是辅助信息，不保证过街安全。
- Capability 的 `compatible_skills` 缺席表示不限制 Skill；字段存在时表示精确允许名单，空数组拒绝所有 Skill。该字段不授予观察授权。

## 合同边界

跨进程数据使用带 `schema_version` 的 snake_case JSON；Agent 内部使用 camelCase 类型。外部适配器使用 `decodeAgentPlan`、`decodeAgentEvent` 和 `decodeEffect` 读取合同对象，使用 `encodeAgentEvent` 和 `encodeEffect` 输出事件或效果。Event 的 wire `source` 会归一为运行时语义来源，同时保留原始 `sourceDetail` 供往返编码。

合同定义和端口接入说明见 [`packages/contracts/README.md`](../../contracts/README.md) 与 [`packages/contracts/interfaces.md`](../../contracts/interfaces.md)。不要在 Provider 输出里传供应商 SDK 对象、原始二进制或模型无法验证的设备状态。

## 本地验证

在仓库根目录运行：

```bash
pnpm test
pnpm typecheck
pnpm validate:contracts
```

本次 P0 离线验收中，测试为 124/124 通过；类型检查通过；合同校验通过（33 个 Schema、10 个示例、5 个能力、8 个 Skill、7 个 Tool）。测试使用录制/模拟依赖，不代表真机或真实服务已接通。

## 接入前必读

1. 阅读 [`跨模块接口`](../../contracts/interfaces.md) 与 [`核心 Agent 设计`](../../../docs/architecture/core-agent-design.md)，确认输入、结果、策略和效果的边界。
2. 阅读 [`场景回放`](../../../docs/scenarios/golden-path-navigation-restaurant.md) 和 [`团队交付矩阵`](../../../docs/team/file-delivery-matrix.md)，按模块实现对应 Provider 或设备适配器。
3. 用录制或模拟 Provider 为适配器补充合同测试；只有在真实设备、地图或模型集成通过独立验收后，才能更新“已接入”状态。
