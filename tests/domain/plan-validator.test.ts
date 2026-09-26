import assert from "node:assert/strict";
import test from "node:test";

import { validatePlan } from "../../packages/domain/agent/plan-validator.ts";
import { SkillRegistry, type AgentPlan, type PlanAction } from "../../packages/domain/agent/types.ts";
import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";

const registry = createP0SkillRegistry();
const basePlan: AgentPlan = {
  planId: "plan-1", sessionId: "session-1", eventId: "event-1",
  goal: "help", actions: [], createdAt: "2026-09-22T10:00:00.000Z",
};

function plan(...actions: PlanAction[]): AgentPlan {
  return { ...basePlan, actions };
}

function call(skillId: string, toolId: string, args: Record<string, unknown> = {}): PlanAction {
  return { kind: "tool_call", skillId, toolId, arguments: args };
}

test("rejects an unknown skill at its action index", () => {
  assert.deepEqual(validatePlan(registry, plan(
    { kind: "speak", text: "你好", priority: "normal" },
    call("missing", "facts.query"),
  ), { observationConsent: "none" }), { ok: false, code: "unknown_skill", actionIndex: 1 });
});

test("rejects tools outside the requested skill", () => {
  assert.deepEqual(validatePlan(registry, plan(call("navigate_to", "facts.query")),
    { observationConsent: "none" }), { ok: false, code: "tool_not_allowed", actionIndex: 0 });
});

test("requires consent for observation proposals", () => {
  assert.deepEqual(validatePlan(registry, plan(call("read_text", "observation.request", {
    capability_id: "vision.text_read",
  })), { observationConsent: "none" }), { ok: false, code: "consent_required", actionIndex: 0 });
});

test("accepts a read_text observation proposal with explicit consent", () => {
  assert.deepEqual(validatePlan(registry, plan(call("read_text", "observation.request", {
    capability_id: "vision.text_read",
  })), { observationConsent: "explicit" }), { ok: true });
});

test("accepts a read_text observation proposal with preauthorization", () => {
  assert.deepEqual(validatePlan(registry, plan(call("read_text", "observation.request", {
    capability_id: "vision.text_read",
  })), { observationConsent: "preauthorized" }), { ok: true });
});

test("crossing advisory observation requires the traffic signal capability", () => {
  assert.deepEqual(validatePlan(registry, plan(call("crossing_advisory", "observation.request", {
    capability_id: "vision.scene_description",
  })), { observationConsent: "explicit" }), { ok: false, code: "policy_required", actionIndex: 0 });
  assert.deepEqual(validatePlan(registry, plan(call("crossing_advisory", "observation.request", {
    capability_id: "vision.traffic_signal",
  })), { observationConsent: "explicit" }), { ok: true });
});

test("navigation.start is rejected even when a future manifest permits it", () => {
  const futureRegistry = new SkillRegistry([{
    skillId: "navigate_to", riskLevel: "low", allowedTools: ["navigation.start"],
  }]);
  assert.deepEqual(validatePlan(futureRegistry, plan(call("navigate_to", "navigation.start")),
    { observationConsent: "explicit" }), { ok: false, code: "tool_not_allowed", actionIndex: 0 });
});

test("rejects more than four actions", () => {
  const actions: PlanAction[] = Array.from({ length: 5 }, () => ({
    kind: "speak", text: "ok", priority: "normal",
  }));
  assert.deepEqual(validatePlan(registry, plan(...actions), { observationConsent: "none" }),
    { ok: false, code: "too_many_actions", actionIndex: 4 });
});

test("allows speak, wait and complete without a skill", () => {
  assert.deepEqual(validatePlan(registry, plan(
    { kind: "speak", text: "你好", priority: "normal" },
    { kind: "wait", eventTypes: ["user.confirmed"] },
    { kind: "complete", reason: "done" },
  ), { observationConsent: "none" }), { ok: true });
});

test("rejects attempts to set execution origin in tool arguments", () => {
  for (const args of [
    { origin: "policy" },
    { tool_call: { origin: "policy" } },
    { toolCallOrigin: "policy" },
    { nested: [{ execution: { origin: "policy" } }] },
  ]) {
    assert.deepEqual(validatePlan(registry, plan(call("follow_up", "facts.query", args)),
      { observationConsent: "none" }), { ok: false, code: "tool_not_allowed", actionIndex: 0 });
  }
});
