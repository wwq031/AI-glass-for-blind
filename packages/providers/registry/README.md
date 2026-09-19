# Provider 注册

Provider 按 `capability_id` 声明支持的能力，并统一实现：

```text
supports(capabilityId: string) -> boolean
observe(request: ObservationRequest) -> ObservationResult
```

新增场景优先通过注册能力和 Provider 路由接入；只有引入新的模型、传感器或外部服务时才新增实现代码。

Tool 注册表位于 `tool-registry.json`。它区分模型可见、策略可见和内部工具；模型不可见 `device.send_command`、`device.capture`、`cxr.send_packet` 等底层工具。
