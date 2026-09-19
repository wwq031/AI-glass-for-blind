# 并行开发约定

## 分工建议

当前采用“三个能力负责人 + 一个系统集成负责人”的方式：

| 负责人 | 目录 | 先交付什么 |
|---|---|---|
| 协议与设备传输 | `packages/providers/transport`, `apps/glasses-agent`, `apps/phone-companion/src/transport`, `tools/protocol-inspector` | `DeviceTransport`、模拟器、CXR 适配、按键/拍摄/播报事件 |
| 导航 | `packages/providers/navigation`, `apps/phone-companion/src/navigation` | 地图适配、定位、导航事实和确定性提醒 |
| 基础识图 | `packages/providers/vision`, `apps/gateway/src/vision` | 场景、入口、菜单、OCR、表情辅助的结构化结果 |
| 系统集成 | `packages/domain`, `apps/phone-companion/src/session`, `tests`, `packages/testkit` | 会话状态机、语音仲裁、故障降级和黄金场景回放 |

每个方向的具体文件、接口和完成标准以 [`file-delivery-matrix.md`](file-delivery-matrix.md) 为准。

## 约束

1. 修改事件字段前先修改合同和场景文档。
2. `packages/domain` 不得依赖设备或供应商 SDK。
3. 研究目录不提交 APK、AIX、系统 dump、密钥或用户媒体。
4. 真实 CXR 未就绪时，使用 `FakeTransport`，不要在业务模块里写临时蓝牙调用。
5. 每个模块 README 记录自己的输入、输出、错误和本地验证命令。
