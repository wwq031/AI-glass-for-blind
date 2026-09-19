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
search(query: DestinationQuery) -> DestinationCandidate[]
confirm(candidateId: string) -> Destination
start(destination: Destination) -> NavigationStartResult
stop() -> void
currentState() -> NavigationState
subscribe(listener: NavigationEventListener) -> Unsubscribe
```

目的地搜索、候选确认和路线导航都由语音驱动；手机屏幕不是必经步骤。地图适配器只能产生导航事实，不负责播报和 Agent 决策。

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

## SpeechInput

```text
start(mode: SpeechInputMode) -> ListeningHandle
stop(handle: ListeningHandle) -> void
subscribe(listener: SpeechInputListener) -> Unsubscribe
```

手机或眼镜麦克风产生的原始语音，必须先转换为带有 `transcript`、`confidence`、`locale` 和 `intent_hint` 的统一输入事件，再交给领域层。目的地搜索、候选确认、菜单追问、重复、取消和表情辅助请求都走这条接口。

## SessionOrchestrator

```text
handle(event: DomainEvent) -> DomainEffect[]
snapshot() -> SessionSnapshot
restore(snapshot: SessionSnapshot) -> void
```

这是领域核心的唯一外部入口。它不创建 Android、JSUI、CXR 或模型客户端，而是通过依赖注入接收适配器。
