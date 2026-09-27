import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";

import { decodeAgentPlan, decodeAgentEvent, decodeEffect, encodeAgentEvent, encodeEffect } from "../../packages/domain/agent/contract-codecs.ts";
import { validatePlan } from "../../packages/domain/agent/plan-validator.ts";
import { SkillRegistry } from "../../packages/domain/agent/types.ts";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

function loadJson(path: string) {
  return JSON.parse(readFileSync(resolve(root, path), "utf8"));
}

function makeAjv() {
  const ajv = new Ajv2020({ allErrors: true, strict: true });
  addFormats(ajv);
  return ajv;
}

test("decodes a schema-valid snake_case plan into a PlanValidator-valid runtime plan", () => {
  const ajv = makeAjv();
  const schema = loadJson("packages/contracts/schemas/agent-plan.schema.json");
  const validateContract = ajv.compile(schema);
  const wirePlan = loadJson("packages/contracts/examples/agent-plan-navigate.json");
  wirePlan.response_draft = "I am looking up People's Hospital.";

  assert.equal(validateContract(wirePlan), true);
  const plan = decodeAgentPlan(wirePlan);
  const registry = new SkillRegistry([{
    skillId: "navigate_to",
    riskLevel: "low",
    allowedTools: ["navigation.search_destination"],
  }]);

  assert.equal(plan.actions[0]?.kind, "tool_call");
  if (plan.actions[0]?.kind === "tool_call") {
    assert.equal(plan.actions[0].skillId, "navigate_to");
    assert.equal(plan.actions[0].toolId, "navigation.search_destination");
  }
  assert.equal(plan.responseDraft, "I am looking up People's Hospital.");
  assert.deepEqual(validatePlan(registry, plan, { observationConsent: "none" }), { ok: true });
});

test("decodes physical event sources through the documented semantic normalization", () => {
  const ajv = makeAjv();
  const validateEvent = ajv.compile(loadJson("packages/contracts/schemas/event-envelope.schema.json"));
  const speechWire = {
    schema_version: "1.0",
    event_id: "speech-1",
    session_id: "session-1",
    sequence: 0,
    occurred_at: "2026-09-27T10:00:00Z",
    source: "speech",
    type: "speech.transcript_final",
    trace_id: "trace-1",
    payload: { transcript: "read this menu" },
  };
  const glassesWire = {
    schema_version: "1.0",
    event_id: "glasses-1",
    session_id: "session-1",
    sequence: 1,
    occurred_at: "2026-09-27T10:00:01Z",
    source: "glasses",
    type: "device.button_pressed",
    payload: { button: "assistant" },
  };
  assert.equal(validateEvent(speechWire), true);
  assert.equal(validateEvent(glassesWire), true);

  const speech = decodeAgentEvent(speechWire);
  const glasses = decodeAgentEvent(glassesWire);

  assert.equal(speech.source, "user");
  assert.equal(speech.traceId, "trace-1");
  assert.equal(speech.sourceDetail, "speech");
  assert.equal(glasses.source, "device");
  assert.equal(glasses.sourceDetail, "glasses");
  assert.equal(encodeAgentEvent(speech).source, "speech");
  assert.equal(encodeAgentEvent(glasses).source, "glasses");
  assert.equal(validateEvent(encodeAgentEvent(speech)), true);
  assert.equal(validateEvent(encodeAgentEvent(glasses)), true);
});

test("encodes runtime events and effects as schema-valid snake_case envelopes", () => {
  const ajv = makeAjv();
  const validateEvent = ajv.compile(loadJson("packages/contracts/schemas/event-envelope.schema.json"));
  const validateEffect = ajv.compile(loadJson("packages/contracts/schemas/effect.schema.json"));
  const event = encodeAgentEvent({
    eventId: "event-1",
    sessionId: "session-1",
    sequence: 2,
    source: "user",
    type: "speech.transcript_final",
    occurredAt: "2026-09-27T10:00:00Z",
    payload: { transcript: "Find the station" },
    traceId: "trace-2",
  });
  const effect = encodeEffect({
    effectId: "effect-1",
    sessionId: "session-1",
    type: "speech",
    createdAt: "2026-09-27T10:00:02Z",
    payload: { text: "Searching for the station.", priority: "normal" },
  });

  assert.equal(event.schema_version, "1.0");
  assert.equal(event.event_id, "event-1");
  assert.equal(event.occurred_at, "2026-09-27T10:00:00Z");
  assert.equal(event.trace_id, "trace-2");
  assert.equal(validateEvent(event), true);
  assert.equal(validateEvent(encodeAgentEvent(decodeAgentEvent({
    schema_version: "1.0",
    event_id: "event-speech-1",
    session_id: "session-1",
    sequence: 0,
    occurred_at: "2026-09-27T10:00:00Z",
    source: "speech",
    type: "speech.transcript_final",
    payload: {},
  }))), true);
  assert.equal(effect.schema_version, "1.0");
  assert.equal(effect.effect_id, "effect-1");
  assert.equal(validateEffect(effect), true);
  const wireEffect = {
    schema_version: "1.0",
    effect_id: "effect-with-plan",
    session_id: "session-1",
    plan_id: "plan-1",
    type: "speech",
    created_at: "2026-09-27T10:00:02Z",
    payload: { text: "Continue straight.", priority: "normal" },
  };
  assert.equal(validateEffect(wireEffect), true);
  assert.deepEqual(encodeEffect(decodeEffect(wireEffect)), wireEffect);
});

test("rejects malformed wire plans and event envelopes", () => {
  assert.throws(() => decodeAgentPlan({
    schema_version: "1.0",
    plan_id: "plan-1",
    session_id: "session-1",
    event_id: "event-1",
    goal: "navigate",
    actions: [{ kind: "tool_call", skill_id: "navigate_to", tool_id: "navigation.search_destination" }],
    created_at: "2026-09-27T10:00:00Z",
  }));
  assert.throws(() => decodeAgentEvent({
    schema_version: "1.0",
    event_id: "event-1",
    session_id: "session-1",
    sequence: -1,
    occurred_at: "2026-09-27T10:00:00Z",
    source: "unknown-source",
    type: "device.button_pressed",
    payload: {},
  }));
});
