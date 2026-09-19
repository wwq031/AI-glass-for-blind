# domain

设备和供应商无关的核心模块。

建议拆分：

- `session-state-machine`：会话状态和合法转移；
- `agent`：事件归一化、工作记忆、能力规划、策略、安全闸门和行动计划；
- `navigation`：导航上下文和路线状态；
- `observation`：观察请求、结果和重拍策略；
- `reminder`：确定性系统提醒规则；
- `speech-priority`：风险、导航、观察和详情的语音仲裁；
- `privacy`：用户确认、表情辅助和媒体生命周期规则。

核心模块只依赖合同类型和注入的接口，不能导入 Android、JSUI、CXR、地图 SDK 或模型 SDK。

核心 Agent 的边界和决策循环见 [`docs/architecture/core-agent-design.md`](../../docs/architecture/core-agent-design.md)。模型只能提出结构化计划候选，不能直接执行设备或 Provider 调用。
