import assert from "node:assert/strict";
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
