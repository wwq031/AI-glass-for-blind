# Agent Tool Loop

计划实现文件：

- `context-builder.ts`：从会话快照、事件、事实、授权和 Tool Catalog 构造 `AgentLoopInput`；
- `tool-catalog.ts`：按状态、能力和权限筛选模型可见 Tool；
- `tool-loop.ts`：执行有限步数的事件-决策-工具-结果循环；
- `tool-gateway.ts`：统一做 Schema、授权、幂等、超时和 Provider 调用；
- `consent-gate.ts`：检查拍摄、上传和主动观察授权；
- `safety-guard.ts`：检查事实有效期、置信度、冲突和不可覆盖规则；
- `model-advisor.ts`：可选模型顾问，只返回结构化 `AgentPlan` 候选；
- `audit-log.ts`：记录 ToolCall、ToolResult 和拒绝原因。

Agent 不直接导入 Android、JSUI、CXR、地图或模型 SDK。Tool Gateway 也不向模型暴露底层设备 Tool。
