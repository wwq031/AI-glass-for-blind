import type { AgentEvent } from "../../../../packages/domain/agent/types.ts";
import type { ObservationProvider, ObservationResult } from "../../../../packages/providers/observation/observation-provider.ts";
import type { DestinationCandidate, DestinationQuery } from "../../../../packages/providers/navigation/navigation-provider.ts";
import type { SessionState } from "../../../../packages/domain/session/session-orchestrator.ts";
import type {
  ToolCall,
  ToolHandler,
  ToolHandlerOutput,
} from "../../../../packages/domain/tools/tool-gateway.ts";
import type { NavigationDestinationPolicy } from "./navigation-destination-policy.ts";
import type { FactsQueryScope, SessionFactStore } from "./session-facts.ts";

/** The concrete gateway the handlers call back into; wired after construction to avoid a cycle. */
export interface PolicyGateway {
  execute(call: ToolCall, state: SessionState): Promise<{
    status: string;
    output?: Record<string, unknown>;
    events?: { type: string; payload?: Record<string, unknown> }[];
    error?: { message?: string };
  }>;
}

/** One authorised capture: the reference, and when the device actually produced it. */
export interface AuthorisedCapture {
  mediaRef: string;
  /** Receipt time of the photo on this device, stamped before any inference could see it. */
  capturedAt: string;
}

/** The outcome of asking the user something, including whether the device could ask at all. */
export interface QuestionWindowResult {
  opened: boolean;
  window: "capture" | "voice";
  reason?: string;
}

export interface ToolRuntime {
  now(): string;
  idFactory(): string;
  locale(): string;
  /** The user Event this turn is handling; a confirmation is only valid for it. */
  currentEvent(): AgentEvent | undefined;
  /** Issues a native request fenced on the operation generation; undefined once it was retired. */
  native<T>(type: string, payload: unknown): Promise<T | undefined>;
  /**
   * The generation the plan being executed was planned under, captured when the turn started. It is
   * read once per turn, never per awaited call: a cancel that lands mid-turn must retire the whole
   * turn, not hand its remaining calls the new generation.
   */
  generation(): number;
  /** Whether that generation is still the live one. False once a cancel or disconnect retired it. */
  isLive(generation: number): boolean;
  /**
   * Runs a nested tool call under the cancellation signal of the call that issued it, so a nested
   * call cannot outlive the timeout of its parent.
   */
  withinParentSignal<T>(signal: AbortSignal, run: () => Promise<T>): Promise<T>;
  /** Whether the call that issued the current nested call has already been aborted. */
  parentAborted(): boolean;
  destinations: NavigationDestinationPolicy;
  facts: SessionFactStore;
  observation: ObservationProvider;
  /** Consumes the capture the user authorised for exactly this capability, once. */
  takeCapture(capabilityId: string): AuthorisedCapture | undefined;
  routeRunning(sessionId: string): boolean;
  onRouteStarted(sessionId: string, candidate: DestinationCandidate): void;
  /**
   * Announces a question and opens the window its answer will arrive on. Resolves once the device
   * really announced it and the window is open; `expiresAt` bounds how long it may wait.
   */
  openQuestionWindow(text: string, capabilityId: string | undefined, expiresAt: string): Promise<QuestionWindowResult>;
  /** Revokes the session through the cancel channel; no later result may revive it. */
  cancelSession(sessionId: string, reason: string): void;
  gateway: PolicyGateway | undefined;
}

/** Fills `{name}` placeholders from the model's parameters, and drops what it did not provide. */
export function renderPrompt(template: string, parameters: Record<string, unknown>): string {
  return template.replace(/\{([a-zA-Z0-9_]+)\}/g, (_match, name: string) => {
    const value = parameters[name];
    return typeof value === "string" || typeof value === "number" ? String(value) : "";
  }).trim();
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

/**
 * The native `tool.execute` reply, in the camel shape the device actually writes. It is checked
 * against the call it answers — a device may answer, but it may not answer as somebody else — and
 * then read field by field, so nothing here pretends a native result is a repository ToolResult.
 */
interface NativeToolResult {
  callId: string;
  sessionId: string;
  toolId: string;
  status: string;
  completedAt?: string;
  output?: Record<string, unknown>;
  facts?: unknown[];
  error?: { message?: string };
}

function nativeToolResult(value: unknown, call: ToolCall): NativeToolResult {
  if (!isRecord(value) || value.callId !== call.call_id || value.sessionId !== call.session_id ||
      value.toolId !== call.tool_id || typeof value.status !== "string" ||
      (value.facts !== undefined && !Array.isArray(value.facts))) {
    throw new Error("tool.execute returned a mismatched ToolResult");
  }
  return value as unknown as NativeToolResult;
}

/**
 * A ToolCall id is `<planId>:<actionIndex>`. A plan id is opaque and may contain separators of its
 * own, so the split is taken at the last separator and the plan id is carried through whole.
 */
export function splitCallId(callId: string): { planId: string; actionIndex: number } {
  const cut = callId.lastIndexOf(":");
  const parsed = cut < 0 ? Number.NaN : Number(callId.slice(cut + 1));
  return {
    planId: cut < 0 ? callId : callId.slice(0, cut),
    actionIndex: Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : 0,
  };
}

function candidateOf(value: unknown): DestinationCandidate | undefined {
  return isRecord(value) && typeof value.candidate_id === "string" && typeof value.name === "string" &&
    typeof value.provider === "string" && isRecord(value.location) &&
    typeof value.location.lat === "number" && typeof value.location.lng === "number"
    ? value as unknown as DestinationCandidate : undefined;
}

/** True once the turn this handler belongs to was retired: the gateway revoked it, or it timed out. */
function retired(runtime: ToolRuntime, context: { signal: AbortSignal }, generation: number): boolean {
  return context.signal.aborted || !runtime.isLive(generation);
}

/**
 * The seven registered tools, bound to this device.
 *
 * Every handler is a transport adapter: it reaches the real map, camera, clock or device, returns
 * what it actually got, and decides nothing about what the user wants. Choosing a destination,
 * choosing when to confirm, and choosing what to observe all stay with the model and the policies.
 *
 * Every handler that awaits also re-checks, before it writes anything, that the turn it belongs to
 * is still the live one. A tool that finished after a cancel — or after its own timeout aborted it —
 * has information about a conversation that is over, and must not be able to record it as state.
 */
export function createToolHandlers(runtime: ToolRuntime): ReadonlyMap<string, ToolHandler> {
  const handlers = new Map<string, ToolHandler>();

  handlers.set("speech.ask_user", async (call): Promise<ToolHandlerOutput> => {
    const template = call.arguments.prompt_template;
    if (typeof template !== "string") throw new Error("ask_user needs a prompt template");
    const parameters = isRecord(call.arguments.parameters) ? call.arguments.parameters : {};
    const text = renderPrompt(template, parameters);
    if (!text) throw new Error("ask_user rendered an empty question");
    const capabilityId = typeof parameters.capability_id === "string" ? parameters.capability_id : undefined;
    const expiresAt = typeof call.arguments.expires_at === "string"
      ? call.arguments.expires_at : call.expires_at;
    if (typeof expiresAt !== "string" || Date.parse(expiresAt) <= Date.parse(runtime.now())) {
      // The question's own window has already closed. Asking it anyway would open a window the user's
      // answer could no longer be accepted on.
      throw new Error("ask_user expired before the question could be asked");
    }
    // The handler waits for the device to actually announce the question and open its window: a
    // prompt nobody heard, or a window that never opened, is a failure the model has to see.
    const asked = await runtime.openQuestionWindow(text, capabilityId, expiresAt);
    if (!asked.opened) throw new Error(`ask_user could not open a ${asked.window} window: ${asked.reason ?? "unknown"}`);
    return {
      output: { prompt: text, window: asked.window, capability_id: capabilityId ?? null },
      events: [{ type: "speech.prompted", payload: { capability_id: capabilityId ?? null } }],
    };
  });

  handlers.set("navigation.search_destination", async (call, context): Promise<ToolHandlerOutput> => {
    const generation = runtime.generation();
    const query = call.arguments as unknown as DestinationQuery;
    const { planId, actionIndex } = splitCallId(call.call_id);
    const answer = await runtime.native<unknown>("tool.execute", {
      sessionId: call.session_id,
      plan: { planId },
      actionIndex,
      toolId: call.tool_id,
      // The device searches a place name; the repository's DestinationQuery is the contract this
      // call was validated against, and its resolved text is what the search runs on.
      arguments: { query: query.transcript },
    });
    if (answer === undefined || retired(runtime, context, generation)) {
      return { status: "partial", output: { cancelled: true } };
    }
    const result = nativeToolResult(answer, call);
    if (result.status !== "succeeded") throw new Error(result.error?.message ?? "destination search failed");
    const candidates = isRecord(result.output) && Array.isArray(result.output.candidates)
      ? result.output.candidates.map(candidateOf) : [];
    if (candidates.some((candidate) => candidate === undefined)) {
      throw new Error("destination search returned an unusable candidate");
    }
    const output = {
      schema_version: "1.0",
      query_id: query.query_id,
      session_id: call.session_id,
      generated_at: runtime.now(),
      candidates: candidates as DestinationCandidate[],
    };
    // Only a result against the live generation may become the candidates the user is confirming.
    runtime.destinations.recordSearch({
      callId: call.call_id, sessionId: call.session_id, toolId: call.tool_id,
      status: "succeeded", completedAt: runtime.now(), output, facts: [],
    });
    return { output, events: [{ type: "destination.candidates_listed", payload: { count: output.candidates.length } }] };
  });

  handlers.set("navigation.confirm_destination", async (call, context): Promise<ToolHandlerOutput> => {
    const generation = runtime.generation();
    // Confirming reads and writes this session's search state; a retired turn may do neither.
    if (retired(runtime, context, generation)) return { status: "partial", output: { cancelled: true } };
    const selected = runtime.destinations.confirm({
      sessionId: call.session_id,
      toolId: call.tool_id,
      candidateId: call.arguments.candidate_id,
    }, runtime.currentEvent());
    const queryId = runtime.destinations.queryIdFor(call.session_id);
    if (!queryId) throw new Error("destination confirmation lost its search context");
    runtime.destinations.markStartRequested(call.session_id, queryId, selected.candidate_id);
    // Starting the route is the policy's act, and it goes through the same gateway: the model asked
    // to confirm a candidate, it never asked to start navigation. The nested call inherits this
    // call's cancellation signal, so the timeout of the confirmation is the timeout of the start.
    const gateway = runtime.gateway;
    if (!gateway) throw new Error("navigation.start gateway is not bound");
    const started = await runtime.withinParentSignal(context.signal, () => gateway.execute({
      schema_version: "1.0",
      call_id: `${call.call_id}:start`,
      session_id: call.session_id,
      tool_id: "navigation.start",
      tool_version: call.tool_version,
      origin: "policy",
      issued_at: runtime.now(),
      idempotency_key: `${call.call_id}:start`,
      arguments: { candidate_id: selected.candidate_id, mode: "walking" },
    }, context.state));
    if (started.status !== "succeeded") {
      throw new Error(started.error?.message ?? "navigation did not start");
    }
    return {
      output: { candidate_id: selected.candidate_id, name: selected.name, ...started.output },
      events: started.events ?? [],
    };
  });

  handlers.set("navigation.start", async (call, context): Promise<ToolHandlerOutput> => {
    // The gateway already refused any origin but policy; this is the only route onto the device.
    const generation = runtime.generation();
    const candidateId = call.arguments.candidate_id;
    const candidate = runtime.destinations.pending(call.session_id)
      .find(({ candidate_id }) => candidate_id === candidateId);
    if (!candidate) throw new Error("navigation.start needs a candidate of this session's search");
    if (runtime.routeRunning(call.session_id)) {
      // The device runs one route at a time and never silently replaces it, so switching destination
      // stops the route the user is leaving before the confirmed one starts.
      await runtime.native("policy.navigation.stop", { sessionId: call.session_id });
      // Stopping the old route is itself a side effect. If this call was retired while it ran — by a
      // cancel, or by the timeout of the call that issued it — no new route may be started on top.
      if (retired(runtime, context, generation) || runtime.parentAborted()) {
        return { status: "partial", output: { cancelled: true } };
      }
    }
    const started = await runtime.native<{ started?: boolean; distanceM?: number; timeSec?: number }>(
      "policy.navigation.start", { sessionId: call.session_id, candidate });
    if (started === undefined || retired(runtime, context, generation) || runtime.parentAborted()) {
      // A route that started under a call which has since been abandoned is stopped again: the
      // timeout of the confirmation must not leave the user walking a route nobody is guiding.
      if (started !== undefined) {
        await runtime.native("policy.navigation.stop", { sessionId: call.session_id });
      }
      return { status: "partial", output: { cancelled: true } };
    }
    if (started.started !== true) throw new Error("native walking navigation did not confirm start");
    runtime.onRouteStarted(call.session_id, candidate);
    return {
      output: { candidate_id: candidate.candidate_id, started: true, distance_m: started.distanceM, time_sec: started.timeSec },
      events: [{ type: "navigation.started", payload: { candidate_id: candidate.candidate_id } }],
    };
  });

  handlers.set("observation.request", async (call, context): Promise<ToolHandlerOutput> => {
    const generation = runtime.generation();
    const capabilityId = call.arguments.capability_id;
    if (typeof capabilityId !== "string") throw new Error("observation needs a capability");
    // The consent is this window's, for this capability; the model cannot ask for another one.
    const capture = runtime.takeCapture(capabilityId);
    if (!capture) throw new Error("no capture is authorised for this capability");
    const modelContext = isRecord(call.arguments.context) ? call.arguments.context : {};
    // When the photo was taken, and how old it may be, are facts about the device's own capture.
    // They are stamped here, from the receipt of the photo, and they override anything the model put
    // in the context: freshness that the model could state is not freshness this build can trust.
    const context_ = { ...modelContext, captured_at: capture.capturedAt, max_age_ms: MAX_CAPTURE_AGE_MS };
    const result: ObservationResult = await runtime.observation.observe({
      schema_version: "1.0",
      session_id: call.session_id,
      request_id: call.call_id,
      capability_id: capabilityId,
      trigger: "user_button",
      media_refs: [capture.mediaRef],
      consent: call.consent === "not_applicable" || call.consent === undefined ? "explicit" : call.consent,
      context: context_,
      policy: { max_age_ms: MAX_CAPTURE_AGE_MS },
    });
    // The reading of a photo of a conversation that is over is not a fact about anything.
    if (retired(runtime, context, generation)) {
      return { status: "partial", output: { capability_id: capabilityId, cancelled: true } };
    }
    // One observation is one batch: a menu read line by line contributes several same-name facts with
    // their own evidence, and all of them belong to this photograph, not to each other.
    runtime.facts.recordBatch(
      call.session_id,
      result.facts.map((fact) => ({
        name: fact.name,
        value: fact.value,
        confidence: fact.confidence,
        ...(fact.valid_until ? { validUntil: fact.valid_until } : {}),
        source: `observation:${capabilityId}`,
        ...(fact.evidence?.length ? { evidence: [...fact.evidence] } : {}),
      })),
      `observation:${capabilityId}`,
    );
    return {
      // A capture that could not be read is reported as it is; "it did not work" is information the
      // model needs, and it must not be flattened into a success.
      status: result.status === "succeeded" ? "succeeded" : "partial",
      output: {
        capability_id: capabilityId,
        observation_status: result.status,
        summary: result.summary,
        confidence: result.confidence,
        needs_retake: result.needs_retake,
        captured_at: capture.capturedAt,
        ...(result.fresh_until ? { fresh_until: result.fresh_until } : {}),
        ...(result.ocr ? { ocr: result.ocr } : {}),
      },
      facts: result.facts,
      events: [{ type: "observation.result_received", payload: { capability_id: capabilityId, status: result.status } }],
    };
  });

  handlers.set("facts.query", async (call): Promise<ToolHandlerOutput> => {
    const names = Array.isArray(call.arguments.names)
      ? call.arguments.names.filter((name): name is string => typeof name === "string") : [];
    const scope: FactsQueryScope = call.arguments.scope === "recent_observation" ||
      call.arguments.scope === "navigation_context" || call.arguments.scope === "current_session"
      ? call.arguments.scope : "current_session";
    const limitValue = call.arguments.limit;
    const limit = Number.isSafeInteger(limitValue) ? Math.min(Math.max(limitValue as number, 1), 20) : 5;
    // The scope is handed to the store, which is what knows which facts an observation recorded and
    // which ones the route reported; the question the user asked is not re-read here.
    const facts = runtime.facts.query(call.session_id, names, limit, scope);
    // Nothing established means nothing is returned: an empty answer is the honest one.
    return {
      output: { scope, count: facts.length, facts },
      events: [{ type: "facts.returned", payload: { count: facts.length } }],
    };
  });

  handlers.set("session.cancel", async (call): Promise<ToolHandlerOutput> => {
    const reason = typeof call.arguments.reason === "string" ? call.arguments.reason : "user_request";
    runtime.cancelSession(call.session_id, reason);
    return {
      output: { cancelled: true, reason },
      events: [{ type: "session.cancelled", payload: { reason } }],
    };
  });

  return handlers;
}

/** How old a photograph may be and still count as evidence of what is in front of the user. */
const MAX_CAPTURE_AGE_MS = 30_000;
