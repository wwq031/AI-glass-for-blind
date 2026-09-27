import assert from "node:assert/strict";
import test from "node:test";
import { adviseCrossing, adviseCrossingFromResult } from "../../packages/domain/policies/crossing-advisory.ts";
import { skillContextFromNavigationEvent } from "../../packages/domain/agent/navigation-trigger.ts";
import type { AgentEvent } from "../../packages/domain/agent/types.ts";

const now = "2026-09-22T10:00:10.000Z";
const validUntil = "2026-09-22T10:00:15.000Z";
const certain = { trafficSignal: "green", directionMatch: "yes", vehicleApproaching: "no", confidence: "high", validUntil, now } as const;

test("crossing policy gives only cautious advice from fresh aligned green evidence", () => {
  const advice = adviseCrossing(certain);
  assert.equal(advice.action, "proceed_with_caution");
  assert.match(advice.speech, /辅助|仅供参考/);
  assert.doesNotMatch(advice.speech, /安全通行|保证安全/);
});

test("red or approaching vehicle requires waiting", () => {
  assert.equal(adviseCrossing({ ...certain, trafficSignal: "red" }).action, "wait");
  assert.equal(adviseCrossing({ ...certain, vehicleApproaching: "yes" }).action, "wait");
  assert.equal(adviseCrossing({ ...certain, trafficSignal: "red", vehicleApproaching: "unknown" }).action, "wait");
  assert.equal(adviseCrossing({ ...certain, trafficSignal: "unknown", vehicleApproaching: "yes" }).action, "wait");
});

test("partial observation never yields proceed advice even with positive facts", () => {
  const result = { callId: "c1", sessionId: "s1", toolId: "observation.request", status: "partial" as const,
    completedAt: now, output: {}, facts: [
      { name: "traffic_signal.state", value: "green", confidence: "high" as const, validUntil },
      { name: "traffic_signal.direction_match", value: "yes", confidence: "high" as const, validUntil },
      { name: "vehicle.activity", value: "no", confidence: "high" as const, validUntil },
    ] };
  assert.notEqual(adviseCrossingFromResult(result, now).action, "proceed_with_caution");
});

test("adapter waits on a fresh confirmed hazard despite unrelated unknown facts", () => {
  const base = { callId: "c1", sessionId: "s1", toolId: "observation.request", status: "partial" as const,
    completedAt: now, output: {}, facts: [
      { name: "traffic_signal.direction_match", value: "yes", confidence: "high" as const, validUntil },
    ] };
  const red = { ...base, facts: [...base.facts,
    { name: "traffic_signal.state", value: "red", confidence: "high" as const, validUntil },
  ] };
  const vehicle = { ...base, facts: [...base.facts,
    { name: "vehicle.activity", value: "yes", confidence: "high" as const, validUntil },
  ] };
  assert.equal(adviseCrossingFromResult(red, now).action, "wait");
  assert.equal(adviseCrossingFromResult(vehicle, now).action, "wait");
  assert.equal(adviseCrossingFromResult({ ...red, facts: red.facts.map((fact) =>
    fact.name === "traffic_signal.state" ? { ...fact, validUntil: "2026-09-22T10:00:09.000Z" } : fact) }, now).action, "cannot_determine");
});

test("duplicate or conflicting signal facts never produce crossing permission", () => {
  const facts = [
    { name: "traffic_signal.state", value: "green", confidence: "high" as const, validUntil },
    { name: "traffic_signal.direction_match", value: "yes", confidence: "high" as const, validUntil },
    { name: "vehicle.activity", value: "no", confidence: "high" as const, validUntil },
  ];
  const base = { callId: "c1", sessionId: "s1", toolId: "observation.request", status: "succeeded" as const,
    completedAt: now, output: {}, facts };
  assert.equal(adviseCrossingFromResult({ ...base, facts: [...facts, { ...facts[0]!, value: "red" }] }, now).action, "cannot_determine");
  assert.equal(adviseCrossingFromResult({ ...base, facts: [...facts, { ...facts[0]! }] }, now).action, "cannot_determine");
});

test("malformed Provider facts fail closed without throwing", () => {
  const base = { callId: "c1", sessionId: "s1", toolId: "observation.request", status: "succeeded" as const,
    completedAt: now, output: {}, facts: [
      { name: "traffic_signal.state", value: "green", confidence: "high" as const, validUntil },
      { name: "traffic_signal.direction_match", value: "yes", confidence: "high" as const, validUntil },
      { name: "vehicle.activity", value: "no", confidence: "high" as const, validUntil },
    ] };
  for (const facts of [
    [null, ...base.facts],
    [42, ...base.facts],
    [{ name: "traffic_signal.state", value: "green", confidence: "bogus", validUntil }, ...base.facts.slice(1)],
    [{ name: "traffic_signal.state", value: "green", confidence: "high", validUntil: 123 }, ...base.facts.slice(1)],
    [...base.facts, { name: "traffic_signal.state", value: "red" }],
  ]) {
    assert.doesNotThrow(() => adviseCrossingFromResult({ ...base, facts: facts as never }, now));
    assert.equal(adviseCrossingFromResult({ ...base, facts: facts as never }, now).action, "cannot_determine");
  }
  assert.equal(adviseCrossingFromResult({ ...base, facts: null as never }, now).action, "cannot_determine");
});

test("unknown, missing, expired, or direction mismatch fails closed", () => {
  for (const evidence of [
    { ...certain, trafficSignal: "unknown" as const },
    { ...certain, directionMatch: "no" as const },
    { ...certain, vehicleApproaching: "unknown" as const },
    { ...certain, validUntil: undefined },
    { ...certain, validUntil: "2026-09-22T10:00:09.000Z" },
  ]) {
    const advice = adviseCrossing(evidence);
    assert.equal(advice.action, "cannot_determine");
    assert.match(advice.speech, /请先停下/);
  }
});

test("fresh low-confidence evidence calls for recheck", () => {
  assert.equal(adviseCrossing({ ...certain, confidence: "low" }).action, "recheck");
});

test("unrecognized provider values and invalid expiry fail closed", () => {
  assert.equal(adviseCrossing({ ...certain, trafficSignal: "blue" as "green" }).action, "cannot_determine");
  assert.equal(adviseCrossing({ ...certain, vehicleApproaching: "none" as "no" }).action, "cannot_determine");
  const result = { callId: "c1", sessionId: "s1", toolId: "observation.request", status: "succeeded" as const,
    completedAt: now, output: {}, facts: [
      { name: "traffic_signal.state", value: "green", confidence: "high" as const, validUntil: "not-a-date" },
      { name: "traffic_signal.direction_match", value: "yes", confidence: "high" as const, validUntil },
      { name: "vehicle.activity", value: "no", confidence: "high" as const, validUntil },
    ] };
  assert.equal(adviseCrossingFromResult(result, now).action, "cannot_determine");
});

test("navigation event contributes context, never an observation call", () => {
  const event: AgentEvent = { eventId: "n1", sessionId: "s1", sequence: 2, source: "navigation", type: "navigation.intersection_approaching", occurredAt: now,
    payload: { intersection_id: "cross-1", distance_m: 22, travel_heading_deg: 90 } };
  assert.deepEqual(skillContextFromNavigationEvent(event, { now }), {
    urgentSkillId: "crossing_advisory", navigation: { intersectionId: "cross-1", distanceM: 22, travelHeadingDeg: 90 },
  });
  assert.deepEqual(skillContextFromNavigationEvent({ ...event, type: "navigation.started" }, { now }), {});
});

test("stale, future, and malformed approach events cannot create actionable crossing context", () => {
  const event: AgentEvent = { eventId: "n1", sessionId: "s1", sequence: 2, source: "navigation",
    type: "navigation.intersection_approaching", occurredAt: now,
    payload: { intersection_id: "junction-7", distance_m: 12, travel_heading_deg: 90 } };
  for (const [candidate, clock] of [
    [{ ...event, occurredAt: "2026-09-22T10:00:00.000Z" }, "2026-09-22T10:00:16.000Z"],
    [{ ...event, occurredAt: "2026-09-22T10:00:11.000Z" }, now],
    [{ ...event, payload: { ...event.payload, distance_m: -1 } }, now],
    [{ ...event, payload: { ...event.payload, intersection_id: "" } }, now],
    [{ ...event, payload: { ...event.payload, travel_heading_deg: 360 } }, now],
  ] as const) {
    const context = skillContextFromNavigationEvent(candidate, { now: clock, maxAgeMs: 15_000 });
    assert.equal(context.urgentSkillId, undefined);
    assert.equal(context.rejected, true);
  }
  assert.equal(skillContextFromNavigationEvent({ ...event, occurredAt: "2026-09-22T10:00:00.000Z" },
    { now: "2026-09-22T10:00:16.000Z", maxAgeMs: 20_000 }).urgentSkillId, "crossing_advisory");
});
