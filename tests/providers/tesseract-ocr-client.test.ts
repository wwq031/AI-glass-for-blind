import assert from "node:assert/strict";
import test from "node:test";
import {
  mapTesseractBlocks,
  TesseractOcrClient,
  type TesseractWorkerLike,
} from "../../packages/providers/ocr/tesseract-ocr-client.ts";

test("把 Tesseract 行映射为项目置信度与位置框格式", () => {
  const lines = mapTesseractBlocks([
    {
      paragraphs: [
        {
          lines: [
            {
              text: " 牛肉面  ",
              confidence: 92,
              bbox: { x0: 10, y0: 20, x1: 120, y1: 44 },
            },
            { text: "   ", confidence: 99 },
            { text: "¥ 28", confidence: 35 },
          ],
        },
      ],
    },
  ]);

  assert.deepEqual(lines, [
    {
      text: "牛肉面",
      confidence: 0.92,
      boundingBox: { left: 10, top: 20, right: 120, bottom: 44 },
    },
    { text: "¥ 28", confidence: 0.35 },
  ]);
});

test("复用同一个本地 worker 并解析项目 mediaRef", async () => {
  let workerCreations = 0;
  const recognizedPaths: string[] = [];
  let terminated = false;
  const worker: TesseractWorkerLike = {
    async recognize(image) {
      recognizedPaths.push(image);
      return {
        data: {
          blocks: [
            { paragraphs: [{ lines: [{ text: "菜单", confidence: 80 }] }] },
          ],
        },
      };
    },
    async terminate() {
      terminated = true;
    },
  };

  const client = new TesseractOcrClient({
    resolveMediaRef: (ref) => ref.replace("media://", "C:/demo/"),
    createWorker: async () => {
      workerCreations += 1;
      return worker;
    },
  });

  assert.deepEqual(await client.recognize("media://menu.jpg"), [
    { text: "菜单", confidence: 0.8 },
  ]);
  await client.recognize("media://menu-2.jpg");
  await client.close();

  assert.equal(workerCreations, 1);
  assert.deepEqual(recognizedPaths, ["C:/demo/menu.jpg", "C:/demo/menu-2.jpg"]);
  assert.equal(terminated, true);
});

test("拒绝空图片引用并在关闭后拒绝继续识别", async () => {
  const client = new TesseractOcrClient({
    createWorker: async () => ({
      async recognize() {
        return { data: { blocks: [] } };
      },
      async terminate() {},
    }),
  });

  await assert.rejects(client.recognize("  "), /mediaRef 不能为空/);
  await client.close();
  await assert.rejects(client.recognize("menu.jpg"), /客户端已关闭/);
});
