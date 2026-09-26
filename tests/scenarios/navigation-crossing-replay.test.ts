import assert from "node:assert/strict";
import test from "node:test";
import { SessionOrchestrator } from "../../packages/domain/agent/session-orchestrator.ts";
import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";
import { RecordedLlmAgent } from "../../packages/testkit/recorded-llm-agent.ts";
import { RecordedToolGateway } from "../../packages/testkit/recorded-tool-gateway.ts";
import type { AgentEvent, AgentPlan, ToolResult } from "../../packages/domain/agent/types.ts";

const at = "2026-09-22T10:00:10.000Z";
const now = () => at;
const nav = (type: string, sequence: number): AgentEvent => ({
  eventId: `nav-${sequence}`, sessionId: "walk-1", sequence, source: "navigation", type, occurredAt: at,
  payload: type === "navigation.intersection_approaching" ?
    { intersection_id: "junction-7", distance_m: 18, travel_heading_deg: 90 } : { route_state: "active" },
});
const plan = (eventId: string, actions: AgentPlan["actions"]): AgentPlan => ({
  planId: `p-${eventId}`, sessionId: "walk-1", eventId, goal: "路口辅助", actions, createdAt: at,
});

test("navigation event triggers planned consented observation, then policy owns uncertain crossing speech", async () => {
  const result: ToolResult = {
    callId: "p-nav-2:0", sessionId: "walk-1", toolId: "observation.request", status: "partial", completedAt: at,
    output: { capability_id: "vision.traffic_signal" },
    facts: [
      { name: "traffic_signal.state", value: "unknown", confidence: "medium", validUntil: "2026-09-22T10:00:15.000Z" },
      { name: "traffic_signal.direction_match", value: "unknown", confidence: "low", validUntil: "2026-09-22T10:00:15.000Z" },
      { name: "vehicle.activity", value: "unknown", confidence: "low", validUntil: "2026-09-22T10:00:15.000Z" },
    ],
  };
  const agent = new RecordedLlmAgent([
    plan("nav-1", [{ kind: "speak", text: "导航已开始。", priority: "normal" }]),
    plan("nav-2", [{ kind: "tool_call", skillId: "crossing_advisory", toolId: "observation.request",
      arguments: { capability_id: "vision.traffic_signal" } }]),
  ]);
  const tools = new RecordedToolGateway([result]);
  const core = new SessionOrchestrator({ agent, tools, skills: createP0SkillRegistry(), now });

  const started = await core.handle(nav("navigation.started", 1));
  assert.equal(started.effects[0]?.payload.text, "导航已开始。");
  assert.equal(tools.calls.length, 0);
  assert.equal(agent.inputs[0]?.session.urgentSkillId, undefined);

  const approaching = await core.handle(nav("navigation.intersection_approaching", 2), { observationConsent: "explicit" });
  assert.equal(agent.inputs[1]?.session.urgentSkillId, "crossing_advisory");
  assert.deepEqual(agent.inputs[1]?.session.navigation, { intersectionId: "junction-7", distanceM: 18, travelHeadingDeg: 90 });
  assert.equal(tools.calls.length, 1);
  assert.equal(tools.calls[0]?.origin, "policy");
  assert.equal(tools.calls[0]?.toolId, "observation.request");
  assert.deepEqual(approaching.effects, []);
  assert.equal(approaching.results[0]?.facts[0]?.value, "unknown");

  const feedback = approaching.followUpEvents?.[0];
  assert.equal(feedback?.type, "tool.results");
  const advice = await core.handle(feedback!);
  assert.equal(advice.effects.length, 1);
  assert.equal(advice.effects[0]?.type, "speech");
  assert.equal(advice.effects[0]?.payload.action, "cannot_determine");
  assert.match(String(advice.effects[0]?.payload.text), /请先停下/);
  assert.equal(agent.inputs.length, 2, "model must not override policy on crossing result turn");
});

test("failed crossing observation produces conservative speech without model improvisation", async () => {
  const agent = new RecordedLlmAgent([plan("nav-2", [{ kind: "tool_call", skillId: "crossing_advisory", toolId: "observation.request", arguments: { capability_id: "vision.traffic_signal" } }])]);
  const tools = new RecordedToolGateway([{ callId: "p-nav-2:0", sessionId: "walk-1", toolId: "observation.request", status: "failed", completedAt: at, output: {}, facts: [] }]);
  const core = new SessionOrchestrator({ agent, tools, skills: createP0SkillRegistry(), now });
  const first = await core.handle(nav("navigation.intersection_approaching", 2), { observationConsent: "explicit" });
  const advice = await core.handle(first.followUpEvents![0]!);
  assert.equal(advice.effects[0]?.payload.action, "cannot_determine");
  assert.match(String(advice.effects[0]?.payload.text), /请先停下/);
  assert.equal(agent.inputs.length, 1);
});

test("crossing context rejects model speech and completion before observation", async () => {
  const agent = new RecordedLlmAgent([plan("nav-2", [
    { kind: "speak", text: "绿灯，可以过。", priority: "critical" },
    { kind: "complete", reason: "已过街" },
  ])]);
  const tools = new RecordedToolGateway([]);
  const core = new SessionOrchestrator({ agent, tools, skills: createP0SkillRegistry(), now });
  const out = await core.handle(nav("navigation.crosswalk_approaching", 2));
  assert.equal(out.rejection?.code, "policy_required");
  assert.equal(out.effects.length, 1);
  assert.notEqual(out.effects[0]?.payload.text, "绿灯，可以过。");
  assert.match(String(out.effects[0]?.payload.text), /请先停下/);
  assert.deepEqual(tools.calls, []);
});

test("crossing context rejects pre-result model speech even when observation is also planned", async () => {
  const agent = new RecordedLlmAgent([plan("nav-2", [
    { kind: "speak", text: "现在可以过马路。", priority: "critical" },
    { kind: "tool_call", skillId: "crossing_advisory", toolId: "observation.request", arguments: { capability_id: "vision.traffic_signal" } },
  ])]);
  const tools = new RecordedToolGateway([]);
  const core = new SessionOrchestrator({ agent, tools, skills: createP0SkillRegistry(), now });
  const out = await core.handle(nav("navigation.intersection_approaching", 2), { observationConsent: "explicit" });
  assert.equal(out.rejection?.code, "policy_required");
  assert.equal(out.effects.length, 1);
  assert.notEqual(out.effects[0]?.payload.text, "现在可以过马路。");
  assert.deepEqual(tools.calls, []);
});

test("user crossing question cannot get model speech before policy even without navigation", async () => {
  const user: AgentEvent = { eventId: "ask-1", sessionId: "walk-1", sequence: 1, source: "user", type: "speech.input",
    occurredAt: at, payload: { transcript: "前面路口能过马路吗？", intent_hint: "query" } };
  const agent = new RecordedLlmAgent([plan("ask-1", [{ kind: "speak", text: "可以过。", priority: "high" }])]);
  const core = new SessionOrchestrator({ agent, tools: new RecordedToolGateway([]), skills: createP0SkillRegistry(), now });
  const out = await core.handle(user);
  assert.equal(out.rejection?.code, "policy_required");
  assert.match(String(out.effects[0]?.payload.text), /请先停下/);
  assert.notEqual(out.effects[0]?.payload.text, "可以过。");
});

test("normalized user.crossing_query activates the guard without transcript matching", async () => {
  const user: AgentEvent = { eventId: "ask-1", sessionId: "walk-1", sequence: 1, source: "user", type: "user.crossing_query",
    occurredAt: at, payload: { transcript: "请帮我判断" } };
  const agent = new RecordedLlmAgent([plan("ask-1", [{ kind: "speak", text: "可以过。", priority: "high" }])]);
  const core = new SessionOrchestrator({ agent, tools: new RecordedToolGateway([]), skills: createP0SkillRegistry(), now });
  const out = await core.handle(user);
  assert.equal(out.rejection?.code, "policy_required");
  assert.match(String(out.effects[0]?.payload.text), /请先停下/);
});

test("explicitly declined capture closes crossing with conservative policy speech and permits later arrival", async () => {
  const agent = new RecordedLlmAgent([
    plan("nav-2", [{ kind: "tool_call", skillId: "crossing_advisory", toolId: "observation.request", arguments: { capability_id: "vision.traffic_signal" } }]),
    plan("nav-3", [{ kind: "speak", text: "已经抵达目的地。", priority: "normal" }]),
  ]);
  const tools = new RecordedToolGateway([]);
  const core = new SessionOrchestrator({ agent, tools, skills: createP0SkillRegistry(), now });
  const declined = await core.handle(nav("navigation.intersection_approaching", 2), { observationConsent: "none" });
  assert.equal(declined.rejection?.code, "consent_required");
  assert.equal(declined.effects[0]?.payload.action, "cannot_determine");
  assert.match(String(declined.effects[0]?.payload.text), /请先停下/);
  assert.deepEqual(tools.calls, []);
  const arrived = await core.handle(nav("navigation.arrived", 3));
  assert.equal(arrived.effects[0]?.payload.text, "已经抵达目的地。");
});

test("wait-only crossing plan closes context and route progress retires rejected crossing feedback", async () => {
  const agent = new RecordedLlmAgent([
    plan("nav-2", [{ kind: "wait", eventTypes: ["navigation.arrived"] }]),
    plan("nav-3", [{ kind: "speak", text: "路线继续。", priority: "normal" }]),
  ]);
  const core = new SessionOrchestrator({ agent, tools: new RecordedToolGateway([]), skills: createP0SkillRegistry(), now });
  const waiting = await core.handle(nav("navigation.intersection_approaching", 2));
  assert.equal(waiting.effects[0]?.payload.action, "cannot_determine");
  const progressed = await core.handle(nav("navigation.approaching_maneuver", 3));
  assert.equal(progressed.effects[0]?.payload.text, "路线继续。");
});

test("authoritative route progress retires a pending crossing plan rejection", async () => {
  const agent = new RecordedLlmAgent([
    plan("nav-2", [{ kind: "speak", text: "可以过。", priority: "high" }]),
    plan("nav-3", [{ kind: "speak", text: "已经抵达。", priority: "normal" }]),
  ]);
  const core = new SessionOrchestrator({ agent, tools: new RecordedToolGateway([]), skills: createP0SkillRegistry(), now });
  const rejected = await core.handle(nav("navigation.intersection_approaching", 2));
  assert.equal(rejected.followUpEvents?.[0]?.type, "plan.rejected");
  const arrival = await core.handle(nav("navigation.arrived", 3));
  assert.equal(arrival.effects[0]?.payload.text, "已经抵达。");
  assert.equal(agent.inputs.length, 2);
});

test("crossing Skill request guards speech even without navigation or crossing words", async () => {
  const user: AgentEvent = { eventId: "ask-1", sessionId: "walk-1", sequence: 1, source: "user", type: "speech.input",
    occurredAt: at, payload: { transcript: "帮我看一下", intent_hint: "query" } };
  const agent = new RecordedLlmAgent([plan("ask-1", [
    { kind: "tool_call", skillId: "crossing_advisory", toolId: "observation.request", arguments: { capability_id: "vision.traffic_signal" } },
    { kind: "speak", text: "现在可以过。", priority: "high" },
  ])]);
  const tools = new RecordedToolGateway([]);
  const core = new SessionOrchestrator({ agent, tools, skills: createP0SkillRegistry(), now });
  const out = await core.handle(user, { observationConsent: "explicit" });
  assert.equal(out.rejection?.code, "policy_required");
  assert.match(String(out.effects[0]?.payload.text), /请先停下/);
  assert.deepEqual(tools.calls, []);
});
