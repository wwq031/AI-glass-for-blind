# providers

供应商适配器的接口和实现。

候选接缝：

- `navigation-provider`：高德或其他地图 SDK；
- `vision-provider`：云端 VLM 或手机端 VLM；
- `ocr-provider`：菜单和短文本 OCR；
- `speech-provider`：手机 TTS 或眼镜播报。

每个真实适配器都应配一个 `testkit` 替身，先用合同和场景回放验证，不把供应商对象传入领域核心。
