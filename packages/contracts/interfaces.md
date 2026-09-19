# 模块接口草案

以下是跨实现的接口形状。参数和错误语义必须保持稳定，具体语言实现放在对应 `apps/` 或 `packages/` 中。

## DeviceTransport

```text
connect() -> TransportStatus
disconnect() -> void
sendCommand(command: DeviceCommand) -> SendResult
sendBinary(transfer: BinaryTransfer) -> SendResult
subscribe(listener: DeviceEventListener) -> Unsubscribe
```

实现：`SimulatorTransport`、`RokidCxrAdapter`、未来的 `DirectCxrAdapter`。

## NavigationProvider

```text
start(destination: Destination) -> NavigationStartResult
stop() -> void
currentState() -> NavigationState
subscribe(listener: NavigationEventListener) -> Unsubscribe
```

地图适配器只能产生导航事实，不负责播报和 Agent 决策。

## ObservationProvider

```text
observe(request: ObservationRequest) -> ObservationResult
```

结果必须包含任务类型、摘要、置信度、是否需要重拍和可选风险/文字/实体信息。

## SpeechOutput

```text
enqueue(effect: SpeechEffect) -> SpeechHandle
cancel(source: string) -> void
repeat(handle: SpeechHandle) -> void
```

所有语音必须经过统一队列；模型和地图适配器不能直接播放音频。

## SessionOrchestrator

```text
handle(event: DomainEvent) -> DomainEffect[]
snapshot() -> SessionSnapshot
restore(snapshot: SessionSnapshot) -> void
```

这是领域核心的唯一外部入口。它不创建 Android、JSUI、CXR 或模型客户端，而是通过依赖注入接收适配器。
