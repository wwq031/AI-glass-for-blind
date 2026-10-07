import assert from "node:assert/strict";
import test from "node:test";

import { isCapabilityCompatibleWithSkill, isPlanToolExposureAllowed, isSkillAllowedForCapability, validatePlan, type ExecutionPermissions } from "../../packages/domain/agent/plan-validator.ts";
import { SkillRegistry, type AgentPlan, type PlanAction } from "../../packages/domain/agent/types.ts";
import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";
import capabilityManifest from "../../packages/contracts/capabilities/registry.json" with { type: "json" };

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
    capability_id: "vision.menu",
  })), { observationConsent: "none" }), { ok: false, code: "consent_required", actionIndex: 0 });
});

test("accepts a read_text observation proposal with explicit consent", () => {
  assert.deepEqual(validatePlan(registry, plan(call("read_text", "observation.request", {
    capability_id: "vision.menu",
  })), { observationConsent: "explicit" }), { ok: true });
});

test("accepts a read_text observation proposal with preauthorization", () => {
  assert.deepEqual(validatePlan(registry, plan(call("read_text", "observation.request", {
    capability_id: "vision.menu",
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

test("navigation.start stays forbidden if its future Tool exposure becomes model", () => {
  assert.equal(isPlanToolExposureAllowed("navigation.start", "model"), false);
  assert.equal(isPlanToolExposureAllowed("facts.query", "model"), true);
});

test("ordinary scene inspection cannot request the crossing-only traffic signal capability", () => {
  for (const skillId of ["inspect_scene", "find_target"]) {
    assert.deepEqual(validatePlan(registry, plan(call(skillId, "observation.request", {
      capability_id: "vision.traffic_signal",
    })), { observationConsent: "explicit" }),
    { ok: false, code: "policy_required", actionIndex: 0 });
  }
  assert.deepEqual(validatePlan(registry, plan(call("inspect_scene", "observation.request", {
    capability_id: "vision.scene",
  })), { observationConsent: "explicit" }), { ok: true });
});

test("generic observation requires a registered capability ID", () => {
  for (const skillId of ["inspect_scene", "find_target"]) {
    for (const args of [{}, { capability_id: "vision.unregistered" }, { capability_id: 7 }]) {
      assert.deepEqual(validatePlan(registry, plan(call(skillId, "observation.request", args)),
        { observationConsent: "explicit" }),
      { ok: false, code: "policy_required", actionIndex: 0 });
    }
  }
  assert.deepEqual(validatePlan(registry, plan(call("inspect_scene", "observation.request", {
    capability_id: "vision.entrance",
  })), { observationConsent: "explicit" }), { ok: true });
});

test("capability-declared Skill compatibility applies to every observation proposal", () => {
  for (const skillId of ["inspect_scene", "find_target"]) {
    assert.deepEqual(validatePlan(registry, plan(call(skillId, "observation.request", {
      capability_id: "vision.menu",
    })), { observationConsent: "explicit" }),
    { ok: false, code: "policy_required", actionIndex: 0 });
  }
  assert.deepEqual(validatePlan(registry, plan(call("read_text", "observation.request", {
    capability_id: "vision.menu",
  })), { observationConsent: "explicit" }), { ok: true });
});

test("traffic signal remains crossing-only when the registry omits crossing_advisory", () => {
  const ordinaryOnly = new SkillRegistry([{
    skillId: "inspect_scene", riskLevel: "low", allowedTools: ["observation.request"],
  }]);
  assert.deepEqual(validatePlan(ordinaryOnly, plan(call("inspect_scene", "observation.request", {
    capability_id: "vision.traffic_signal",
  })), { observationConsent: "explicit" }),
  { ok: false, code: "policy_required", actionIndex: 0 });
});

test("a custom inspect_scene with crossing policy still cannot request traffic signal", () => {
  const impersonatingRegistry = new SkillRegistry([{
    skillId: "inspect_scene", riskLevel: "high", allowedTools: ["observation.request"],
    requiredPolicy: "crossing-advisory",
  }]);
  assert.deepEqual(validatePlan(impersonatingRegistry, plan(call("inspect_scene", "observation.request", {
    capability_id: "vision.traffic_signal",
  })), { observationConsent: "explicit" }),
  { ok: false, code: "policy_required", actionIndex: 0 });
});

test("a Skill manifest cannot grant an unregistered tool", () => {
  const futureRegistry = new SkillRegistry([{
    skillId: "future", riskLevel: "low", allowedTools: ["future.private_tool"],
  }]);
  assert.deepEqual(validatePlan(futureRegistry, plan(call("future", "future.private_tool")),
    { observationConsent: "explicit" }), { ok: false, code: "tool_not_allowed", actionIndex: 0 });
});

test("read_text rejects an unregistered or missing observation capability", () => {
  for (const args of [{}, { capability_id: "vision.text_read" }, { capability_id: "vision.scene" }]) {
    assert.deepEqual(validatePlan(registry, plan(call("read_text", "observation.request", args)),
      { observationConsent: "explicit" }), { ok: false, code: "policy_required", actionIndex: 0 });
  }
});

test("registered text capability declares read_text compatibility", () => {
  const menu = capabilityManifest.capabilities.find((capability) => capability.id === "vision.menu");
  assert.deepEqual((menu as { compatible_skills?: string[] })?.compatible_skills, ["read_text"]);
});

test("a second registered text capability can be accepted without policy-name coupling", () => {
  const capabilities = [
    { id: "vision.menu", policy: "menu-summary", compatible_skills: ["read_text"] },
    { id: "vision.ocr", policy: "ocr-policy-v2", compatible_skills: ["read_text"] },
  ];
  assert.equal(isCapabilityCompatibleWithSkill(capabilities, "vision.ocr", "read_text"), true);
  assert.equal(isCapabilityCompatibleWithSkill(capabilities, "vision.ocr", "crossing_advisory"), false);
  assert.equal(isCapabilityCompatibleWithSkill(capabilities, "vision.missing", "read_text"), false);
});

test("an explicit empty compatible_skills list denies every Skill", () => {
  assert.equal(isSkillAllowedForCapability({ compatible_skills: [] }, "inspect_scene"), false);
  assert.equal(isSkillAllowedForCapability({ compatible_skills: [] }, "read_text"), false);
  assert.equal(isSkillAllowedForCapability({ compatible_skills: ["read_text"] }, "read_text"), true);
  assert.equal(isSkillAllowedForCapability({}, "inspect_scene"), true);
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

test("rejects model-origin fields on the Plan and action itself", () => {
  const baseCall = call("follow_up", "facts.query");
  assert.deepEqual(validatePlan(registry, {
    ...plan(baseCall), origin: "policy",
  } as AgentPlan, { observationConsent: "none" }),
  { ok: false, code: "tool_not_allowed", actionIndex: 0 });
  assert.deepEqual(validatePlan(registry, plan({
    ...baseCall, origin: "policy",
  } as PlanAction), { observationConsent: "none" }),
  { ok: false, code: "tool_not_allowed", actionIndex: 0 });
});

test("malformed root plans return invalid_plan without throwing", () => {
  for (const malformed of [null, {}, { ...basePlan, actions: null }, { ...basePlan, actions: "speak" }]) {
    assert.deepEqual(validatePlan(registry, malformed as AgentPlan, { observationConsent: "none" }),
      { ok: false, code: "invalid_plan", actionIndex: 0 });
  }
});

test("null and unknown action kinds fail closed at their index", () => {
  for (const malformed of [null, { kind: "device_raw_command", command: "camera.capture" }]) {
    assert.deepEqual(validatePlan(registry, {
      ...basePlan,
      actions: [{ kind: "speak", text: "ok", priority: "normal" }, malformed],
    } as AgentPlan, { observationConsent: "none" }),
    { ok: false, code: "invalid_plan", actionIndex: 1 });
  }
});

test("malformed fields in each supported action fail closed", () => {
  for (const malformed of [
    { kind: "tool_call", skillId: "read_text", toolId: "observation.request", arguments: null },
    { kind: "tool_call", skillId: "read_text", arguments: {} },
    { kind: "speak", text: "", priority: "normal" },
    { kind: "speak", text: "hello", priority: "urgent" },
    { kind: "wait", eventTypes: null },
    { kind: "wait", eventTypes: [] },
    { kind: "complete" },
  ]) {
    assert.deepEqual(validatePlan(registry, { ...basePlan, actions: [malformed] } as AgentPlan,
      { observationConsent: "explicit" }), { ok: false, code: "invalid_plan", actionIndex: 0 });
  }
});

test("observation consent must be exactly explicit or preauthorized", () => {
  const observation = plan(call("read_text", "observation.request", { capability_id: "vision.menu" }));
  for (const permissions of [
    {}, { observationConsent: "bogus" }, { observationConsent: null }, null,
  ]) {
    const result = validatePlan(registry, observation, permissions as ExecutionPermissions);
    assert.deepEqual(result, { ok: false, code: "consent_required", actionIndex: 0 });
  }
});

test("malformed plan identity and unexpected fields fail closed", () => {
  for (const fields of [
    { planId: "" }, { sessionId: null }, { eventId: 7 },
    { goal: "" }, { createdAt: "not-a-date" }, { createdAt: "2026-09-22" }, { responseDraft: 42 },
    { unexpected: true },
  ]) {
    assert.deepEqual(validatePlan(registry, { ...basePlan, ...fields } as AgentPlan,
      { observationConsent: "none" }), { ok: false, code: "invalid_plan", actionIndex: 0 });
  }
});

test("malformed tool and effect action fields fail closed", () => {
  for (const malformed of [
    { kind: "tool_call", skillId: "bad skill", toolId: "facts.query", arguments: {} },
    { kind: "tool_call", skillId: "follow_up", toolId: "not-a-tool-id", arguments: {} },
    { kind: "tool_call", skillId: "follow_up", toolId: "facts.query", arguments: [] },
    { kind: "tool_call", skillId: "follow_up", toolId: "facts.query", arguments: {}, extra: true },
    { kind: "speak", text: "hello", priority: "normal", extra: true },
    { kind: "wait", eventTypes: ["ready", "ready"] },
    { kind: "complete", reason: "done", extra: true },
  ]) {
    assert.deepEqual(validatePlan(registry, { ...basePlan, actions: [malformed] } as AgentPlan,
      { observationConsent: "none" }), { ok: false, code: "invalid_plan", actionIndex: 0 });
  }
});
