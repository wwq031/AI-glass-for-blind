# 并行开发约定

## 分工建议

| 方向 | 目录 | 先交付什么 |
|---|---|---|
| 领域与协议 | `packages/contracts`, `packages/domain` | 合同、状态机转移、策略测试 |
| 眼镜端 | `apps/glasses-agent` | JSUI 按键、摄像和播报壳 |
| 手机导航 | `apps/phone-companion` | 语音目的地、导航事件、提醒队列 |
| CXR 研究 | `research/phone-apk-analysis`, `apps/phone-companion/transport` | 符号清单、适配器实验、连接降级 |
| Agent/VLM | `apps/gateway`, `packages/providers` | 结构化观察结果和超时降级 |
| 回放与验收 | `tests`, `packages/testkit` | 黄金场景脚本和跨模块回放 |

## 约束

1. 修改事件字段前先修改合同和场景文档。
2. `packages/domain` 不得依赖设备或供应商 SDK。
3. 研究目录不提交 APK、AIX、系统 dump、密钥或用户媒体。
4. 真实 CXR 未就绪时，使用 `FakeTransport`，不要在业务模块里写临时蓝牙调用。
5. 每个模块 README 记录自己的输入、输出、错误和本地验证命令。
