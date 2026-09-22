import assert from "node:assert/strict";
import test from "node:test";

import {
  ToolGateway,
  type ToolCall,
  type ToolDefinition,
  type ToolHandler,
} from "../../packages/domain/tools/tool-gateway.ts";

const now = new Date("2026-09-22T00:00:00Z");
const definition: ToolDefinition = {
  tool_id: "observation.request",
  version: "1.0",
  exposure: "policy",
  operation: "request",
  risk: "medium",
  input_schema: "observation-tool-input.schema.json",
  output_schema: "tool-result.schema.json",
  requires_consent: true,
  timeout_ms: 25,
  retry_policy: "never",
  allowed_states: ["entrance_check"],
  emits: ["observation.result_received"],
};

function call(overrides: Partial<ToolCall> = {}): ToolCall {
  return {
    schema_version: "1.0",
    call_id: "call-1",
    session_id: "session-1",
    tool_id: "observation.request",
    tool_version: "1.0",
    origin: "policy",
    issued_at: now.toISOString(),
    expires_at: "2026-09-22T00:01:00Z",
    idempotency_key: "key-1",
    consent: "explicit",
    arguments: { capability_id: "vision.entrance" },
    ...overrides,
  };
}

function gateway(handler: ToolHandler, valid = true) {
  return new ToolGateway({
    definitions: [definition],
    handlers: new Map([[definition.tool_id, handler]]),
    validateArguments: () => ({ valid, errors: valid ? [] : ["capability_id is required"] }),
    now: () => now,
    idFactory: () => "error-1",
  });
}

test("executes an allowed, consented, schema-valid tool call", async () => {
  const result = await gateway(async () => ({
    output: { accepted: true },
    events: [{ type: "observation.result_received" }],
  })).execute(call(), "entrance_check");
  assert.equal(result.status, "succeeded");
  assert.equal(result.output.accepted, true);
});

test("reuses the same result for an idempotency key", async () => {
  let executions = 0;
  const toolGateway = gateway(async () => {
    executions++;
    return { output: { executions } };
  });
  const first = await toolGateway.execute(call(), "entrance_check");
  const second = await toolGateway.execute(call({ call_id: "call-2" }), "entrance_check");
  assert.equal(executions, 1);
  assert.equal(second, first);
  assert.equal(toolGateway.auditLog.at(-1)?.decision, "cached");
});

test("denies invalid state, missing consent, origin, and arguments", async () => {
  const handler: ToolHandler = async () => ({ output: {} });
  assert.equal((await gateway(handler).execute(call(), "navigating")).status, "denied");
  assert.equal((await gateway(handler).execute(call({ consent: undefined }), "entrance_check")).status, "denied");
  assert.equal((await gateway(handler).execute(call({ origin: "agent" }), "entrance_check")).status, "denied");
  assert.equal((await gateway(handler, false).execute(call(), "entrance_check")).status, "denied");
});

test("rejects expired calls before executing the handler", async () => {
  let executed = false;
  const result = await gateway(async () => {
    executed = true;
    return {};
  }).execute(call({ expires_at: "2026-09-21T23:59:59Z" }), "entrance_check");
  assert.equal(result.status, "expired");
  assert.equal(executed, false);
});

test("aborts and returns a structured error when a handler times out", async () => {
  const result = await gateway(
    (_call, context) => new Promise((resolve) => context.signal.addEventListener("abort", () => resolve({}))),
  ).execute(call(), "entrance_check");
  assert.equal(result.status, "failed");
  assert.equal(result.error?.code, "observation_timeout");
  assert.equal(result.error?.retryable, true);
});
