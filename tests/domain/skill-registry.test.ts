import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import { createP0SkillRegistry } from "../../packages/domain/skills/skill-registry.ts";

test("registers navigate_to as a low-risk skill", () => {
  const registry = createP0SkillRegistry();

  assert.equal(registry.get("navigate_to")?.riskLevel, "low");
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
