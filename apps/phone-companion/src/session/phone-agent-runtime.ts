import {
  SessionOrchestrator,
  type AgentHandleOutput,
} from "../../../../packages/domain/agent/session-orchestrator.ts";
import type { LlmAgent } from "../../../../packages/domain/agent/llm-agent.ts";
import type { ExecutionPermissions } from "../../../../packages/domain/agent/plan-validator.ts";
import type { ToolGateway } from "../../../../packages/domain/agent/tool-gateway.ts";
import type { AgentEvent, Effect, SkillRegistry } from "../../../../packages/domain/agent/types.ts";

export interface AgentEffectSink {
  publish(effect: Effect): Promise<void> | void;
}

export interface PhoneAgentRuntimeOptions {
  agent: LlmAgent;
  tools: ToolGateway;
  skills: SkillRegistry;
  effectSink: AgentEffectSink;
  now?: () => string;
  navigationTriggerMaxAgeMs?: number;
}

/** Phone-side composition boundary for the device-independent Agent core. */
export class PhoneAgentRuntime {
  private readonly core: SessionOrchestrator;
  private readonly effectSink: AgentEffectSink;

  constructor(options: PhoneAgentRuntimeOptions) {
    this.core = new SessionOrchestrator({
      agent: options.agent,
      tools: options.tools,
      skills: options.skills,
      now: options.now,
      navigationTriggerMaxAgeMs: options.navigationTriggerMaxAgeMs,
    });
    this.effectSink = options.effectSink;
  }

  async handle(
    event: AgentEvent,
    permissions?: ExecutionPermissions,
  ): Promise<AgentHandleOutput> {
    const output = await this.core.handle(event, permissions);
    for (const effect of output.effects) {
      await this.effectSink.publish(effect);
    }
    return output;
  }
}
