import { buildAgentTurnInput } from "./context-builder.ts";
import type { LlmAgent, AgentSessionView } from "./llm-agent.ts";
import { validatePlan, type ExecutionPermissions, type PlanValidation } from "./plan-validator.ts";
import { TaskRunner } from "./task-runner.ts";
import type { ToolGateway } from "./tool-gateway.ts";
import type { AgentEvent, Effect, SkillRegistry, ToolResult } from "./types.ts";

export interface AgentHandleOutput {
  effects: Effect[];
  results: ToolResult[];
  /** Caller may explicitly submit these as the next Event; no automatic LLM loop. */
  followUpEvents?: AgentEvent[];
  rejection?: {
    code: "stale_event" | "pending_feedback" | "invalid_feedback" | "interrupted" | Exclude<PlanValidation, { ok: true }>["code"];
    actionIndex?: number;
  };
}

interface SessionRecord {
  view: AgentSessionView;
  pendingFeedback?: AgentEvent;
  epoch: number;
}

export class SessionOrchestrator {
  private readonly options: { agent: LlmAgent; tools: ToolGateway; skills: SkillRegistry; now?: () => string };
  private readonly sessions = new Map<string, SessionRecord>();
  private readonly processing = new Set<string>();
  private readonly runner: TaskRunner;

  constructor(options: {
    agent: LlmAgent;
    tools: ToolGateway;
    skills: SkillRegistry;
    now?: () => string;
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
    const pending = session.pendingFeedback;
    if (pending && !this.matchesPendingFeedback(event, pending)) {
      // The feedback owns the next sequence. Resubmit unrelated Events with a new sequence after feedback.
      return { effects: [], results: [], rejection: { code: "pending_feedback" } };
    }
    if (!pending && this.isFeedback(event)) {
      return { effects: [], results: [], rejection: { code: "invalid_feedback" } };
    }
    this.processing.add(event.sessionId);
    const epoch = session.epoch;
    try {
      const canonicalEvent = pending ? structuredClone(pending) : event;
      const input = buildAgentTurnInput({
        event: canonicalEvent, session: session.view, skills: this.options.skills.list(),
        recentResults: canonicalEvent.type === "tool.results" && Array.isArray(canonicalEvent.payload.results)
          ? canonicalEvent.payload.results : [],
      });
      const plan = await this.options.agent.plan(input);
      if (session.epoch !== epoch) return { effects: [], results: [], rejection: { code: "interrupted" } };
      const validation = validatePlan(this.options.skills, plan, permissions);
      if (!validation.ok) {
        return this.rejectPlan(canonicalEvent, session, { code: validation.code, actionIndex: validation.actionIndex });
      }
      if (plan.sessionId !== canonicalEvent.sessionId || plan.eventId !== canonicalEvent.eventId || !plan.planId) {
        return this.rejectPlan(canonicalEvent, session, { code: "policy_required" });
      }
      // Commit before tool execution: a thrown gateway may already have caused an external side effect.
      session.view.lastSequence = canonicalEvent.sequence;
      session.view.activePlanId = plan.planId;
      session.view.goal = plan.goal;
      const output = await this.runner.run(plan, permissions, () => session.epoch === epoch);
      if (session.epoch !== epoch) {
        return { effects: [], results: output.results, rejection: { code: "interrupted" } };
      }
      if (output.results.length === 0) {
        session.pendingFeedback = undefined;
        return output;
      }
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
