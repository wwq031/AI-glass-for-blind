export type EventSource = "user" | "system" | "navigation" | "motion" | "provider" | "device";

export interface AgentEvent {
  eventId: string;
  sessionId: string;
  sequence: number;
  source: EventSource;
  type: string;
  occurredAt: string;
  payload: Record<string, unknown>;
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
  skillId: string;
  riskLevel: "low" | "medium" | "high";
  allowedTools: string[];
  requiredPolicy?: string;
}

export class SkillRegistry {
  private readonly skills: SkillDefinition[];

  constructor(skills: SkillDefinition[]) {
    this.skills = [...skills];
  }

  get(skillId: string): SkillDefinition | undefined {
    return this.skills.find((skill) => skill.skillId === skillId);
  }

  list(): SkillDefinition[] {
    return [...this.skills];
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
}

export interface Effect {
  effectId: string;
  sessionId: string;
  type: "speech" | "haptic" | "device_command" | "navigation" | "session";
  createdAt: string;
  payload: Record<string, unknown>;
}
