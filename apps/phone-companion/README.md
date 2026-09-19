# phone-companion

Android 手机伴侣应用的实现入口。第一阶段面向盲人用户采用无屏主流程。

## 负责

- 实体键触发的短语音目的地输入；
- 音频候选确认和常用地点别名；
- 地图 SDK、定位、方向和关键导航事件；
- `ReminderPolicy` 系统提醒；
- 会话协调和本地降级；
- `DeviceTransport` 适配器。

## 传输适配器

- `SimulatorTransport`：场景回放和开发默认实现；
- `RokidCxrAdapter`：研究中的 CXR/系统通道实现；
- `DirectCxrAdapter`：只有协议和授权条件满足后才增加。

手机屏幕只作为配置、家属协助和故障排查界面，不应成为黄金场景的必经步骤。
