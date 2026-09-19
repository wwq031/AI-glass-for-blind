# testkit

为领域和跨端联调提供确定性替身：

- `FakeNavigationProvider`：按脚本产生转弯、偏航、到达事件；
- `FakeVisionProvider`：返回入口、菜单和表情场景结果；
- `FakeTransport`：记录发送到眼镜的命令和播报；
- `EventReplay`：按时间和序号重放完整黄金场景。

真实设备只用于最后的硬件联调，不作为日常单元测试依赖。
