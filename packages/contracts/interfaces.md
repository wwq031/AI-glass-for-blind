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

`DeviceCommand` 和 `DeviceEvent` 使用对应 Schema。P0 必须覆盖连接/断开、按键、拍摄完成/失败和播报完成/失败；原始二进制通过 `MediaTransfer` 引用，不直接放入事件。

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

路口协作使用 `navigation.intersection_approaching` 或 `navigation.crosswalk_approaching` 事件。导航只说明“接近哪里、距离多远、行进方向是什么”，不直接判断能否通行。

## ObservationProvider

```text
observe(request: ObservationRequest) -> ObservationResult
```

结果必须包含任务类型、摘要、置信度、是否需要重拍和可选风险/文字/实体信息。路口类观察还可以返回信号灯状态、方向是否匹配、斑马线、车辆活动和结果有效期；这些是视觉事实，不是安全保证。

## CrossingAdvisoryPolicy

```text
advise(observation: ObservationResult, navigation: NavigationContext) -> CrossingAdvisory
```

只允许输出 `wait`、`recheck`、`proceed_with_caution` 或 `cannot_determine`。图像过期、方向不匹配、低置信度、车辆风险或信号灯不可见时不得输出确定性的可通行结论。

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

手机或眼镜麦克风产生的原始语音，必须先转换为带有 `input_id`、`session_id`、`occurred_at`、`transcript`、`confidence`、`locale`、`is_final` 和 `intent_hint` 的统一输入事件，再交给领域层。目的地搜索、候选确认、菜单追问、重复、取消和表情辅助请求都走这条接口。

## DestinationSearch

```text
search(query: DestinationQuery) -> DestinationCandidates
confirm(candidateId: string) -> Destination
```

`DestinationQuery` 和 `DestinationCandidates` 使用对应 Schema；候选必须带稳定的 `candidate_id`，确认只提交 ID，不把地图 SDK 对象传给领域层。

## SessionSnapshot / ContractError

会话恢复使用 `SessionSnapshot`，跨模块失败使用 `ContractError`。错误必须说明来源、错误码、是否可重试和建议的用户动作，不能只传自由文本。

## SessionOrchestrator

```text
handle(event: DomainEvent) -> DomainEffect[]
snapshot() -> SessionSnapshot
restore(snapshot: SessionSnapshot) -> void
```

这是领域核心的唯一外部入口。它不创建 Android、JSUI、CXR 或模型客户端，而是通过依赖注入接收适配器。
