# 跨模块接口与接入边界

以下接口分为已实现的 P0 Agent 核心端口与待接入的真实设备/服务适配器。接口草案不代表 Rokid、地图、语音或视觉 SDK 已完成集成。参数和错误语义必须保持稳定，具体语言实现放在对应 `apps/` 或 `packages/` 中。

## Agent 核心：Event → Plan → Result → Effect

`packages/domain/agent/session-orchestrator.ts` 中的 `SessionOrchestrator.handle(event: AgentEvent, permissions?)` 是 P0 Agent 核心的唯一入口，异步返回 `effects`、`results`、可选 `rejection` 与 `followUpEvents`。调用方维护事件序号，并显式把 `followUpEvents` 作为下一次输入；工具结果不能越过这一步直接变成新的模型结论。拒绝的计划也会生成反馈 Event。取消、求助与设备断开可优先打断待处理计划；已经发送到外部的调用不保证撤销。

`LlmAgent` 只接收脱敏的 Event、会话视图、Skill 列表和近期结果，并返回结构化 `AgentPlan`。模型可组合注册的 Skill，但 `PlanValidator` 和策略界定能否执行：模型不能自行调用 `navigation.start`；观察需明确或预先授权，执行时以策略来源进入 ToolGateway；路口安全建议仅由确定性 `CrossingAdvisoryPolicy` 基于有效 Provider 事实生成。导航路口 Event 只建立上下文，不等于拍摄许可。

`ToolGateway` 是逻辑调用合同，而不是某一种蓝牙、地图或云端 SDK。核心依赖注入该合同；已有 `ConcreteToolGatewayAdapter` 负责映射到 Tool 注册表与执行网关。`LocalProvider`、`RemoteProvider`、`McpProviderAdapter` 和 `RecordedProvider` 是核心外 `ProviderRouter` 的实现选择，不是 Agent 核心内必须同时启动的组件。当前离线验收使用录制的 LLM 计划和 Tool 结果；真实本地、远程或 MCP 适配器尚需分别接入和验证。

### JSON 合同与运行时类型

跨进程 JSON 使用 `snake_case` 和 `schema_version`；Agent 内部的 `AgentPlan`、`AgentEvent`、`Effect` 使用 `camelCase`。端口适配器必须在进入 Agent 前调用 `decodeAgentPlan` / `decodeAgentEvent`，并在向外发送事件或效果前调用 `encodeAgentEvent` / `encodeEffect`；需要把 Effect JSON 读入运行时时可调用 `decodeEffect`。编解码器拒绝未知字段和不符合字段类型的值；`schema_version` 当前解码时检查主次版本格式，编码时省略参数则写入 `1.0`。计划的 `response_draft` 是可选字段，转换后保存在 `responseDraft`。

Event 的 wire `source` 表示产生事件的设备或子系统；Agent 内的 `EventSource` 是较粗的语义分类。归一规则为：`glasses`、`phone`、`transport`、`device` → `device`；`speech`、`user` → `user`；`navigation`、`motion` 保持原类；`vision`、`provider` → `provider`；`agent`、`system`、`storage`、`simulator` → `system`。解码后将原 wire 值保存在 `sourceDetail`，所以未修改的事件重新编码时会保留精确来源；新建事件没有该字段时，编码器使用 `user`、`device`、`navigation`、`motion`、`provider` 或 `system` 作为规范语义来源。编码器会拒绝与归一类别不匹配的 `sourceDetail`。`trace_id` 在运行时映射为 `traceId` 并在编码时保留。

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
supports(capabilityId: string) -> boolean
```

请求使用能力注册表中的 `capability_id`，而不是在核心接口中增加场景枚举。结果必须包含状态、摘要、聚合置信度、是否需要重拍和 `facts[]`。路口能力可以通过 `traffic_signal.state`、`traffic_signal.direction_match`、`crosswalk.present` 等事实返回信号灯、方向、斑马线和车辆信息；这些是视觉事实，不是安全保证。

## CrossingAdvisoryPolicy

```text
advise(observation: ObservationResult, navigation: NavigationContext) -> CrossingAdvisory
```

策略通过事实名称匹配规则，不依赖某个 Provider 的专用字段。只允许输出 `wait`、`recheck`、`proceed_with_caution` 或 `cannot_determine`。图像过期、方向不匹配、低置信度、车辆风险或信号灯不可见时不得输出确定性的可通行结论。

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

## 应用/会话层 SessionOrchestrator 草案

```text
handle(event: DomainEvent) -> DomainEffect[]
snapshot() -> SessionSnapshot
restore(snapshot: SessionSnapshot) -> void
```

这是应用/会话层的早期接口草案，不要与上面已经实现的 `packages/domain/agent/session-orchestrator.ts` 混为一谈。Agent 核心 `handle` 的实际输入、输出和异步语义以上面的 P0 合同为准。两层都不应直接创建 Android、JSUI、CXR 或模型客户端，而应注入适配器。
