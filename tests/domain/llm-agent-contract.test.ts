import assert from "node:assert/strict";
import test from "node:test";

import { buildAgentTurnInput } from "../../packages/domain/agent/context-builder.ts";
import { RecordedLlmAgent } from "../../packages/testkit/recorded-llm-agent.ts";

const occurredAt = "2026-09-22T10:00:00.000Z";

test("recorded LLM returns a preloaded speak plan for button.short_press", async () => {
  const agent = new RecordedLlmAgent([{
    planId: "plan-1",
    sessionId: "s-1",
    eventId: "e-1",
    goal: "navigate",
    actions: [{ kind: "speak", text: "请说目的地。", priority: "normal" }],
    createdAt: occurredAt,
  }]);

  const plan = await agent.plan({
    event: {
      eventId: "e-1", sessionId: "s-1", sequence: 1,
      source: "user", type: "button.short_press", occurredAt, payload: {},
    },
    session: { sessionId: "s-1", lastSequence: 0, activeSkills: [] },
    skills: [],
  });

  assert.deepEqual(plan.actions, [{ kind: "speak", text: "请说目的地。", priority: "normal" }]);
  assert.equal(agent.inputs[0].event.type, "button.short_press");
});

test("context builder removes raw media and secrets but retains a summary", () => {
  const context = buildAgentTurnInput({
    event: {
      eventId: "e-2", sessionId: "s-1", sequence: 2,
      source: "provider", type: "observation.completed", occurredAt,
      payload: { mediaBytes: "raw", token: "secret", summary: "入口在右前方" },
    },
    session: { sessionId: "s-1", lastSequence: 1, activeSkills: [] },
    skills: [],
    recentResults: [],
  });

  assert.deepEqual(context.event.payload, { summary: "入口在右前方" });
});

test("context builder filters nested packets and credentials without changing its inputs", () => {
  const input = {
    event: {
      eventId: "e-3", sessionId: "s-1", sequence: 3,
      source: "device" as const, type: "observation.completed", occurredAt,
      payload: {
        observationId: "ob-1",
        detail: { summary: "门在右侧", bluetoothPacket: [1, 2], Secret: "hidden" },
      },
    },
    session: { sessionId: "s-1", lastSequence: 2, activeSkills: ["describe_surroundings"] },
    skills: [{ skillId: "describe_surroundings", riskLevel: "low" as const, allowedTools: ["facts.query"] }],
    recentResults: [{ callId: "call-1", facts: [{ name: "door", value: { side: "right", authorization: "hidden" } }], mediaBytes: [3, 4] }],
  };
  const original = structuredClone(input);

  const context = buildAgentTurnInput(input, "crossing_advisory");

  assert.deepEqual(context.event.payload, {
    observationId: "ob-1", detail: { summary: "门在右侧" },
  });
  assert.deepEqual(context.recentResults, [{
    callId: "call-1", facts: [{ name: "door", value: { side: "right" } }],
  }]);
  assert.equal(context.session.urgentSkillId, "crossing_advisory");
  assert.deepEqual(input, original);

  (context.event.payload.detail as { summary: string }).summary = "changed";
  context.session.activeSkills.push("other");
  assert.deepEqual(input, original);
});

test("context builder removes common media bytes and credentials inside nested arrays", () => {
  const input = {
    event: {
      eventId: "e-4", sessionId: "s-1", sequence: 4,
      source: "provider" as const, type: "observation.completed", occurredAt,
      payload: {
        observations: [{
          observationId: "ob-2", summary: "路口在前方",
          audioBytes: [1], imageBytes: [2], videoBytes: [3],
          accessToken: "private", apiKey: "private",
          details: [{ factId: "f-1", value: "green", clientSecret: "private", rawBluetoothPacket: [4] }],
        }],
      },
    },
    session: { sessionId: "s-1", lastSequence: 3, activeSkills: [] },
    skills: [],
    recentResults: [{ callId: "c-1", output: [{ summary: "已识别", audioBytes: [5], apiKey: "private" }] }],
  };
  const original = structuredClone(input);

  const context = buildAgentTurnInput(input);

  assert.deepEqual(context.event.payload, {
    observations: [{
      observationId: "ob-2", summary: "路口在前方",
      details: [{ factId: "f-1", value: "green" }],
    }],
  });
  assert.deepEqual(context.recentResults, [{ callId: "c-1", output: [{ summary: "已识别" }] }]);
  assert.deepEqual(input, original);
});

test("context builder omits cyclic branches without changing source objects", () => {
  const detail: Record<string, unknown> = { summary: "出口在左边" };
  detail.self = detail;
  const input = {
    event: {
      eventId: "e-5", sessionId: "s-1", sequence: 5,
      source: "provider" as const, type: "observation.completed", occurredAt,
      payload: { detail, observations: [detail] },
    },
    session: { sessionId: "s-1", lastSequence: 4, activeSkills: [] },
    skills: [],
    recentResults: [{ callId: "c-2", detail }],
  };

  const context = buildAgentTurnInput(input);

  assert.deepEqual(context.event.payload, {
    detail: { summary: "出口在左边" },
    observations: [{ summary: "出口在左边" }],
  });
  assert.deepEqual(context.recentResults, [{ callId: "c-2", detail: { summary: "出口在左边" } }]);
  assert.equal(detail.self, detail);
  assert.equal(input.event.payload.detail, detail);
});

test("recorded LLM consumes plans in order and isolates replay records", async () => {
  const plans = [
    { planId: "p-1", sessionId: "s-1", eventId: "e-1", goal: "ask", actions: [{ kind: "speak" as const, text: "first", priority: "normal" as const }], createdAt: occurredAt },
    { planId: "p-2", sessionId: "s-1", eventId: "e-2", goal: "ask", actions: [{ kind: "speak" as const, text: "second", priority: "normal" as const }], createdAt: occurredAt },
  ];
  const agent = new RecordedLlmAgent(plans);
  const input = buildAgentTurnInput({
    event: { eventId: "e-1", sessionId: "s-1", sequence: 1, source: "user", type: "button.short_press", occurredAt, payload: { summary: "hello" } },
    session: { sessionId: "s-1", lastSequence: 0, activeSkills: [] },
    skills: [],
  });

  plans[0].actions[0].text = "altered before replay";
  const first = await agent.plan(input);
  assert.equal(first.actions[0].kind, "speak");
  if (first.actions[0].kind === "speak") first.actions[0].text = "altered after replay";
  input.event.payload.summary = "altered input";
  const exposed = agent.inputs;
  exposed[0].event.payload.summary = "altered record";

  assert.equal(agent.inputs[0].event.payload.summary, "hello");
  assert.equal((await agent.plan(input)).planId, "p-2");
  await assert.rejects(agent.plan(input), { message: "Recorded LLM has no remaining plan" });
});
