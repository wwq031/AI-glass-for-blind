# Tool 系统设计

Tool 是 Agent 与系统能力之间的受控合同，不是把 Android、CXR、地图 SDK 或摄像头函数直接暴露给模型。

## 分层

```text
Model / Policy
    ↓ 仅可见白名单 Tool
ToolGateway
    ↓ Schema、授权、幂等、超时、审计
Provider / Device Adapter
    ↓
地图、视觉、眼镜、TTS、存储
```

ToolGateway 是唯一执行入口。模型输出 `ToolCall`，Gateway 校验后才调用 Provider；Provider 的返回值统一成为 `ToolResult`、`DomainEvent` 或 `Fact`。

## Tool 定义

每个 Tool 在 [`packages/providers/registry/tool-registry.json`](../../packages/providers/registry/tool-registry.json) 中注册，至少包含：

- `tool_id` 和版本；
- `exposure`：`model`、`policy` 或 `internal`；
- `operation`：只读、请求或副作用；
- 风险等级和是否需要授权；
- 输入/输出 Schema；
- 超时、重试和允许状态；
- 执行后产生的事件。

模型只能看到 `exposure=model` 的白名单。设备底层调用始终是 `internal`。

## P0 Tool

| Tool | 公开给 | 作用 |
|---|---|---|
| `speech.ask_user` | 模型 | 提出确认、澄清、重拍或取消问题 |
| `navigation.search_destination` | 模型 | 将语音目的地转换为候选 POI |
| `navigation.confirm_destination` | 模型 | 提交用户确认的候选 ID |
| `navigation.start` | 策略 | 在目的地确认后启动导航 |
| `observation.request` | 策略 | 经过授权后完成拍摄、媒体传输和能力分析 |
| `facts.query` | 模型 | 查询当前会话已确认的事实 |
| `session.cancel` | 模型 | 取消当前任务 |

模型不能直接使用 `device.capture`、`device.send_command`、`cxr.send_packet`、任意网络请求或任意 TTS。

## Tool 调用生命周期

```text
构造 AgentLoopInput
  → 生成 ToolCall
  → 校验 tool_id、版本和 arguments
  → 检查会话状态、授权、设备和有效期
  → 检查幂等键和副作用风险
  → 串行执行副作用 Tool / 并行执行只读 Tool
  → 归一化为 ToolResult
  → 写入 DomainEvent、Fact 和审计日志
  → 进入下一轮或等待用户
```

单轮只允许一个副作用 Tool；只读 Tool 才允许并行。所有调用都有 `call_id`、`idempotency_key` 和截止时间。

## 观察 Tool 的高层边界

模型调用：

```json
{
  "tool_id": "observation.request",
  "arguments": {
    "capability_id": "vision.traffic_signal",
    "capture_mode": "single_frame",
    "consent": "explicit",
    "context": {
      "intersection_id": "intersection-001",
      "travel_heading_deg": 90
    }
  }
}
```

Gateway 内部完成：

```text
授权检查
→ capture.requested
→ 等待 MediaTransfer
→ 按 capability_id 路由 Provider
→ 校验 ObservationResult.facts[]
→ 返回 ToolResult
```

模型不负责拼接摄像头命令、媒体引用或供应商请求。

## 结果和错误

成功结果必须携带 `status=succeeded` 或 `partial`，以及结构化 `facts` 和产生的事件。失败结果必须携带 `ContractError`，说明：

- 错误码；
- 是否可重试；
- 建议的用户动作；
- 是否已经产生副作用。

副作用 Tool 默认不自动重试；只有明确幂等且策略允许的查询可以重试。

## 路口示例

```text
navigation.intersection_approaching
  → policy 使用 speech.ask_user
  → 等待“检查”或按键
  → policy 使用 observation.request
  → 返回 traffic_signal.* / crosswalk.* / vehicle.* facts
  → CrossingAdvisoryPolicy
  → wait / recheck / proceed_with_caution / cannot_determine
```

Agent 不得把 `unknown` 或过期事实变成确定的“可以通行”。

## 回放与安全

ToolCall、ToolResult、授权检查、拒绝原因和实际事件都写入审计记录。回放时使用 Fake Tool 和已记录事实，不再次调用实时模型或真实设备。

Tool 输入输出必须通过 Schema 校验；日志默认脱敏，不记录账号令牌、私钥、原始图片或任意网络响应。
