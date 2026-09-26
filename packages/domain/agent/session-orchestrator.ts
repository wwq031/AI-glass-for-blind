import { buildAgentTurnInput } from "./context-builder.ts";
import type { LlmAgent, AgentSessionView } from "./llm-agent.ts";
import { validatePlan, type ExecutionPermissions, type PlanValidation } from "./plan-validator.ts";
import { TaskRunner } from "./task-runner.ts";
import type { ToolGateway } from "./tool-gateway.ts";
import type { AgentEvent, Effect, SkillRegistry, ToolResult } from "./types.ts";

export interface AgentHandleOutput {
  effects: Effect[];
  results: ToolResult[];
  rejection?: {
    code: "stale_event" | Exclude<PlanValidation, { ok: true }>["code"];
    actionIndex?: number;
  };
}

interface SessionRecord {
  view: AgentSessionView;
  recentResults: ToolResult[];
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

  async handle(event: AgentEvent, permissions: ExecutionPermissions = { observationConsent: "none" }): Promise<AgentHandleOutput> {
    const session = this.getSession(event.sessionId);
    if (event.sequence <= session.view.lastSequence || this.processing.has(event.sessionId)) {
      return { effects: [], results: [], rejection: { code: "stale_event" } };
    }
    this.processing.add(event.sessionId);
    try {
      const input = buildAgentTurnInput({
        event, session: session.view, skills: this.options.skills.list(), recentResults: session.recentResults,
      });
      const plan = await this.options.agent.plan(input);
      const validation = validatePlan(this.options.skills, plan, permissions);
      if (!validation.ok) {
        return { effects: [], results: [], rejection: { code: validation.code, actionIndex: validation.actionIndex } };
      }
      if (plan.sessionId !== event.sessionId || plan.eventId !== event.eventId || !plan.planId) {
        return { effects: [], results: [], rejection: { code: "policy_required" } };
      }
      const output = await this.runner.run(plan, permissions);
      session.view.lastSequence = event.sequence;
      session.view.activePlanId = plan.planId;
      session.view.goal = plan.goal;
      session.recentResults = structuredClone(output.results);
      return output;
    } finally {
      this.processing.delete(event.sessionId);
    }
  }

  private getSession(sessionId: string): SessionRecord {
    let session = this.sessions.get(sessionId);
    if (!session) {
      session = { view: { sessionId, lastSequence: 0, activeSkills: [] }, recentResults: [] };
      this.sessions.set(sessionId, session);
    }
    return session;
  }
}
