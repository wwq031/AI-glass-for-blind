import assert from "node:assert/strict";
import test from "node:test";

import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";
import type { Effect } from "../../packages/domain/agent/types.ts";
import { PhoneAgentRuntime } from "../../apps/phone-companion/src/session/phone-agent-runtime.ts";
import { RecordedLlmAgent } from "../../packages/testkit/recorded-llm-agent.ts";
import { RecordedToolGateway } from "../../packages/testkit/recorded-tool-gateway.ts";

const now = "2026-09-22T10:00:00.000Z";

test("phone runtime sends the policy result effect after explicit ToolResult replay", async () => {
  const effects: Effect[] = [];
  const tools = new RecordedToolGateway([{
    callId: "crossing-plan:0",
    sessionId: "session-1",
    toolId: "observation.request",
    status: "succeeded",
    completedAt: now,
    output: {
      capability_id: "vision.traffic_signal",
      context: { intersection_id: "junction-1", travel_heading_deg: 90 },
    },
    facts: [
      {
        name: "traffic_signal.state",
        value: "red",
        confidence: "high",
        validUntil: "2026-09-22T10:00:15.000Z",
      },
      {
        name: "traffic_signal.direction_match",
        value: "yes",
        confidence: "high",
        validUntil: "2026-09-22T10:00:15.000Z",
      },
    ],
  }]);
  const runtime = new PhoneAgentRuntime({
    agent: new RecordedLlmAgent([
      {
        planId: "navigation-plan",
        sessionId: "session-1",
        eventId: "crossing-event",
        goal: "提醒用户确认是否观察路口",
        actions: [{
          kind: "tool_call",
          skillId: "crossing_advisory",
          toolId: "observation.request",
          arguments: { capability_id: "vision.traffic_signal" },
        }],
        createdAt: now,
      },
      {
        planId: "crossing-plan",
        sessionId: "session-1",
        eventId: "confirmation-event",
        goal: "检查前方路口",
        actions: [{
          kind: "tool_call",
          skillId: "crossing_advisory",
          toolId: "observation.request",
          arguments: { capability_id: "vision.traffic_signal" },
        }],
        createdAt: now,
      },
    ]),
    tools,
    skills: createP0SkillRegistry(),
    effectSink: { publish: (effect) => { effects.push(effect); } },
    now: () => now,
  });

  const reminder = await runtime.handle({
    eventId: "crossing-event",
    sessionId: "session-1",
    sequence: 1,
    source: "navigation",
    type: "navigation.intersection_approaching",
    occurredAt: now,
    payload: { intersection_id: "junction-1", distance_m: 12, travel_heading_deg: 90 },
  }, { observationConsent: "explicit" });

  assert.match(String(reminder.effects[0]?.payload.text), /按键|检查/);
  assert.deepEqual(reminder.followUpEvents, undefined);
  assert.deepEqual(tools.calls, []);
  assert.deepEqual(effects, reminder.effects);

  const requested = await runtime.handle({
    eventId: "confirmation-event",
    sessionId: "session-1",
    sequence: 2,
    source: "user",
    type: "user.crossing_query",
    occurredAt: now,
    payload: { input_kind: "button" },
  }, { observationConsent: "explicit" });

  assert.equal(requested.followUpEvents?.[0]?.type, "tool.results");
  assert.deepEqual(effects, reminder.effects);

  const advised = await runtime.handle(requested.followUpEvents![0]!);

  assert.equal(advised.effects[0]?.payload.action, "wait");
  assert.deepEqual(effects.slice(1), advised.effects);
});

test("crossing observation waits for a separate user confirmation and reuses the navigation context", async () => {
  const tools = new RecordedToolGateway([{
    callId: "confirmed-crossing-plan:0",
    sessionId: "session-2",
    toolId: "observation.request",
    status: "succeeded",
    completedAt: now,
    output: {
      capability_id: "vision.traffic_signal",
      context: { intersection_id: "junction-2", travel_heading_deg: 180 },
    },
    facts: [
      { name: "traffic_signal.state", value: "red", confidence: "high", validUntil: "2026-09-22T10:00:15.000Z" },
      { name: "traffic_signal.direction_match", value: "yes", confidence: "high", validUntil: "2026-09-22T10:00:15.000Z" },
    ],
  }]);
  const agent = new RecordedLlmAgent([
      {
        planId: "unconsented-crossing-plan",
        sessionId: "session-2",
        eventId: "approach-2",
        goal: "提醒用户确认是否观察路口",
        actions: [{
          kind: "tool_call",
          skillId: "crossing_advisory",
          toolId: "observation.request",
          arguments: { capability_id: "vision.traffic_signal" },
        }],
        createdAt: now,
      },
      {
        planId: "confirmed-crossing-plan",
        sessionId: "session-2",
        eventId: "user-check-2",
        goal: "按用户确认观察路口",
        actions: [{
          kind: "tool_call",
          skillId: "crossing_advisory",
          toolId: "observation.request",
          arguments: { capability_id: "vision.traffic_signal" },
        }],
        createdAt: now,
      },
    ]);
  const runtime = new PhoneAgentRuntime({
    agent,
    tools,
    skills: createP0SkillRegistry(),
    effectSink: { publish: () => {} },
    now: () => now,
  });

  const reminder = await runtime.handle({
    eventId: "approach-2",
    sessionId: "session-2",
    sequence: 1,
    source: "navigation",
    type: "navigation.intersection_approaching",
    occurredAt: now,
    payload: { intersection_id: "junction-2", distance_m: 9, travel_heading_deg: 180 },
  });

  assert.match(String(reminder.effects[0]?.payload.text), /按键|检查/);
  assert.deepEqual(tools.calls, []);

  const consented = await runtime.handle({
    eventId: "user-check-2",
    sessionId: "session-2",
    sequence: 2,
    source: "user",
    type: "user.crossing_query",
    occurredAt: now,
    payload: { input_kind: "button" },
  }, { observationConsent: "explicit" });

  assert.deepEqual(agent.inputs[1]?.session.navigation, {
    intersectionId: "junction-2",
    distanceM: 9,
    travelHeadingDeg: 180,
  });
  assert.deepEqual(tools.calls[0]?.arguments.context, {
    intersection_id: "junction-2",
    travel_heading_deg: 180,
  });
  assert.equal(consented.followUpEvents?.[0]?.type, "tool.results");

  const advice = await runtime.handle(consented.followUpEvents![0]!);
  assert.equal(advice.effects[0]?.payload.action, "wait");
});
