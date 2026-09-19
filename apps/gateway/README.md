# gateway

可选的模型网关模块。第一阶段可以嵌入手机伴侣，稳定后再独立部署。

## 负责

- 请求鉴权和图片引用校验；
- 调用 Coze、云端 VLM 或 OCR；
- 将供应商响应归一化为 `observation-result.schema.json`；
- 超时、重试和模型不可用降级；
- 不保存超出会话需要的原始媒体。

网关不控制摄像头、地图和系统提醒，也不替代 `SessionOrchestrator`。
