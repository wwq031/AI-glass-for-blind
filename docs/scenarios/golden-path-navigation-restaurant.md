# 黄金场景：导航到约定餐厅并完成菜单与表情辅助

## 用户目标

盲人用户无需看手机，从发起导航开始，经过入口确认、进入餐厅、菜单识别和用户主动请求的可见表情辅助，完成一次连续任务。

## 前置条件

- 眼镜已连接，手机伴侣在后台运行；
- 用户已经配置“约定的餐厅”语音别名，或允许语音搜索 POI；
- 地图和视觉服务可用；
- 用户未开启主动自动拍摄。

## 状态和事件

| 状态 | 进入事件 | 系统动作 | 用户动作 |
|---|---|---|---|
| `idle` | `destination.input_requested` | 播报“请说目的地” | 说出目的地 |
| `destination_confirm` | `destination.candidates_listed` | 播报候选 POI | 说“第一个”或别名 |
| `navigating` | `navigation.started` | 开始关键节点提醒 | 行走 |
| `intersection_check` | `navigation.intersection_approaching` | 播报“前方路口，需要检查时请按键或说检查” | 按键或说“检查” |
| `crossing_advisory` | `observation.result_received(capability_id=vision.traffic_signal)` | 播报等待、重查或谨慎辅助建议 | 根据提示停留或继续确认 |
| `approaching_destination` | `navigation.approaching_maneuver` | 播报接近目的地 | 准备观察 |
| `entrance_check` | `observation.prompted` | 播报“请按键观察入口” | 按键拍摄 |
| `inside_restaurant` | `observation.entrance_confirmed` | 播报入口结果 | 进入餐厅 |
| `menu_reading` | `observation.menu_requested` | 读取菜单摘要 | 按键或追问 |
| `conversation_assist` | `observation.expression_requested` | 描述可见表情和限制 | 明确提出请求 |
| `completed` | `session.completed` | 播报结束或保持待机 | — |

## 语音示例

```text
系统：请说目的地。
用户：去约定的餐厅。
系统：找到一个候选，XX 餐厅。确认请说“确认”。
系统：导航开始。前方约三十米右转。
系统：前方路口，需要检查红绿灯时请按键或说“检查”。
用户：检查。
系统：暂时无法确认与你方向对应的信号灯，请停留并调整方向后重试。
系统：即将到达，请按键观察入口。
用户：按键。
系统：前方看到餐厅招牌，入口在右前方，画面中没有足够依据判断门槛。
用户：按键拍菜单。
系统：已识别十二道菜，包含面食、炒菜和饮料。你可以继续询问。
用户：有没有不辣的？
系统：识别到三道标注为不辣或清淡的菜。
用户：帮我看看对面的人表情。
系统：看起来嘴角上扬，可能在微笑，但无法确定真实情绪。
```

## 必须验证

- 用户全程不需要看手机屏幕；
- 导航提醒和识图播报不会同时占用语音输出；
- 接近目的地只提醒，不自动拍摄；
- 接近路口时由 Agent 提醒，只有用户按键或语音确认后才拍摄；
- 路口观察结果包含信号灯状态、方向匹配、斑马线、车辆活动和有效期；
- 红灯、未知、过期、方向不匹配或低置信度时只能提示等待/重查；
- 入口画面低置信度时明确要求重拍；
- 菜单结果可以通过语音追问；
- 表情辅助必须由用户明确请求，并输出不确定性；
- 地图、模型或 CXR 断开时有明确降级提示；
- 每个事件的 `session_id` 和 `sequence` 可回放。

## 不在本场景承诺

- 自动识别和拍摄路人；
- 身份识别或真实情绪推断；
- 完全自主导航；
- 可靠实时避障、红绿灯决策或安全距离保证；
- 通过手机触摸地图完成目的地选择。
