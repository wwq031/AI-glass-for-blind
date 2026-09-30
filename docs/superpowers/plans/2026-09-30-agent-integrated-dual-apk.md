# Agent 集成双端 APK 实施与验收清单

> 状态：实施中。既有批准设计为 `docs/superpowers/specs/2026-09-29-device-navigation-loop-design.md`。本文件记录实际集成边界和必须取得的证据；勾选只依据现场输出。

**目标：** 两台设备安装我们自己的 APK，在无手机选点的情况下，从眼镜语音目的地出发，经真实地点搜索与步行导航，到达后由用户明确确认拍摄入口，并在眼镜听到真实模型的结果。APK 同时承载仓库已有 Agent、合同、Skill/Tool 与策略代码；已注册的扩展能力保留同一接入方式。

**架构：** `apps/phone-companion/src/android/agent-browser.ts` 将 `PhoneAgentRuntime`、`packages/domain/agent`、合同编解码、Skill 注册表打进手机 APK。Kotlin 仅实现 Android SDK 的模型、定位、地图、蓝牙、摄像与音频适配，不实现第二套业务任务状态机。眼镜 APK 仅提供设备事件与授权后的媒体采集/播报。现有 Java `NavigationTask`、`PhoneBtActivity` 及其自造事件只能作为迁移参考，不得作为正式主流程。

## 验收顺序

- [x] 手机 APK 内实际执行仓库 `PhoneAgentRuntime`：合同格式 `user.cancel` 经 WebView 到 Agent，实机日志收到 `session=cancelled` Effect（2026-09-30 02:11）。这只证明运行入口。
- [x] 眼镜设备适配代码迁到 Kotlin，独立 `:glasses-bt:assembleDebug` 成功、APK 安装并启动，眼镜日志显示蓝牙服务等待手机（2026-09-30 02:14）。还需迁移公共协议与双端重连复验。
- [ ] 手机启动入口换成 Agent 宿主；按键、WAV、JPEG、播放回执、断连统一编码为 `AgentEvent`，媒体只传引用，不传入模型上下文。
- [ ] 模型：手机应用私有目录加载 Gemma；真实语音→识别文本；真实图片→结构化观察事实；真实文本→严格 JSON `AgentPlan`，经 `decodeAgentPlan` 与 Harness 校验。模型失败不得伪装成成功。
- [ ] 工具与 Provider：`navigation.search_destination`、`navigation.confirm_destination`、策略发起的 `navigation.start`、`observation.request`、`speech.ask_user`、`facts.query`、`session.cancel` 对接真实设备/地图/模型，结果按 `ToolResult` 显式回放。能力注册表中的入口、路口、菜单和表情请求遵守原有授权与事实合同。
- [ ] 真实高德定位、POI、步行路线、连续导航回调归一为有序 `AgentEvent`；到达事件不能自动触发摄像头。
- [ ] 所有 `Effect` 由统一语音调度转成离线 TTS 音频并发送眼镜，等待 `play.done`；失败、打断、优先级、重试不重复执行外部动作。
- [ ] 移除正式双端 APK 中的平行 Java 业务流程；生成双端安装包并核对 APK 内 Agent bundle、模型/地图/设备适配，以及密钥不入包或源码。
- [ ] 先用仓库现有合同/场景测试验证事件次序与授权，再在荣耀手机和 Rokid 眼镜上记录完整“语音目的地→候选确认→真实导航→到达→确认拍入口→播报”的单会话证据。只有此项通过才称可展示完整 Demo。

## 可委派的机械工作

眼镜设备代码、蓝牙媒体传输、公共协议、Android SDK 适配的 Kotlin 迁移可交给 Claude Code，限定文件范围；每项由主任务独立编译与实机复核。Agent 决策、合同映射、权限边界与验收结论由主任务核对。不得把 Claude 的报告或 APK 编译成功替代实机证据。
