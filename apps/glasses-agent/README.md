# glasses-agent

Rokid JSUI/Ink 眼镜端入口。

## 负责

- 实体键和允许的触摸/姿态事件映射；
- 用户确认后的摄像请求；
- 设备连接状态反馈；
- TTS 或音频播放；
- 将设备事件转换为 `packages/contracts` 中的事件。

## 不负责

- 地图路线和 GPS；
- Agent 长期上下文；
- 直接决定风险优先级；
- 直接调用具体 VLM 或 Coze。

当前目录只放将来可编译的 JSUI Agent 源码。设备导出的小游戏和 JSAI 证据放在仓库外的研究目录，不复制为生产代码。
