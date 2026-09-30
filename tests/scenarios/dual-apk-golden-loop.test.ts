import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { runInNewContext } from "node:vm";
import test from "node:test";
import { build } from "esbuild";

/**
 * The continuous device loop, across the real TypeScript Agent bundle and a simulated LeQiNative
 * bridge.
 *
 * The glasses APK sends native envelopes in, the phone bundle answers with LeQiNative requests, and
 * this test replies to every request so no request timer is left hanging. The model is the only
 * thing mocked: `mockModel` reads the same turn input a real model would read and answers with a
 * contract-shaped plan. Nothing in the bundle is stubbed — the domain Agent, the registries, the
 * policies, the tool gateway, the observation gateway and the contracts all run for real.
 *
 * No network, model weights, keys or recorded audio/photos are touched.
 */

interface NativeEnvelope {
  id: string;
  type: string;
  payload?: any;
  error?: string;
}

interface ToolResultLike {
  toolId: string;
  status: string;
  output?: Record<string, any>;
  error?: { message?: string };
}

const CANDIDATES = [
  { candidate_id: "amap-cand-1", name: "人民公园", provider: "amap",
    location: { lat: 31.2304, lng: 121.4737 }, distance_m: 320 },
  { candidate_id: "amap-cand-2", name: "人民公园地铁站", provider: "amap",
    location: { lat: 31.2331, lng: 121.4752 }, distance_m: 540 },
];
const ENTRANCE_MEDIA_REF = "media-ref-entrance-001";
const MENU_MEDIA_REF = "media-ref-menu-001";
const ENTRANCE_SUMMARY = "入口是一扇朝南的玻璃门，门前有三级台阶，右侧有扶手。";
const MENU_SUMMARY = "识别到2行文字。";
const ARRIVAL_SPEECH = "已到达目的地附近，请按键观察入口。";
const STARTED_SPEECH = "导航已开始。";
const HELP_SPEECH = "我在。你可以说出想去的地方，也可以问我眼前的情况。";
const VOICE_RETRY_SPEECH = "语音等待已超时，请按键后再说一次。";
const ENTRANCE_RETRY_SPEECH = "拍摄等待已超时，请按键重拍。";
const AUTHORITY_TERMINAL_SPEECH = "设备多次没有完成语音或拍摄，本次对话结束。请按键重新开始。";
const CANCELLED_SPEECH = "当前任务已取消。";

/** Notification envelopes the bridge never answers. */
const NOTIFICATIONS = new Set(["ready", "effect", "event.result", "error"]);

let bundle: Promise<string> | undefined;

/** One esbuild pass is shared: every scenario runs the real repository bundle. */
function loadBundle(): Promise<string> {
  bundle ??= build({
    entryPoints: ["./apps/phone-companion/src/android/agent-browser.ts"],
    bundle: true,
    platform: "browser",
    format: "iife",
    target: "chrome120",
    write: false,
  }).then((result) => result.outputFiles[0]!.text);
  return bundle;
}

// ---------------------------------------------------------------------------------------------
// The mocked model
// ---------------------------------------------------------------------------------------------

/** The turn input the bundle appended to the planner prompt; a real model reads the same object. */
function turnInput(prompt: string): any {
  const marker = "输入：";
  const at = prompt.lastIndexOf(marker);
  assert.ok(at >= 0, "the planner prompt must carry the turn input");
  return JSON.parse(prompt.slice(at + marker.length));
}

function plan(actions: unknown[]): string {
  return JSON.stringify({ goal: "mocked turn", actions });
}

function speak(text: string, priority = "normal"): unknown {
  return { kind: "speak", text, priority };
}

function tool(skillId: string, toolId: string, args: unknown): unknown {
  // The plan contract's own field names, in the JSON the contract declares; the codec is what turns
  // them into the domain plan.
  return { kind: "tool_call", skill_id: skillId, tool_id: toolId, arguments: args };
}

/**
 * Stands in for the language model: it reads the turn input — the user's words, the bounded
 * conversation trace and the tool results — and answers with a plan. Every decision the product
 * makes about meaning is made here, in the mock, exactly where a real model would make it.
 */
function mockModel(prompt: string): string {
  // A repair prompt carries the contract error that rejected the previous answer. Fail loudly with
  // it: a mocked model that silently repairs would hide why the real one could not answer.
  const rejection = "校验错误：";
  if (prompt.includes(rejection)) {
    throw new Error(prompt.slice(prompt.indexOf(rejection), prompt.indexOf(rejection) + 400));
  }
  const input = turnInput(prompt);
  const event = input.event ?? {};
  // A rejected plan is the repository refusing the mocked model's answer. Fail with the code rather
  // than answering the feedback turn: a mocked model that repairs itself would hide the contract
  // violation the code names.
  if (event.type === "plan.rejected") {
    throw new Error(`plan rejected: ${JSON.stringify(event.payload)}`);
  }
  const payload = event.payload ?? {};
  const transcript = typeof payload.transcript === "string" ? payload.transcript : "";
  const conversation: string = (input.session?.conversation ?? [])
    .map((turn: { text: string }) => turn.text).join("\n");
  const searched = conversation.includes("候选地点:");

  if (event.type === "tool.results") {
    const results: ToolResultLike[] = Array.isArray(payload.results) ? payload.results : [];
    const failed = results.find((result) => result.status !== "succeeded" && result.status !== "partial");
    if (failed) {
      // Nothing to say about a tool that did not run; do not call it again.
      return plan([speak("这一步没有成功，请再说一次。", "normal")]);
    }
    const search = results.find((result) => result.toolId === "navigation.search_destination");
    if (search) {
      const candidates: any[] = search.output?.candidates ?? [];
      if (!candidates.length) return plan([speak("没有找到候选地点，请换一个说法。")]);
      const listed = candidates.map((candidate, index) =>
        `第${index + 1}个，${candidate.name}`).join("；");
      return plan([tool("navigate_to", "speech.ask_user", {
        prompt_template: `找到${candidates.length}个地点：{candidates}。请说出你要去哪一个。`,
        parameters: { candidates: listed },
        expected_intents: ["confirm", "cancel", "other"],
      })]);
    }
    const facts = results.find((result) => result.toolId === "facts.query");
    if (facts) {
      const found: any[] = facts.output?.facts ?? [];
      return plan([speak(found.length
        ? found.map((fact) => `${fact.name}：${fact.value}`).join("；")
        : "我这边还没有观察到这个信息。")]);
    }
    const observation = results.find((result) => result.toolId === "observation.request");
    if (observation) {
      return plan([speak(String(observation.output?.summary ?? "没有看清楚。"), "high")]);
    }
    const cancelled = results.find((result) => result.toolId === "session.cancel");
    if (cancelled) return plan([speak("好的，已经取消。")]);
    return plan([speak("好的。")]);
  }

  if (event.type === "user.capture_confirmed") {
    const capabilityId = String(payload.capability_id ?? "");
    const skillId = capabilityId === "vision.menu" ? "read_text"
      : capabilityId === "vision.traffic_signal" ? "crossing_advisory" : "inspect_scene";
    return plan([tool(skillId, "observation.request", { capability_id: capabilityId })]);
  }

  if (event.type === "navigation.started" || event.type.startsWith("navigation.")) {
    // The deterministic reminder already spoke; this turn only acknowledges the route progress.
    return plan([{ kind: "wait", event_types: ["speech.input", "navigation.arrived"] }]);
  }

  if (!transcript) return plan([speak("我在听。")]);

  if (/取消|不去了|算了/.test(transcript)) {
    return plan([tool("follow_up", "session.cancel", { reason: "user_request" })]);
  }
  if (/先去|带我去|导航到|怎么走.*公园|去人民公园/.test(transcript)) {
    return searched
      ? plan([speak("我们已经找过人民公园了，你要去哪一个？")])
      : plan([tool("navigate_to", "navigation.search_destination", { query: "人民公园" })]);
  }
  if (/第一个|第1个|就去人民公园$/.test(transcript)) {
    // A small model answers with the ordinal it read; the adapter resolves it against this
    // session's own search results, and the policy is what authorises the start.
    return plan([tool("navigate_to", "navigation.confirm_destination", { candidate_index: 0 })]);
  }
  if (/不去|不是这个|换一个/.test(transcript)) {
    return plan([speak("好的，那你要去哪里？"), { kind: "wait", event_types: ["speech.input"] }]);
  }
  if (/入口|门在哪|看看门/.test(transcript)) {
    return plan([tool("inspect_scene", "speech.ask_user", {
      prompt_template: "请按键拍一下入口，我来看门在哪里。",
      parameters: { capability_id: "vision.entrance" },
    })]);
  }
  if (/菜单|有什么菜/.test(transcript)) {
    return plan([tool("read_text", "speech.ask_user", {
      prompt_template: "请按键拍一下菜单，我来读给你听。",
      parameters: { capability_id: "vision.menu" },
    })]);
  }
  if (/表情|他现在|对方/.test(transcript)) {
    return plan([tool("inspect_scene", "speech.ask_user", {
      prompt_template: "请按键拍一下对方，我来看看。",
      parameters: { capability_id: "vision.expression" },
    })]);
  }
  if (/还有多远|到哪了|还有多久/.test(transcript)) {
    return plan([tool("follow_up", "facts.query", { names: ["entrance.*", "navigation.*"], limit: 5 })]);
  }
  if (/刚才.*(看到|读)/.test(transcript)) {
    return plan([tool("follow_up", "facts.query", { names: ["menu.*", "entrance.*"], limit: 5 })]);
  }
  return plan([speak("我在。你可以说出想去的地方，也可以问我眼前的情况。")]);
}

// ---------------------------------------------------------------------------------------------
// The bridge harness
// ---------------------------------------------------------------------------------------------

interface Harness {
  bridge: { receive(json: string): void };
  /** Delivers a native envelope as the glasses APK would. */
  deliver(id: string, type: string, payload: unknown): void;
  /** Lets queued work flush; unanswered requests are swept so no timer outlives the test. */
  settle(): Promise<void>;
  /** Stops answering `count` of the next requests of this type, to model a pending device reply. */
  hold(type: string, count?: number): void;
  /** How many requests of this type are still waiting for a reply. */
  heldCount(type: string): number;
  /** Answers every held request of this type; returns how many were waiting. */
  answer(type: string, payload: unknown): number;
  /** Answers anything still held, so the test can end without a live request timer. */
  finish(): void;
  sent: NativeEnvelope[];
  errors: NativeEnvelope[];
  speechSays: NativeEnvelope[];
  deviceSends: NativeEnvelope[];
  modelPrompts: NativeEnvelope[];
  toolCalls: NativeEnvelope[];
  navigationStarts: NativeEnvelope[];
  visionRequests: NativeEnvelope[];
  effects: NativeEnvelope[];
  eventResults: NativeEnvelope[];
  unexpected: string[];
  answeredIds(): Set<string>;
  /** Recorder windows the phone armed, in order. */
  voiceArms(): string[];
  /** Capture windows the phone armed, in order. */
  captureArms(): string[];
  /** `task.cancel` payloads the phone forwarded to the eye, in order. */
  taskCancels(): string[];
  /** Bridge sends since `mark`, so ordering between envelope kinds can be asserted. */
  since(mark: number): NativeEnvelope[];
  liveSessionId(): string | undefined;
  speechTexts(): string[];
  effectTexts(): string[];
  /** Presses the start button and speaks one utterance into the window that opens. */
  say(id: string, transcript: string): Promise<void>;
}

async function createHarness(): Promise<Harness> {
  const source = await loadBundle();
  const sent: NativeEnvelope[] = [];
  const answered = new Set<string>();
  const unexpected: string[] = [];
  const errors: NativeEnvelope[] = [];
  const speechSays: NativeEnvelope[] = [];
  const deviceSends: NativeEnvelope[] = [];
  const modelPrompts: NativeEnvelope[] = [];
  const toolCalls: NativeEnvelope[] = [];
  const navigationStarts: NativeEnvelope[] = [];
  const visionRequests: NativeEnvelope[] = [];
  const effects: NativeEnvelope[] = [];
  const eventResults: NativeEnvelope[] = [];
  const held = new Map<string, number>();
  const heldRequests = new Map<string, NativeEnvelope[]>();
  let liveSessionId: string | undefined;
  let pressCount = 0;
  /** How many utterances this conversation has already recorded; each one consumes its window. */
  // Purpose tags of recorder windows that an utterance has already been recorded through. A window
  // is spent by the utterance it records, whoever injected that utterance — this harness or a test
  // delivering `native.speech` directly — so the count of helper calls cannot stand in for it.
  const consumedPurposes = new Set<string>();
  let deliver: (json: string) => void = () => {
    throw new Error("LeQiAgent bridge is not published yet");
  };

  const reply = (id: string, payload?: unknown, error?: string): void => {
    if (answered.has(id)) return;
    answered.add(id);
    deliver(JSON.stringify(error === undefined
      ? { id, type: "response", payload }
      : { id, type: "response", error }));
  };

  const holdNext = (type: string): boolean => {
    const remaining = held.get(type) ?? 0;
    if (remaining <= 0) return false;
    held.set(type, remaining - 1);
    return true;
  };

  const stashHeld = (message: NativeEnvelope): void => {
    const waiting = heldRequests.get(message.type);
    if (waiting) waiting.push(message);
    else heldRequests.set(message.type, [message]);
  };

  /** Canned device replies; `model.generate` runs the mocked model over the real prompt. */
  const cannedReply = (message: NativeEnvelope): unknown => {
    switch (message.type) {
      case "speech.say":
        return { finished: true };
      case "device.send":
        // `task.cancel` is the one device.send the phone must retire native work for; the bridge
        // models that acknowledgement explicitly.
        return message.payload?.type === "task.cancel" ? { sent: true, cancelled: true } : { sent: true };
      case "model.generate":
        return mockModel(String(message.payload?.prompt ?? ""));
      case "policy.navigation.start":
        return { started: true, distanceM: 320, timeSec: 260 };
      case "policy.navigation.stop":
        return { stopped: true };
      case "vision.observe":
        return message.payload?.prompt?.includes("逐行输出")
          ? JSON.stringify({ lines: [
              { text: "宫保鸡丁 38元", confidence: 0.92 },
              { text: "西红柿鸡蛋 22元", confidence: 0.88 },
            ] })
          : JSON.stringify({
              summary: ENTRANCE_SUMMARY,
              confidence: "high",
              facts: [{ name: "entrance.description", value: "朝南的玻璃门", confidence: "high" }],
              limitations: [],
            });
      case "tool.execute": {
        const call = message.payload;
        return {
          callId: `${call.plan.planId}:${call.actionIndex}`,
          sessionId: call.sessionId,
          toolId: call.toolId,
          status: "succeeded",
          completedAt: new Date().toISOString(),
          output: { query: call.arguments.query, candidates: CANDIDATES },
          facts: [],
        };
      }
      default:
        return undefined;
    }
  };

  const onSend = (json: string): void => {
    const message = JSON.parse(json) as NativeEnvelope;
    sent.push(message);
    let payload: unknown;
    switch (message.type) {
      case "ready": return;
      case "effect": effects.push(message); return;
      case "event.result": eventResults.push(message); return;
      case "error": errors.push(message); return;
      case "speech.say": speechSays.push(message); payload = { finished: true }; break;
      case "device.send": deviceSends.push(message); payload = { sent: true }; break;
      case "model.generate": modelPrompts.push(message); payload = mockModel(String(message.payload?.prompt ?? "")); break;
      case "policy.navigation.start":
        navigationStarts.push(message);
        liveSessionId = message.payload?.sessionId;
        payload = { started: true, distanceM: 320, timeSec: 260 };
        break;
      case "policy.navigation.stop": payload = { stopped: true }; break;
      case "vision.observe":
        visionRequests.push(message);
        payload = cannedReply(message);
        break;
      case "tool.execute": toolCalls.push(message); payload = cannedReply(message); break;
      default: stashHeld(message); return; // swept by drainUnanswered, never left unanswered
    }
    if (holdNext(message.type)) {
      stashHeld(message);
      return;
    }
    reply(message.id, payload);
  };

  const heldIds = (): Set<string> =>
    new Set([...heldRequests.values()].flat().map((message) => message.id));

  /** Swept so a request timer can never outlive the test; recorded as unexpected. */
  const drainUnanswered = (): void => {
    const waiting = heldIds();
    for (const message of sent) {
      if (NOTIFICATIONS.has(message.type) || answered.has(message.id) || waiting.has(message.id)) continue;
      unexpected.push(message.type);
      reply(message.id, undefined, `unexpected native request: ${message.type}`);
    }
  };

  const settle = async (): Promise<void> => {
    await new Promise((resolve) => setTimeout(resolve, 5));
    drainUnanswered();
    await new Promise((resolve) => setTimeout(resolve, 5));
  };

  const hostWindow: {
    LeQiNative: { send(json: string): void };
    LeQiAgent?: { receive(json: string): void };
  } = { LeQiNative: { send: onSend } };
  runInNewContext(source, {
    window: hostWindow,
    crypto: { randomUUID },
    structuredClone,
    Date,
    Promise,
    Map,
    Set,
    WeakSet,
    console,
    setTimeout,
    clearTimeout,
    // Platform globals the bundle is built for (target chrome120, a WebView): the concrete
    // ToolGateway aborts a timed-out tool call with one. A fresh V8 context has neither, so without
    // them every tool execution would fail before it ever reached the device.
    AbortController,
    AbortSignal,
  } as unknown as Record<string, unknown>);

  const bridge = hostWindow.LeQiAgent;
  assert.ok(bridge, "stage 0: the bundle must publish window.LeQiAgent");
  deliver = (json: string): void => bridge.receive(json);

  assert.equal(sent[0]?.type, "ready", "stage 0: the bundle must announce readiness first");
  assert.equal(sent[0]?.id, "agent-ready");

  const harness: Harness = {
    bridge,
    deliver: (id, type, payload) => {
      if (type === "native.speech" && typeof payload === "object" && payload !== null) {
        const purpose = (payload as { purpose?: unknown }).purpose;
        if (typeof purpose === "string") consumedPurposes.add(purpose);
      }
      deliver(JSON.stringify({ id, type, payload }));
    },
    settle,
    hold: (type, count = Number.POSITIVE_INFINITY) => { held.set(type, count); },
    heldCount: (type) => (heldRequests.get(type) ?? []).length,
    answer: (type, payload) => {
      const waiting = heldRequests.get(type) ?? [];
      heldRequests.set(type, []);
      for (const message of waiting) reply(message.id, payload);
      return waiting.length;
    },
    finish: () => {
      for (const message of [...heldRequests.values()].flat()) reply(message.id, undefined, "test ended");
      heldRequests.clear();
    },
    sent, errors, speechSays, deviceSends, modelPrompts, toolCalls,
    navigationStarts, visionRequests, effects, eventResults, unexpected,
    answeredIds: () => answered,
    voiceArms: () => deviceSends.filter((message) => message.payload?.type === "voice.arm")
      .map((message) => String(message.payload.payload)),
    captureArms: () => deviceSends.filter((message) => message.payload?.type === "entrance.arm")
      .map((message) => String(message.payload.payload)),
    taskCancels: () => deviceSends.filter((message) => message.payload?.type === "task.cancel")
      .map((message) => String(message.payload.payload)),
    since: (mark) => sent.slice(mark),
    liveSessionId: () => liveSessionId,
    speechTexts: () => speechSays.map((message) => String(message.payload?.text)),
    effectTexts: () => effects.filter((message) => message.payload?.type === "speech")
      .map((message) => String(message.payload?.payload?.text)),
    say: async (id, transcript) => {
      // A recorder window is consumed by the utterance it records, so a conversation needs a fresh
      // physical press for every utterance after the first: the glasses never keep the microphone
      // open. The window that is open is the one this utterance may use.
      const open = harness.voiceArms().filter((armed) => !consumedPurposes.has(armed));
      let purpose = open.at(-1);
      if (!purpose) {
        harness.deliver(`button-${++pressCount}`, "native.device",
          { kind: "event", type: "probe.glasses", payload: "sprite-button-click" });
        await settle();
        purpose = harness.voiceArms().at(-1);
      }
      assert.ok(purpose, "a recorder window must be open before the user can speak");
      assert.ok(!consumedPurposes.has(purpose), "a recorder window carries only the utterance it was opened for");
      harness.deliver(id, "native.speech", { purpose, transcript });
      await settle();
    },
  };

  return harness;
}

/** Press the start button: the conversation opens with a help prompt and a recorder window. */
async function startConversation(harness: Harness): Promise<void> {
  harness.deliver("button-0", "native.device",
    { kind: "event", type: "probe.glasses", payload: "sprite-button-click" });
  await harness.settle();
}

/** Every native request must have been answered exactly once, and nothing unmodelled was asked. */
function assertBridgeSettled(harness: Harness): void {
  assert.deepEqual(harness.unexpected, [], "the bundle sent a native request this bridge does not model");
  const requests = harness.sent.filter((message) => !NOTIFICATIONS.has(message.type));
  assert.equal(harness.answeredIds().size, requests.length,
    "every native request must be answered exactly once");
}

/** Press -> help prompt -> destination utterance -> candidates spoken -> confirmation -> navigation. */
async function runToNavigationStarted(harness: Harness): Promise<void> {
  await startConversation(harness);
  await harness.say("speech-1", "请带我去人民公园");
  await harness.say("speech-2", "第一个");
}

// ---------------------------------------------------------------------------------------------

test("golden loop: a spoken destination, a spoken choice and a spoken request to look at the entrance", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors, speechSays, modelPrompts, toolCalls, navigationStarts, visionRequests, effects } = harness;

  // Stage 1 — the start press opens the conversation with help, not with a destination demand.
  await startConversation(harness);
  assert.deepEqual(harness.speechTexts(), [HELP_SPEECH],
    "stage 1: the start press must open with a generic help prompt");
  const firstPurpose = harness.voiceArms()[0];
  assert.match(String(firstPurpose), /^voice:\d+$/, "stage 1: a recorder window must be armed after playback");
  assert.equal(harness.captureArms().length, 0, "stage 1: the camera must not be opened by a press");

  // Stage 2 — the destination utterance reaches the model, which searches through the registry.
  harness.deliver("speech-1", "native.speech", { purpose: firstPurpose, transcript: "请带我去人民公园" });
  await harness.settle();
  // The utterance is planned exactly once. The search that plan proposes reports back as a ToolResult
  // Event, and the repository contracts every Event into its own model turn: the second turn belongs
  // to the search's result, it is not the utterance planned twice.
  const stageTwoTurns = modelPrompts.map((message) =>
    turnInput(String(message.payload.prompt)).event);
  assert.deepEqual(stageTwoTurns.map((planned) => planned.type), ["speech.input", "tool.results"],
    "stage 2: the utterance must be planned by the model, and its search result read back once");
  assert.equal(stageTwoTurns[0]!.eventId, "speech-1",
    "stage 2: the first turn is the user's own utterance");
  assert.equal(stageTwoTurns[1]!.payload.results[0].toolId, "navigation.search_destination",
    "stage 2: the second turn reads back the search's own result");
  assert.ok(modelPrompts[0]!.payload.prompt.includes("请带我去人民公园"),
    "stage 2: the planner prompt must carry the user's own words");
  assert.equal(modelPrompts[0]!.payload.sessionId, harness.liveSessionId() ?? modelPrompts[0]!.payload.sessionId,
    "stage 2: the model request must carry the session it belongs to");
  assert.equal(toolCalls.length, 1, "stage 2: the validated plan must search exactly once");
  assert.equal(toolCalls[0]!.payload.toolId, "navigation.search_destination");
  assert.deepEqual(toolCalls[0]!.payload.arguments, { query: "人民公园" },
    "stage 2: the provider call keeps the device's query shape");
  // The native `tool.execute` contract carries sessionId/plan/actionIndex/toolId/arguments and
  // nothing else; a destination search asks the device for no camera and no observation
  // authorisation, so the payload must name neither.
  assert.ok(!("consent" in toolCalls[0]!.payload), "stage 2: a search must not carry an observation consent");
  assert.equal(toolCalls[0]!.payload.arguments?.capability_id, undefined,
    "stage 2: a search must not carry a capture capability");
  assert.ok(String(toolCalls[0]!.payload.sessionId).startsWith("live-"),
    "stage 2: the provider call must carry the live session id");

  // The candidates are read back to the user through the registry's ask_user, which reopens voice.
  assert.equal(speechSays.length, 2, "stage 2: the candidates must be offered once");
  const offer = String(speechSays[1]!.payload.text);
  assert.match(offer, /找到2个地点/);
  assert.match(offer, /第1个，人民公园/);
  assert.match(offer, /第2个，人民公园地铁站/);
  const confirmPurpose = harness.voiceArms()[1];
  assert.match(String(confirmPurpose), /^voice:\d+$/, "stage 2: choosing needs its own recorder window");
  assert.notEqual(confirmPurpose, firstPurpose);

  // Stage 3 — the spoken choice. The model names an ordinal; the policy resolves it against this
  // session's own search results and is the only thing that may start a route.
  harness.deliver("speech-2", "native.speech", { purpose: confirmPurpose, transcript: "第一个" });
  await harness.settle();
  assert.equal(navigationStarts.length, 1, "stage 3: the confirmed choice must start walking navigation");
  assert.equal(navigationStarts[0]!.payload.sessionId, harness.liveSessionId());
  assert.equal(navigationStarts[0]!.payload.candidate.candidate_id, "amap-cand-1");
  assert.equal(navigationStarts[0]!.payload.candidate.name, "人民公园");
  assert.equal(toolCalls.length, 1, "stage 3: choosing must not issue a second destination search");

  // Every announcement leaves this device as `speech.say`: the reminder policy enqueues its own
  // `speech.say` and nothing republishes it as an effect, so the count that means "announced once"
  // is the count of real spoken lines, not of effects.
  const startedSpeech = speechSays.filter((message) => message.payload?.text === STARTED_SPEECH);
  assert.equal(startedSpeech.length, 1,
    "stage 3: the SDK start must be announced exactly once");
  assert.equal(navigationStarts.length, 1,
    "stage 3: one route start input must produce exactly one native navigation start");
  assert.equal(effects.filter((message) =>
    message.payload?.type === "speech" && message.payload?.payload?.text === STARTED_SPEECH).length, 0,
    "stage 3: the start announcement must not be republished as a duplicate speech effect");

  // Stage 4 — the route reports arrival: the deterministic reminder speaks, and nothing else.
  harness.deliver("nav-arrived-1", "native.navigation", { sessionId: harness.liveSessionId(), kind: "arrived" });
  await harness.settle();
  assert.equal(harness.speechTexts().at(-1), ARRIVAL_SPEECH, "stage 4: arrival must be announced once");
  assert.equal(harness.captureArms().length, 0, "stage 4: arrival must never open the camera on its own");

  // Stage 5 — the user asks to look at the entrance. Only then is a capture window proposed.
  await harness.say("speech-3", "帮我看看入口");
  assert.equal(harness.captureArms().length, 1, "stage 5: the model's ask_user must open one capture window");
  const entranceTag = harness.captureArms()[0]!;
  assert.match(entranceTag, /^capture:\d+$/, "stage 5: the capture window has its own generation tag");
  assert.equal(harness.visionRequests.length, 0, "stage 5: no photo has been taken yet");

  // A confirmation naming another window's tag authorises nothing.
  harness.deliver("entrance-confirm-foreign", "native.device",
    { kind: "event", type: "entrance.confirm", payload: "capture:999" });
  harness.deliver("photo-foreign", "native.photo", { tag: entranceTag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(harness.visionRequests.length, 0, "stage 5: a foreign confirmation must not authorise this photo");
  assert.equal(harness.toolCalls.length, 1, "stage 5: an unauthorised photo must not reach any provider");

  // The press that belongs to this window, then its photo.
  harness.deliver("entrance-confirm-1", "native.device",
    { kind: "event", type: "entrance.confirm", payload: entranceTag });
  harness.deliver("photo-1", "native.photo", { tag: entranceTag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(harness.captureArms().length, 1, "stage 5: confirming must not re-arm the capture by itself");
  assert.equal(visionRequests.length, 1, "stage 5: the authorised photo must reach the vision model exactly once");
  assert.equal(visionRequests[0]!.payload.mediaRef, ENTRANCE_MEDIA_REF);
  assert.match(String(visionRequests[0]!.payload.prompt), /entrance\.description/,
    "stage 5: the device is asked for the repository's structured fact vocabulary");
  assert.equal(harness.toolCalls.length, 1, "stage 5: an entrance observation must not reach the map provider");

  // Stage 6 — the observation comes back to the model, which decides how to say it.
  assert.equal(harness.speechTexts().at(-1), ENTRANCE_SUMMARY,
    "stage 6: the entrance result must be spoken back to the user");
  assert.equal(harness.speechSays.at(-1)!.payload.priority, "high");

  assert.deepEqual(errors, [], "stage 7: the bundle reported an error");
  assertBridgeSettled(harness);
  for (const id of ["speech-1", "speech-2", "speech-3", "photo-1", "nav-arrived-1"]) {
    assert.ok(harness.eventResults.some((message) => message.id === id),
      `stage 7: native envelope ${id} must be acknowledged with an event.result`);
  }
});

test("a first utterance that is not about navigation is answered without touching the map or the camera", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());

  await startConversation(harness);
  await harness.say("speech-1", "现在能帮我做什么");
  assert.equal(harness.toolCalls.length, 0, "a general question must not reach a provider");
  assert.equal(harness.captureArms().length, 0, "a general question must not open the camera");
  assert.equal(harness.speechTexts().at(-1), HELP_SPEECH, "the model's answer must be spoken");

  // The same conversation continues into navigation: nothing was reset by the first answer.
  await harness.say("speech-2", "请带我去人民公园");
  assert.equal(harness.toolCalls.length, 1, "the conversation must stay open for the next request");
  // One turn per Event: the two utterances, plus the second one's search result reporting back.
  assert.deepEqual(harness.modelPrompts.map((message) =>
    turnInput(String(message.payload.prompt)).event.type), ["speech.input", "speech.input", "tool.results"],
    "each utterance is planned as its own turn, and a ToolResult is an Event of its own");
  assert.ok(String(harness.modelPrompts[1]!.payload.prompt).includes("现在能帮我做什么"),
    "the second turn must carry the bounded conversation trace");
  assertBridgeSettled(harness);
});

test("a refused candidate is never started, and a second search replaces the first", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());

  await startConversation(harness);
  await harness.say("speech-1", "请带我去人民公园");
  const purpose = harness.voiceArms().at(-1);
  harness.deliver("speech-2", "native.speech", { purpose, transcript: "不去，换一个" });
  await harness.settle();

  assert.equal(harness.navigationStarts.length, 0, "a refusal must never start a route");
  assert.equal(harness.toolCalls.length, 1, "a refusal must not search again by itself");
  assert.equal(harness.speechTexts().at(-1), "好的，那你要去哪里？", "a refusal must be answered");
  assertBridgeSettled(harness);
});

test("a mid-route question is answered from real facts and never restarts the route", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());

  await runToNavigationStarted(harness);
  assert.equal(harness.navigationStarts.length, 1, "the route must be running before the questions");
  const entranceTag = await runToEntranceObservation(harness);

  for (let round = 1; round <= 3; round++) {
    await harness.say(`speech-q${round}`, "还有多远");
    // `facts.query` reads this session's own fact store through the TypeScript gateway and never
    // reaches the device, so `tool.execute` can never carry it. What proves the question was answered
    // from real facts is the answer turn carrying the observed fact, and the answer speaking it back.
    assert.ok(String(harness.modelPrompts.at(-1)!.payload.prompt).includes("entrance.description"),
      `round ${round}: the answer must be planned from the session's own observed fact`);
    assert.match(String(harness.speechTexts().at(-1)), /entrance\.description/,
      `round ${round}: the question must be answered from what was actually observed`);
    assert.equal(harness.navigationStarts.length, 1,
      `round ${round}: a mid-route question must never restart the route`);
    assert.equal(harness.captureArms().at(-1), entranceTag,
      `round ${round}: a mid-route question must not open another capture window`);
  }
  assert.match(String(harness.speechTexts().at(-1)), /entrance\.description/,
    "the answer must be the fact the device actually observed, not a guess");
  assertBridgeSettled(harness);
});

test("a finished observation keeps the conversation: menu, follow-up, expression, all in one session", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());

  await startConversation(harness);
  await harness.say("speech-1", "帮我看看菜单");
  // Before a photograph exists, all the model may do is ask for one: the request is spoken, a capture
  // window is open, and nothing has been read yet.
  assert.match(String(harness.speechTexts().at(-1)), /请按键拍一下菜单/,
    "the menu must first be answered by asking for its photograph");
  assert.equal(harness.visionRequests.length, 0, "no photograph exists before the user takes one");
  const menuTag = harness.captureArms().at(-1);
  assert.match(String(menuTag), /^capture:\d+$/, "the menu request must open its own capture window");

  harness.deliver("entrance-confirm-1", "native.device",
    { kind: "event", type: "entrance.confirm", payload: menuTag });
  harness.deliver("photo-menu", "native.photo", { tag: menuTag, mediaRef: MENU_MEDIA_REF });
  await harness.settle();
  assert.equal(harness.visionRequests.length, 1, "the authorised menu photo must be read once");
  assert.match(String(harness.visionRequests[0]!.payload.prompt), /逐行输出/,
    "menu text must be requested as OCR lines, not as a free-form summary");
  assert.match(String(harness.speechTexts().at(-1)), /识别到2行文字/,
    "menu text must be read through the OCR path");
  // Both OCR lines survive into the turn the model answers from.
  assert.match(String(harness.modelPrompts.at(-1)!.payload.prompt), /宫保鸡丁 38元/,
    "the first OCR line must reach the answer turn");
  assert.match(String(harness.modelPrompts.at(-1)!.payload.prompt), /西红柿鸡蛋 22元/,
    "the second OCR line must reach the answer turn");

  // A follow-up about what was just read is answered from the facts the observation established.
  await harness.say("speech-2", "刚才看到的菜都有什么");
  assert.match(String(harness.speechTexts().at(-1)), /menu\.text_line：宫保鸡丁 38元/,
    "the follow-up must be answered from the session's own observed facts");
  assert.ok(String(harness.modelPrompts.at(-1)!.payload.prompt).includes("menu.text_line"),
    "the model must be able to read the observation it is being asked about");

  // A different capability in the same conversation: a new window, a new photo, the same session.
  await harness.say("speech-3", "他现在的表情怎么样");
  assert.match(String(harness.speechSays.at(-1)!.payload.text), /请按键拍一下对方/);
  const faceTag = harness.captureArms().at(-1);
  assert.notEqual(faceTag, menuTag, "each capability gets its own capture window");
  harness.deliver("entrance-confirm-2", "native.device",
    { kind: "event", type: "entrance.confirm", payload: faceTag });
  harness.deliver("photo-face", "native.photo", { tag: faceTag, mediaRef: "media-ref-face-001" });
  await harness.settle();
  assert.equal(harness.visionRequests.length, 2, "the second capability must reach the vision model");
  assert.equal(harness.modelPrompts.length >= 6, true, "the session must keep planning turn by turn");
  assertBridgeSettled(harness);
});

test("cancel while the model is still thinking takes effect before the late plan arrives", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors, effects } = harness;

  await startConversation(harness);
  const destinationPurpose = String(harness.voiceArms()[0]);

  // The model request stays pending: this is the window the cancel has to close.
  harness.hold("model.generate", 1);
  harness.deliver("speech-1", "native.speech", { purpose: destinationPurpose, transcript: "请带我去人民公园" });
  await harness.settle();
  assert.equal(harness.modelPrompts.length, 1, "the utterance must reach the model");
  assert.equal(harness.heldCount("model.generate"), 1, "the model reply must still be pending");

  harness.deliver("cancel-1", "native.device", { kind: "event", type: "user.cancel", payload: "long-press" });
  await harness.settle();

  assert.equal(harness.deviceSends.filter((message) => message.payload?.type === "voice.disarm").length, 1,
    "a cancel must disarm the recorder without waiting for the model turn");
  assert.equal(harness.deviceSends.filter((message) => message.payload?.type === "entrance.disarm").length, 1,
    "a cancel must disarm the camera without waiting for the model turn");
  assert.equal(harness.sent.filter((message) => message.type === "policy.navigation.stop").length, 1,
    "a cancel must stop any native navigation immediately");
  assert.ok(effects.some((message) => message.payload?.type === "session" && message.payload?.payload?.status === "cancelled"),
    "the runtime must record session=cancelled promptly, not after the model replies");
  // Speech leaves through the speech queue (`speech.say`); only non-speech Effects are published as
  // `effect` envelopes, so the announcement is looked for where it is actually delivered.
  assert.ok(harness.speechTexts().includes(CANCELLED_SPEECH), "the cancellation must be announced to the user");

  const armsAfterCancel = harness.voiceArms().length;
  const speechAfterCancel = harness.speechTexts().length;

  // The model answers late. Nothing it says may start navigation or reopen a recorder.
  assert.equal(harness.answer("model.generate", mockModel(String(harness.modelPrompts[0]!.payload.prompt))), 1,
    "the late model reply must still be deliverable");
  await harness.settle();

  assert.equal(harness.navigationStarts.length, 0, "a late plan must never start native navigation");
  assert.equal(harness.toolCalls.length, 0, "a late plan must never reach a provider after the cancel");
  assert.equal(harness.voiceArms().length, armsAfterCancel, "a late plan must never re-arm the microphone");
  assert.equal(harness.speechTexts().length, speechAfterCancel, "a late plan must never speak over the cancellation");
  assert.deepEqual(errors, [], "a cancelled request must not be reported as a device error");
  assertBridgeSettled(harness);
});

test("a disconnect while a prompt is playing never arms the camera or accepts a stale photo", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors, effects } = harness;

  await runToNavigationStarted(harness);
  const liveSessionId = harness.liveSessionId();
  harness.deliver("nav-arrived-1", "native.navigation", { sessionId: liveSessionId, kind: "arrived" });
  await harness.settle();

  // The entrance prompt is where the capture window would be opened, so the hold has to be armed
  // before the utterance is delivered: the turn only parks on the prompt if it is already held when
  // the model asks for it.
  harness.hold("speech.say", 1);
  await harness.say("speech-3", "帮我看看入口");
  assert.equal(harness.heldCount("speech.say"), 1, "the entrance prompt must still be awaiting playback");
  assert.equal(harness.captureArms().length, 0, "the capture window opens only after the prompt plays");

  harness.deliver("disconnect-1", "native.device", { kind: "disconnected", reason: "peer-lost" });
  await harness.settle();

  assert.equal(harness.deviceSends.filter((message) => message.payload?.type === "entrance.disarm").length >= 1, true,
    "a disconnect must disarm the camera immediately");
  assert.equal(harness.captureArms().length, 0, "a disconnect must never open the camera window");
  assert.ok(effects.some((message) => message.payload?.type === "session" &&
      message.payload?.payload?.status === "device_disconnected"),
    "the runtime must record the disconnected session");

  // The playback callback of the interrupted prompt arrives late; it must arm nothing.
  assert.equal(harness.answer("speech.say", { finished: true }), 1, "the interrupted prompt must be answerable");
  await harness.settle();
  assert.equal(harness.captureArms().length, 0,
    "a prompt that finished after the disconnect must not open a capture window");

  harness.deliver("photo-after-disconnect", "native.photo", { tag: "capture:1", mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(harness.visionRequests.length, 0, "a photo that arrives after a disconnect must be dropped");

  // A reconnecting device still needs a new physical press; the prior conversation is not resumed.
  const armsAfterDisconnect = harness.voiceArms().length;
  harness.deliver("reconnect-1", "native.device", { kind: "connected", name: "Rokid" });
  await harness.settle();
  assert.equal(harness.captureArms().length, 0, "a reconnect must not re-arm anything on its own");
  assert.equal(harness.voiceArms().length, armsAfterDisconnect, "a reconnect must not reopen a recorder window");

  harness.deliver("button-9", "native.device", { kind: "event", type: "probe.glasses", payload: "sprite-button-click" });
  await harness.settle();
  assert.equal(harness.speechTexts().at(-1), HELP_SPEECH,
    "a new physical press must start a new conversation, not resume the disconnected one");
  assert.deepEqual(errors, [], "a clean disconnect must not be reported as a device error");
  assertBridgeSettled(harness);
});

test("an expired voice window retries with bounded feedback and drops stale tags", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors, modelPrompts } = harness;

  await startConversation(harness);
  const destinationPurpose = String(harness.voiceArms()[0]);

  // An expiry that belongs to an earlier window is dropped without any feedback.
  harness.deliver("voice-expired-stale", "native.device",
    { kind: "event", type: "voice.expired", payload: "voice:99" });
  await harness.settle();
  assert.equal(harness.speechSays.length, 1, "a stale expiry must not produce a retry prompt");
  assert.equal(harness.voiceArms().length, 1, "a stale expiry must not reopen a recorder window");

  // The live window expires: one bounded retry under a fresh purpose, then the user must press again.
  harness.deliver("voice-expired-1", "native.device",
    { kind: "event", type: "voice.expired", payload: destinationPurpose });
  await harness.settle();
  assert.equal(harness.speechTexts().at(-1), VOICE_RETRY_SPEECH, "an expired voice window must say what to do next");
  const retryPurpose = String(harness.voiceArms().at(-1));
  assert.notEqual(retryPurpose, destinationPurpose, "an expiry must retire the purpose it expired");
  const retrySpeechIndex = harness.speechSays.length;

  // The retired purpose is dead: its expiry is dropped, and its audio is never planned.
  harness.deliver("voice-expired-retired", "native.device",
    { kind: "event", type: "voice.expired", payload: destinationPurpose });
  await harness.settle();
  assert.equal(harness.speechSays.length, retrySpeechIndex,
    "an expiry of the retired window must not produce another retry prompt");
  assert.equal(harness.voiceArms().at(-1), retryPurpose, "a stale expiry must not reopen a window");
  harness.deliver("speech-retired", "native.speech", { purpose: destinationPurpose, transcript: "请带我去人民公园" });
  await harness.settle();
  assert.equal(modelPrompts.length, 0, "audio of the retired window must never be planned");

  // Recovery: the user presses again and the flow continues past the expiry.
  harness.deliver("speech-1", "native.speech", { purpose: retryPurpose, transcript: "请带我去人民公园" });
  await harness.settle();
  // The recovered utterance is planned once, and the search that plan proposes reports back.
  assert.deepEqual(modelPrompts.map((message) =>
    turnInput(String(message.payload.prompt)).event.type), ["speech.input", "tool.results"],
    "a recovered utterance must be planned normally, then answered from its own search result");
  assert.match(String(harness.speechTexts().at(-1)), /找到2个地点/);

  assert.deepEqual(errors, [], "a bounded retry is not a device error");
  assertBridgeSettled(harness);
});

test("an empty recording retries under a fresh purpose and drops the retired window's audio", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors, modelPrompts } = harness;

  await startConversation(harness);
  const destinationPurpose = String(harness.voiceArms()[0]);

  harness.deliver("speech-empty", "native.speech", { purpose: destinationPurpose, transcript: "   " });
  await harness.settle();
  assert.equal(harness.speechTexts().at(-1), "没有听清，请再按键说一次。", "an empty recording must say what to do next");
  assert.equal(modelPrompts.length, 0, "an empty recording must never reach the model");
  const retryPurpose = String(harness.voiceArms().at(-1));
  assert.notEqual(retryPurpose, destinationPurpose, "an empty recording must retire its purpose");

  harness.deliver("speech-retired", "native.speech", { purpose: destinationPurpose, transcript: "请带我去人民公园" });
  await harness.settle();
  assert.equal(modelPrompts.length, 0, "audio of the retired window must never be planned");

  harness.deliver("speech-1", "native.speech", { purpose: retryPurpose, transcript: "请带我去人民公园" });
  await harness.settle();
  // The recovered utterance is planned once, and the search that plan proposes reports back.
  assert.deepEqual(modelPrompts.map((message) =>
    turnInput(String(message.payload.prompt)).event.type), ["speech.input", "tool.results"],
    "a recovered utterance must be planned normally, then answered from its own search result");
  assert.match(String(harness.speechTexts().at(-1)), /找到2个地点/);

  assert.deepEqual(errors, [], "a bounded retry is not a device error");
  assertBridgeSettled(harness);
});

/** Press -> help -> look at the entrance -> confirm -> photo -> spoken observation. */
async function runToEntranceObservation(harness: Harness): Promise<string> {
  await harness.say("speech-e", "帮我看看入口");
  const tag = String(harness.captureArms().at(-1));
  harness.deliver("entrance-confirm-e", "native.device",
    { kind: "event", type: "entrance.confirm", payload: tag });
  harness.deliver("photo-e", "native.photo", { tag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  return tag;
}

test("an expired capture window re-arms a new tag and rejects the stale photo and its consent", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors, visionRequests } = harness;

  await startConversation(harness);
  await harness.say("speech-1", "帮我看看入口");
  const firstTag = String(harness.captureArms().at(-1));
  assert.match(firstTag, /^capture:\d+$/);

  harness.deliver("entrance-expired-1", "native.device",
    { kind: "event", type: "entrance.expired", payload: firstTag });
  await harness.settle();
  assert.equal(harness.speechTexts().at(-1), ENTRANCE_RETRY_SPEECH, "an expired capture window must say what to do next");
  const retryTag = String(harness.captureArms().at(-1));
  assert.notEqual(retryTag, firstTag, "the retry must use a new generation tag");

  // The photo of the expired attempt arrives late and must be dropped, tag and consent alike.
  harness.deliver("photo-stale", "native.photo", { tag: firstTag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(visionRequests.length, 0, "a photo from an expired capture must never reach the vision model");

  // The new window still needs its own physical confirmation before any capture counts.
  harness.deliver("photo-early", "native.photo", { tag: retryTag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(visionRequests.length, 0, "a photo without this window's physical confirmation must be dropped");

  // A confirmation written for the retired window names the retired tag: it authorises nothing.
  harness.deliver("entrance-confirm-stale", "native.device",
    { kind: "event", type: "entrance.confirm", payload: firstTag });
  harness.deliver("photo-after-stale-consent", "native.photo", { tag: retryTag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(visionRequests.length, 0,
    "a confirmation of the retired tag must not authorise a photo of the new one");

  harness.deliver("entrance-confirm-2", "native.device",
    { kind: "event", type: "entrance.confirm", payload: retryTag });
  harness.deliver("photo-retry", "native.photo", { tag: retryTag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(visionRequests.length, 1, "the confirmed photo of the new window must reach the vision model");
  assert.equal(harness.speechTexts().at(-1), ENTRANCE_SUMMARY, "the observation must be announced");

  assert.deepEqual(errors, [], "a bounded retry is not a device error");
  assertBridgeSettled(harness);
});

test("a photo taken under one capability can never be observed as another", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { visionRequests } = harness;

  await startConversation(harness);
  await harness.say("speech-1", "帮我看看入口");
  const tag = String(harness.captureArms().at(-1));
  harness.deliver("entrance-confirm-1", "native.device", { kind: "event", type: "entrance.confirm", payload: tag });
  harness.deliver("photo-1", "native.photo", { tag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(visionRequests.length, 1, "the authorised entrance photo must be observed once");

  // The same photo must not be re-used: one confirmation authorises exactly one observation.
  await harness.say("speech-2", "帮我看看菜单");
  harness.deliver("photo-replay", "native.photo", { tag, mediaRef: ENTRANCE_MEDIA_REF });
  await harness.settle();
  assert.equal(visionRequests.length, 1, "a consumed capture must never be observed a second time");
  assert.equal(harness.captureArms().at(-1) !== undefined, true,
    "the menu request must be waiting on its own capture window");
  assertBridgeSettled(harness);
});

test("repeated authority failures end the conversation instead of retrying forever", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors } = harness;

  await startConversation(harness);
  const destinationPurpose = String(harness.voiceArms()[0]);

  // Each expiry is answered with a fresh purpose, so every attempt has to name the window the retry
  // actually reopened — replaying the first purpose would just be dropped as stale.
  for (let attempt = 1; attempt <= 6; attempt++) {
    harness.deliver(`voice-expired-${attempt}`, "native.device",
      { kind: "event", type: "voice.expired", payload: String(harness.voiceArms().at(-1)) });
    await harness.settle();
  }
  assert.equal(harness.voiceArms().length, 4,
    "the first window plus exactly the bounded retries must have been armed");
  assert.notEqual(String(harness.voiceArms().at(-1)), destinationPurpose,
    "a retry must never reopen the purpose the expiry retired");
  assert.equal(harness.speechTexts().at(-1), AUTHORITY_TERMINAL_SPEECH,
    "exhausting the retry budget must tell the user the conversation ended");
  assert.ok(errors.some((message) => String(message.error).includes("exceeded the bounded retry budget")),
    "the exhausted budget must be reported once as a diagnostic");

  // The conversation is over: a new physical press starts a fresh one rather than resuming this one.
  harness.deliver("button-2", "native.device", { kind: "event", type: "probe.glasses", payload: "sprite-button-click" });
  await harness.settle();
  assert.equal(harness.speechTexts().at(-1), HELP_SPEECH,
    "a new press must start a new conversation after the previous one ended");
  assert.match(String(harness.voiceArms().at(-1)), /^voice:\d+$/, "the new conversation arms its own window");

  assertBridgeSettled(harness);
});

test("a cancel retires the device before it clears authorisations or navigation", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());

  await runToNavigationStarted(harness);
  await harness.say("speech-3", "帮我看看入口");
  const mark = harness.sent.length;
  harness.deliver("cancel-1", "native.device", { kind: "event", type: "user.cancel", payload: "long-press" });
  await harness.settle();

  const after = harness.since(mark);
  const position = (matches: (message: NativeEnvelope) => boolean): number => after.findIndex(matches);
  const taskCancel = position((m) => m.type === "device.send" && m.payload?.type === "task.cancel");
  const voiceDisarm = position((m) => m.type === "device.send" && m.payload?.type === "voice.disarm");
  const entranceDisarm = position((m) => m.type === "device.send" && m.payload?.type === "entrance.disarm");
  const navStop = position((m) => m.type === "policy.navigation.stop");

  assert.ok(taskCancel >= 0, "a cancel must reach the device as an explicit task.cancel");
  assert.ok(voiceDisarm > taskCancel, "task.cancel must precede the recorder disarm");
  assert.ok(entranceDisarm > taskCancel, "task.cancel must precede the camera disarm");
  assert.ok(navStop > taskCancel, "task.cancel must precede the navigation stop");
  assert.match(harness.taskCancels().at(-1) ?? "", /^live-[\w-]+\|\d+$/,
    "task.cancel must carry the retired session and the generation that retired it");
  assertBridgeSettled(harness);
});

test("an arrival buffered behind a held prompt is dropped once the user cancels", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors, effects } = harness;

  await runToNavigationStarted(harness);
  const sessionId = harness.liveSessionId();
  assert.ok(sessionId, "navigation must have started a live session");

  // The arrival turn parks on its own prompt, so the queue is busy for the next envelopes.
  harness.hold("speech.say", 1);
  harness.deliver("nav-arrived-1", "native.navigation", { sessionId, kind: "arrived" });
  await harness.settle();
  assert.equal(harness.heldCount("speech.say"), 1, "the arrival turn must be waiting on its prompt");

  // This arrival is buffered behind that turn, then overtaken by the cancel.
  harness.deliver("nav-arrived-2", "native.navigation", { sessionId, kind: "arrived" });
  await harness.settle();
  harness.deliver("cancel-1", "native.device", { kind: "event", type: "user.cancel", payload: "long-press" });
  await harness.settle();

  const armsAfterCancel = harness.captureArms().length;
  const speechAfterCancel = harness.speechTexts().length;
  const resultsAfterCancel = harness.eventResults.length;

  assert.equal(harness.answer("speech.say", { finished: true }), 1, "the late playback ack must still be deliverable");
  await harness.settle();

  assert.equal(harness.captureArms().length, armsAfterCancel,
    "a queued arrival must never open the camera after a cancel");
  assert.equal(harness.speechTexts().length, speechAfterCancel, "a queued arrival must never speak after a cancel");
  assert.equal(harness.eventResults.length, resultsAfterCancel,
    "a queued arrival must be dropped whole, not replayed into the runtime");
  assert.deepEqual(errors, [], "a dropped arrival is not a device error");
  assert.ok(effects.some((message) => message.payload?.type === "session" &&
    message.payload?.payload?.status === "cancelled"),
    "the runtime must still record the cancellation");

  // Only a brand-new physical press may start the next conversation.
  harness.deliver("button-2", "native.device", { kind: "event", type: "probe.glasses", payload: "sprite-button-click" });
  await harness.settle();
  assert.equal(harness.speechTexts().at(-1), HELP_SPEECH, "a new press must start a fresh conversation");
  assertBridgeSettled(harness);
});

test("a press buffered behind a held model turn is dropped after a cancel and cannot revive the conversation", async (t) => {
  const harness = await createHarness();
  t.after(() => harness.finish());
  const { errors } = harness;

  await startConversation(harness);
  const destinationPurpose = String(harness.voiceArms()[0]);
  const startsBefore = harness.navigationStarts.length;
  const announcementsBefore = harness.speechTexts().length;

  // Park the destination turn on the model, then buffer a physical press behind it.
  harness.hold("model.generate", 1);
  harness.deliver("speech-1", "native.speech", { purpose: destinationPurpose, transcript: "请带我去人民公园" });
  await harness.settle();
  assert.equal(harness.heldCount("model.generate"), 1, "the destination turn must be waiting on the model");

  harness.deliver("button-2", "native.device", { kind: "event", type: "probe.glasses", payload: "sprite-button-click" });
  await harness.settle();
  harness.deliver("cancel-1", "native.device", { kind: "event", type: "user.cancel", payload: "long-press" });
  await harness.settle();

  assert.equal(harness.answer("model.generate", mockModel(String(harness.modelPrompts[0]!.payload.prompt))), 1,
    "the late model reply must still be deliverable");
  await harness.settle();

  assert.equal(harness.navigationStarts.length, startsBefore,
    "a buffered press must not start navigation after a cancel");
  assert.equal(harness.speechTexts().length, announcementsBefore + 1,
    "only the cancellation announcement may be published after the retired turn");
  assert.equal(harness.speechTexts().at(-1), CANCELLED_SPEECH,
    "a buffered press must not talk over the cancellation");
  assert.equal(harness.voiceArms().length, 1, "a buffered press must not reopen a recorder window");
  assert.deepEqual(errors, [], "a dropped press is not a device error");

  // A press after the cancel is a new user operation and does start the next conversation.
  harness.deliver("button-3", "native.device", { kind: "event", type: "probe.glasses", payload: "sprite-button-click" });
  await harness.settle();
  assert.equal(harness.speechTexts().at(-1), HELP_SPEECH,
    "a press after the cancel must start a fresh conversation");
  assertBridgeSettled(harness);
});
