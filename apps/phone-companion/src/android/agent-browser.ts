import { MobileAgentHost } from "./mobile-agent-host.ts";
import { decodeAgentEvent, encodeAgentEvent, type EffectContract } from "../../../../packages/domain/agent/contract-codecs.ts";
import { generateValidatedPlan } from "./model-plan-response.ts";
import { nativeNavigationEvent, type NativeNavigationPayload } from "./native-navigation-event.ts";
import { GlassesTaskFlow } from "./glasses-task-flow.ts";
import { createToolHandlers, type ToolRuntime } from "./tool-handlers.ts";
import { createBrowserArgumentValidator } from "./contract-schemas.ts";
import { createNativeVisionClient } from "./vision-client.ts";
import { SessionFactStore } from "./session-facts.ts";
import { SpeechQueue } from "./speech-queue.ts";
import { NavigationDestinationPolicy } from "./navigation-destination-policy.ts";
import { createDeviceEventMapper, mapNativeDeviceEvent, type NativeDevicePayload } from "./native-device-events.ts";
import { ToolGateway as ConcreteToolGateway, type ToolDefinition } from "../../../../packages/domain/tools/tool-gateway.ts";
import { ConcreteToolGatewayAdapter, type ToolGateway } from "../../../../packages/domain/agent/tool-gateway.ts";
import { NavigationReminderPolicy } from "../../../../packages/domain/reminder/navigation-reminder-policy.ts";
import { ObservationGateway } from "../../../gateway/src/observation/observation-gateway.ts";
import { OcrObservationAdapter } from "../../../gateway/src/observation/ocr-adapter.ts";
import { VlmObservationAdapter } from "../../../gateway/src/observation/vlm-adapter.ts";
import { createP0SkillRegistry } from "../../../../packages/domain/skills/skill-registry.ts";
import type { AgentTurnInput, LlmAgent } from "../../../../packages/domain/agent/llm-agent.ts";
import type { AgentEvent, AgentPlan } from "../../../../packages/domain/agent/types.ts";
import type { ExecutionPermissions } from "../../../../packages/domain/agent/plan-validator.ts";
import type { SessionState } from "../../../../packages/domain/session/session-orchestrator.ts";
import type { NavigationEvent } from "../../../../packages/providers/navigation/navigation-provider.ts";
import toolManifest from "../../../../packages/providers/registry/tool-registry.json" with { type: "json" };
import capabilityManifest from "../../../../packages/contracts/capabilities/registry.json" with { type: "json" };

/**
 * Composition root of the phone-side Agent.
 *
 * Everything here is wiring. What the user wants, which place they mean, whether a place was
 * confirmed, what to look at — all of that belongs to the domain Agent, the registries, the
 * policies and the tool gateway, which are used exactly as they are. This file only connects them to
 * one device: it moves bytes over the WebView bridge, opens and closes the recorder and camera
 * windows the hardware owns, and fences work that a cancel or a disconnect has retired.
 */

interface Envelope {
  id: string;
  type: string;
  payload?: unknown;
  error?: string;
  permissions?: ExecutionPermissions;
}

declare global {
  interface Window {
    LeQiNative: { send(json: string): void };
    LeQiAgent: { receive(json: string): void };
  }
}

const pending = new Map<string, {
  resolve(value: unknown): void;
  reject(error: Error): void;
  timeout: ReturnType<typeof setTimeout>;
}>();
let nextRequestId = 0;
let eventQueue: Promise<void> = Promise.resolve();
let activeEvent: AgentEvent | undefined;
let activePlan: AgentPlan | undefined;
/**
 * The generation the plan currently executing was planned under. TaskRunner runs a plan's actions
 * after `plan()` resolved, so the generation is captured once, with the plan, and every action of
 * that plan is fenced on it — never on whatever generation happens to be current when an action
 * finally gets its turn.
 */
let activePlanGeneration = 0;
const lastSequence = new Map<string, number>();
let nextLiveSession = 0;
let nextModelPlanId = 0;

/** Raised when a native request is abandoned because the user cancelled or the glasses disconnected. */
class OperationCancelled extends Error {
  constructor(subject: string) {
    super(`${subject} abandoned: the operation was cancelled or the device disconnected`);
    this.name = "OperationCancelled";
  }
}

/**
 * Bumped by every cancel, disconnect and terminal device failure. Every awaited continuation is
 * fenced on this value, so a late model reply, a late photo or a late route callback cannot revive
 * an operation that is already over.
 */
let operationGeneration = 0;

/** Bounded automatic recovery: past this many failed windows the conversation ends instead. */
const AUTHORITY_RETRY_LIMIT = 3;
let voiceRetryCount = 0;
let captureRetryCount = 0;

/**
 * Sessions a cancel, disconnect or terminal device failure retired. Session ids are minted fresh per
 * conversation (`live-<time>-<n>`), so a retired id never becomes live again: any Effect still
 * carrying one belongs to a turn that must not reach the bridge. Only the most recent ids are kept,
 * which is all a straggling turn can still reference.
 */
const retiredSessions = new Set<string>();
const RETIRED_SESSION_LIMIT = 16;

/** Events that retire a session; the turn handling one may still announce its own retirement. */
const RETIREMENT_EVENTS = new Set(["user.cancel", "device.disconnected"]);

/**
 * Set while the turn that performed a model-requested cancel is still running, so that turn and its
 * tool-result continuation may still speak the cancellation. Cleared by the next event that is not
 * feedback for it.
 */
let retiredByCurrentTurn: string | undefined;

/**
 * The one Effect a retired session is still allowed to speak: the cancellation the retirement turn
 * itself announces. It is named by effect id, so a turn that was already running when the retirement
 * landed cannot slip its own remaining speeches through as "the retirement's own announcement".
 */
let retirementAnnouncementId: string | undefined;
/** The same announcement as it was enqueued, for the queue's own drain time. */
let retirementAnnouncementText: string | undefined;

/**
 * The cancellation signal of the tool call that is running right now. A nested tool call inherits it,
 * so a confirmation that timed out can never leave a route started by its nested start behind.
 */
let activeToolSignal: AbortSignal | undefined;

function retireSession(sessionId: string | undefined): void {
  if (!sessionId) return;
  retiredSessions.delete(sessionId);
  retiredSessions.add(sessionId);
  while (retiredSessions.size > RETIRED_SESSION_LIMIT) {
    const oldest = retiredSessions.values().next().value;
    if (oldest === undefined) break;
    retiredSessions.delete(oldest);
  }
}

function send(message: Envelope): void {
  window.LeQiNative.send(JSON.stringify(message));
}

function requestNative(type: string, payload: unknown): Promise<unknown> {
  const id = `agent-${++nextRequestId}`;
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`${type} timed out`));
    }, 120_000);
    pending.set(id, { resolve, reject, timeout });
    send({ id, type, payload });
  });
}

function isOperationCancelled(error: unknown): boolean {
  return error instanceof OperationCancelled;
}

/**
 * Awaits a native reply that must not outlive the operation that issued it.
 * Returns undefined when the request was abandoned or the operation changed while it ran, so the
 * caller stops instead of acting on a device conversation that is already over.
 */
async function nativeStep<T>(generation: number, type: string, payload: unknown): Promise<T | undefined> {
  if (generation !== operationGeneration) return undefined;
  try {
    const value = await requestNative(type, payload) as T;
    return generation === operationGeneration ? value : undefined;
  } catch (error) {
    if (isOperationCancelled(error) || generation !== operationGeneration) return undefined;
    throw error;
  }
}

/** Fire-and-forget native request; skipped once its operation is gone, never reported as a failure. */
function nativeNotice(generation: number, type: string, payload: unknown, envelopeId: string): void {
  if (generation !== operationGeneration) return;
  void requestNative(type, payload).catch((error) => {
    if (isOperationCancelled(error)) return;
    send({ id: envelopeId, type: "error", error: `${type} failed: ${String(error)}` });
  });
}

function disarmDevice(envelopeId: string): void {
  for (const type of ["voice.disarm", "entrance.disarm"]) {
    nativeNotice(operationGeneration, "device.send", { type, payload: "" }, envelopeId);
  }
}

/**
 * Cancels the device half of an operation. The explicit `task.cancel` write goes first and is the
 * only signal that retires work the phone already started natively — a queued playback, a recorder
 * thread, an open capture window. `voice.disarm` / `entrance.disarm` only clear a standing
 * authorisation, so they follow: a device that processed a disarm and then a stale re-arm would
 * otherwise keep a window open across the cancel. The payload carries the retired session and the
 * generation that retired it, so the phone can drop native work belonging to an older generation
 * even when the user has already started the next conversation.
 */
function cancelDevice(sessionId: string | undefined, envelopeId: string): void {
  nativeNotice(operationGeneration, "device.send",
    { type: "task.cancel", payload: `${sessionId ?? "-"}|${operationGeneration}` }, envelopeId);
  disarmDevice(envelopeId);
  if (sessionId) nativeNotice(operationGeneration, "policy.navigation.stop", { sessionId }, envelopeId);
}

/**
 * Drops every trace of the operation in flight: task order, candidate state, the buffered capture
 * reference, and every native request still waiting on a device that is gone.
 * Returns the session that was retired.
 */
function invalidateOperation(): string | undefined {
  operationGeneration++;
  const sessionId = glasses.sessionId;
  glasses.cancel();
  retireSession(sessionId);
  destinationCapture = undefined;
  activePlan = undefined;
  activePlanGeneration = operationGeneration;
  // Everything the speech queue still holds for this session belongs to the conversation that just
  // ended and is dropped. The cancellation announcement is enqueued after this, by the retirement
  // turn itself, so it is the only thing this session can still say.
  if (sessionId) speech.cancelSession(sessionId);
  // A retirement announcement from the previous retirement is not this retirement's.
  retirementAnnouncementId = undefined;
  retirementAnnouncementText = undefined;
  voiceRetryCount = 0;
  captureRetryCount = 0;
  runningRoutes.clear();
  if (sessionId) {
    destinations.clearSession(sessionId);
    facts.clear(sessionId);
  }
  for (const [id, entry] of [...pending]) {
    pending.delete(id);
    clearTimeout(entry.timeout);
    entry.reject(new OperationCancelled("native request"));
  }
  return sessionId;
}

/**
 * Delivers a retirement Event to the Agent core *now*, above the serial queue.
 *
 * The core retires the running turn's epoch on user.cancel and device.disconnected, and the
 * TaskRunner stops on that epoch. Queuing this behind the turn it retires would make the retirement
 * wait for exactly the work it exists to stop, so it is delivered concurrently, the way a physical
 * cancel arrives. The turn that performs the retirement is the one turn of that session which may
 * still speak: its announcement is the last thing the session is allowed to say.
 */
function retireCoreNow(envelopeId: string, sessionId: string, event: AgentEvent): void {
  retiredByCurrentTurn = sessionId;
  // The core announces a retirement as `<eventId>:speech`; that one Effect, and no other, is what
  // this session is still allowed to say.
  retirementAnnouncementId = `${event.eventId}:speech`;
  retirementAnnouncementText = undefined;
  void acceptEvent({ id: envelopeId, type: "event", payload: encodeAgentEvent(event) })
    .catch((error) => {
      if (isOperationCancelled(error)) return;
      send({ id: envelopeId, type: "error", error: String(error) });
    });
}

/** A model-requested cancel: the same revocation as a user cancel, from inside the running turn. */
function cancelSession(sessionId: string, envelopeId: string): void {
  invalidateOperation();
  cancelDevice(sessionId, envelopeId);
  // The core's own epoch is retired too, or the turn that asked for this cancel would keep running
  // its remaining actions after the conversation it belonged to was revoked.
  retireCoreNow(`${envelopeId}:core`, sessionId, {
    eventId: `${envelopeId}:core`, sessionId,
    sequence: (lastSequence.get(sessionId) ?? 0) + 1,
    occurredAt: new Date().toISOString(), source: "user", sourceDetail: "user",
    type: "user.cancel", payload: { reason: "user_request", user_initiated: true },
  });
}

/**
 * Queues a handler behind the serial event queue, remembering the operation generation at the moment
 * the envelope arrived. An envelope buffered behind a slow turn and then overtaken by a cancel is
 * stale by the time it would run, and is dropped rather than replayed: a queued press must not open
 * a window, and a queued route callback must not speak, after the user cancelled.
 */
function enqueue(handler: () => Promise<void> | void, envelopeId: string): void {
  const generation = operationGeneration;
  eventQueue = eventQueue.then(() => {
    if (generation !== operationGeneration) return;
    return handler();
  }).catch((error) => {
    if (isOperationCancelled(error)) return;
    send({ id: envelopeId, type: "error", error: String(error) });
  });
}

/** Splits a device `tag|reason` payload; expired/denied authority only ever carries its own tag. */
function splitAuthority(value: string | undefined): { tag: string; reason: string } {
  const raw = typeof value === "string" ? value : "";
  const cut = raw.indexOf("|");
  return cut < 0
    ? { tag: raw.trim(), reason: "" }
    : { tag: raw.slice(0, cut).trim(), reason: raw.slice(cut + 1).trim() };
}

// ---------------------------------------------------------------------------------------------
// Composition
// ---------------------------------------------------------------------------------------------

const skills = createP0SkillRegistry();
const destinations = new NavigationDestinationPolicy();
const facts = new SessionFactStore();
const glasses = new GlassesTaskFlow();
const reminders = new NavigationReminderPolicy();
const deviceEvents = createDeviceEventMapper();

/** Sessions with a route the device confirmed started. The device runs one route at a time. */
const runningRoutes = new Set<string>();

/**
 * The one capture the user authorised, and the capability their press authorised it for. A photo
 * lands here; the observation that consumes it must ask for exactly that capability, and only once.
 * The model may propose an observation, but it cannot retarget this consent.
 */
let destinationCapture: { sessionId: string; capabilityId: string; mediaRef: string; capturedAt: string } | undefined;

const vision = createNativeVisionClient(async ({ mediaRef, prompt }) => {
  const answer = await nativeStep<unknown>(operationGeneration, "vision.observe", { mediaRef, prompt });
  if (answer === undefined) throw new OperationCancelled("vision.observe");
  return answer;
});

/**
 * The capability registry decides which capabilities exist and that they need explicit consent; the
 * analyzers only turn one capture into one ObservationResult. Menu text goes through OCR, everything
 * else through the vision model — both behind the same structured-fact result.
 */
const observation = new ObservationGateway(
  capabilityManifest.capabilities.map((capability) => ({
    id: capability.id,
    provider: capability.provider,
    consent: capability.consent as "explicit" | "preauthorized" | "not_applicable",
  })),
  new Map(capabilityManifest.capabilities.map((capability) => [
    capability.id,
    capability.id === "vision.menu"
      ? new OcrObservationAdapter(vision.ocr)
      : new VlmObservationAdapter(vision.vlm),
  ])),
);

const speech = new SpeechQueue({
  speak: async (effect) => {
    // An utterance that was queued before the retirement and is only reached now belongs to a
    // conversation that is over; it never reaches the device. The one exception is the turn that
    // performed the retirement, whose own announcement is the last thing that session may say.
    if (retiredSessions.has(effect.session_id) && effect.text !== retirementAnnouncementText) {
      return false;
    }
    const generation = operationGeneration;
    const spoken = await nativeStep<{ finished?: boolean }>(generation, "speech.say", {
      text: effect.text, priority: effect.priority, allowRepeat: true,
    });
    return generation === operationGeneration && spoken?.finished === true;
  },
});

const toolRuntime: ToolRuntime = {
  now: () => new Date().toISOString(),
  idFactory: () => `call-${++nextRequestId}`,
  locale: () => "zh-CN",
  currentEvent: () => activeEvent,
  // Bound to the plan's own generation: an action of a retired turn must not issue native work under
  // the generation that replaced it, and its own retirement is what makes the answer undefined.
  native: (type, payload) => nativeStep(activePlanGeneration, type, payload),
  generation: () => activePlanGeneration,
  isLive: (generation) => generation === operationGeneration,
  withinParentSignal: async (signal, run) => {
    const previous = activeToolSignal;
    activeToolSignal = signal;
    try {
      return await run();
    } finally {
      activeToolSignal = previous;
    }
  },
  parentAborted: () => activeToolSignal?.aborted === true,
  destinations,
  facts,
  observation,
  takeCapture: (capabilityId) => {
    const capture = destinationCapture;
    if (!capture || capture.capabilityId !== capabilityId || capture.sessionId !== glasses.sessionId) {
      return undefined;
    }
    // Single consumption: this photo is spent whether or not the model could read anything in it.
    destinationCapture = undefined;
    return { mediaRef: capture.mediaRef, capturedAt: capture.capturedAt };
  },
  routeRunning: (sessionId) => runningRoutes.has(sessionId),
  onRouteStarted: (sessionId, candidate) => {
    runningRoutes.add(sessionId);
    queueNavigation({
      id: `navigation-start-${sessionId}-${candidate.candidate_id}`,
      type: "native.navigation",
      payload: { sessionId, kind: "started" },
    });
  },
  openQuestionWindow: async (text, capabilityId, expiresAt) => {
    // The window belongs to the plan that asked the question, so it is fenced on that plan's
    // generation: a question from a retired turn opens nothing.
    const generation = activePlanGeneration;
    const sessionId = glasses.sessionId;
    const window = capabilityId ? "capture" as const : "voice" as const;
    if (!sessionId) return { opened: false, window, reason: "no live conversation" };
    // The tool call that asked the question has its own expiry; a question asked after it passed
    // would open a window the user's answer could no longer be accepted on.
    const expired = () => Date.parse(expiresAt) <= Date.now();
    if (expired()) return { opened: false, window, reason: "the question expired before it was asked" };
    const finished = await speech.enqueue(text, "normal", sessionId);
    if (!finished) return { opened: false, window, reason: "the question was not played" };
    if (expired()) return { opened: false, window, reason: "the question expired while it was playing" };
    if (generation !== operationGeneration || !glasses.live) {
      return { opened: false, window, reason: "the conversation was retired" };
    }
    if (capabilityId) {
      // The prompt names the capability; the press that follows authorises that capture, and only it.
      const tag = glasses.armCapture(capabilityId);
      const armed = await nativeStep(generation, "device.send", { type: "entrance.arm", payload: tag });
      if (armed === undefined) {
        glasses.closeCapture();
        return { opened: false, window, reason: "the capture window did not open" };
      }
    } else {
      const purpose = glasses.armVoice();
      const armed = await nativeStep(generation, "device.send", { type: "voice.arm", payload: purpose });
      if (armed === undefined) {
        glasses.closeVoice();
        return { opened: false, window, reason: "the recorder window did not open" };
      }
    }
    return { opened: true, window };
  },
  cancelSession: (sessionId, reason) => {
    cancelSession(sessionId, `cancel-${sessionId}`);
    send({ id: `session-cancel-${sessionId}`, type: "event.result", payload: { cancelled: true, reason } });
  },
  gateway: undefined,
};

const handlers = createToolHandlers(toolRuntime);
const dispatch = new ConcreteToolGateway({
  definitions: toolManifest.tools as unknown as ToolDefinition[],
  handlers,
  validateArguments: createBrowserArgumentValidator(),
});
toolRuntime.gateway = dispatch;

/** Which conversation state an observation of this capability is made in. */
const CAPABILITY_STATE: Record<string, SessionState> = {
  "vision.traffic_signal": "intersection_check",
  "vision.menu": "menu_reading",
  "vision.entrance": "entrance_check",
  "vision.scene": "entrance_check",
  "vision.expression": "entrance_check",
};

/**
 * Which session state this turn is in. The registry gates every tool on `allowed_states`, and no
 * single state admits all seven tools, so the state has to be projected from what this conversation
 * is actually doing rather than declared. Only real pending things are read here, and the registry
 * only ever narrows the result: if the derived state does not admit a proposed tool, the call is
 * denied.
 */
function sessionState(sessionId: string): SessionState {
  const plan = activePlan?.sessionId === sessionId ? activePlan : undefined;
  const toolIds = plan
    ? plan.actions.flatMap((action) => action.kind === "tool_call" ? [action.toolId] : []) : [];

  // What this conversation is doing, in the order the device makes it true. The state is derived from
  // real pending things — the capture window the user's press opened, the capture waiting for its
  // observation, the route the device is running, the candidates waiting for an answer, the search
  // this turn is performing. It is never chosen to make a proposed tool pass its allow-list: when the
  // derivation says a tool does not belong in this state, the registry denies it, which is the point
  // of the allow-list.
  const requested = plan?.actions.flatMap((action) => {
    if (action.kind !== "tool_call") return [];
    if (action.toolId === "observation.request") {
      const capability = action.arguments.capability_id;
      return typeof capability === "string" ? [capability] : [];
    }
    if (action.toolId === "speech.ask_user") {
      // The registered ask_user contract keeps the capability it is asking the user to capture inside
      // its own `parameters` object, so that is where this device reads it from.
      const parameters = action.arguments.parameters;
      if (typeof parameters !== "object" || parameters === null || Array.isArray(parameters)) return [];
      const capability = (parameters as Record<string, unknown>).capability_id;
      return typeof capability === "string" ? [capability] : [];
    }
    return [];
  }) ?? [];

  const capability = glasses.armedCaptureCapability() ?? destinationCapture?.capabilityId ?? requested[0];
  const observing = capability ? CAPABILITY_STATE[capability] : undefined;
  if (observing) return observing;
  if (runningRoutes.has(sessionId)) return "navigating";
  if (toolIds.includes("navigation.search_destination")) return "destination_input";
  if (destinations.candidateCount(sessionId) > 0) return "destination_confirm";
  return "conversation_assist";
}

/**
 * The ToolGateway the Agent sees. The registry keeps deciding version, exposure, consent, schema,
 * idempotency, timeout and audit; this only supplies the version the registry declares and the state
 * this conversation is actually in.
 */
const tools: ToolGateway = new ConcreteToolGatewayAdapter(dispatch, {
  state: (sessionId) => sessionState(sessionId),
});

// ---------------------------------------------------------------------------------------------
// Planning
// ---------------------------------------------------------------------------------------------

function plannerPrompt(input: AgentTurnInput): string {
  return [
    "你是乐奇 AI 眼镜的对话 Agent。用户是视障人士，请用简短、具体、可执行的中文回答。",
    "只输出一个 JSON 对象，不要 Markdown，不要解释。字段只有 goal 和 actions，actions 是对象数组。",
    "可用动作：",
    "  1) kind=speak：字段 text、priority(critical|high|normal|detail)。",
    "  2) kind=tool_call：字段 skill_id、tool_id、arguments。skill_id 必须是 allowedTools 里含该 tool 的技能。",
    "  3) kind=wait：字段 event_types（用户可能说的下一种事件）。",
    "  4) kind=complete：字段 reason。",
    "工具与参数：",
    '  navigation.search_destination：{"query":"要搜索的地点，可用上文补全指代"}。用户换目的地时重新搜索。',
    '  navigation.confirm_destination：{"candidate_id":"上一轮候选列表中用户选中的那个"}。只有用户明确确认某个候选时才调用；用户否认、犹豫或说了别的地点时不要调用，改用 speak 或重新搜索。',
    '  speech.ask_user：{"prompt_template":"要问用户的话，可含 {place} 占位","parameters":{}}。要请用户按键拍一张照片时，在 parameters 里写 {"capability_id":"vision.entrance"|"vision.scene"|"vision.menu"|"vision.expression"|"vision.traffic_signal"}。',
    '  observation.request：{"capability_id":"..."}。只有用户刚为这个能力按下拍摄键、本轮带着那张照片时才会执行；没有授权会被拒绝，此时改用 speech.ask_user 请用户按键拍摄。',
    '  facts.query：{"names":["事实名，可用前缀*"],"limit":数字}。只返回本会话真实观察到的事实；没有就如实说没有，不要编造。',
    '  session.cancel：{"reason":"..."}。只有用户明确要求放弃当前目标时才用。',
    "约束：",
    "  · 一次最多四个动作；不确定就 speak 或 wait，不要猜。",
    "  · 导航提醒（开始、转弯、偏航、到达）已由策略确定性播报过，不要重复播报，也不要重复调用导航工具。",
    "  · 不要给出任何“可以安全通行”的结论。",
    "  · session.conversation 是本会话历史，接着上文回答，不要重新开始。",
    "输入：" + JSON.stringify(input),
  ].join("\n");
}

/**
 * Turns the model's own answer into the repository's contracts. The model speaks in place names and
 * candidate choices; what crosses a contract boundary is a DestinationQuery, a candidate id and a
 * consent this device really holds. Nothing here reads the user's words for meaning.
 */
function normalizeModelPlan(plan: AgentPlan, input: AgentTurnInput): AgentPlan {
  const now = new Date().toISOString();
  const spoken = typeof input.event.payload.transcript === "string" ? input.event.payload.transcript : undefined;
  const actions = plan.actions.map((action, index) => {
    if (action.kind !== "tool_call") return action;
    const args = action.arguments;
    switch (action.toolId) {
      case "navigation.search_destination": {
        const query = typeof args.query === "string" && args.query.trim() ? args.query.trim() : spoken;
        if (!query) return action;
        return { ...action, arguments: {
          schema_version: "1.0",
          query_id: `${plan.planId}:q${index}`,
          session_id: plan.sessionId,
          transcript: query,
          locale: "zh-CN",
          requested_at: now,
        } };
      }
      case "navigation.confirm_destination": {
        if (typeof args.candidate_id === "string" && args.candidate_id) {
          return { ...action, arguments: { candidate_id: args.candidate_id } };
        }
        // A small model may answer with the number of the candidate it read. Turning that number
        // into this session's candidate id is bookkeeping, not interpretation.
        const ordinal = args.candidate_index;
        if (typeof ordinal === "number" && Number.isSafeInteger(ordinal)) {
          const chosen = destinations.pending(plan.sessionId)[ordinal];
          if (chosen) return { ...action, arguments: { candidate_id: chosen.candidate_id } };
        }
        return { ...action, arguments: {} };
      }
      case "observation.request":
        // `capture_mode` and `consent` belong to this device's authorisation: the TaskRunner stamps
        // them from the permissions the user's own press established, never from the model.
        return { ...action, arguments: {
          capability_id: args.capability_id,
          ...(args.context !== undefined ? { context: args.context } : {}),
          ...(typeof args.request_reason === "string" ? { request_reason: args.request_reason } : {}),
        } };
      case "speech.ask_user":
        return { ...action, arguments: {
          prompt_template: args.prompt_template,
          ...(args.parameters !== undefined ? { parameters: args.parameters } : {}),
          expected_intents: Array.isArray(args.expected_intents) && args.expected_intents.length
            ? args.expected_intents : ["confirm", "cancel", "other"],
          expires_at: typeof args.expires_at === "string"
            ? args.expires_at : new Date(Date.now() + 30_000).toISOString(),
        } };
      case "facts.query": {
        const names = Array.isArray(args.names)
          ? args.names.filter((name): name is string => typeof name === "string" && name.length > 0) : [];
        if (!names.length) return action;
        const limit = typeof args.limit === "number" && Number.isSafeInteger(args.limit)
          ? Math.min(Math.max(args.limit, 1), 20) : 5;
        return { ...action, arguments: {
          names, limit, ...(typeof args.scope === "string" ? { scope: args.scope } : {}),
        } };
      }
      case "session.cancel":
        return { ...action, arguments: {
          reason: typeof args.reason === "string" && args.reason ? args.reason : "user_request",
          user_initiated: true,
        } };
      default:
        return action;
    }
  });
  return { ...plan, actions };
}

/** The same navigation fact in the contract shape the reminder policy reads. */
function toNavigationEvent(event: AgentEvent): NavigationEvent {
  return {
    schema_version: "1.0",
    event_id: event.eventId,
    session_id: event.sessionId,
    sequence: event.sequence,
    occurred_at: event.occurredAt,
    source: "navigation",
    type: event.type as NavigationEvent["type"],
    payload: event.payload as unknown as NavigationEvent["payload"],
  };
}

/** A plan whose turn was retired mid-flight performs nothing; the wait keeps it contract-valid. */
function stalePlan(input: AgentTurnInput): AgentPlan {
  return {
    planId: `stale-${input.event.eventId}`, sessionId: input.event.sessionId,
    eventId: input.event.eventId, goal: "该轮已被取消或设备断开，不执行任何动作",
    actions: [{ kind: "wait", eventTypes: ["user.cancel"] }],
    createdAt: new Date().toISOString(),
  };
}

const agent: LlmAgent = {
  async plan(input) {
    // Whatever this turn awaits, it must not act once the operation it belonged to is gone.
    const generation = operationGeneration;

    // A navigation reminder is deterministic and must not wait on a model: the policy owns it.
    if (input.event.source === "navigation" && input.event.sessionId === glasses.sessionId) {
      const reminder = reminders.create(toNavigationEvent(input.event));
      if (reminder) void speech.enqueue(reminder.text, reminder.priority, input.event.sessionId);
    }

    const metadata = {
      planId: `model-${input.event.eventId}-${++nextModelPlanId}`,
      sessionId: input.event.sessionId,
      eventId: input.event.eventId,
      createdAt: new Date().toISOString(),
    };
    try {
      const planned = await generateValidatedPlan(plannerPrompt(input), async (prompt) => {
        const response = await nativeStep<string>(generation, "model.generate", {
          prompt, sessionId: input.event.sessionId,
        });
        if (typeof response !== "string") throw new OperationCancelled("model.generate");
        return response;
      }, metadata);
      if (generation !== operationGeneration) return stalePlan(input);
      // The plan and the generation it belongs to are published together: every action this plan
      // runs is fenced on this generation, even the ones that run much later.
      activePlanGeneration = generation;
      activePlan = normalizeModelPlan(planned, input);
      return activePlan;
    } catch (error) {
      if (generation !== operationGeneration) return stalePlan(input);
      send({ id: `model-failure-${input.event.eventId}`, type: "error", error: String(error) });
      // The model could not be reached, or could not answer in contract terms. The conversation
      // stays open and says so; nothing is invented on its behalf.
      activePlan = undefined;
      return {
        planId: metadata.planId, sessionId: metadata.sessionId, eventId: metadata.eventId,
        goal: "模型本轮不可用，保持会话并请用户重复",
        actions: [{ kind: "speak", text: "我这次没听明白，请再说一次。", priority: "normal" }],
        createdAt: metadata.createdAt,
      };
    }
  },
};

// ---------------------------------------------------------------------------------------------
// Effects
// ---------------------------------------------------------------------------------------------

const host = new MobileAgentHost({
  agent,
  tools,
  skills,
  publishContractEffect: async (effect: EffectContract) => {
    if (effect.type !== "speech") {
      send({ id: effect.effect_id, type: "effect", payload: effect });
      return;
    }
    // A retired session never speaks again — not even the turn that was running when the retirement
    // landed, and not any of its remaining speeches. The single exception is the cancellation the
    // retirement turn itself announces, named by the effect id that turn published.
    const retiring = effect.effect_id === retirementAnnouncementId;
    if (!retiring && retiredSessions.has(effect.session_id)) return;
    const text = effect.payload.text;
    if (typeof text !== "string" || !text) throw new Error("speech Effect lacks text");
    const priority = typeof effect.payload.priority === "string"
      ? effect.payload.priority as "critical" | "high" | "normal" | "detail" : "normal";
    if (retiring) retirementAnnouncementText = text;
    await speech.enqueue(text, priority, effect.session_id);
  },
});

async function acceptEvent(message: Envelope): Promise<void> {
  let wireEvent = message.payload;
  let permissions = message.permissions;
  // Feedback for the turn that is still running keeps that turn's retirement alive; anything else
  // starts a new turn, and a session retired by an older one must not speak into it.
  const wireType = (wireEvent as { type?: unknown } | undefined)?.type;
  // Feedback keeps the retirement of the turn it continues alive, and a retirement event keeps its
  // own: its announcement is published while it runs. Any other new turn ends it.
  if (wireType !== "tool.results" && wireType !== "plan.rejected" &&
      !RETIREMENT_EVENTS.has(String(wireType))) {
    retiredByCurrentTurn = undefined;
    retirementAnnouncementId = undefined;
    retirementAnnouncementText = undefined;
  }
  try {
    for (let replay = 0; replay < 8; replay++) {
      activeEvent = decodeAgentEvent(wireEvent);
      lastSequence.set(activeEvent.sessionId,
        Math.max(lastSequence.get(activeEvent.sessionId) ?? 0, activeEvent.sequence));
      const output = await host.accept(wireEvent, permissions);
      if (output.rejection?.code === "consent_required") requestCaptureForRejectedPlan();
      const feedback = output.followUpEvents;
      if (!feedback?.length) {
        send({ id: message.id, type: "event.result",
          payload: { rejection: output.rejection ?? null, replays: replay } });
        return;
      }
      if (feedback.length !== 1) throw new Error("multiple feedback Events require explicit scheduling");
      wireEvent = encodeAgentEvent(feedback[0]!);
      permissions = undefined;
    }
    throw new Error("Agent feedback loop exceeded eight rounds");
  } finally {
    activeEvent = undefined;
  }
}

/**
 * Opens the explicit capture window a rejected observation asked for. The capability comes from the
 * plan the model just proposed, so what the press authorises is what was on the table — and the
 * photo it produces still goes to the model, which decides what it means.
 */
function requestCaptureForRejectedPlan(): void {
  if (glasses.armedCaptureTag() || destinationCapture) return;
  const observing = activePlan?.actions.find((action) =>
    action.kind === "tool_call" && action.toolId === "observation.request");
  const capabilityId = observing?.kind === "tool_call" ? observing.arguments.capability_id : undefined;
  if (typeof capabilityId !== "string" || !capabilityId) return;
  void toolRuntime.openQuestionWindow(
    "请按键拍摄，我来看看。", capabilityId, new Date(Date.now() + 30_000).toISOString());
}

async function acceptNativeEvent(
  id: string, sessionId: string, source: "glasses" | "speech" | "user",
  type: string, payload: Record<string, unknown>, permissions?: ExecutionPermissions,
): Promise<void> {
  const event: AgentEvent = {
    eventId: id, sessionId, sequence: (lastSequence.get(sessionId) ?? 0) + 1,
    occurredAt: new Date().toISOString(),
    source: source === "glasses" ? "device" : "user",
    sourceDetail: source,
    type, payload,
  };
  await acceptEvent({ id, type: "event", payload: encodeAgentEvent(event), permissions });
}

/**
 * Cancel and disconnect are answered the moment the envelope arrives, and the Agent record follows
 * on the serial queue. Answering immediately is what stops a turn that is still awaiting a model or
 * a playback from acting afterwards.
 */
function handleUrgentDevice(message: Envelope): void {
  const native = message.payload as NativeDevicePayload;
  const sessionId = invalidateOperation();
  cancelDevice(sessionId, message.id);
  if (!sessionId) return;
  const disconnected = native.kind === "disconnected";
  // Delivered above the queue, never behind it: the record of the retirement is what interrupts the
  // turn the retirement is for.
  retireCoreNow(message.id, sessionId, {
    eventId: message.id, sessionId, sequence: (lastSequence.get(sessionId) ?? 0) + 1,
    occurredAt: new Date().toISOString(),
    source: disconnected ? "device" : "user", sourceDetail: disconnected ? "glasses" : "user",
    type: disconnected ? "device.disconnected" : "user.cancel",
    payload: disconnected ? { reason: native.reason ?? "" } : {},
  });
}

function isUrgentDevice(native: NativeDevicePayload | undefined): boolean {
  return native?.kind === "disconnected" ||
    (native?.kind === "event" && native.type === "user.cancel");
}

async function handleNativeDevice(message: Envelope): Promise<void> {
  const native = message.payload as NativeDevicePayload | undefined;
  if (!native) throw new Error("native device payload missing");
  // A reconnect is never a resume: the next conversation still starts from a fresh physical press.
  if (native.kind !== "event") return;

  if (native.type === "entrance.confirm") {
    // The eye writes the tag it captured at the moment of the press, so a confirmation that was in
    // flight across a disarm or a rearm names the retired tag and authorises nothing.
    const tag = typeof native.payload === "string" ? native.payload.trim() : "";
    if (tag) glasses.confirmCapture(tag);
    return;
  }

  const mapped = mapNativeDeviceEvent(deviceEvents, glasses.sessionId ?? "-", native);
  if (!mapped) return;

  if (mapped.type === "capture.failed" || mapped.type === "speech.playback_failed") {
    await handleAuthorityFailure(message, native, mapped.type);
    return;
  }
  if (mapped.type !== "button.pressed") return;
  await handlePress(message);
}

/** A physical press: it opens a recorder window, or starts the conversation if none is live. */
async function handlePress(message: Envelope): Promise<void> {
  // A press the user makes while a capture window is open authorises that capture on the glasses
  // itself; it must not also open a recorder.
  if (glasses.armedCaptureTag()) return;
  const generation = operationGeneration;
  if (!glasses.live) {
    const sessionId = glasses.start(`live-${Date.now()}-${++nextLiveSession}`);
    voiceRetryCount = 0;
    captureRetryCount = 0;
    try {
      // The opening line is help, not a destination prompt: whatever the user says next decides
      // which skill this conversation is about.
      const finished = await speech.enqueue(
        "我在。你可以说出想去的地方，也可以问我眼前的情况。", "normal", sessionId);
      if (!finished || generation !== operationGeneration) return;
      const purpose = glasses.armVoice();
      await nativeStep(generation, "device.send", { type: "voice.arm", payload: purpose });
      send({ id: message.id, type: "event.result", payload: { sessionId } });
    } catch (error) {
      if (isOperationCancelled(error) || generation !== operationGeneration) return;
      glasses.cancel();
      throw new Error(`cannot start the glasses conversation: ${String(error)}`);
    }
    return;
  }
  // Already talking: a press just asks for the floor again.
  const purpose = glasses.armVoice();
  await nativeStep(generation, "device.send", { type: "voice.arm", payload: purpose });
}

/**
 * A window that expired, was denied, or produced nothing usable. Only the window that is open right
 * now is answered; anything else is a leftover from an earlier attempt and is dropped.
 */
async function handleAuthorityFailure(
  message: Envelope, native: NativeDevicePayload, mappedType: string,
): Promise<void> {
  const { tag, reason } = splitAuthority(native.payload);
  const generation = operationGeneration;
  const sessionId = glasses.sessionId;
  if (!sessionId) return;

  if (mappedType === "speech.playback_failed") {
    if (!glasses.matchesVoice(tag)) return;
    if (++voiceRetryCount > AUTHORITY_RETRY_LIMIT) {
      await endTaskOnAuthorityFailure(message.id, native.type ?? "voice", reason);
      return;
    }
    const text = native.type === "audio.failed"
      ? "这次没有录到声音，请按键后再说一次。"
      : "语音等待已超时，请按键后再说一次。";
    if (!await speech.enqueue(text, "high", sessionId)) return;
    if (generation !== operationGeneration) return;
    // A new purpose retires the failed attempt: its late audio and its late failure can no longer be
    // accepted. Re-arming only reopens the window; the recorder still needs a new physical press.
    const retryPurpose = glasses.rearmVoice();
    if (!retryPurpose) return;
    await nativeStep(generation, "device.send", { type: "voice.arm", payload: retryPurpose });
    return;
  }

  if (glasses.armedCaptureTag() !== tag) return;
  if (++captureRetryCount > AUTHORITY_RETRY_LIMIT) {
    await endTaskOnAuthorityFailure(message.id, native.type ?? "capture", reason);
    return;
  }
  const text = native.type === "photo.failed"
    ? "拍摄没有成功，请按键重拍。"
    : "拍摄等待已超时，请按键重拍。";
  if (!await speech.enqueue(text, "high", sessionId)) return;
  if (generation !== operationGeneration) return;
  // A new tag retires the failed attempt: its photo and its consent can never be accepted.
  const retryTag = glasses.rearmCapture();
  if (!retryTag) return;
  await nativeStep(generation, "device.send", { type: "entrance.arm", payload: retryTag });
}

/** The bound on automatic recovery: feedback once, then the conversation ends instead of retrying. */
async function endTaskOnAuthorityFailure(envelopeId: string, type: string, reason: string): Promise<void> {
  const sessionId = invalidateOperation();
  cancelDevice(sessionId, envelopeId);
  send({ id: `${envelopeId}:authority-terminal`, type: "error",
    error: `${type} exceeded the bounded retry budget${reason ? `: ${reason}` : ""}` });
  if (!sessionId) return;
  // This is the retirement's own announcement, and like the cancellation it is the last thing the
  // session says: every other utterance still queued for it is dropped.
  retirementAnnouncementText = "设备多次没有完成语音或拍摄，本次对话结束。请按键重新开始。";
  await speech.enqueue(retirementAnnouncementText, "high", sessionId);
}

async function handleNativeSpeech(message: Envelope): Promise<void> {
  const native = message.payload as { purpose?: string; transcript?: string } | undefined;
  if (!native || typeof native.purpose !== "string") throw new Error("native speech purpose missing");
  const generation = operationGeneration;
  const sessionId = glasses.sessionId;
  if (!sessionId) return;
  if (typeof native.transcript !== "string" || !native.transcript.trim()) {
    if (!glasses.matchesVoice(native.purpose)) return;
    if (++voiceRetryCount > AUTHORITY_RETRY_LIMIT) {
      await endTaskOnAuthorityFailure(message.id, "speech.input", "empty-transcript");
      return;
    }
    if (!await speech.enqueue("没有听清，请再按键说一次。", "high", sessionId)) return;
    if (generation !== operationGeneration) return;
    // An empty recording retires its window exactly like an expiry does, so the audio and the
    // failure of the attempt just answered can never be counted against the reopened one.
    const retryPurpose = glasses.rearmVoice();
    if (!retryPurpose) return;
    await nativeStep(generation, "device.send", { type: "voice.arm", payload: retryPurpose });
    return;
  }
  // Audio recorded under a retired purpose is not this conversation's; it is dropped here.
  if (!glasses.acceptSpeech(native.purpose)) return;
  voiceRetryCount = 0;
  await acceptNativeEvent(message.id, sessionId, "speech", "speech.input", {
    // The user's own words, verbatim. Nothing in this pipeline classifies them.
    transcript: native.transcript.trim(), input_kind: "voice", purpose: native.purpose,
  });
}

async function handleNativePhoto(message: Envelope): Promise<void> {
  const native = message.payload as { tag?: string; mediaRef?: string } | undefined;
  if (!native || typeof native.tag !== "string" || typeof native.mediaRef !== "string") return;
  const sessionId = glasses.sessionId;
  if (!sessionId) return;
  // Only the capture window open right now, confirmed by its own press, is accepted; a late photo
  // from an expired or retired attempt is dropped here.
  const authorised = glasses.acceptPhoto(native.tag);
  if (!authorised) return;
  // When this photograph was taken is stamped here, at the moment the device handed it over, before
  // any inference or any model could have an opinion about it.
  destinationCapture = {
    sessionId, capabilityId: authorised.capabilityId, mediaRef: native.mediaRef,
    capturedAt: new Date().toISOString(),
  };
  captureRetryCount = 0;
  await acceptNativeEvent(message.id, sessionId, "user", "user.capture_confirmed", {
    input_kind: "button", capability_id: authorised.capabilityId,
  }, { observationConsent: "explicit" });
}

function queueNavigation(message: Envelope): void {
  // Enqueue-fenced: a route callback buffered behind a slow turn is dropped whole if a cancel or a
  // disconnect lands while it waits, so a stale arrival can never open a session or speak against a
  // retired one.
  enqueue(async () => {
    const native = message.payload as NativeNavigationPayload;
    if (!native || typeof native.sessionId !== "string" || typeof native.kind !== "string") {
      throw new Error("invalid native navigation callback");
    }
    const sequence = (lastSequence.get(native.sessionId) ?? 0) + 1;
    const event = nativeNavigationEvent(native, message.id, sequence, new Date().toISOString());
    if (native.kind === "route_failed" || native.kind === "arrived") runningRoutes.delete(native.sessionId);
    await acceptEvent({ id: message.id, type: "event", payload: encodeAgentEvent(event) });
  }, message.id);
}

window.LeQiAgent = {
  receive(json) {
    let message: Envelope;
    try {
      message = JSON.parse(json) as Envelope;
      if (!message || typeof message.id !== "string" || typeof message.type !== "string") {
        throw new Error("invalid envelope");
      }
    } catch (error) {
      send({ id: "invalid", type: "error", error: String(error) });
      return;
    }
    if (message.type === "response") {
      const entry = pending.get(message.id);
      if (!entry) return;
      pending.delete(message.id);
      clearTimeout(entry.timeout);
      if (message.error) entry.reject(new Error(message.error));
      else entry.resolve(message.payload);
      return;
    }
    if (message.type === "event") {
      // The phone's own cancel/disconnect is answered synchronously, above the serial queue: it must
      // not wait behind a turn that is still awaiting a model or a playback.
      const urgent = message.payload as { type?: unknown; session_id?: unknown } | undefined;
      if (urgent && (urgent.type === "user.cancel" || urgent.type === "device.disconnected") &&
          typeof urgent.session_id === "string") {
        const sessionId = invalidateOperation() ?? urgent.session_id;
        cancelDevice(sessionId, message.id);
        retireCoreNow(message.id, urgent.session_id, decodeAgentEvent(message.payload));
        return;
      }
      enqueue(() => acceptEvent(message), message.id);
      return;
    }
    if (message.type === "native.navigation") {
      queueNavigation(message);
      return;
    }
    if (message.type === "native.device") {
      const native = message.payload as NativeDevicePayload | undefined;
      if (isUrgentDevice(native)) {
        handleUrgentDevice(message);
        return;
      }
    }
    if (message.type === "native.device" || message.type === "native.speech" || message.type === "native.photo") {
      const handler = message.type === "native.device" ? handleNativeDevice :
        message.type === "native.speech" ? handleNativeSpeech : handleNativePhoto;
      enqueue(() => handler(message), message.id);
    }
  },
};

send({ id: "agent-ready", type: "ready" });
