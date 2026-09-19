# 系统架构

## 分层

### 设备端

`apps/glasses-agent` 是运行在 Rokid JSUI 上的薄客户端，负责实体键、摄像请求、设备状态和语音/音频反馈。它不保存长期任务逻辑，也不决定导航路线。

### 手机端

`apps/phone-companion` 是第一阶段的业务中心，负责语音输入、短语音目的地搜索、POI 确认、地图 SDK、定位质量、导航事件、系统提醒、会话持久化和 `DeviceTransport`。

### 领域核心

`packages/domain` 负责会话状态机、观察策略、风险优先级和语音仲裁。它只消费和产生合同中的事件与效果。

### 供应商适配器

`packages/providers` 约束地图、视觉、OCR 和播报的最小接口。高德、Coze、云端 VLM、手机端 VLM 和模拟器均通过适配器接入。

### 可选网关

`apps/gateway` 只负责模型调用、鉴权、图片校验、超时和结果归一化。第一阶段可以把它作为手机内的模块，不要求独立部署。

## 关键数据流

```text
physical_key / speech_input
        ↓
InteractionEvent
        ↓
SessionOrchestrator
        ├── NavigationProvider → NavigationEvent → ReminderPolicy
        ├── ObservationProvider → ObservationResult
        └── SpeechOutput ← SpeechEffect
        ↓
DeviceTransport → glasses-agent
```

## 无屏交互原则

所有需要表达语义的交互都以语音为主：目的地搜索、候选确认、菜单追问、重复播报、取消、帮助和表情辅助请求。实体键只承担无需看屏幕即可可靠完成的动作：唤起、拍摄确认、重拍、暂停/打断和紧急取消。任何语音输入都先归一化为 `SpeechInput`，再进入会话状态机。

## 状态机

```text
idle
 → destination_input
 → destination_confirm
 → navigating
 → approaching_destination
 → entrance_check
 → inside_restaurant
 → menu_reading
 → conversation_assist
 → completed
```

任何状态都可以转入 `cancelled`、`device_disconnected`、`navigation_unavailable` 或 `vision_timeout`，但错误恢复必须通过结构化事件回到可播报状态。

## 适配器接缝

### DeviceTransport

手机与眼镜之间的唯一业务接缝。第一实现可以调用 Rokid 官方 CXR/系统通道；测试使用 `SimulatorTransport`。领域核心不能出现 CXR 类名。

### NavigationProvider

输出开始、接近转弯、偏航、重规划、GPS 弱、到达和停止等事件。地图 SDK 的高频 GPS 采样留在手机内部，不上传给 Agent。

### ObservationProvider

输入任务类型、图片引用和会话上下文，输出结构化观察结果。路口任务只输出信号灯、方向、斑马线和车辆等可见事实；模型不能直接写入会话状态。

### 路口协作

导航产生 `navigation.intersection_approaching` 后，领域层播报检查提示并等待用户按键或语音确认。确认后才发送 `capture.requested`，图像通过 `MediaTransfer` 引用进入 `ObservationProvider`。`CrossingAdvisoryPolicy` 将观察事实和导航上下文合成为短时、保守的辅助建议；未知或过期结果统一进入等待/重查，不输出安全保证。

### SpeechOutput

接收已经经过 `SpeechPolicy` 仲裁的 `SpeechEffect`，负责实际 TTS 或音频播放。

## 故障降级

| 故障 | 保留能力 | 用户提示 |
|---|---|---|
| 云端模型超时 | 导航和系统提醒 | “识别服务暂时不可用，请稍后重拍。” |
| 地图失联 | 用户主动拍摄和基础识图 | “导航暂时不可用，当前观察仍可使用。” |
| CXR 断连 | 手机本地会话和状态记录 | “眼镜连接中断。” |
| 图片低置信度 | 重拍流程 | “画面不清楚，请调整方向后重拍。” |
| 语音冲突 | 高优先级风险/导航提醒 | 低优先级详情进入稍后查询 |
