import type { SessionState } from "../session/session-orchestrator.ts";
import type { ToolCall, ToolGateway as ConcreteGateway, ToolResult as ConcreteResult } from "../tools/tool-gateway.ts";
import type { AgentPlan, ToolResult } from "./types.ts";
import toolManifest from "../../providers/registry/tool-registry.json" with { type: "json" };

export interface AgentToolCall {
  sessionId: string;
  plan: AgentPlan;
  actionIndex: number;
  toolId: string;
  arguments: Record<string, unknown>;
  origin: "agent" | "policy";
  consent: "none" | "explicit" | "preauthorized";
}

/** Logical Agent-core port; it does not choose a device, provider or transport. */
export interface ToolGateway {
  execute(input: AgentToolCall): Promise<ToolResult>;
}

type ConcreteExecutor = Pick<ConcreteGateway, "execute">;

export class ConcreteToolGatewayAdapter implements ToolGateway {
  private readonly versions = new Map(toolManifest.tools.map((tool) => [tool.tool_id, tool.version]));
  private readonly gateway: ConcreteExecutor;
  private readonly options: { state: (sessionId: string) => SessionState; now?: () => string };

  constructor(
    gateway: ConcreteExecutor,
    options: { state: (sessionId: string) => SessionState; now?: () => string },
  ) {
    this.gateway = gateway;
    this.options = options;
  }

  async execute(input: AgentToolCall): Promise<ToolResult> {
    const version = this.versions.get(input.toolId);
    if (!version) throw new Error(`Unregistered tool: ${input.toolId}`);
    const issuedAt = (this.options.now ?? (() => new Date().toISOString()))();
    const callId = `${input.plan.planId}:${input.actionIndex}`;
    const call: ToolCall = {
      schema_version: "1.0", call_id: callId, session_id: input.sessionId,
      tool_id: input.toolId, tool_version: version, origin: input.origin,
      issued_at: issuedAt, idempotency_key: callId,
      consent: input.consent === "none" ? "not_applicable" : input.consent,
      arguments: structuredClone(input.arguments),
    };
    const result: ConcreteResult = await this.gateway.execute(call, this.options.state(input.sessionId));
    return {
      callId: result.call_id, sessionId: result.session_id, toolId: result.tool_id,
      status: result.status, completedAt: result.completed_at, output: result.output,
      facts: result.facts.map((fact) => ({
        name: fact.name, value: fact.value, confidence: fact.confidence, validUntil: fact.valid_until,
      })),
    };
  }
}
