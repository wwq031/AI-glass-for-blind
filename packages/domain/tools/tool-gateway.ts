import type { Fact } from "../../providers/observation/observation-provider.ts";
import type { SessionState } from "../session/session-orchestrator.ts";

export interface ToolDefinition {
  tool_id: string;
  version: string;
  exposure: "model" | "policy" | "internal";
  operation: "read" | "request" | "effect";
  risk: "none" | "low" | "medium" | "high" | "critical";
  input_schema: string;
  output_schema: string;
  requires_consent: boolean;
  timeout_ms: number;
  retry_policy: "never" | "idempotent_only" | "policy_controlled";
  allowed_states?: string[];
  emits: string[];
}

export interface ToolCall {
  schema_version: string;
  call_id: string;
  session_id: string;
  tool_id: string;
  tool_version: string;
  origin: "agent" | "policy" | "system";
  issued_at: string;
  expires_at?: string;
  idempotency_key: string;
  consent?: "explicit" | "preauthorized" | "not_applicable";
  arguments: Record<string, unknown>;
}

export interface ContractError {
  schema_version: string;
  error_id: string;
  session_id: string;
  occurred_at: string;
  source: "system";
  code: "permission_denied" | "unsupported" | "observation_timeout" | "unknown";
  severity: "warning" | "critical";
  retryable: boolean;
  user_action: "none" | "retry" | "wait" | "cancel";
  message: string;
}

export interface ToolResult {
  schema_version: string;
  call_id: string;
  session_id: string;
  tool_id: string;
  status: "succeeded" | "partial" | "failed" | "denied" | "expired" | "cancelled";
  completed_at: string;
  output: Record<string, unknown>;
  events: Array<{ type: string; payload?: Record<string, unknown> }>;
  facts: Fact[];
  error?: ContractError;
}

export interface ToolHandlerOutput {
  status?: "succeeded" | "partial";
  output?: Record<string, unknown>;
  events?: ToolResult["events"];
  facts?: Fact[];
}

export interface ToolExecutionContext {
  signal: AbortSignal;
  state: SessionState;
  definition: ToolDefinition;
}

export type ToolHandler = (
  call: ToolCall,
  context: ToolExecutionContext,
) => Promise<ToolHandlerOutput>;

export interface ArgumentValidationResult {
  valid: boolean;
  errors?: string[];
}

export type ArgumentValidator = (
  schemaPath: string,
  argumentsValue: Record<string, unknown>,
) => ArgumentValidationResult;

export interface ToolAuditRecord {
  call_id: string;
  tool_id: string;
  state: SessionState;
  decision: "executed" | "cached" | "denied" | "expired" | "failed";
  reason?: string;
  completed_at: string;
}

export interface ToolGatewayOptions {
  definitions: ToolDefinition[];
  handlers: ReadonlyMap<string, ToolHandler>;
  validateArguments: ArgumentValidator;
  now?: () => Date;
  idFactory?: () => string;
}

/** Enforces the registry contract before any provider or device side effect. */
export class ToolGateway {
  readonly auditLog: ToolAuditRecord[] = [];
  private readonly definitions: Map<string, ToolDefinition>;
  private readonly handlers: ReadonlyMap<string, ToolHandler>;
  private readonly validateArguments: ArgumentValidator;
  private readonly now: () => Date;
  private readonly idFactory: () => string;
  private readonly idempotency = new Map<string, Promise<ToolResult>>();

  constructor(options: ToolGatewayOptions) {
    this.definitions = new Map(options.definitions.map((definition) => [definition.tool_id, definition]));
    this.handlers = options.handlers;
    this.validateArguments = options.validateArguments;
    this.now = options.now ?? (() => new Date());
    this.idFactory = options.idFactory ?? (() => crypto.randomUUID());
  }

  execute(call: ToolCall, state: SessionState): Promise<ToolResult> {
    const key = `${call.session_id}:${call.tool_id}:${call.idempotency_key}`;
    const cached = this.idempotency.get(key);
    if (cached) {
      this.audit(call, state, "cached", "idempotency_key_reused");
      return cached;
    }

    const execution = this.executeOnce(call, state);
    this.idempotency.set(key, execution);
    return execution;
  }

  private async executeOnce(call: ToolCall, state: SessionState): Promise<ToolResult> {
    const definition = this.definitions.get(call.tool_id);
    if (!definition || !this.handlers.has(call.tool_id)) {
      return this.reject(call, state, "denied", "unsupported", "Tool is not registered or has no handler.");
    }
    if (definition.version !== call.tool_version) {
      return this.reject(call, state, "denied", "unsupported", "Tool version does not match the registry.");
    }
    if (!this.originCanUse(call.origin, definition.exposure)) {
      return this.reject(call, state, "denied", "permission_denied", "Call origin cannot use this tool.");
    }
    if (definition.allowed_states?.length && !definition.allowed_states.includes(state)) {
      return this.reject(call, state, "denied", "permission_denied", `Tool is not allowed in state ${state}.`);
    }
    if (definition.requires_consent && !["explicit", "preauthorized"].includes(call.consent ?? "not_applicable")) {
      return this.reject(call, state, "denied", "permission_denied", "Tool requires user consent.");
    }
    if (call.expires_at && Date.parse(call.expires_at) <= this.now().getTime()) {
      return this.reject(call, state, "expired", "permission_denied", "Tool call has expired.");
    }
    const validation = this.validateArguments(definition.input_schema, call.arguments);
    if (!validation.valid) {
      return this.reject(
        call,
        state,
        "denied",
        "unsupported",
        `Tool arguments failed schema validation: ${(validation.errors ?? []).join("; ")}`,
      );
    }

    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const handler = this.handlers.get(call.tool_id)!;
      const timeout = new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          reject(new ToolTimeoutError());
          controller.abort();
        }, definition.timeout_ms);
      });
      const handled = await Promise.race([handler(call, { signal: controller.signal, state, definition }), timeout]);
      const result: ToolResult = {
        schema_version: "1.0",
        call_id: call.call_id,
        session_id: call.session_id,
        tool_id: call.tool_id,
        status: handled.status ?? "succeeded",
        completed_at: this.now().toISOString(),
        output: handled.output ?? {},
        events: handled.events ?? [],
        facts: handled.facts ?? [],
      };
      this.audit(call, state, "executed");
      return result;
    } catch (error) {
      const timedOut = error instanceof ToolTimeoutError;
      return this.reject(
        call,
        state,
        "failed",
        timedOut ? "observation_timeout" : "unknown",
        timedOut ? "Tool execution timed out." : "Tool handler failed.",
        timedOut,
      );
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  private originCanUse(origin: ToolCall["origin"], exposure: ToolDefinition["exposure"]): boolean {
    if (origin === "system") return true;
    if (origin === "policy") return exposure !== "internal";
    return exposure === "model";
  }

  private reject(
    call: ToolCall,
    state: SessionState,
    status: "denied" | "expired" | "failed",
    code: ContractError["code"],
    message: string,
    retryable = false,
  ): ToolResult {
    const completed_at = this.now().toISOString();
    const decision = status === "expired" ? "expired" : status === "failed" ? "failed" : "denied";
    this.audit(call, state, decision, message);
    return {
      schema_version: "1.0",
      call_id: call.call_id,
      session_id: call.session_id,
      tool_id: call.tool_id,
      status,
      completed_at,
      output: {},
      events: [],
      facts: [],
      error: {
        schema_version: "1.0",
        error_id: this.idFactory(),
        session_id: call.session_id,
        occurred_at: completed_at,
        source: "system",
        code,
        severity: status === "failed" ? "critical" : "warning",
        retryable,
        user_action: retryable ? "retry" : "none",
        message,
      },
    };
  }

  private audit(
    call: ToolCall,
    state: SessionState,
    decision: ToolAuditRecord["decision"],
    reason?: string,
  ): void {
    this.auditLog.push({
      call_id: call.call_id,
      tool_id: call.tool_id,
      state,
      decision,
      reason,
      completed_at: this.now().toISOString(),
    });
  }
}

class ToolTimeoutError extends Error {}
