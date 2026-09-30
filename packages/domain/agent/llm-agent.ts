import type { AgentEvent, AgentPlan, SkillDefinition } from "./types.ts";

/**
 * One turn of the ongoing conversation as the model may read it back: what the user said, what the
 * turn aimed at, what was spoken to the user, and what a tool established. Only text is kept, so a
 * turn can never carry media, a capture reference or a credential.
 */
export interface AgentConversationTurn {
  kind: "user" | "goal" | "agent" | "fact";
  text: string;
}

export interface AgentSessionView {
  sessionId: string;
  lastSequence: number;
  goal?: string;
  activePlanId?: string;
  activeSkills: string[];
  urgentSkillId?: string;
  navigation?: { intersectionId?: string; distanceM?: number; travelHeadingDeg?: number };
  pendingQuestion?: string;
  /**
   * Bounded, sanitized trace of this session's conversation. It outlives a finished task, so the
   * next utterance is planned as a continuation of the same conversation, not as a cold start.
   * An explicit cancel or disconnect clears it.
   */
  conversation?: AgentConversationTurn[];
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
