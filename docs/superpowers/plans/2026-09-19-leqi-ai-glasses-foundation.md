# 乐奇 AI 眼镜工程骨架实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立一个可供组员并行准备实现的助盲眼镜单仓库骨架，固定领域协议、模块接缝和餐厅全流程场景。

**Architecture:** 采用模块化单仓库。眼镜 JSUI、手机伴侣、模型网关和共享领域协议分开；核心会话只依赖小接口，Rokid CXR、地图、视觉模型和设备模拟器都通过适配器接入。第一阶段只承诺“用户主动观察 + 系统导航提醒”，不承诺主动拍摄或安全级避障。

**Tech Stack:** Markdown、JSON Schema、TypeScript/Kotlin 友好的接口文档；保留现有 JSUI/Android/CXR 研究证据，不把专有 APK 或设备快照提交到仓库。

---

### Task 1: 建立仓库规范和领域上下文

**Files:**
- Create: `README.md`
- Create: `CONTEXT.md`
- Create: `.gitignore`
- Create: `docs/adr/0001-modular-monorepo-and-transport-seam.md`

- [x] **Step 1: 写项目入口和范围**
  - 说明目标用户、黄金路径、当前非目标和目录导航。
- [x] **Step 2: 固定领域词汇**
  - 定义会话、导航事件、观察请求、观察结果、系统提醒、主动观察等词。
- [x] **Step 3: 记录架构决策**
  - 记录为什么采用模块化单仓库，以及为什么把 CXR 放在 `DeviceTransport` 接缝后。
- [x] **Step 4: 排除敏感和原始资料**
  - 忽略 `.aix`、APK、设备导出、密钥和构建产物。

### Task 2: 固定跨端事件协议和接口

**Files:**
- Create: `packages/contracts/README.md`
- Create: `packages/contracts/interfaces.md`
- Create: `packages/contracts/schemas/event-envelope.schema.json`
- Create: `packages/contracts/schemas/navigation-event.schema.json`
- Create: `packages/contracts/schemas/observation-request.schema.json`
- Create: `packages/contracts/schemas/observation-result.schema.json`
- Create: `packages/contracts/schemas/speech-effect.schema.json`
- Create: `packages/contracts/schemas/speech-input.schema.json`
- Create: `packages/contracts/schemas/device-command.schema.json`
- Create: `packages/contracts/schemas/device-event.schema.json`
- Create: `packages/contracts/schemas/media-transfer.schema.json`
- Create: `packages/contracts/schemas/destination-query.schema.json`
- Create: `packages/contracts/schemas/destination-candidates.schema.json`
- Create: `packages/contracts/schemas/session-snapshot.schema.json`
- Create: `packages/contracts/schemas/contract-error.schema.json`
- Create: `packages/contracts/schemas/crossing-advisory.schema.json`
- Create: `packages/contracts/schemas/fact.schema.json`
- Create: `packages/contracts/schemas/capability-definition.schema.json`
- Create: `packages/contracts/schemas/capability-registry.schema.json`
- Create: `packages/contracts/examples/`

- [x] **Step 1: 定义统一事件信封**
  - 所有事件包含 `schema_version`、`event_id`、`session_id`、`sequence`、`occurred_at`、`source`、`type` 和 `payload`。
- [x] **Step 2: 定义导航事件**
  - 覆盖开始、接近转向、偏航、重新规划、GPS 弱、到达和停止。
- [x] **Step 3: 定义观察请求与结果**
  - 以 `capability_id` 和 `facts[]` 支持可注册能力；P0 初始覆盖场景、入口、菜单、表情和路口观察，以及置信度、重拍和风险字段。
- [x] **Step 4: 定义播报效果**
  - 固定优先级、是否可打断、来源和过期时间。
- [x] **Step 5: 写适配器接口**
  - 定义 `DeviceTransport`、`NavigationProvider`、`VisionProvider`、`SpeechOutput` 和 `SessionOrchestrator` 的最小接口。

### Task 3: 建立运行时模块目录和并行开发说明

**Files:**
- Create: `apps/glasses-agent/README.md`
- Create: `apps/phone-companion/README.md`
- Create: `apps/gateway/README.md`
- Create: `packages/domain/README.md`
- Create: `packages/providers/README.md`
- Create: `packages/testkit/README.md`
- Create: `docs/team/parallel-work.md`
- Create: `docs/team/file-delivery-matrix.md`
- Create: `packages/contracts/capabilities/registry.json`
- Create: `packages/contracts/capabilities/README.md`
- Create: `packages/contracts/capabilities/voice/`
- Create: `packages/domain/policies/README.md`
- Create: `packages/providers/registry/README.md`

- [x] **Step 1: 说明眼镜端职责**
  - 只负责按键、摄像、设备反馈和播报，不承载地图和长期 Agent 状态。
- [x] **Step 2: 说明手机端职责**
  - 负责无屏目的地输入、地图、定位、系统提醒、会话协调和通信适配。
- [x] **Step 3: 说明网关职责**
  - 作为可选模型中转；第一阶段允许嵌入手机端。
- [x] **Step 4: 说明领域核心和测试替身**
  - 核心状态机不能导入 Android、JSUI、CXR 或具体模型 SDK。
- [x] **Step 5: 说明组员分工和合并规则**
  - 每个方向先遵守协议文件，使用模拟适配器并行开发。

### Task 4: 固定黄金场景和验收边界

**Files:**
- Create: `docs/scenarios/golden-path-navigation-restaurant.md`
- Create: `tests/scenarios/README.md`

- [x] **Step 1: 写无屏目的地输入**
  - 实体键或短语音触发，音频确认 POI，不把手机地图选点作为必经步骤。
- [x] **Step 2: 写导航和系统提醒**
  - 系统提醒由确定性规则产生，不触发自动拍照。
- [x] **Step 3: 写入口观察**
  - 接近目的地后提醒用户按键，低置信度要求重拍。
- [x] **Step 4: 写菜单识别和追问**
  - 首播摘要，后续语音查询菜名、价格和限制条件。
- [x] **Step 5: 写表情辅助边界**
  - 仅在用户明确请求后单帧观察，只播报可见的粗粒度表情并说明不确定性。
- [x] **Step 6: 写故障和非目标**
  - 覆盖断连、地图失联、模型超时和无法识别；不承诺自动避障或真实情绪判断。

### Task 5: 归档逆向研究入口

**Files:**
- Create: `research/README.md`
- Create: `research/phone-apk-analysis/README.md`
- Create: `research/phone-apk-analysis/cxr-native-symbols.md`
- Create: `research/jsui-analysis/README.md`
- Create: `research/device-dump/README.md`
- Create: `tools/protocol-inspector/README.md`

- [x] **Step 1: 记录 APK 证据来源**
  - 说明原始 `apk_unpacked.zip` 不提交到 Git，研究笔记只保留可复核结论。
- [x] **Step 2: 记录 CXR Native 符号**
  - 记录认证、请求、传输、音频流、客户端管理和 Flora/cxr-service 入口。
- [x] **Step 3: 记录 JSUI 和设备证据**
  - 将现有设备和 JSUI 结论与产品源码隔离。
- [x] **Step 4: 规定协议研究工具边界**
  - 只做自有设备的只读分析、日志和模拟，不在工具目录放入系统覆盖脚本。

### Task 6: 结构检查和提交

**Files:**
- Modify: all files created in Tasks 1-5

- [x] **Step 1: 检查目录和链接**
  - 确认 README、协议、场景和研究入口彼此可导航。
- [x] **Step 2: 检查协议一致性**
  - 确认场景中使用的事件名和接口字段都出现在合同文件中。
- [x] **Step 3: 检查敏感文件**
  - 确认 APK、AIX、设备 dump 和密钥没有进入 Git。
- [x] **Step 4: 提交初始骨架**
  - 使用提交信息 `chore: establish assistive glasses project foundation`。
- [x] **Step 5: 推送远程仓库**
  - 推送到用户提供的 `origin/main`，并报告提交哈希和目录摘要。
