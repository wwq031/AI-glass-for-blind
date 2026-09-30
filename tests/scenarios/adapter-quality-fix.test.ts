import assert from "node:assert/strict";
import test from "node:test";

import type { ToolResult } from "../../packages/domain/agent/types.ts";
import { VlmObservationAdapter } from "../../apps/gateway/src/observation/vlm-adapter.ts";
import { OcrObservationAdapter } from "../../apps/gateway/src/observation/ocr-adapter.ts";
import { SessionFactStore } from "../../apps/phone-companion/src/android/session-facts.ts";
import { SpeechQueue } from "../../apps/phone-companion/src/android/speech-queue.ts";
import { createNativeVisionClient } from "../../apps/phone-companion/src/android/vision-client.ts";

const T0 = "2026-09-30T03:00:00.000Z";

/** A ToolResult carrying one observation batch, the way tool-handlers hands one over. */
function observationResult(sessionId: string, values: string[], at = T0): ToolResult {
  return {
    callId: `call-${values.join("-")}`,
    sessionId,
    toolId: `observation:vision.menu`,
    status: "succeeded",
    completedAt: at,
    output: {},
    facts: values.map((value) => ({ name: "menu.text_line", value, confidence: "high" as const })),
  };
}

test("one observation batch keeps every same-name OCR line, and the next batch replaces it", () => {
  const store = new SessionFactStore({ now: () => new Date(T0) });
  store.recordResult(observationResult("s1", ["担担面", "担担面", "酸辣粉"]));

  const lines = store.query("s1", ["menu.text_line"], 10);
  assert.deepEqual(lines.map((fact) => fact.value), ["担担面", "担担面", "酸辣粉"]);
  // Two lines with the same text are still two claims, each with its own identity.
  assert.equal(new Set(lines.map((fact) => fact.id)).size, 3);
  assert.equal(new Set(lines.map((fact) => fact.batchId)).size, 1);

  store.recordResult(observationResult("s1", ["小面", "抄手"], "2026-09-30T03:00:10.000Z"));
  const replaced = store.query("s1", ["menu.text_line"], 10);
  assert.deepEqual(replaced.map((fact) => fact.value), ["小面", "抄手"]);
});

test("a scalar nav fact stays one live claim per name", () => {
  const store = new SessionFactStore({ now: () => new Date(T0) });
  store.record("s1", { name: "traffic_signal.state", value: "red", confidence: "high", source: "vision" });
  store.record("s1", { name: "traffic_signal.state", value: "green", confidence: "high", source: "vision" });
  const facts = store.query("s1", ["traffic_signal.state"], 5);
  assert.deepEqual(facts.map((fact) => fact.value), ["green"]);
});

test("query scopes narrow the same recorded facts", () => {
  const store = new SessionFactStore({ now: () => new Date(T0) });
  store.record("s1", { name: "route.instruction", value: "左转", confidence: "high", source: "navigation.turn" });
  store.recordResult(observationResult("s1", ["老店", "本店"]));
  store.recordBatch("s1", [{ name: "entrance.door_side", value: "left", confidence: "high", source: "vision" }],
    "observation:vision.entrance");

  const names = ["route.instruction", "menu.text_line", "entrance.door_side"];
  const wholeSession = ["route.instruction", "menu.text_line", "menu.text_line", "entrance.door_side"];
  assert.deepEqual(store.query("s1", names, 10).map((fact) => fact.name), wholeSession);
  assert.deepEqual(store.query("s1", names, 10, "current_session").map((fact) => fact.name), wholeSession);
  assert.deepEqual(store.query("s1", names, 10, "recent_observation").map((fact) => fact.name),
    ["entrance.door_side"]);
  assert.deepEqual(store.query("s1", names, 10, "navigation_context").map((fact) => fact.name),
    ["route.instruction"]);
  // An unregistered scope reads as the default rather than widening the answer.
  assert.deepEqual(store.query("s1", names, 10, "everything" as unknown as "current_session").map((f) => f.name),
    wholeSession);
});

test("expired facts and over-long batches stay bounded", () => {
  const store = new SessionFactStore({ now: () => new Date("2026-09-30T03:05:00.000Z"), limit: 4 });
  store.record("s1", {
    name: "vehicle.activity", value: "yes", confidence: "high", source: "vision",
    validUntil: "2026-09-30T03:00:30.000Z",
  });
  assert.deepEqual(store.query("s1", ["vehicle.activity"], 5), []);

  store.recordResult(observationResult("s1", ["一", "二", "三", "四", "五"]));
  const kept = store.query("s1", ["menu.text_line"], 10);
  assert.equal(kept.length, 4);
  // The bounded store still keeps the whole current batch's tail rather than evicting it entirely.
  assert.deepEqual(kept.map((fact) => fact.value), ["二", "三", "四", "五"]);
});

test("an utterance that expires while queued settles false instead of never settling", async () => {
  const spoken: string[] = [];
  const queue = new SpeechQueue({
    now: () => new Date(T0),
    ttlMs: 0,
    idFactory: () => `effect-${spoken.length}`,
    speak: async (effect) => { spoken.push(effect.text); return true; },
  });
  assert.equal(await queue.enqueue("已经过期的提示", "normal", "s1"), false);
  await queue.idle();
  assert.deepEqual(spoken, []);
  assert.equal(queue.size, 0);
});

test("an utterance that expires behind another settles false without anyone calling idle", async () => {
  let clock = Date.parse(T0);
  const spoken: string[] = [];
  const blocking = (() => {
    let release!: (finished: boolean) => void;
    const promise = new Promise<boolean>((resolve) => { release = resolve; });
    return { promise, release };
  })();
  const queue = new SpeechQueue({
    now: () => new Date(clock),
    ttlMs: 100,
    idFactory: (() => { let n = 0; return () => `effect-${++n}`; })(),
    speak: async (effect) => { spoken.push(effect.text); return blocking.promise; },
  });

  const active = queue.enqueue("正在说话。", "normal", "s1");
  await new Promise((resolve) => setTimeout(resolve, 0));
  clock += 200;
  const expiresWhileWaiting = queue.enqueue("排在后面，会过期。", "normal", "s2");
  clock += 200;
  blocking.release(true);
  assert.equal(await active, true);

  // The policy drops the expired entry the moment it is asked for the next one, so the queue has to
  // settle it before that ask; otherwise nothing would ever settle this promise again.
  const settled = await Promise.race([
    expiresWhileWaiting,
    new Promise((resolve) => setTimeout(() => resolve("never settled"), 2_000)),
  ]);
  assert.equal(settled, false);
  assert.deepEqual(spoken, ["正在说话。"]);
});

test("cancelSession settles queued utterances false and never replays them", async () => {
  const spoken: string[] = [];
  const first = (() => {
    let release!: (finished: boolean) => void;
    const promise = new Promise<boolean>((resolve) => { release = resolve; });
    return { promise, release };
  })();
  const queue = new SpeechQueue({
    now: () => new Date(T0),
    idFactory: (() => { let n = 0; return () => `effect-${++n}`; })(),
    speak: async (effect) => {
      spoken.push(effect.text);
      return effect.priority === "critical" ? first.promise : true;
    },
  });

  const critical = queue.enqueue("前方路口，请停下。", "critical", "s1");
  const queuedHigh = queue.enqueue("即将到达路口。", "high", "s1");
  const otherSession = queue.enqueue("别的会话。", "normal", "s2");
  await new Promise((resolve) => setTimeout(resolve, 0));

  queue.cancelSession("s1");
  assert.equal(await queuedHigh, false);
  // The active utterance is the native owner's to revoke; the queue takes its playback verdict.
  first.release(true);
  assert.equal(await critical, true);
  // The other session's utterance is not this cancellation's to drop, and waits its turn.
  assert.equal(await otherSession, true);
  await queue.idle();

  assert.deepEqual(spoken, ["前方路口，请停下。", "别的会话。"]);
  assert.equal(queue.size, 0);
});

test("a traffic observation without a trusted capture time is unknown, not observed", async () => {
  const inferenceAt = Date.parse("2026-09-30T03:00:00.000Z");
  const make = () => createNativeVisionClient(
    async () => JSON.stringify({
      summary: "路口观察到绿灯。", confidence: "high",
      facts: [
        { name: "traffic_signal.state", value: "green", confidence: "high" },
        { name: "traffic_signal.direction_match", value: "yes", confidence: "high" },
        { name: "crosswalk.present", value: true, confidence: "high" },
        { name: "vehicle.activity", value: "no", confidence: "high" },
      ],
    }),
    { now: () => inferenceAt, validUntilMs: 60_000 },
  );
  const adapter = new VlmObservationAdapter(make().vlm);
  const context = { intersection_id: "junction-7", travel_heading_deg: 90 };

  const missing = await adapter.analyze({
    schema_version: "1.0", session_id: "s1", request_id: "r1", capability_id: "vision.traffic_signal",
    trigger: "user_button", media_refs: ["media-1"], consent: "explicit", context,
  });
  assert.equal(missing.needs_retake, true);
  assert.equal(missing.confidence, "unknown");
  assert.ok(missing.limitations?.includes("capture_time_untrusted"));
  const missingFacts = Object.fromEntries(missing.facts.map((fact) => [fact.name, fact.value]));
  assert.equal(missingFacts["traffic_signal.state"], "unknown");
  assert.equal(missingFacts["traffic_signal.direction_match"], "unknown");
  assert.equal("crosswalk.present" in missingFacts, false);

  // A frame the device captured 120s before inference is older than its 60s window, whatever the
  // model read in it: the receipt's own timestamp is the only thing that could have dated it.
  const stale = await adapter.analyze({
    schema_version: "1.0", session_id: "s1", request_id: "r2", capability_id: "vision.traffic_signal",
    trigger: "user_button", media_refs: ["media-1"], consent: "explicit",
    context: { ...context, captured_at: "2026-09-30T02:58:00.000Z" },
  });
  assert.equal(stale.needs_retake, true);
  assert.ok(stale.limitations?.includes("capture_expired"));
  assert.equal(Object.fromEntries(stale.facts.map((f) => [f.name, f.value]))["traffic_signal.state"], "unknown");
});

test("a travel heading never becomes a direction match, and facts expire from the capture", async () => {
  const inferenceAt = Date.parse("2026-09-30T03:00:00.000Z");
  const capturedAt = "2026-09-30T02:59:50.000Z";
  const vision = createNativeVisionClient(
    async () => JSON.stringify({
      summary: "画面中信号灯为红色。", confidence: "high",
      facts: [
        { name: "traffic_signal.state", value: "red", confidence: "high" },
        { name: "traffic_signal.direction_match", value: "yes", confidence: "high" },
        { name: "crosswalk.present", value: true, confidence: "medium" },
      ],
    }),
    { now: () => inferenceAt, validUntilMs: 60_000 },
  );
  const result = await new VlmObservationAdapter(vision.vlm).analyze({
    schema_version: "1.0", session_id: "s1", request_id: "r1", capability_id: "vision.traffic_signal",
    trigger: "user_button", media_refs: ["media-1"], consent: "explicit",
    // The device confirmed the intersection and the walking direction — and nothing about the camera.
    context: { intersection_id: "junction-7", travel_heading_deg: 90, captured_at: capturedAt },
  });

  const byName = Object.fromEntries(result.facts.map((fact) => [fact.name, fact]));
  assert.equal(byName["traffic_signal.state"]?.value, "red");
  assert.equal(byName["traffic_signal.direction_match"]?.value, "unknown");
  assert.equal(byName["crosswalk.present"]?.value, true);
  // Freshness runs from the capture the device reported, not from when inference finished.
  assert.equal(byName["traffic_signal.state"]?.valid_until, "2026-09-30T03:00:50.000Z");
  assert.deepEqual(byName["crosswalk.present"]?.evidence, [`capture:${capturedAt}`]);
  assert.equal(result.needs_retake, false);
});

test("unstructured device answers become no facts and a retake, never a success", async () => {
  const vision = createNativeVisionClient(async () => "画面里好像有个人。", { now: () => Date.parse(T0) });
  const unparsed = await new VlmObservationAdapter(vision.vlm).analyze({
    schema_version: "1.0", session_id: "s1", request_id: "r1", capability_id: "vision.scene",
    trigger: "user_button", media_refs: ["media-1"], consent: "explicit",
  });
  assert.equal(unparsed.needs_retake, true);
  assert.deepEqual(unparsed.facts, []);
  assert.deepEqual(unparsed.limitations, ["vision_result_unparsed"]);

  // The older device build answers a bare summary; a sentence is not evidence.
  const legacyVision = createNativeVisionClient(
    async () => JSON.stringify({ summary: "这是一家面馆。" }), { now: () => Date.parse(T0) });
  const legacy = await new VlmObservationAdapter(legacyVision.vlm).analyze({
    schema_version: "1.0", session_id: "s1", request_id: "r2", capability_id: "vision.menu",
    trigger: "user_button", media_refs: ["media-1"], consent: "explicit",
  });
  assert.equal(legacy.needs_retake, true);
  assert.deepEqual(legacy.facts, []);
  assert.deepEqual(legacy.limitations, ["vision_result_not_structured"]);

  const ocr = await new OcrObservationAdapter(legacyVision.ocr).analyze({
    schema_version: "1.0", session_id: "s1", request_id: "r3", capability_id: "vision.menu",
    trigger: "user_button", media_refs: ["media-1"], consent: "explicit",
  });
  assert.equal(ocr.needs_retake, true);
  assert.deepEqual(ocr.facts, []);
});

test("OCR lines from the device port keep every line in reading order", async () => {
  const vision = createNativeVisionClient(
    async () => '```json\n{"lines":[{"text":"担担面 12","confidence":0.9},{"text":"酸辣粉 10","confidence":0.7}]}\n```',
    { now: () => Date.parse(T0) },
  );
  const lines = await vision.ocr.recognize("media-1");
  assert.deepEqual(lines.map((line) => line.text), ["担担面 12", "酸辣粉 10"]);
  assert.deepEqual(lines.map((line) => line.confidence), [0.9, 0.7]);
});
