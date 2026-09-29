import { mkdir } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import type { Worker } from "tesseract.js";

export interface OcrBoundingBox {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** 和项目现有 OcrClient 的返回字段兼容，并额外保留文本位置。 */
export interface OcrLine {
  text: string;
  /** 统一成 0 到 1，便于与项目现有置信度阈值对接。 */
  confidence: number;
  boundingBox?: OcrBoundingBox;
}

export interface OcrClient {
  recognize(mediaRef: string): Promise<OcrLine[]>;
}

export interface TesseractWorkerLike {
  recognize(
    image: string,
    options?: Record<string, unknown>,
    output?: { blocks?: boolean },
  ): Promise<{ data: { blocks?: TesseractBlock[] | null } }>;
  terminate(): Promise<unknown>;
}

export interface TesseractBlock {
  paragraphs?: Array<{
    lines?: Array<{
      text?: string;
      confidence?: number;
      bbox?: { x0: number; y0: number; x1: number; y1: number };
    }>;
  }>;
}

export interface TesseractOcrOptions {
  /** 资源引用转成本地文件路径；media:// 之类的项目引用必须提供解析器。 */
  resolveMediaRef?: (mediaRef: string) => string | Promise<string>;
  /** 用简体中文和英文识别菜单中的汉字、数字和常见英文。 */
  languages?: string[];
  /** 持久缓存目录；默认写入用户目录下的 .cache/seeing-ai-ocr。 */
  cachePath?: string;
  /** 可选的本地模型目录，配置后可用于严格离线启动。 */
  langPath?: string;
  /** 本地模型是否为 gzip 压缩格式；读取未压缩 .traineddata 时设置为 false。 */
  gzip?: boolean;
  /** 测试替身注入点；普通使用无需填写。 */
  createWorker?: () => Promise<TesseractWorkerLike>;
}

/** 把 Tesseract.js 封装成项目 observation 层需要的 OcrClient。 */
export class TesseractOcrClient implements OcrClient {
  private readonly resolveMediaRef: (mediaRef: string) => string | Promise<string>;
  private readonly createWorker: () => Promise<TesseractWorkerLike>;
  private workerPromise?: Promise<TesseractWorkerLike>;
  private isClosed = false;

  constructor(options: TesseractOcrOptions = {}) {
    this.resolveMediaRef = options.resolveMediaRef ?? ((mediaRef) => mediaRef);
    this.createWorker = options.createWorker ?? createTesseractWorkerFactory(options);
  }

  async recognize(mediaRef: string): Promise<OcrLine[]> {
    if (this.isClosed) {
      throw new Error("OCR 客户端已关闭，请创建新实例后再识别。");
    }
    if (!mediaRef.trim()) {
      throw new Error("mediaRef 不能为空。");
    }

    const imagePath = await this.resolveMediaRef(mediaRef);
    if (!imagePath.trim()) {
      throw new Error(`mediaRef 没有解析到本地图片路径：${mediaRef}`);
    }

    const worker = await this.getWorker();
    const { data } = await worker.recognize(imagePath, {}, { blocks: true });
    return mapTesseractBlocks(data.blocks ?? []);
  }

  /** 结束识别线程并释放 wasm 内存；应用退出时调用。 */
  async close(): Promise<void> {
    if (this.isClosed) return;
    this.isClosed = true;
    if (!this.workerPromise) return;

    const worker = await this.workerPromise;
    await worker.terminate();
  }

  private getWorker(): Promise<TesseractWorkerLike> {
    this.workerPromise ??= this.createWorker();
    return this.workerPromise;
  }
}

export function mapTesseractBlocks(blocks: TesseractBlock[]): OcrLine[] {
  const lines: OcrLine[] = [];

  for (const block of blocks) {
    for (const paragraph of block.paragraphs ?? []) {
      for (const line of paragraph.lines ?? []) {
        const text = line.text?.trim() ?? "";
        if (!text) continue;

        const confidence = Math.max(0, Math.min(1, (line.confidence ?? 0) / 100));
        const bbox = line.bbox;
        lines.push({
          text,
          confidence,
          ...(bbox
            ? {
                boundingBox: {
                  left: bbox.x0,
                  top: bbox.y0,
                  right: bbox.x1,
                  bottom: bbox.y1,
                },
              }
            : {}),
        });
      }
    }
  }

  return lines;
}

function createTesseractWorkerFactory(
  options: TesseractOcrOptions,
): () => Promise<TesseractWorkerLike> {
  return async () => {
    const { createWorker } = await import("tesseract.js");
    const cachePath = options.cachePath ?? join(homedir(), ".cache", "seeing-ai-ocr");
    await mkdir(cachePath, { recursive: true });

    const workerOptions: { cachePath: string; langPath?: string; gzip?: boolean } = {
      cachePath,
    };
    if (options.langPath) workerOptions.langPath = options.langPath;
    if (options.gzip !== undefined) workerOptions.gzip = options.gzip;

    const worker: Worker = await createWorker(
      // 英文模型放在前面，兼容当前环境中中英联合加载的顺序问题。
      options.languages ?? ["eng", "chi_sim"],
      undefined,
      workerOptions,
    );
    return worker;
  };
}
