import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";
import { SkillRegistry, type SkillDefinition } from "../../packages/domain/agent/types.ts";

test("registers navigate_to as a low-risk skill", () => {
  const registry = createP0SkillRegistry();

  assert.equal(registry.get("navigate_to")?.riskLevel, "low");
});

test("navigate_to excludes policy-owned navigation.start", () => {
  assert.equal(createP0SkillRegistry().get("navigate_to")?.allowedTools.includes("navigation.start"), false);
});

test("skill registry copies inputs and freezes returned authorization data", () => {
  const input: Array<{ skillId: string; riskLevel: "low" | "medium" | "high"; allowedTools: string[] }> =
    [{ skillId: "example", riskLevel: "low", allowedTools: ["facts.query"] }];
  const registry = new SkillRegistry(input);
  input[0].riskLevel = "high";
  input[0].allowedTools.push("session.cancel");
  input.push({ skillId: "other", riskLevel: "high", allowedTools: [] });

  const definition = registry.get("example")!;
  const list = registry.list();
  list.push({ skillId: "injected", riskLevel: "high", allowedTools: [] });
  assert.throws(() => { (definition as { riskLevel: string }).riskLevel = "high"; }, TypeError);
  assert.throws(() => { (definition.allowedTools as string[]).push("session.cancel"); }, TypeError);
  assert.throws(() => { (list[0].allowedTools as string[]).push("session.cancel"); }, TypeError);

  assert.deepEqual(registry.get("example"), { skillId: "example", riskLevel: "low", allowedTools: ["facts.query"] });
  assert.equal(registry.get("other"), undefined);
  assert.equal(registry.get("injected"), undefined);

  const first = createP0SkillRegistry();
  const navigation = first.get("navigate_to")!;
  assert.throws(() => { (navigation.allowedTools as string[]).push("session.cancel"); }, TypeError);
  assert.deepEqual(createP0SkillRegistry().get("navigate_to")?.allowedTools, navigation.allowedTools);
});

test("requires the crossing advisory policy for crossing_advisory", () => {
  const registry = createP0SkillRegistry();

  assert.equal(registry.get("crossing_advisory")?.requiredPolicy, "crossing-advisory");
});

test("limits crossing_advisory to its approved logical tools", () => {
  const registry = createP0SkillRegistry();

  assert.deepEqual(registry.get("crossing_advisory")?.allowedTools, [
    "observation.request",
    "facts.query",
    "speech.ask_user",
  ]);
});

test("runtime P0 skills match every manifest authorization field", () => {
  const manifest = JSON.parse(
    readFileSync(new URL("../../packages/contracts/skills/registry.json", import.meta.url), "utf8")
  ) as {
    skills: Array<{
      skill_id: string;
      risk_level: string;
      allowed_tools: string[];
      required_policy?: string;
    }>;
  };
  const runtime = createP0SkillRegistry().list();

  assert.deepEqual(
    runtime
      .map(({ skillId, riskLevel, allowedTools, requiredPolicy }) => ({
        skillId,
        riskLevel,
        allowedTools,
        requiredPolicy,
      }))
      .sort((a, b) => a.skillId.localeCompare(b.skillId)),
    manifest.skills
      .map(({ skill_id, risk_level, allowed_tools, required_policy }) => ({
        skillId: skill_id,
        riskLevel: risk_level,
        allowedTools: allowed_tools,
        requiredPolicy: required_policy,
      }))
      .sort((a, b) => a.skillId.localeCompare(b.skillId))
  );
});
