# Agent Harness P0

SessionOrchestrator.handle(Event) is the only Agent-core entrypoint.
It returns validated Effects, ToolResults, and a rejection when a Plan cannot execute.
It never imports Android, JSUI, CXR, map SDK, or model SDK code.

这里的 `Event` 指统一的 `AgentEvent`；实际调用是异步的 `handle(event, permissions?)`。P0 核心已实现，但目前由录制的模型计划和 Tool 结果进行离线回放，不能据此宣称真机、地图、视觉模型或云端服务已经接通。

## 一次处理的边界

```text
AgentEvent + 会话视图 + Skill 注册表
  → 构造脱敏上下文 → LlmAgent 生成 AgentPlan
  → PlanValidator 校验 Skill、Tool、权限和高风险边界
  → TaskRunner 经逻辑 ToolGateway 执行 → ToolResult / Effect
  → 调用方显式提交 followUpEvents，进入下一次 handle
```

LLM 可以按当前目标组合已注册的 Skill，而不是选择一条预置的“去餐厅流程”。Tool 和 Provider 不因场景切换而重新注册；Skill 声明能力依赖，但不等于模型拥有底层调用权限。`navigation.start` 不可由模型计划直接执行。`observation.request` 仅是模型提出的观察请求；取得明确或预先授权后，由策略来源调用。任何观察提醒本身都不代表拍摄许可。

`handle` 一次只处理一个 Event，不在内部无限递归调用模型。工具执行后的标准结果作为 `followUpEvents` 返回；调用方须按 `sessionId` 和 `sequence` 提交回放，不能自行篡改待处理结果。重复或过期 Event 被拒绝；`user.cancel`、`user.help_requested`、`device.disconnected` 可抢占待处理反馈。取消会阻止后续计划效果，但不能保证已发给外部设备的调用被撤回。

接近路口的导航 Event 提供方向、距离及 `crossing_advisory` 上下文，不等于立即拍摄或判定通行。只有取得观察授权、产生有效的 `vision.traffic_signal` ToolResult 后，路口安全建议才由 `CrossingAdvisoryPolicy` 生成；模型不得给出可通行结论。策略仍只是辅助提醒，不保证过街安全。

## 外部接入

`LlmAgent` 和 `ToolGateway` 是核心依赖注入端口。`ConcreteToolGatewayAdapter` 可把核心调用映射到现有 Tool 注册表与执行网关；实际 ProviderRouter 可按工具选择本地、远程、MCP 或录制 Provider。这些适配器及 Android/JSUI/CXR、地图 SDK、语音和视觉模型集成均在核心之外。P0 的 `RecordedLlmAgent`、`RecordedToolGateway` 只用于无网络、无真机的确定性验收。

核心接口与授权边界参见 [跨模块接口](../../contracts/interfaces.md)；离线路口回放参见 [navigation-crossing-replay.test.ts](../../../tests/scenarios/navigation-crossing-replay.test.ts)。
