# 场景测试

场景测试以合同事件为输入，以 `SpeechEffect`、状态转移和设备命令为输出。

第一条回放脚本对应 [`golden-path-navigation-restaurant.md`](../../docs/scenarios/golden-path-navigation-restaurant.md)，至少覆盖：

1. 目的地语音确认；
2. 导航接近提醒；
3. 用户确认入口观察；
4. 入口低置信度重拍；
5. 菜单摘要与追问；
6. 用户主动请求可见表情辅助；
7. CXR 断连、模型超时和地图失联降级。
