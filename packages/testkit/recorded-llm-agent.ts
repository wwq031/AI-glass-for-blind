import type { AgentPlan } from "../domain/agent/types.ts";
import type { AgentTurnInput, LlmAgent } from "../domain/agent/llm-agent.ts";

export class RecordedLlmAgent implements LlmAgent {
  private readonly plans: AgentPlan[];
  private readonly recordedInputs: AgentTurnInput[] = [];

  constructor(plans: AgentPlan[]) {
    this.plans = structuredClone(plans);
  }

  get inputs(): AgentTurnInput[] {
    return structuredClone(this.recordedInputs);
  }

  async plan(input: AgentTurnInput): Promise<AgentPlan> {
    this.recordedInputs.push(structuredClone(input));
    const next = this.plans.shift();
    if (next === undefined) throw new Error("Recorded LLM has no remaining plan");
    return structuredClone(next);
  }
}
