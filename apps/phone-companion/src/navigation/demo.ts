/**
 * 最小闭环演示：定位 → 找附近的地方 → 算步行路线 → 说人话。
 *
 * 运行方式：
 *   1. 没有高德 key：直接 `pnpm dev`，走内置假数据，一样能看到指路文本。
 *   2. 有高德 key：把 key 写进项目根目录的 `.env` 文件（复制 `.env.example` 后改一行），
 *      或设置环境变量 AMAP_KEY 后运行 `pnpm dev`。
 *
 * 真实产品中，起点坐标来自手机定位（现在用假定位），目的地来自"围绕当前位置"的 POI 搜索。
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { AmapNavigationProvider } from "./map-adapter.ts";
import { FakeNavigationProvider } from "../../../../packages/testkit/fake-navigation-provider.ts";
import { FakeLocationProvider } from "../../../../packages/testkit/fake-location-provider.ts";
import { FakeSpeechInputProvider } from "../../../../packages/testkit/fake-speech-input-provider.ts";
import type {
  SpeechInput,
  SpeechInputProvider,
} from "../../../../packages/providers/speech/speech-input-provider.ts";
import type {
  DestinationQuery,
  NavigationProvider,
} from "../../../../packages/providers/navigation/navigation-provider.ts";

/**
 * 读取高德 key，优先级：环境变量 AMAP_KEY > 项目根目录 .env 文件。
 */
function readAmapKey(): string {
  const fromEnv = process.env.AMAP_KEY?.trim();
  if (fromEnv) return fromEnv;

  try {
    const envPath = join(import.meta.dirname, "../../../../.env");
    const content = readFileSync(envPath, "utf8");
    const match = content.match(/^AMAP_KEY\s*=\s*(.+)$/m);
    if (match?.[1]) {
      return match[1].trim().replace(/^["']|["']$/g, "");
    }
  } catch {
    // .env 不存在，忽略
  }
  return "";
}

const AMAP_KEY = readAmapKey();

function buildProvider(): NavigationProvider {
  if (AMAP_KEY && process.env.DEMO_USE_FAKE_NAVIGATION !== "1") {
    console.log(`[配置] 使用真实高德接口（key=${AMAP_KEY.slice(0, 4)}…${AMAP_KEY.slice(-4)}）`);
    return new AmapNavigationProvider({ apiKey: AMAP_KEY });
  }
  console.log("[配置] 未检测到 AMAP_KEY，使用内置假数据（把 key 写进 .env 即可切换）");
  return new FakeNavigationProvider();
}

async function captureDestination(
  provider: SpeechInputProvider,
  sessionId: string,
): Promise<SpeechInput> {
  let unsubscribe = () => {};
  const inputPromise = new Promise<SpeechInput>((resolve) => {
    unsubscribe = provider.subscribe((input) => {
      if (input.session_id === sessionId && input.is_final) resolve(input);
    });
  });
  const handle = await provider.start(sessionId, "destination");
  const input = await inputPromise;
  unsubscribe();
  await provider.stop(handle);
  return input;
}

async function main() {
  const provider = buildProvider();
  const sessionId = "session-demo-001";

  // A phone build can inject real microphone/ASR input through the same seam.
  const transcript = process.argv.slice(2).join(" ").trim() || "人民公园";
  const speechProvider = new FakeSpeechInputProvider({ transcript });
  const speechInput = await captureDestination(speechProvider, sessionId);
  console.log(`语音输入：${speechInput.transcript}`);

  // ── 第 1 步：拿到"我的位置"（定位） ──────────────────────────────
  // 真机上用高德定位 SDK / 系统定位；现在用假定位返回演示坐标。
  const locationProvider = new FakeLocationProvider();
  const origin = await locationProvider.getCurrentLocation();
  console.log(`我的位置（定位）：${origin.lng},${origin.lat}`);

  const query: DestinationQuery = {
    schema_version: "1.0",
    query_id: "query-001",
    session_id: sessionId,
    transcript: speechInput.transcript,
    locale: speechInput.locale,
    requested_at: new Date().toISOString(),
  };

  // ── 第 2 步：找附近的地方 ────────────────────────────────────────
  const result = await provider.search(query, { near: origin });
  console.log(`\n找到 ${result.candidates.length} 个候选（按距离排序）：`);
  result.candidates.forEach((c, i) => {
    const dist = c.distance_m ? `，约 ${Math.round(c.distance_m)} 米` : "";
    const coord = c.location ? `，坐标 ${c.location.lng},${c.location.lat}` : "，无坐标";
    console.log(`  [${i + 1}] ${c.name}（${c.address ?? "地址未知"}${dist}${coord}）`);
  });

  const target = result.candidates[0];
  if (!target) {
    console.log("没有找到候选，请换一个目的地再试。");
    return;
  }
  if (!target.location) {
    console.log(`候选「${target.name}」缺少经纬度，无法计算路线。`);
    return;
  }

  // ── 第 3 步：算步行路线 ──────────────────────────────────────────
  const route = await provider.walkingRoute(origin, target.location);

  // ── 第 4 步：把路线翻译成"指路人话" ──────────────────────────────
  console.log(`\n路线：全程 ${route.distance_m} 米，约 ${Math.round(route.duration_sec / 60)} 分钟`);
  console.log("\n【盲人指路播报】");
  route.steps.forEach((s, i) => {
    console.log(`  第 ${i + 1} 步：${s.instruction}`);
  });
  console.log(`\n  已到达目的地：${target.name}`);
}

main().catch((err) => {
  console.error("\n运行失败：", err instanceof Error ? err.message : err);
  process.exitCode = 1;
});
