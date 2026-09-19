# 声明式领域策略

领域策略消费 `ObservationResult.facts`、导航上下文和用户授权，不按场景在 `SessionOrchestrator` 中堆叠 `if/else`。

策略输出统一的 `DomainEffect` 或 `CrossingAdvisory`，语音文本由能力注册表引用的模板生成。

安全相关策略可以包含不可被模型覆盖的保守规则，例如：事实过期、方向未知、信号不可见或置信度过低时只能输出等待/重查。
