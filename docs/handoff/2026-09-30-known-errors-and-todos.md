# Codex 已知错误、漏洞与待办（2026-09-30）

此前“硬件完全接入/基本完成”的汇报不成立，现明确撤回。分项实验、模拟测试和构建成功，不能证明完整双端实机任务已经完成。

本文件记录本轮已知错误、实现缺口、工程风险和未验收项，共 **19 项**。每项包含证据、影响和完成条件，可直接供组员接手。历史已修复问题单独注明，不以历史问题替代当前源码事实；无复现证据的风险明确标注，不能将所有待办解释为当前已复现故障。

## 交付与范围

- [当前源码、双端 APK 与证据快照（未通过完整实机验收）](https://github.com/wwq031/AI-glass-for-blind/releases/tag/device-handoff-20260930)
- [历史 APK 复盘材料](2026-09-30-historical-apk-audit.md)：含历史状态说明，其中建议方案不构成新的实现授权。
- 代码证据基线：`b7555488a7bba20c1495c15e097b446f0f10770e`。日志证据在 Release 的 `evidence.zip` 中，使用压缩包内实际文件名。
- 用户目标：正式双端 APK 容纳当前仓库已实现的 Agent、事件、合同、会话、技能与策略；手机运行本地 Gemma；持续自由交互，导航至到达并按键确认入口。业务沿用 TypeScript，Kotlin 仅做设备适配。
- 机械性工作按用户约定交给 Claude，复用已有测试，只进行必要、最小的补充检查；不得另起业务 Agent 或状态机。长期 YOLO/Laya 讨论规划不列为本轮缺口。
- `current-open` 当前未解决；`current-unaccepted` 当前未验收；`historical-fixed-needs-regression` 历史已修复、需回归。
- P0 是核心交付或结论阻塞，P1 是工程质量与流程问题，不是安全漏洞等级。本清单不构成未知安全问题的全面审计结论。

## 待办索引

| 编号 | 优先级 | 待办 | 状态 |
| --- | --- | --- | --- |
| KN-01 | P0 | 撤回“硬件已完整集成/基本完成”结论，修正版快照仍未验收 | current-open |
| KN-02 | P0 | 历史未获批的并行 Java NavigationTask/业务事件；当前 TS 核心已接入但仍需审计重复实现 | current-open |
| KN-03 | P0 | 设备入口与官方助手共存未解决；短按仅听到 Rokid App 提示且无 Agent 回复 | current-unaccepted |
| KN-04 | P0 | 眼镜进程被系统回收：Activity 独占接收器/蓝牙/媒体，Manifest 无 Service | current-open |
| KN-05 | P0 | CXR-M/CXR-L 未区分与过早路由判断；CXR-L 根因仍未核实 | current-open |
| KN-06 | P1 | 按键形态契约未对齐：DOWN/UP/DOUBLE 已注册未处理，长按硬映射取消 | current-open |
| KN-07 | P0 | phone agent-browser 中存在未经批准的额外业务约束与重复提示词 | current-open |
| KN-08 | P0 | Gemma ASR/文本 AgentPlan/视觉推理未在设备上验收 | current-unaccepted |
| KN-09 | P0 | 持续多轮自由交互未实机验收：澄清/追问/任务切换/打断 | current-unaccepted |
| KN-10 | P0 | 高德 POI/定位/步行路线/连续事件序列/导航/到达未实机验收 | current-unaccepted |
| KN-11 | P0 | 授权拍照/VLM/事实核验/入口确认及已注册技能覆盖未验收 | current-unaccepted |
| KN-12 | P0 | TTS/麦克风/播放实际链路未验收；历史原生崩溃与路径缓存问题需审计 | historical-fixed-needs-regression |
| KN-13 | P1 | 断连/重连状态与就绪判断自相矛盾，入口恢复未验证 | current-open |
| KN-14 | P1 | 模型/TTS 部署可移植性与早期错误归属诊断 | current-open |
| KN-15 | P1 | 测试来源与剩余回归：模拟依赖通过不等于实机就绪 | current-open |
| KN-16 | P1 | 构建依赖/缓存/复现：bundleAgent 输入不完整构成静态依赖风险 | current-open |
| KN-17 | P1 | 打包/元数据/归档：package-device-release.ps1 遗漏源码与配置 | current-open |
| KN-18 | P1 | 上传工具 publish-team-handoff.ps1：摘要/状态/并发幂等缺陷（草稿问题已修复） | current-open |
| KN-19 | P1 | 工作流/诊断/委托失误与流程约束 | current-open |

---

## KN-01 · P0 · 撤回“硬件已完整集成/基本完成”结论，修正版快照仍未验收

状态标记：`current-open`

**Codex 责任记录**：此前“硬件完全接入/基本完成”的汇报不成立，已在本次对话明确撤回；本待办将该错误公开记录，并追踪验收与汇报规范。此处保留的分项设备实验不得作为完整 Demo 已完成的证据。

**状态**：当前未解决。原验收结论需撤回；修正版快照（device-handoff-20260930）仍然未验收。

**证据**：
- 交接文档自述“这是开发交接快照，**未通过完整实机验收**”“启动自检不能证明模型推理、语音识别或完整导航任务已经成功”：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md
- 历史报告仅以启动自检/探针通过即宣称硬件已完整集成、基本完成。
- 公开快照与 APK：https://github.com/wwq031/AI-glass-for-blind/releases/tag/device-handoff-20260930

**影响**：组长与接手组员会据错误结论判断真实进度，可能跳过实机验收直接对外提供；用户原始目标被误报为已完成。

**跟踪项**：
- [x] 已在本次对话撤回原“已完整集成/基本完成”结论：分项实验不足以证明完整接入；通过本待办公布更正。
- [ ] 每条完成度声明标注证据等级：build / static / mock / native / physical / full-demo，且可追溯到原始日志或实机记录。
- [ ] 修正版快照在标题与摘要处标注“未验收”，不再使用验收性措辞。
- [ ] 针对用户原始目标（现有整仓业务 + 自有双 APK + 本地 Gemma + 持续自由交互与导航直至到达/入口）逐项列出当前证据与差距。

---

## KN-02 · P0 · 历史未获批的并行 Java NavigationTask/业务事件；当前 TS 核心已接入但仍需审计重复实现

状态标记：`current-open`

**状态**：历史缺陷 + 当前待审计。历史问题：在未获批情况下于 Java/Kotlin 侧另起 NavigationTask 与业务事件通道，且仓库 Agent 未被接入。当前状态：TS 核心已接入，但残留的重复 prompt/state/计划适配实现必须对照权威实现审计，不得再新增业务层。历史发现与当前源码状态需分开记录。

**证据**：
- 当前会话权威实现：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/packages/domain/agent/session-orchestrator.ts
- 手机侧 Agent 宿主与桥接：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/src/android/agent-browser.ts
- 历史审计材料（仅作历史，其建议方案不构成本轮授权；该文件晚于 b755548 产生且未纳入该提交，故链接固定在分支上）：https://github.com/wwq031/AI-glass-for-blind/blob/agent-harness-p0/docs/handoff/2026-09-30-historical-apk-audit.md

**影响**：重复业务实现会与仓库合同/会话状态产生第二权威源，导致行为分叉、状态不一致与后续维护困难。

**验收（保持未勾选）**：
- [ ] 列出所有与权威实现重复的 prompt / 状态 / 计划适配代码位置与对应权威来源。
- [ ] 明确每处是删除、改为委托仓库实现，还是标注为纯设备适配（有书面理由）。
- [ ] 确认未新增任何业务层（无新 FSM、无意图关键字实现、无并行业务事件通道）。
- [ ] 在文档中分别记录“历史发现”与“当前源码状态”，避免以历史结论替代当前事实。

---

## KN-03 · P0 · 设备入口与官方助手共存未解决；短按仅听到 Rokid App 提示且无 Agent 回复

状态标记：`current-unaccepted`

**状态**：当前未解决、未验收。当前 GlassesBtActivity 采用动态广播与 onKeyUp 返回 super，尚无可验证的官方 SDK CustomApp 按键路由或与官方助手协同的结论。

**证据**：
- 眼镜入口实现：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/glasses-agent/android/src/main/java/com/leqi/experiment/glassesbt/GlassesBtActivity.kt
- 交接文档记录最新用户实测“短按一次镜腿功能键，听到『连接 Rokid App』；没有得到本项目 Agent 的真实回复”：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md

**影响**：设备侧目前没有稳定、已验收的入口；入口能否在本项目监听下可用尚未经实机确认，后续语音、导航与入口确认链路因此无法开始。

**验收（保持未勾选）**：
- [ ] 取得官方支持的按键/入口接入方式证据（SDK 版本、接口、示例或官方文档），并记录验证过程。
- [ ] 说明官方助手与本项目的协同/互斥行为，不臆测不同事件的成因是否相同。
- [ ] 实机记录：短按确实到达本项目监听并被处理，附原始日志。
- [ ] 不声称 CXR-L 或任一 SDK 接入方式的变更必然修复该入口问题，除非有对应实机证据。

---

## KN-04 · P0 · 眼镜进程被系统回收：Activity 独占接收器/蓝牙/媒体，Manifest 无 Service

状态标记：`current-open`

**状态**：当前阻塞项，未修复。

**证据**：
- ADB 系统退出记录：2026-09-30 12:40:01，眼镜应用 PID 3177 被 LOW_MEMORY / TOO MANY EMPTY PROCS 回收，importance 400、state empty（记录于交接文档）：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md
- 现有 GlassesBtActivity 同时承载动态按键接收器、蓝牙服务、录音/拍照/播放：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/glasses-agent/android/src/main/java/com/leqi/experiment/glassesbt/GlassesBtActivity.kt
- 眼镜 Manifest 未注册后台 Service：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/glasses-agent/android/src/main/AndroidManifest.xml

**影响**：进程回收后无按键监听与蓝牙服务，手机重连失败，设备端功能整体失效；长时间佩戴场景不可用。

**验收（保持未勾选）**：
- [ ] 设备运行层迁移到合规前台服务，生命周期独立于 Activity。
- [ ] 保留原传输协议、授权窗口、代号隔离、取消与资源释放语义。
- [ ] 覆盖恢复路径：进程被回收后按键与蓝牙可自动恢复，并给出日志证据。
- [ ] 不以“保持屏幕常亮”作为正式解决方案。
- [ ] 不新增 Agent/FSM 或意图关键字实现。

---

## KN-05 · P0 · CXR-M/CXR-L 未区分与过早路由判断；CXR-L 根因仍未核实

状态标记：`current-open`

**状态**：当前未解决。已确认的错误是“把 CXR-M 结论推广到全部 CXR 接入方式”与“过早回退 RFCOMM”这两项判断失误；CXR-L 自身的根因目前仍属未核实（unverified），本项不做根因结论。

**证据**：
- CXR-M：授权为空、SN_CHECK_FAILED。
- CXR-L：已授权，connect(token)=true，但 binder=null、无回调、无消息；即请求被接受，不等于最终链路建立。
- 交接文档与设备实验说明：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md 、 https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/device-experiment/android/README.md

**影响**：把 CXR-M 的 lc/clientSecret 结论推广到全部 CXR 接入方式会误导后续排查；未诊断 CXR-L 的 binder/回调缺失，链路走不通也看不出原因。

**验收（保持未勾选）**：
- [ ] 记录官方支持的接入方式、SDK 版本、所需服务、会话与回调时序。
- [ ] 保留 CXR-M 已有授权失败记录；排查 CXR-L 时记录授权、服务绑定、会话、回调与消息，不要求重跑 CXR-M 作为前置。
- [ ] 先走 CXR-L 的官方支持诊断路径；不以重跑 CXR-M 授权测试或申请 CXR-M 凭据作为 L 侧诊断的前置条件。
- [ ] 基于证据给出路由决策，不预设强制 WiFi、不替换官方通路。
- [ ] 明确区分“请求被接受”与“链路已建立”两类证据。
- [ ] 明确标注 CXR-L 根因仍未核实，在取得证据前不作结论。

---

## KN-06 · P1 · 按键形态契约未对齐：DOWN/UP/DOUBLE 已注册未处理，长按硬映射取消

状态标记：`current-open`

**状态**：当前未对齐，未验收。

**证据**：
- DeviceEventMapper 支持 short/long/double：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/glasses-agent/src/device-event-mapper.ts
- 眼镜 Android 侧注册 DOWN/UP/DOUBLE 但未处理，长按被硬映射为取消：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/glasses-agent/android/src/main/java/com/leqi/experiment/glassesbt/GlassesBtActivity.kt
- 手机侧过滤 keycode-* 事件：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/src/android/native-device-events.ts
- 交接文档：“当前长按映射取消；DOWN/UP/DOUBLE 已注册但未处理。触控入口尚未得到真实事件验收。不要臆造映射”：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md

**影响**：多形态按键无法使用或行为不可预期；重复/并发事件可能造成重复触发；按键契约与已约定的触发方式不一致。未发现按键绕过采集同意窗口的复现证据，本项不作该主张。

**验收（保持未勾选）**：
- [ ] 对照现有契约与已约定的触发方式，列出映射表（物理事件 → 契约事件 → 行为）。
- [ ] 对重复/并发事件做去重，并给出测试或实机证据。
- [ ] 保留显式采集同意语义，核对映射时一并确认没有任何按键路径绕过同意窗口。
- [ ] 不发明未约定的映射；不把“仅靠物理键”作为唯一要求。

---

## KN-07 · P0 · phone agent-browser 中存在未经批准的额外业务约束与重复提示词

状态标记：`current-open`

**状态**：当前存在，待与既有设计/注册表核对后处理。以下均为代码事实，不代表所有技术性边界都是缺陷。

**证据**：
- phone 侧（https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/src/android/agent-browser.ts）：plannerPrompt 硬编码 6 条工具引导与 max 4 actions；normalizeModelPlan 对输出做填充/默认值处理；sessionState 由 proposed plan 推导；语音武装之前先播放开场帮助；replay 上限 8。
- 固定语音窗口 3000ms 位于眼镜 Kotlin：`VOICE_RECORD_MS = 3000`（GlassesBtActivity.kt:902）：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/glasses-agent/android/src/main/java/com/leqi/experiment/glassesbt/GlassesBtActivity.kt
- 权威会话实现：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/packages/domain/agent/session-orchestrator.ts

**影响**：模型被硬编码引导与动作上限约束，削弱自由交互；固定 3000ms 录音窗口可能截断或空录语音。由提议计划推导会话状态与权威会话状态之间可能存在冲突，属待评审风险（尚未复现为故障），需在核对时判定。

**验收（保持未勾选）**：
- [ ] 逐条与现有设计文档、工具/技能注册表核对，标注“批准保留 / 需删除 / 待确认”。
- [ ] 移除未经批准的业务约束与重复提示词，改为由注册表/合同驱动。
- [ ] 确认由提议计划推导状态是否会与权威会话状态冲突，并给出结论与依据。
- [ ] 不削弱合同、技术代际边界与同意语义。
- [ ] 不把技术性边界一律认定为缺陷，需给出逐条判断依据。

---

## KN-08 · P0 · Gemma ASR/文本 AgentPlan/视觉推理未在设备上验收

状态标记：`current-unaccepted`

**状态**：当前未验收。引擎初始化成功、文件可读或 mock 测试通过，均不等于实际推理成功。

**证据**：
- 本地模型桥接中的上下文与提示词上限：`MAX_CONTEXT_TOKENS = 4096`（GemmaLocalBridge.kt:257）、`MAX_PROMPT_CHARS = 16000`（GemmaLocalBridge.kt:263）：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/android/src/main/java/com/leqi/experiment/phonebt/GemmaLocalBridge.kt
- 交接文档：“自检展示的『本地模型已加载』不能代替成功创建推理会话和实际输出”：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md

**影响**：语音识别、任务规划与视觉理解可能全部不可用，而界面/日志显示“已加载”，造成错误信心。

**验收（保持未勾选）**：
- [ ] 实机验证 ASR 输出与真实语音一致，以脱敏后的输入/输出文本作为证据。
- [ ] 实机验证文本到 AgentPlan 的结构化输出符合合同。
- [ ] 实机验证视觉输入产生真实推理结果，证据同样以脱敏结果呈现。
- [ ] 针对上文 cited 的 4096 token 上下文与 16000 字符提示词上限，验证真实超限与截断行为。
- [ ] 不以伪造结果或云端替代充当本地推理证据。
- [ ] 不公开原始录音、照片或任何可识别个人的素材。

---

## KN-09 · P0 · 持续多轮自由交互未实机验收：澄清/追问/任务切换/打断

状态标记：`current-unaccepted`

**状态**：当前未验收。

**证据**：
- 交接文档要求“随后持续交互、真实定位导航、到达与入口确认”：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md
- 会话/状态权威：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/packages/domain/agent/session-orchestrator.ts
- 当前手机侧会话处理：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/src/android/agent-browser.ts

**影响**：多轮与自由交互尚未验收；目前没有实机证据表明一次对话能跨澄清、追问、任务切换、打断与下一任务连续进行。目标是让用户不必走固定的“仅目的地”流程，该目标同样未验收。

**验收（保持未勾选）**：
- [ ] 同一次会话内完成：澄清 → 追问 → 任务切换 → 打断 → 下一任务，且状态连续正确。
- [ ] 会话状态以仓库权威实现为准，不以提议计划反推。
- [ ] 全程使用真实 Gemma 推理，不使用模拟模型。
- [ ] 附实机交互记录与对应状态轨迹。

---

## KN-10 · P0 · 高德 POI/定位/步行路线/连续事件序列/导航/到达未实机验收

状态标记：`current-unaccepted`

**状态**：当前未验收（SDK 已接入，不等于链路可用）。

**证据**：
- 模块构建说明与 AMap Key 绑定要求（包名 com.leqi.experiment.phonebt、本机签名 SHA1）：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/device-experiment/android/README.md
- 交接文档构建章节第 4 条：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md

**影响**：定位与导航是用户核心诉求，未验收则整个产品价值无法确认。

**验收（保持未勾选）**：
- [ ] 校验应用包名、签名与平台 Key 匹配（只记录是否匹配，不记录任何密钥值）。
- [ ] 验证隐私与定位精度行为、以及各类错误状态展示。
- [ ] 验证 POI 检索、当前位置、步行路线为真实回调结果。
- [ ] 验证连续事件序列驱动的导航推进与到达判定。
- [ ] 使用仓库既有合同、provider 与策略，不改动业务语义。

---

## KN-11 · P0 · 授权拍照/VLM/事实核验/入口确认及已注册技能覆盖未验收

状态标记：`current-unaccepted`

**状态**：当前未验收。

**证据**：
- 交接文档入口与后续实机检查章节（拍照须经拍照窗口确认）：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md
- 历史审计（仅作历史材料，未纳入 b755548 提交，故链接固定分支）：https://github.com/wwq031/AI-glass-for-blind/blob/agent-harness-p0/docs/handoff/2026-09-30-historical-apk-audit.md

**影响**：视觉类能力（入口确认、场景、菜单、表情、交通信号）是否可用目前无从判断。本轮没有自动拍照的复现证据，不主张存在该缺陷；但显式授权与保守的过马路策略必须继续保持。

**验收（保持未勾选）**：
- [ ] 产出一张表：当前已实现的仓库实体 → 适配器 → 数据来源 → mock/实机证据。
- [ ] 覆盖范围以注册表为准：场景/入口/菜单/表情/交通信号；不把长期 YOLO/Laya 实现列为本轮需求。
- [ ] 拍照必须经由显式授权窗口，附实机证据。
- [ ] 保留仓库显式采集授权和保守路口策略；导航提醒不得直接授权拍摄，不得输出安全通行保证。

---

## KN-12 · P0 · TTS/麦克风/播放实际链路未验收；历史原生崩溃与路径缓存问题需审计

状态标记：`historical-fixed-needs-regression`

**状态**：历史问题已修复，但需回归确认；端到端播放链路当前未验收。不得把旧崩溃描述为当前仍在发生。

**证据**：
- 历史：OfflineTts(context.assets, 绝对路径) 触发原生 EXIT_SELF 255；当前已改为 OfflineTts(null, config)：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/android/src/main/java/com/leqi/experiment/phonebt/OfflineTtsBridge.kt
- 历史修复后 TTS 播放曾成功；最终集成 APK 的完整语音链路仍未验收（交接文档）：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md

**影响**：用户可能听不到播报，或在高优先级播报/取消时出现原生致命错误导致进程退出。

**验收（保持未勾选）**：
- [ ] 回归确认当前构造方式不再触发原生 EXIT_SELF 255（附日志）。
- [ ] 实机验证用户确实听到可见文本对应的语音。
- [ ] 验证 play.done 回调、取消、优先级抢占行为。
- [ ] 明确原生致命错误的边界与兜底（含资源释放）。
- [ ] 报告中区分“历史崩溃”与“当前状态”，不混用。

---

## KN-13 · P1 · 断连/重连状态与就绪判断自相矛盾，入口恢复未验证

状态标记：`current-open`

**状态**：当前未解决。

**证据**：
- 最新手机显示 `device.send failed ... entrance.disarm`，而恢复连接后蓝牙自检全部通过；重连尝试 5 次后停止（交接文档）：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md

**影响**：断连后的状态显示与真实可用性互相矛盾：同一场景既报告发送失败，又在恢复后判定自检全部通过；重连 5 次后停止后入口能否恢复尚未验证。本地媒体权限在断连时已由既有代码撤销，本项不主张其残留。

**验收（保持未勾选）**：
- [ ] 核对并修正断连/重连的状态与错误提示，使其准确反映真实连接情况与当前就绪状态。
- [ ] 发送失败必须如实上报，不得显示为成功。
- [ ] 重连后入口可恢复到可用状态，并给出实机日志。
- [ ] 回归确认既有本地媒体权限撤销路径按预期生效，不以“权限残留”为前提设计改动。

---

## KN-14 · P1 · 模型/TTS 部署可移植性与早期错误归属诊断

状态标记：`current-open`

**状态**：当前部分修复（静态可见问题已修正），全新安装路径仍未验证。

**证据**：
- Gemma 目录应由应用私有 files/models/ 持有；旧 TTS 由 shell 持有属历史差异；Java stat 与原生 open 的可读性判断不同。
- 当前改为私有目录与 OfflineTts(null, config)：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/android/src/main/java/com/leqi/experiment/phonebt/GemmaLocalBridge.kt 、 https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/android/src/main/java/com/leqi/experiment/phonebt/OfflineTtsBridge.kt
- 早期给出的部署指令基于过期/缓存的外部优先选择与缺失的目录权限，属错误归属诊断。
- APK 不包含模型权重，交付说明见：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md

**影响**：其他组员按错误指令部署会失败；换机后模型/TTS 不可用。

**验收（保持未勾选）**：
- [ ] 验证全新安装（无历史数据）后的模型与 TTS 供给流程。
- [ ] 给出热替换与重新检查规则，并验证生效。
- [ ] 用原生读取路径验证文件可读性，而非仅 Java stat。
- [ ] 提供可复现部署文档，明确 APK 不含权重、不假设任何在线 AI 密钥。

---

## KN-15 · P1 · 测试来源与剩余回归：模拟依赖通过不等于实机就绪

状态标记：`current-open`

**状态**：当前记录，需补齐回归说明与真实缺陷清单。

**证据**（`artifacts/` 已被 .gitignore 第 35 行忽略，故日志不提供 blob 链接，改为指向 release 附件 evidence.zip，并标注对应日志文件名）：
- 最新定向测试 43/43 通过、exit 0（15 dual-apk-golden-loop + 11 adapter-quality-fix + 17 navigation-crossing-replay），模型与设备均为模拟依赖 —— evidence.zip 内 `final-integrated-gates.log`：https://github.com/wwq031/AI-glass-for-blind/releases/download/device-handoff-20260930/evidence.zip
- 语音解析测试 38/38 —— evidence.zip 内 `native-model-port-parser-tests.log`：https://github.com/wwq031/AI-glass-for-blind/releases/download/device-handoff-20260930/evidence.zip
- 早期 183 scenario / 169 pass / 14 fail，非最终结果；失败项包含：VM 缺 AbortController、event_types 不匹配、followUp 调用次数、effect 与 speech 通道混用、本地 factstore 期望、helper consumed tag 等；其中真实缺陷为嵌套 ask_user parameters.capability_id 与 nullfact recordTurn（当前已修复）。

**影响**：把模拟通过误读为实机就绪会再次产生错误验收；把全部 14 项失败一律当作运行时缺陷也会误导修复优先级。

**验收（保持未勾选）**：
- [ ] 保留原有安全相关测试，不做删减。
- [ ] 修复后运行一次必要的既有整仓门禁，补做与修改直接相关的最小原生/实机验证，并记录准确范围与结果。
- [ ] 逐条记录 14 项失败的性质分类（测试环境问题 / 断言期望问题 / 真实缺陷）。
- [ ] 明确说明 43/43 为模拟依赖结果，不代表实机就绪。

---

## KN-16 · P1 · 构建依赖/缓存/复现：bundleAgent 输入不完整构成静态依赖风险

状态标记：`current-open`

**状态**：当前未解决。当前模块构建实际通过，不得称为“无法构建”；陈旧 bundle 属静态依赖风险，尚未复现为实际故障。

**证据**：
- Gradle 中 `tasks.register('bundleAgent', Exec)` 的 `inputs.files(fileTree(repoRoot))` 仅显式包含部分路径（build.gradle:52 起，include 列表见 54 行起）；未列入的导入源码包括 apps/gateway、apps/glasses-agent 的 mapper、packages/providers 与 device 侧 TS，以及构建脚本本体。改动这些文件可能不触发重新打包：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/phone-companion/android/build.gradle
- 双模块构建成功 —— evidence.zip 内 `final-dual-build.log`：https://github.com/wwq031/AI-glass-for-blind/releases/download/device-handoff-20260930/evidence.zip
- 工程说明：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/apps/device-experiment/android/README.md

**影响**：源码与打进 APK 的 bundle 存在不一致风险，可能使验证结论失效且难以察觉；该风险目前来自静态的输入清单缺口，未见实际陈旧 bundle 的复现证据。

**验收（保持未勾选）**：
- [ ] 补全 bundleAgent 输入集合，覆盖全部被导入的源码与构建脚本。
- [ ] 提供内容哈希与 source → bundle → APK 的对应关系。
- [ ] 提供异机 clean build 文档，固定依赖、Gradle、SDK、ABI、AAR 与签名配置。
- [ ] 保持“当前实际可构建”的事实描述，不使用“无法构建”的表述。

---

## KN-17 · P1 · 打包/元数据/归档：package-device-release.ps1 遗漏源码与配置

状态标记：`current-open`

**状态**：当前未解决。

**证据**：
- 打包脚本仅包含 packages/domain/contracts，遗漏 providers/registry、根 package/lock/tsconfig 与模块 build.gradle：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/tools/package-device-release.ps1
- 旧 release README/manifest 存在过期或错误声明，源码 ZIP 不完整。
- 当前正确交接使用 git archive（commit b755548，214 个文件，8 个服务端摘要匹配），它不是最终演示包：https://github.com/wwq031/AI-glass-for-blind/releases/tag/device-handoff-20260930

**影响**：接手方拿到不完整源码，无法复现构建；过期验收标注会误导判断。

**验收（保持未勾选）**：
- [ ] 修复可复用打包脚本，使其包含全部必需源码与配置文件。
- [ ] 门禁不仅检查文件存在，还需检查内容条目完整性。
- [ ] 保留 APK 与源码的来源可追溯信息（commit、文件数、摘要）。
- [ ] 清理或标注旧 README/manifest 的过期声明。

---

## KN-18 · P1 · 上传工具 publish-team-handoff.ps1：摘要/状态/并发幂等缺陷（草稿问题已修复）

状态标记：`current-open`

**状态**：当前未解决。剩余缺陷为摘要/状态判定与并发处理；历史草稿中的若干问题已在使用前修复，二者需在文档中区分。

**证据**：
- 脚本当前按文件大小相同即跳过（未比对摘要或状态），并缓存 release 资源列表：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/tools/publish-team-handoff.ps1
- 并发场景下已上传的源码导致 HTTP 422，但手机侧成功且 8 个资源核验正确。
- 历史草稿曾存在自哈希门禁、默认授权下的不安全重定向、PowerShell 回调 runspace 问题，均在使用前修复（历史修复项，非当前缺陷）。
- 公开快照：https://github.com/wwq031/AI-glass-for-blind/releases/tag/device-handoff-20260930

**影响**：重复或并发上传会误判为已完成或直接失败；日志若泄露凭据会扩大风险面。

**验收（保持未勾选）**：
- [ ] 用摘要与状态做幂等判定，替代按大小跳过。
- [ ] 每次上传前获取最新资源列表，不做缓存复用。
- [ ] 处理并发/已存在资源，并能在失败后清理。
- [ ] 日志中不输出任何密钥或凭据。
- [ ] 在文档中区分“已修复的草稿问题”与“仍存在的缺陷”。

---

## KN-19 · P1 · 工作流/诊断/委托失误与流程约束

状态标记：`current-open`

**状态**：当前流程问题，需按新约定执行。

**证据与事实**：
- 曾忽略仓库文档与本地模型前提；在已存在宿主机 ADB 接口问题的情况下反复要求用户物理重连。
- 误读高德平台配置行并建议使用云端 Key。
- 亲自执行测试而非按约定委托 Claude 执行。
- 把 maxturns、权限拒绝、多行 CLI 参数丢失、未知工具链等执行环境问题当作实现进度上报。
- 荣耀 MI_02 的 Android 接口 GUID 已确定并获得授权；修复工具有备份与值级回滚，不进行全局 USB 重置：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/tools/repair-honor-adb-interface.ps1
- 本机特定工具不应对其他组员机器直接套用本机标识（交接文档工具说明章节）：https://github.com/wwq031/AI-glass-for-blind/blob/b7555488a7bba20c1495c15e097b446f0f10770e/docs/handoff/2026-09-30-device-apk-handoff.md

**影响**：浪费实机操作窗口；错误诊断与错误建议被写入交接材料；无法区分“环境阻塞”与“实现进展”。

**验收（保持未勾选）**：
- [ ] 机械性工作交给 Claude 执行，执行者输出必须复核后才计入结论。
- [ ] 不以叙述性文字宣告完成，完成必须附带可核验证据。
- [ ] 不擅自引入架构选择或新功能。
- [ ] 保留 MI_02 修复的备份与回滚范围，不执行全局 USB 重置。
- [ ] 遇到宿主机/工具链问题先做只读诊断并记录，再决定是否需要用户物理操作。

