# 跨端合同

这里是手机、眼镜、网关和测试替身共享的唯一事件事实来源。合同使用 JSON Schema 表达，具体语言可以生成 TypeScript、Kotlin 或其他模型。

## 版本规则

- 所有合同带 `schema_version`。
- 新增可选字段使用向后兼容方式；删除或改变含义必须提升主版本。
- 事件使用 `session_id + sequence` 做会话内排序和去重。
- 图片、音频和大文件通过引用或传输句柄表达，不把二进制直接塞进事件 JSON。
- `payload` 只描述事实和结果，不放供应商 SDK 对象。

## 主要合同

- `event-envelope.schema.json`：所有事件的公共信封。
- `navigation-event.schema.json`：手机地图产生的关键导航事件。
- `observation-request.schema.json`：用户确认后的视觉观察请求。
- `observation-result.schema.json`：场景、入口、菜单和可见表情辅助的结构化结果。
- `speech-effect.schema.json`：经过优先级仲裁后交给播报层的效果。
- `speech-input.schema.json`：手机或眼镜麦克风产生的统一语音输入。
- `device-command.schema.json` / `device-event.schema.json`：设备命令和设备事实事件。
- `media-transfer.schema.json`：图像/音频引用、哈希、过期时间和传输元数据。
- `destination-query.schema.json` / `destination-candidates.schema.json`：语音目的地搜索和候选确认。
- `session-snapshot.schema.json`：会话恢复所需的最小状态。
- `contract-error.schema.json`：统一错误码、重试性和用户动作。
- `crossing-advisory.schema.json`：路口检查的保守辅助建议，不代表安全通行保证。
- `fact.schema.json`：跨能力通用的命名事实。
- `capability-definition.schema.json`：能力注册项的结构。
- `capability-registry.schema.json`：能力注册表的结构。

`capabilities/registry.json` 是观察能力的注册表。新增普通观察能力时优先新增注册项和事实 Schema，不修改会话核心合同。

`examples/` 中的样例用于 Fake 和合同测试；样例不包含真实设备、个人图像或账号数据。
