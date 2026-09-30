import { PhoneAgentRuntime, type PhoneAgentRuntimeOptions } from "../session/phone-agent-runtime.ts";
import type { AgentHandleOutput } from "../../../../packages/domain/agent/session-orchestrator.ts";
import { decodeAgentEvent, encodeEffect, type EffectContract } from "../../../../packages/domain/agent/contract-codecs.ts";
import type { ExecutionPermissions } from "../../../../packages/domain/agent/plan-validator.ts";

export type MobileAgentHostOptions = Omit<PhoneAgentRuntimeOptions, "effectSink"> & {
  publishContractEffect(effect: EffectContract): Promise<void> | void;
};

export class MobileAgentHost {
  private readonly runtime: PhoneAgentRuntime;

  constructor(options: MobileAgentHostOptions) {
    this.runtime = new PhoneAgentRuntime({
      agent: options.agent,
      tools: options.tools,
      skills: options.skills,
      now: options.now,
      navigationTriggerMaxAgeMs: options.navigationTriggerMaxAgeMs,
      effectSink: { publish: (effect) => options.publishContractEffect(encodeEffect(effect)) },
    });
  }

  async accept(wireEvent: unknown, permissions?: ExecutionPermissions): Promise<AgentHandleOutput> {
    return this.runtime.handle(decodeAgentEvent(wireEvent), permissions);
  }
}
