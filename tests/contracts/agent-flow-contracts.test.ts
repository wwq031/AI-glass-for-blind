import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

function loadJson(path: string) {
  return JSON.parse(readFileSync(resolve(root, path), "utf8"));
}

test("validates the complete Event to Plan to Result to Effect contract set", () => {
  assert.doesNotThrow(() => {
    execFileSync(process.execPath, [resolve(root, "tools/validate-contracts.mjs")], {
      cwd: root,
      encoding: "utf8",
      stdio: "pipe",
    });
  });
});

test("agent plans reject raw device commands", () => {
  const schema = loadJson("packages/contracts/schemas/agent-plan.schema.json");
  const ajv = new Ajv2020({ strict: true });
  addFormats(ajv);
  const validate = ajv.compile(schema);
  const valid = validate({
    schema_version: "1.0",
    plan_id: "plan-raw-command",
    session_id: "session-1",
    event_id: "event-1",
    goal: "inspect the scene",
    actions: [{ kind: "device_raw_command", command: "camera.capture" }],
    created_at: "2026-09-22T00:00:00Z",
  });

  assert.equal(valid, false);
});

test("skill registry exposes exactly the P0 composable skills", () => {
  const registry = loadJson("packages/contracts/skills/registry.json");
  assert.deepEqual(
    registry.skills.map((skill: { skill_id: string }) => skill.skill_id),
    [
      "navigate_to",
      "inspect_scene",
      "read_text",
      "find_target",
      "follow_up",
      "crossing_advisory",
      "obstacle_advisory",
      "menu_structuring",
    ],
  );
});

test("event and session schemas preserve P0 agent context without breaking prior snapshots", () => {
  const ajv = new Ajv2020({ strict: true });
  addFormats(ajv);
  ajv.addSchema(loadJson("packages/contracts/schemas/fact.schema.json"));
  const event = ajv.compile(loadJson("packages/contracts/schemas/event-envelope.schema.json"));
  const snapshot = ajv.compile(loadJson("packages/contracts/schemas/session-snapshot.schema.json"));

  const eventBase = {
    schema_version: "1.0",
    event_id: "event-1",
    session_id: "session-1",
    sequence: 1,
    occurred_at: "2026-09-22T00:00:00Z",
    type: "motion.heading_changed",
    payload: {},
  };
  assert.equal(event({ ...eventBase, source: "motion" }), true);
  assert.equal(event({ ...eventBase, source: "provider" }), true);
  assert.equal(
    snapshot({
      schema_version: "1.0",
      session_id: "session-1",
      state: "navigating",
      last_sequence: 1,
      updated_at: "2026-09-22T00:00:00Z",
    }),
    true,
  );
  assert.equal(
    snapshot({
      schema_version: "1.0",
      session_id: "session-1",
      state: "navigating",
      last_sequence: 1,
      updated_at: "2026-09-22T00:00:00Z",
      goal: "reach the hospital",
      active_plan_id: "plan-1",
      pending_question: "Which entrance should I find?",
      facts: [],
      active_skills: ["navigate_to"],
    }),
    true,
  );
});
