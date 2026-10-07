import type { ExecutionPermissions } from "./plan-validator.ts";
import type { ToolGateway } from "./tool-gateway.ts";
import type { AgentPlan, Effect, ToolResult } from "./types.ts";

export interface TaskRunOutput {
  effects: Effect[];
  results: ToolResult[];
}

/** Only accepts Plans already checked by PlanValidator. */
export class TaskRunner {
  private readonly options: { tools: ToolGateway; now?: () => string };

  constructor(options: { tools: ToolGateway; now?: () => string }) {
    this.options = options;
  }

  async run(
    plan: AgentPlan, permissions: ExecutionPermissions, shouldContinue: () => boolean = () => true,
    trustedCrossingContext?: { intersection_id: string; travel_heading_deg: number },
  ): Promise<TaskRunOutput> {
    const effects: Effect[] = [];
    const results: ToolResult[] = [];
    let awaitingResult = false;
    const createdAt = () => (this.options.now ?? (() => new Date().toISOString()))();
    for (const [actionIndex, action] of plan.actions.entries()) {
      if (!shouldContinue()) break;
      if (action.kind === "tool_call") {
        const observation = action.toolId === "observation.request";
        if (observation && permissions.observationConsent !== "explicit" && permissions.observationConsent !== "preauthorized") {
          throw new Error("Observation requires consent before policy execution");
        }
        let result: ToolResult;
        try {
          const args = structuredClone(action.arguments);
          if (observation) {
            args.capture_mode = "single_frame";
            args.consent = permissions.observationConsent;
            if (action.skillId === "crossing_advisory") {
              delete args.context;
              if (trustedCrossingContext) args.context = structuredClone(trustedCrossingContext);
            }
          }
          result = await this.options.tools.execute({
            sessionId: plan.sessionId, plan, actionIndex,
            toolId: action.toolId, arguments: args,
            origin: observation ? "policy" : "agent",
            consent: observation ? permissions.observationConsent : "none",
          });
        } catch {
          // The provider may already have performed a side effect; never report success or retry here.
          result = {
            callId: `${plan.planId}:${actionIndex}`, sessionId: plan.sessionId, toolId: action.toolId,
            status: "failed", completedAt: createdAt(), output: {}, facts: [],
            error: { code: "execution_uncertain", message: "Tool outcome is uncertain; do not retry automatically.", retryable: false },
          };
        }
        results.push(result);
        awaitingResult = true;
        if (result.status !== "succeeded" && result.status !== "partial") break;
      } else if (action.kind === "speak") {
        if (awaitingResult) continue;
        effects.push({ effectId: `${plan.planId}:${actionIndex}`, sessionId: plan.sessionId,
          type: "speech", createdAt: createdAt(), payload: { text: action.text, priority: action.priority } });
      } else if (action.kind === "complete") {
        if (awaitingResult) continue;
        effects.push({ effectId: `${plan.planId}:${actionIndex}`, sessionId: plan.sessionId,
          type: "session", createdAt: createdAt(), payload: { status: "completed", reason: action.reason } });
      }
    }
    return { effects, results };
  }
}
