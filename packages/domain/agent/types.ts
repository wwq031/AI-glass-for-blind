export type EventSource = "user" | "system" | "navigation" | "motion" | "provider" | "device";
export type EventSourceDetail =
  | "glasses"
  | "phone"
  | "transport"
  | "speech"
  | "navigation"
  | "motion"
  | "vision"
  | "provider"
  | "agent"
  | "system"
  | "storage"
  | "simulator"
  | "user"
  | "device";

export interface AgentEvent {
  eventId: string;
  sessionId: string;
  sequence: number;
  source: EventSource;
  type: string;
  occurredAt: string;
  payload: Record<string, unknown>;
  traceId?: string;
  /** Original contract producer when source has been normalized to a semantic category. */
  sourceDetail?: EventSourceDetail;
}

export type PlanAction =
  | {
      kind: "tool_call";
      skillId: string;
      toolId: string;
      arguments: Record<string, unknown>;
    }
  | {
      kind: "speak";
      text: string;
      priority: "critical" | "high" | "normal" | "detail";
    }
  | {
      kind: "wait";
      eventTypes: string[];
    }
  | {
      kind: "complete";
      reason: string;
    };

export interface AgentPlan {
  planId: string;
  sessionId: string;
  eventId: string;
  goal: string;
  actions: PlanAction[];
  responseDraft?: string;
  createdAt: string;
}

export interface SkillDefinition {
  readonly skillId: string;
  readonly riskLevel: "low" | "medium" | "high";
  /** Overall skill dependencies; model-call permission also requires Tool exposure="model". */
  readonly allowedTools: readonly string[];
  readonly requiredPolicy?: string;
}

export class SkillRegistry {
  private readonly byId: ReadonlyMap<string, SkillDefinition>;

  constructor(skills: readonly SkillDefinition[]) {
    this.byId = new Map(
      skills.map((skill) => [
        skill.skillId,
        Object.freeze({ ...skill, allowedTools: Object.freeze([...skill.allowedTools]) }),
      ])
    );
  }

  get(skillId: string): SkillDefinition | undefined {
    return this.byId.get(skillId);
  }

  list(): SkillDefinition[] {
    return [...this.byId.values()];
  }
}

export interface ToolResult {
  callId: string;
  sessionId: string;
  toolId: string;
  status: "succeeded" | "partial" | "failed" | "denied" | "expired" | "cancelled";
  completedAt: string;
  output: Record<string, unknown>;
  facts: Array<{
    name: string;
    value: unknown;
    confidence: "high" | "medium" | "low" | "unknown";
    validUntil?: string;
  }>;
  events?: Array<{ type: string; payload?: Record<string, unknown> }>;
  error?: { code: string; message: string; retryable: boolean; [key: string]: unknown };
}

export interface Effect {
  effectId: string;
  sessionId: string;
  planId?: string;
  type: "speech" | "haptic" | "device_command" | "navigation" | "session";
  createdAt: string;
  payload: Record<string, unknown>;
}
