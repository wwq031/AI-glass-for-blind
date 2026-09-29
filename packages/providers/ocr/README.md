# 本地 OCR Provider

## 组件定位

这是供当前 TypeScript/Node.js 工程使用的本地 OCR Provider，使用 Tesseract.js `7.0.0` 和 WebAssembly OCR 引擎。它实现项目 OCR adapter 所需的 `recognize(mediaRef)` 形状，默认加载英文与简体中文模型，并将识别行、置信度和文字位置返回给调用方。

项目目前还没有完整的 Android 手机端或相机媒体仓库，因此它适合先接入 Node.js 演示/服务端和本地样图流程；它不是 Android 原生库，也还没有连上真实眼镜相机。

## 接入位置

`TesseractOcrClient` 可以传给 `apps/gateway/src/observation/ocr-adapter.ts` 中的 `OcrObservationAdapter`：

```ts
const ocrClient = new TesseractOcrClient({
  // 将 media:// 标识解析成实际可读的本地图片路径。
  resolveMediaRef: (mediaRef) => mediaRepository.resolveLocalPath(mediaRef),
});

const menuAnalyzer = new OcrObservationAdapter(ocrClient);
```

`OcrLine` 保留了现有接口的 `text` 和 `confidence` 字段，`confidence` 已从 Tesseract 的 0–100 换算为 0–1；另外提供可选 `boundingBox`，方便后续把菜名与同一行附近的价格关联起来。当前 `OcrObservationAdapter` 会消费文本和置信度，暂时不会把位置框写入 observation 结果。

## 图片引用与本地处理

- `recognize` 接收项目的 `mediaRef`，但 OCR 引擎只能读图片文件路径，所以真实接入时必须传 `resolveMediaRef`。
- 仓库测试中的 `media://image-1` 是占位引用，不对应真实图片文件；当前项目还没有生产媒体仓库实现。
- 图片识别通过本地 WASM 引擎执行，不发送到 OCR 云服务。首次运行会从 Tesseract.js 数据源下载所需语言模型，并缓存到用户目录下的 `.cache/seeing-ai-ocr`；之后使用缓存。
- 从第一次启动起就必须断网时，将 `chi_sim`、`eng` 的 `.traineddata` 放入本地目录，设置 `langPath` 并设 `gzip: false`。本代码包不提交模型权重。
- 组件保留一个 worker 供多张静态图片复用；当前适用于用户按键拍摄后的单张菜单识别，不应把每一帧视频都排队送入它。

## 质量与限制

- 已验证代码结构、置信度/位置映射和本地 worker 生命周期；在一张开发者工具截图上的冒烟识别能返回多行文本。
- 目前没有用餐厅菜单照片做准确率、耗时和低端手机内存测试。截图冒烟测试不能代表菜名、价格识别已经可靠。
- Tesseract 置信度是识别器分数，不是校准过的正确率。低清、倾斜、反光、复杂版面都可能造成误识别；调用方仍应保留重拍/复核路径。
- 若后续手机端成为 Android 原生应用并要求离线、实时相机识别，应再比较 ML Kit Text Recognition v2 等原生实现，不要把本 Node.js worker 直接当成手机相机引擎。

## 运行环境与验证

Tesseract.js `7.0.0` 支持 Node.js；本项目当前开发运行时为 Node.js 24。依赖由仓库根目录管理，组件的单测和类型检查通过根目录脚本执行：

```powershell
pnpm test
pnpm typecheck
```

上游项目与 API：<https://github.com/naptha/tesseract.js/>。
