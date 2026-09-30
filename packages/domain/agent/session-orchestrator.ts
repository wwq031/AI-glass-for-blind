import { buildAgentTurnInput, sanitizeForModel } from "./context-builder.ts";
import { isCrossingApproachEvent, skillContextFromNavigationEvent } from "./navigation-trigger.ts";
import { adviseCrossing, adviseCrossingFromResult } from "../policies/crossing-advisory.ts";
import type { AgentConversationTurn, LlmAgent, AgentSessionView } from "./llm-agent.ts";
import { validatePlan, type ExecutionPermissions, type PlanValidation } from "./plan-validator.ts";
import { TaskRunner } from "./task-runner.ts";
import type { ToolGateway } from "./tool-gateway.ts";
import type { AgentEvent, AgentPlan, Effect, SkillRegistry, ToolResult } from "./types.ts";

/**
 * The conversation trace is a bounded, sanitized summary of what happened in this session, so the
 * next utterance is planned as a continuation instead of a cold start. Only text enters it: a user
 * utterance, the goal a turn aimed at, what was spoken, and what a tool established.
 */
const CONVERSATION_TURN_LIMIT = 24;
const CONVERSATION_TEXT_LIMIT = 400;

function conversationText(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const text = value.trim();
  return text.length === 0 ? undefined : text.slice(0, CONVERSATION_TEXT_LIMIT);
}

function appendConversation(view: AgentSessionView, kind: AgentConversationTurn["kind"], text: string): void {
  const conversation = view.conversation ?? (view.conversation = []);
  const previous = conversation.at(-1);
  if (previous && previous.kind === kind && previous.text === text) return;
  conversation.push({ kind, text });
  while (conversation.length > CONVERSATION_TURN_LIMIT) conversation.shift();
}

/** Forgets the conversation and everything derived from it; a cancel or a disconnect ends it. */
function clearConversation(view: AgentSessionView): void {
  view.conversation = undefined;
  view.goal = undefined;
  view.pendingQuestion = undefined;
}

export interface AgentHandleOutput {
  effects: Effect[];
  results: ToolResult[];
  /** Caller may explicitly submit these as the next Event; no automatic LLM loop. */
  followUpEvents?: AgentEvent[];
  rejection?: {
    code: "stale_event" | "pending_feedback" | "invalid_feedback" | "interrupted" | "crossing_context_retired" | "invalid_navigation_event" | Exclude<PlanValidation, { ok: true }>["code"];
    actionIndex?: number;
  };
}

interface SessionRecord {
  view: AgentSessionView;
  pendingFeedback?: AgentEvent;
  pendingCrossingCallId?: string;
  pendingCrossingEvidenceStale?: boolean;
  crossingContext?: { intersection_id: string; travel_heading_deg: number };
  crossingPending?: boolean;
  epoch: number;
}

export class SessionOrchestrator {
  private readonly options: { agent: LlmAgent; tools: ToolGateway; skills: SkillRegistry; now?: () => string; navigationTriggerMaxAgeMs?: number };
  private readonly sessions = new Map<string, SessionRecord>();
  private readonly processing = new Set<string>();
  private readonly runner: TaskRunner;

  constructor(options: {
    agent: LlmAgent;
    tools: ToolGateway;
    skills: SkillRegistry;
    now?: () => string;
    navigationTriggerMaxAgeMs?: number;
  }) {
    this.options = options;
    this.runner = new TaskRunner({ tools: options.tools, now: options.now });
  }

  snapshot(sessionId: string): Pick<AgentSessionView, "sessionId" | "lastSequence" | "activePlanId"> {
    const view = this.getSession(sessionId).view;
    return { sessionId: view.sessionId, lastSequence: view.lastSequence, activePlanId: view.activePlanId };
  }

  /**
   * Feed followUpEvents back explicitly. While feedback is pending, an unrelated Event is
   * rejected with pending_feedback; submit it again with a new sequence after feedback.
   * The pending Event's stored payload is authoritative, never the caller's copy.
   * Route progress cannot replace a pending crossing ToolResult. It receives pending_feedback
   * and a conservative stop reminder; submit canonical feedback, then route progress with a
   * new sequence. With no pending crossing result, route progress retires the context with
   * crossing_context_retired and a policy reminder; resubmit it with a new sequence.
   * user.cancel, user.help_requested, device.disconnected, and a user speech Event
   * with payload.intent_hint=cancel always preempt pending feedback. No contact is made.
   */
  async handle(event: AgentEvent, permissions: ExecutionPermissions = { observationConsent: "none" }): Promise<AgentHandleOutput> {
    const session = this.getSession(event.sessionId);
    if (event.sequence <= session.view.lastSequence) {
      return { effects: [], results: [], rejection: { code: "stale_event" } };
    }
    const urgentStatus = this.urgentStatus(event);
    if (urgentStatus) return this.handleUrgent(event, session, urgentStatus);
    if (this.processing.has(event.sessionId)) {
      return { effects: [], results: [], rejection: { code: "stale_event" } };
    }
    if (session.pendingCrossingCallId && this.isRouteInvalidating(event)) {
      // Do not consume canonical feedback, but ensure its evidence cannot authorize passage.
      session.pendingCrossingEvidenceStale = true;
    }
    if (session.crossingPending && this.isRouteProgress(event)) {
      if (session.pendingCrossingCallId) {
        // The canonical ToolResult owns the next sequence. Submit it first, then resubmit
        // this route Event with a sequence newer than the consumed feedback.
        return { effects: [this.unconfirmedCrossingEffect(event)], results: [], rejection: { code: "pending_feedback" } };
      }
      // No observation is pending: retire this crossing conservatively. The route Event is
      // consumed without model planning; the caller may resubmit it at a fresh sequence.
      return this.finishUnconfirmedCrossing(event, session, { code: "crossing_context_retired" });
    }
    const pending = session.pendingFeedback;
    if (pending && !this.matchesPendingFeedback(event, pending)) {
      // The feedback owns the next sequence. Resubmit unrelated Events with a new sequence after feedback.
      return { effects: [], results: [], rejection: { code: "pending_feedback" } };
    }
    if (!pending && this.isFeedback(event)) {
      return { effects: [], results: [], rejection: { code: "invalid_feedback" } };
    }
    if (pending && session.pendingCrossingCallId && pending.type === "tool.results") {
      const results = pending.payload.results;
      const candidates = Array.isArray(results) ? results.filter((item): item is ToolResult =>
        typeof item === "object" && item !== null && "callId" in item && item.callId === session.pendingCrossingCallId) : [];
      const candidate = candidates.length === 1 ? candidates[0] : undefined;
      const output = candidate?.output;
      const echoedContext = output && typeof output.context === "object" && output.context !== null &&
        !Array.isArray(output.context) ? output.context as Record<string, unknown> : undefined;
      const trustedContext = session.crossingContext;
      const result = candidate && candidate.sessionId === event.sessionId && candidate.toolId === "observation.request" &&
        output?.capability_id === "vision.traffic_signal" && trustedContext && echoedContext &&
        echoedContext.intersection_id === trustedContext.intersection_id &&
        echoedContext.travel_heading_deg === trustedContext.travel_heading_deg && Array.isArray(candidate.facts)
        ? candidate : undefined;
      const createdAt = (this.options.now ?? (() => new Date().toISOString()))();
      const advisory = result && !session.pendingCrossingEvidenceStale ? adviseCrossingFromResult(result, createdAt) : adviseCrossingFromResult({
        callId: session.pendingCrossingCallId, sessionId: event.sessionId, toolId: "observation.request",
        status: "failed", completedAt: createdAt, output: {}, facts: [],
      }, createdAt);
      session.view.lastSequence = pending.sequence;
      session.pendingFeedback = undefined;
      session.pendingCrossingCallId = undefined;
      session.pendingCrossingEvidenceStale = undefined;
      session.crossingPending = false;
      session.crossingContext = undefined;
      session.view.navigation = undefined;
      return { effects: [{ effectId: `${pending.eventId}:crossing-advisory`, sessionId: event.sessionId,
        type: "speech", createdAt, payload: { text: advisory.speech, action: advisory.action, priority: "critical" } }], results: [] };
    }
    this.processing.add(event.sessionId);
    const epoch = session.epoch;
    try {
      const canonicalEvent = pending ? structuredClone(pending) : event;
      const navigationContext = skillContextFromNavigationEvent(canonicalEvent, {
        now: (this.options.now ?? (() => new Date().toISOString()))(),
        maxAgeMs: this.options.navigationTriggerMaxAgeMs,
      });
      if (navigationContext.rejected) {
        return this.finishUnconfirmedCrossing(canonicalEvent, session, { code: "invalid_navigation_event" });
      }
      if (navigationContext.urgentSkillId) {
        session.crossingPending = true;
        session.pendingCrossingEvidenceStale = undefined;
        const navigation = navigationContext.navigation;
        session.view.navigation = navigation;
        session.crossingContext = navigation?.intersectionId !== undefined && navigation.travelHeadingDeg !== undefined ?
          { intersection_id: navigation.intersectionId, travel_heading_deg: navigation.travelHeadingDeg } : undefined;
      } else if (this.isUserCrossingRequest(canonicalEvent)) session.crossingPending = true;
      // A user utterance joins the trace before planning, so the model plans it as a continuation.
      // Answering a pending question retires it.
      if (canonicalEvent.source === "user") {
        const spoken = conversationText(canonicalEvent.payload.transcript);
        if (spoken !== undefined) {
          appendConversation(session.view, "user", spoken);
          session.view.pendingQuestion = undefined;
        }
      }
      const input = buildAgentTurnInput({
        event: canonicalEvent, session: { ...session.view, ...(navigationContext.navigation ? { navigation: navigationContext.navigation } : {}) }, skills: this.options.skills.list(),
        recentResults: canonicalEvent.type === "tool.results" && Array.isArray(canonicalEvent.payload.results)
          ? canonicalEvent.payload.results : [],
      }, navigationContext.urgentSkillId);
      const plan = await this.options.agent.plan(input);
      if (session.epoch !== epoch) return { effects: [], results: [], rejection: { code: "interrupted" } };
      const proposesCrossing = Array.isArray(plan?.actions) && plan.actions.some((action) =>
        action?.kind === "tool_call" && action.skillId === "crossing_advisory");
      if (proposesCrossing) session.crossingPending = true;
      // A navigation trigger can request confirmation, but it cannot carry camera consent.
      // Consent must arrive with a distinct user event, after the reminder has been delivered.
      const turnPermissions: ExecutionPermissions = navigationContext.urgentSkillId
        ? { ...permissions, observationConsent: "none" }
        : permissions;
      const validation = validatePlan(this.options.skills, plan, turnPermissions);
      if (!validation.ok) {
        if (session.crossingPending && proposesCrossing && validation.code === "consent_required") {
          return this.waitForCrossingConfirmation(canonicalEvent, session, { code: validation.code, actionIndex: validation.actionIndex });
        }
        return this.rejectPlan(canonicalEvent, session, { code: validation.code, actionIndex: validation.actionIndex });
      }
      if (plan.sessionId !== canonicalEvent.sessionId || plan.eventId !== canonicalEvent.eventId || !plan.planId) {
        return this.rejectPlan(canonicalEvent, session, { code: "policy_required" });
      }
      if (session.crossingPending && plan.actions.some((action) =>
        action.kind === "speak" || action.kind === "complete" ||
        (action.kind === "tool_call" && action.toolId === "speech.ask_user"))) {
        const rejected = this.rejectPlan(canonicalEvent, session, { code: "policy_required" });
        const createdAt = (this.options.now ?? (() => new Date().toISOString()))();
        rejected.effects.push({ effectId: `${canonicalEvent.eventId}:crossing-reminder`, sessionId: canonicalEvent.sessionId,
          type: "speech", createdAt, payload: { text: "请先停下。路口情况尚未确认，需要观察结果后才能提供辅助提示。", priority: "critical" } });
        return rejected;
      }
      if (session.crossingPending && plan.actions.every((action) => action.kind === "wait")) {
        session.view.activePlanId = plan.planId;
        return this.waitForCrossingConfirmation(canonicalEvent, session);
      }
      // Commit before tool execution: a thrown gateway may already have caused an external side effect.
      session.view.lastSequence = canonicalEvent.sequence;
      session.view.activePlanId = plan.planId;
      session.view.goal = plan.goal;
      const output = await this.runner.run(plan, turnPermissions, () => session.epoch === epoch, session.crossingContext);
      if (session.epoch !== epoch) {
        return { effects: [], results: output.results, rejection: { code: "interrupted" } };
      }
      this.recordTurn(session, plan, output);
      // A question the device really asked owns the floor for the rest of the turn. Its answer is the
      // next user Event, not a ToolResult the model reads back: feeding it back would make the model
      // speak over the question it just asked, on a window the user is still answering into.
      if (this.asksForAnAnswer(plan, output)) {
        session.pendingFeedback = undefined;
        session.pendingCrossingCallId = undefined;
        return output;
      }
      if (output.results.length === 0) {
        session.pendingFeedback = undefined;
        session.pendingCrossingCallId = undefined;
        return output;
      }
      const crossingIndex = plan.actions.findIndex((action) =>
        action.kind === "tool_call" && action.skillId === "crossing_advisory" && action.toolId === "observation.request" &&
        action.arguments.capability_id === "vision.traffic_signal");
      // If a gateway returns malformed or mismatched feedback, the expected call is absent and policy fails closed.
      session.pendingCrossingCallId = crossingIndex < 0 ? undefined : `${plan.planId}:${crossingIndex}`;
      const feedback: AgentEvent = {
        eventId: `${plan.planId}:results`, sessionId: canonicalEvent.sessionId,
        sequence: canonicalEvent.sequence + 1, source: "provider", type: "tool.results",
        occurredAt: (this.options.now ?? (() => new Date().toISOString()))(),
        payload: { results: structuredClone(output.results) },
      };
      session.pendingFeedback = structuredClone(feedback);
      return { ...output, followUpEvents: [structuredClone(feedback)] };
    } finally {
      this.processing.delete(event.sessionId);
    }
  }

  /**
   * Keeps the trace of one committed turn: what the turn aimed at, what was actually said, and what
   * a tool actually established. Spoken text and tool facts enter it so a later utterance can be
   * planned against what the user already heard, never against a guess about it.
   */
  private recordTurn(session: SessionRecord, plan: AgentPlan, output: AgentHandleOutput): void {
    const goal = conversationText(plan.goal);
    if (goal !== undefined) appendConversation(session.view, "goal", goal);
    for (const effect of output.effects) {
      if (effect.type !== "speech") continue;
      const spoken = conversationText(effect.payload.text);
      if (spoken !== undefined) appendConversation(session.view, "agent", spoken);
    }
    for (const result of output.results) {
      // History is a summary, not evidence. A malformed entry is skipped here so one bad fact list
      // cannot crash the turn; the raw result is left exactly as the gateway reported it, so the
      // policies that must fail closed on malformed evidence still see it whole.
      const facts: unknown = result.facts;
      for (const fact of Array.isArray(facts) ? facts : []) {
        if (fact === null || typeof fact !== "object") continue;
        const { name: rawName, value: rawValue } = fact as { name?: unknown; value?: unknown };
        const name = conversationText(rawName);
        if (name === undefined) continue;
        const value = typeof rawValue === "string" ? rawValue : JSON.stringify(rawValue);
        appendConversation(session.view, "fact", `${name}: ${value ?? ""}`);
      }
      // A destination search answers with candidates the user is about to choose between. The
      // identifiers are kept so that the next utterance — the user's actual choice — can be planned
      // against the same list, instead of against a list the model saw once and can no longer name.
      if (result.toolId === "navigation.search_destination" && result.status === "succeeded") {
        const candidates = result.output.candidates;
        const listed = (Array.isArray(candidates) ? candidates : []).flatMap((candidate) => {
          if (candidate === null || typeof candidate !== "object") return [];
          const { candidate_id: id, name } = candidate as Record<string, unknown>;
          return typeof id === "string" && id && typeof name === "string" && name ? [`${id} ${name}`] : [];
        });
        if (listed.length) appendConversation(session.view, "fact", `候选地点: ${listed.join("; ")}`);
      }
    }
    const asked = plan.actions.find((action) =>
      action.kind === "tool_call" && action.toolId === "speech.ask_user");
    if (asked && asked.kind === "tool_call") {
      const question = conversationText(asked.arguments.prompt_template) ??
        conversationText(asked.arguments.question);
      if (question !== undefined) session.view.pendingQuestion = question;
    }
  }

  /**
   * True when the turn ended on a question the user is expected to answer. Only a question the device
   * actually asked counts: if the window never opened, the model has to see the failure and decide
   * again, exactly like any other ToolResult.
   */
  private asksForAnAnswer(plan: AgentPlan, output: AgentHandleOutput): boolean {
    for (let index = plan.actions.length - 1; index >= 0; index--) {
      const action = plan.actions[index];
      if (!action || action.kind !== "tool_call") continue;
      if (action.toolId !== "speech.ask_user") return false;
      const asked = output.results.find((result) => result.callId === `${plan.planId}:${index}`);
      return asked?.status === "succeeded" || asked?.status === "partial";
    }
    return false;
  }

  private urgentStatus(event: AgentEvent): "cancelled" | "help_requested" | "device_disconnected" | undefined {
    if (event.source === "device" && event.type === "device.disconnected") return "device_disconnected";
    if (event.source !== "user") return undefined;
    if (event.type === "user.help_requested") return "help_requested";
    if (event.type === "user.cancel" || event.payload.intent_hint === "cancel") return "cancelled";
    return undefined;
  }

  private handleUrgent(
    event: AgentEvent,
    session: SessionRecord,
    status: "cancelled" | "help_requested" | "device_disconnected",
  ): AgentHandleOutput {
    session.epoch++;
    session.view.lastSequence = event.sequence;
    session.view.activePlanId = undefined;
    session.pendingFeedback = undefined;
    session.pendingCrossingCallId = undefined;
    session.pendingCrossingEvidenceStale = undefined;
    session.crossingPending = false;
    session.crossingContext = undefined;
    session.view.navigation = undefined;
    // An explicit cancel or a disconnect revokes the conversation as well as the plan: nothing
    // planned against it may be revived by a result or a final announcement that arrives later.
    if (status !== "help_requested") clearConversation(session.view);
    const createdAt = (this.options.now ?? (() => new Date().toISOString()))();
    const effects: Effect[] = [{
      effectId: `${event.eventId}:session`, sessionId: event.sessionId,
      type: "session", createdAt, payload: { status },
    }];
    const message = status === "cancelled" ? "当前任务已取消。" :
      status === "help_requested" ? "当前任务已暂停。请向身边可信的人求助。" : undefined;
    if (message) effects.push({
      effectId: `${event.eventId}:speech`, sessionId: event.sessionId,
      type: "speech", createdAt, payload: { text: message, priority: "high" },
    });
    return { effects, results: [] };
  }

  private rejectPlan(
    event: AgentEvent,
    session: SessionRecord,
    rejection: NonNullable<AgentHandleOutput["rejection"]>,
  ): AgentHandleOutput {
    session.view.lastSequence = event.sequence;
    const payload: Record<string, unknown> = { code: rejection.code };
    if (rejection.actionIndex !== undefined) payload.actionIndex = rejection.actionIndex;
    const feedback: AgentEvent = {
      eventId: `${event.eventId}:plan-rejected`, sessionId: event.sessionId,
      sequence: event.sequence + 1, source: "system", type: "plan.rejected",
      occurredAt: (this.options.now ?? (() => new Date().toISOString()))(), payload,
    };
    session.pendingFeedback = structuredClone(feedback);
    return {
      effects: [], results: [], rejection,
      followUpEvents: [structuredClone(feedback)],
    };
  }

  private isFeedback(event: AgentEvent): boolean {
    return event.type === "tool.results" || event.type === "plan.rejected";
  }

  private isUserCrossingRequest(event: AgentEvent): boolean {
    if (event.source !== "user") return false;
    if (event.type === "user.crossing_query") return true;
    if (event.type !== "speech.input") return false;
    const transcript = event.payload.transcript;
    // Provisional fallback for legacy speech adapters; normalize to user.crossing_query upstream.
    return typeof transcript === "string" && /(过马路|过街|红绿灯|斑马线|路口.{0,8}(能过|可以过|过吗)|crosswalk|traffic light|safe to cross)/i.test(transcript);
  }

  private isRouteProgress(event: AgentEvent): boolean {
    return event.source === "navigation" && ["navigation.started", "navigation.approaching_maneuver",
      "navigation.off_route", "navigation.rerouting", "navigation.arrived", "navigation.stopped",
      "navigation.location_quality_changed"].includes(event.type);
  }

  private isRouteInvalidating(event: AgentEvent): boolean {
    return event.source === "navigation" && (isCrossingApproachEvent(event) ||
      ["navigation.started", "navigation.off_route", "navigation.rerouting",
      "navigation.arrived", "navigation.stopped", "navigation.location_quality_changed"].includes(event.type));
  }

  private finishUnconfirmedCrossing(
    event: AgentEvent, session: SessionRecord, rejection?: AgentHandleOutput["rejection"],
  ): AgentHandleOutput {
    session.view.lastSequence = event.sequence;
    session.crossingPending = false;
    session.pendingCrossingCallId = undefined;
    session.pendingCrossingEvidenceStale = undefined;
    session.pendingFeedback = undefined;
    session.crossingContext = undefined;
    session.view.navigation = undefined;
    return { effects: [this.unconfirmedCrossingEffect(event)],
      results: [], ...(rejection ? { rejection } : {}) };
  }

  private waitForCrossingConfirmation(
    event: AgentEvent, session: SessionRecord, rejection?: AgentHandleOutput["rejection"],
  ): AgentHandleOutput {
    session.view.lastSequence = event.sequence;
    session.pendingFeedback = undefined;
    session.pendingCrossingCallId = undefined;
    session.pendingCrossingEvidenceStale = undefined;
    session.crossingPending = true;
    const createdAt = (this.options.now ?? (() => new Date().toISOString()))();
    return {
      effects: [{ effectId: `${event.eventId}:crossing-confirmation`, sessionId: event.sessionId,
        type: "speech", createdAt,
        payload: { text: "前方路口，需要检查时请按键或说“检查”。请先停下。", action: "cannot_determine", priority: "critical" } }],
      results: [], ...(rejection ? { rejection } : {}),
    };
  }

  private unconfirmedCrossingEffect(event: AgentEvent): Effect {
    const createdAt = (this.options.now ?? (() => new Date().toISOString()))();
    const advisory = adviseCrossing({ now: createdAt });
    return { effectId: `${event.eventId}:crossing-unconfirmed`, sessionId: event.sessionId,
      type: "speech", createdAt, payload: { text: advisory.speech, action: advisory.action, priority: "critical" } };
  }

  private matchesPendingFeedback(event: AgentEvent, pending: AgentEvent): boolean {
    return this.isFeedback(event) && event.eventId === pending.eventId &&
      event.sessionId === pending.sessionId && event.sequence === pending.sequence &&
      event.type === pending.type && event.source === pending.source;
  }

  private getSession(sessionId: string): SessionRecord {
    let session = this.sessions.get(sessionId);
    if (!session) {
      session = { view: { sessionId, lastSequence: 0, activeSkills: [] }, epoch: 0 };
      this.sessions.set(sessionId, session);
    }
    return session;
  }
}
