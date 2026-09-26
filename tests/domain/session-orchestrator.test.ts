import assert from "node:assert/strict";
import test from "node:test";
import { SessionOrchestrator } from "../../packages/domain/agent/session-orchestrator.ts";
import { TaskRunner } from "../../packages/domain/agent/task-runner.ts";
import { ConcreteToolGatewayAdapter } from "../../packages/domain/agent/tool-gateway.ts";
import { ToolGateway as ConcreteToolGateway } from "../../packages/domain/tools/tool-gateway.ts";
import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";
import { RecordedLlmAgent } from "../../packages/testkit/recorded-llm-agent.ts";
import { RecordedToolGateway } from "../../packages/testkit/recorded-tool-gateway.ts";
import type { AgentEvent, AgentPlan, ToolResult } from "../../packages/domain/agent/types.ts";

const now = () => "2026-09-22T10:00:00.000Z";
function event(eventId = "e-1", sequence = 1): AgentEvent {
  return { eventId, sequence, sessionId: "s-1", source: "user", type: "button.short_press", occurredAt: now(), payload: {} };
}
function plan(actions: AgentPlan["actions"], eventId = "e-1"): AgentPlan {
  return { planId: "p-1", sessionId: "s-1", eventId, goal: "help", actions, createdAt: now() };
}
function orchestrator(plans: AgentPlan[], tools = new RecordedToolGateway([])) {
  const agent = new RecordedLlmAgent(plans);
  return { agent, tools, core: new SessionOrchestrator({ agent, tools, skills: createP0SkillRegistry(), now }) };
}

test("button Event becomes a planned SpeechEffect and advances session", async () => {
  const { core } = orchestrator([plan([{ kind: "speak", text: "请说目的地。", priority: "normal" }])]);
  const output = await core.handle(event());
  assert.equal(output.effects[0]?.type, "speech");
  assert.equal(output.effects[0]?.payload.text, "请说目的地。");
  assert.deepEqual(output.results, []);
  assert.deepEqual(core.snapshot("s-1"), { sessionId: "s-1", lastSequence: 1, activePlanId: "p-1" });
});

test("stale Event is rejected before reaching the LLM", async () => {
  const { core, agent } = orchestrator([plan([])]);
  await core.handle(event());
  const output = await core.handle(event("e-dup", 1));
  assert.equal(output.rejection?.code, "stale_event");
  assert.equal(agent.inputs.length, 1);
});

test("a Plan for a different Event is rejected without executing effects or tools", async () => {
  const { core, tools } = orchestrator([plan([{ kind: "speak", text: "wrong", priority: "normal" }], "other")]);
  const output = await core.handle(event());
  assert.equal(output.rejection?.code, "policy_required");
  assert.deepEqual(output.effects, []);
  assert.deepEqual(tools.calls, []);
});

test("an invalid policy-only navigation.start Plan never reaches the gateway", async () => {
  const { core, tools } = orchestrator([plan([{ kind: "tool_call", skillId: "navigate_to", toolId: "navigation.start", arguments: {} }])]);
  const output = await core.handle(event());
  assert.equal(output.rejection?.code, "tool_not_allowed");
  assert.deepEqual(tools.calls, []);
});

test("rejected Plan creates a bounded repair Event for the next LLM turn", async () => {
  const invalid = plan([{
    kind: "tool_call", skillId: "missing", toolId: "facts.query", arguments: {},
  }]);
  const repaired = plan([{ kind: "speak", text: "请再说明目的地", priority: "normal" }], "e-1:plan-rejected");
  const { core, agent, tools } = orchestrator([invalid, repaired]);

  const rejected = await core.handle(event());
  assert.equal(rejected.rejection?.code, "unknown_skill");
  assert.deepEqual(rejected.effects, []);
  assert.deepEqual(rejected.results, []);
  assert.deepEqual(tools.calls, []);
  assert.equal(core.snapshot("s-1").lastSequence, 1);
  assert.equal(rejected.followUpEvents?.length, 1);
  const repairEvent = rejected.followUpEvents![0]!;
  assert.equal(repairEvent.source, "system");
  assert.equal(repairEvent.type, "plan.rejected");
  assert.equal(repairEvent.sequence, 2);
  assert.deepEqual(repairEvent.payload, { code: "unknown_skill", actionIndex: 0 });

  const stale = await core.handle(event());
  assert.equal(stale.rejection?.code, "stale_event");
  assert.deepEqual(stale.followUpEvents, undefined);
  assert.equal(agent.inputs.length, 1);

  const repairedOutput = await core.handle(repairEvent);
  assert.equal(repairedOutput.effects[0]?.payload.text, "请再说明目的地");
  assert.equal(agent.inputs[1]?.event.type, "plan.rejected");
});

test("malformed model Plan is a structured rejection without gateway side effects", async () => {
  const malformed = { ...plan([]), actions: null } as unknown as AgentPlan;
  const { core, tools } = orchestrator([malformed]);
  const output = await core.handle(event());
  assert.equal(output.rejection?.code, "invalid_plan");
  assert.deepEqual(output.effects, []);
  assert.deepEqual(tools.calls, []);
});

test("null model output is rejected before identity checks or gateway use", async () => {
  const { core, tools } = orchestrator([null as unknown as AgentPlan]);
  const output = await core.handle(event());
  assert.equal(output.rejection?.code, "invalid_plan");
  assert.deepEqual(tools.calls, []);
});

test("tool calls run sequentially; returns only recorded ToolResults", async () => {
  const results: ToolResult[] = ["one", "two"].map((callId) => ({
    callId, sessionId: "s-1", toolId: "facts.query", status: "succeeded", completedAt: now(), output: { callId }, facts: [],
  }));
  const tools = new RecordedToolGateway(results);
  const { core } = orchestrator([plan([
    { kind: "tool_call", skillId: "follow_up", toolId: "facts.query", arguments: { names: ["a"] } },
    { kind: "tool_call", skillId: "follow_up", toolId: "facts.query", arguments: { names: ["b"] } },
    { kind: "wait", eventTypes: ["user.reply"] },
    { kind: "complete", reason: "done" },
  ])], tools);
  const output = await core.handle(event());
  assert.deepEqual(output.results, results);
  assert.deepEqual(tools.calls.map((call) => call.origin), ["agent", "agent"]);
  assert.deepEqual(tools.calls.map((call) => call.arguments), [{ names: ["a"] }, { names: ["b"] }]);
  assert.deepEqual(output.effects, []);
  assert.deepEqual(output.followUpEvents?.map((event) => event.sequence), [2]);
  assert.deepEqual(output.followUpEvents?.[0]?.payload.results, results);
});

test("ToolResult becomes an explicit follow-up Event that the LLM can interpret", async () => {
  const result: ToolResult = {
    callId: "call-1", sessionId: "s-1", toolId: "facts.query", status: "succeeded", completedAt: now(),
    output: { answer: "入口在左侧" }, facts: [],
  };
  const { core, agent } = orchestrator([
    plan([
      { kind: "tool_call", skillId: "follow_up", toolId: "facts.query", arguments: {} },
      { kind: "speak", text: "未核实的预写回答", priority: "normal" },
    ]),
    plan([{ kind: "speak", text: "入口在左侧", priority: "normal" }], "p-1:results"),
  ], new RecordedToolGateway([result]));
  const first = await core.handle(event());
  assert.deepEqual(first.effects, []);
  assert.equal(first.followUpEvents?.length, 1);
  const followUp = first.followUpEvents![0]!;
  assert.equal(followUp.source, "provider");
  assert.equal(followUp.type, "tool.results");
  assert.deepEqual(followUp.payload.results, [result]);
  const second = await core.handle(followUp);
  assert.equal(second.effects[0]?.payload.text, "入口在左侧");
  assert.equal(agent.inputs[1]?.event.eventId, followUp.eventId);
});

test("failed ToolResult stops later tool calls and prevents pre-result speech or completion", async () => {
  const tools = new RecordedToolGateway([{
    callId: "failed", sessionId: "s-1", toolId: "facts.query", status: "failed", completedAt: now(),
    output: {}, facts: [], error: { code: "unknown", message: "provider failed", retryable: true },
  }]);
  const { core } = orchestrator([plan([
    { kind: "tool_call", skillId: "follow_up", toolId: "facts.query", arguments: {} },
    { kind: "speak", text: "已经查到", priority: "normal" },
    { kind: "complete", reason: "done" },
    { kind: "tool_call", skillId: "follow_up", toolId: "facts.query", arguments: {} },
  ])], tools);
  const output = await core.handle(event());
  assert.equal(output.results[0]?.status, "failed");
  assert.deepEqual(output.effects, []);
  assert.equal(output.followUpEvents?.length, 1);
  assert.equal(tools.calls.length, 1);
});

test("consented observation is executed as policy origin, not a model-set origin", async () => {
  const tools = new RecordedToolGateway([{
    callId: "obs", sessionId: "s-1", toolId: "observation.request", status: "partial", completedAt: now(), output: {}, facts: [],
  }]);
  const { core } = orchestrator([plan([{
    kind: "tool_call", skillId: "crossing_advisory", toolId: "observation.request", arguments: { capability_id: "vision.traffic_signal" },
  }])], tools);
  const output = await core.handle(event(), { observationConsent: "explicit" });
  assert.equal(output.results[0]?.status, "partial");
  assert.equal(tools.calls[0]?.origin, "policy");
  assert.equal(tools.calls[0]?.consent, "explicit");
});

test("TaskRunner propagates missing recorded ToolResult rather than inventing success", async () => {
  const runner = new TaskRunner({ tools: new RecordedToolGateway([]), now });
  await assert.rejects(() => runner.run(plan([{ kind: "tool_call", skillId: "follow_up", toolId: "facts.query", arguments: {} }]), { observationConsent: "none" }), /Recorded gateway has no remaining result/);
});

test("TaskRunner does not elevate an observation without consent", async () => {
  const tools = new RecordedToolGateway([]);
  const runner = new TaskRunner({ tools, now });
  await assert.rejects(() => runner.run(plan([{
    kind: "tool_call", skillId: "crossing_advisory", toolId: "observation.request",
    arguments: { capability_id: "vision.traffic_signal" },
  }]), { observationConsent: "none" }), /consent/i);
  assert.deepEqual(tools.calls, []);
});

test("TaskRunner rejects a malformed consent value on its public run path", async () => {
  const tools = new RecordedToolGateway([]);
  const runner = new TaskRunner({ tools, now });
  await assert.rejects(() => runner.run(plan([{
    kind: "tool_call", skillId: "crossing_advisory", toolId: "observation.request",
    arguments: { capability_id: "vision.traffic_signal" },
  }]), { observationConsent: "bogus" } as never), /consent/i);
  assert.deepEqual(tools.calls, []);
});

test("concrete adapter preserves origin and consent in ToolCall", async () => {
  let handledOrigin: string | undefined;
  let handledConsent: string | undefined;
  const concrete = new ConcreteToolGateway({
    definitions: [{
      tool_id: "observation.request", version: "1.0", exposure: "policy", operation: "request", risk: "medium",
      input_schema: "observation", output_schema: "result", requires_consent: true,
      timeout_ms: 1000, retry_policy: "never", allowed_states: ["intersection_check"], emits: [],
    }],
    handlers: new Map([["observation.request", async (call) => {
      handledOrigin = call.origin;
      handledConsent = call.consent;
      return {
        events: [{ type: "capture.requested", payload: { request_id: "r-1" } }],
        facts: [{ name: "traffic_signal.state", value: "unknown", confidence: "low" as const }],
      };
    }]]),
    validateArguments: () => ({ valid: true }),
    now: () => new Date(now()),
  });
  const adapter = new ConcreteToolGatewayAdapter(concrete, { state: () => "intersection_check", now });
  const input = { sessionId: "s-1", plan: plan([]), actionIndex: 0, toolId: "observation.request",
    arguments: { capability_id: "vision.traffic_signal" }, origin: "policy" as const, consent: "explicit" as const };
  const result = await adapter.execute(input);
  assert.equal(result.status, "succeeded");
  assert.equal(result.facts[0]?.name, "traffic_signal.state");
  assert.deepEqual(result.events, [{ type: "capture.requested", payload: { request_id: "r-1" } }]);
  assert.equal(handledOrigin, "policy");
  assert.equal(handledConsent, "explicit");
  assert.equal(concrete.auditLog[0]?.decision, "executed");

  const denied = await adapter.execute({ ...input, actionIndex: 1, origin: "agent" });
  assert.equal(denied.status, "denied");
  assert.equal(denied.error?.code, "permission_denied");
  assert.equal(denied.error?.retryable, false);
  assert.equal(concrete.auditLog[1]?.decision, "denied");
});
