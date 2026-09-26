import type { AgentEvent, AgentPlan, SkillDefinition } from "./types.ts";

export interface AgentSessionView {
  sessionId: string;
  lastSequence: number;
  goal?: string;
  activePlanId?: string;
  activeSkills: string[];
  urgentSkillId?: string;
  navigation?: { intersectionId?: string; distanceM?: number; travelHeadingDeg?: number };
  pendingQuestion?: string;
}

export interface AgentTurnInput {
  event: AgentEvent;
  session: AgentSessionView;
  skills: SkillDefinition[];
  recentResults?: unknown[];
}

export interface LlmAgent {
  plan(input: AgentTurnInput): Promise<AgentPlan>;
}
