# 能力注册表

观察任务不通过核心代码里的固定枚举扩展，而是通过 `registry.json` 注册能力。`registry.json` 使用 `../schemas/capability-registry.schema.json` 校验；每个能力声明自己的请求约束、结果事实、Provider、领域策略和语音模板。

## 新增能力的规则

1. 分配一个命名空间 ID，例如 `vision.bus_stop` 或 `ocr.medication_label`。
2. 复用 `ObservationRequest` 和 `ObservationResult` 基础合同。
3. 只有新增事实时，才在本目录增加能力专属结果 Schema。
4. 只有行为规则不同，才增加领域策略；不要在 `SessionOrchestrator` 中增加场景分支。
5. 通过通用场景回放器增加 JSON/YAML 夹具，不复制一套测试代码。

`vision.traffic_signal`、`vision.entrance`、`vision.menu`、`vision.expression` 和 `vision.scene` 是当前 P0 的初始注册项。

语音模板放在 `voice/`，由 `SpeechEffect` 生成器按能力和结果状态选择，不在 Provider 中拼接最终播报文本。
