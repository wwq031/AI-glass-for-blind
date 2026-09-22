import assert from "node:assert/strict";
import test from "node:test";

import { SessionOrchestrator, type SessionEvent } from "../../packages/domain/session/session-orchestrator.ts";

const sessionId = "session-golden";
const now = "2026-09-22T00:00:00Z";
let id = 0;
const orchestrator = new SessionOrchestrator(sessionId, {
  now: () => new Date(now),
  idFactory: () => `generated-${++id}`,
});

const speech = (transcript: string, intent_hint: "destination" | "confirmation" | "expression_request"): SessionEvent => ({
  schema_version: "1.0",
  input_id: `input-${++id}`,
  session_id: sessionId,
  occurred_at: now,
  transcript,
  confidence: 1,
  locale: "zh-CN",
  source: "phone_mic",
  is_final: true,
  intent_hint,
});

const navigation = (type: "navigation.started" | "navigation.intersection_approaching" | "navigation.arrived", sequence: number): SessionEvent => ({
  schema_version: "1.0",
  event_id: `navigation-${sequence}`,
  session_id: sessionId,
  sequence,
  occurred_at: now,
  source: "navigation",
  type,
  payload: { route_state: type === "navigation.arrived" ? "arrived" : "active", distance_m: 20 },
});

const button = (): SessionEvent => ({
  schema_version: "1.0",
  event_id: `button-${++id}`,
  session_id: sessionId,
  occurred_at: now,
  type: "button.pressed",
  payload: { button_id: "primary", press_kind: "short" },
});

const observation = (capability_id: string, summary: string): SessionEvent => ({
  schema_version: "1.0",
  session_id: sessionId,
  request_id: `observation-${++id}`,
  capability_id,
  status: "succeeded",
  confidence: "high",
  needs_retake: false,
  summary,
  facts: [],
});

test("golden restaurant scenario advances every state through events", () => {
  orchestrator.handle({ type: "destination.input_requested", session_id: sessionId });
  assert.equal(orchestrator.snapshot().state, "destination_input");
  assert.equal(orchestrator.handle(speech("去约定的餐厅", "destination"))[0].type, "search_destination");

  orchestrator.handle({
    type: "destination.candidates_listed",
    session_id: sessionId,
    candidates: [{ candidate_id: "poi-1", name: "约定餐厅" }],
  });
  assert.equal(orchestrator.snapshot().state, "destination_confirm");
  assert.equal(orchestrator.handle(speech("确认", "confirmation"))[0].type, "confirm_destination");
  orchestrator.handle({
    type: "destination.confirmed",
    session_id: sessionId,
    destination: { candidate_id: "poi-1", name: "约定餐厅" },
  });
  orchestrator.handle(navigation("navigation.started", 10));
  assert.equal(orchestrator.snapshot().state, "navigating");

  orchestrator.handle(navigation("navigation.intersection_approaching", 11));
  assert.equal(orchestrator.snapshot().state, "intersection_check");
  const crossingRequest = orchestrator.handle(button())[0];
  assert.deepEqual(
    crossingRequest.type === "request_observation" ? crossingRequest.capability_id : undefined,
    "vision.traffic_signal",
  );
  orchestrator.handle(observation("vision.traffic_signal", "暂时无法确认信号灯，请停留重查。"));
  assert.equal(orchestrator.snapshot().state, "navigating");

  orchestrator.handle(navigation("navigation.arrived", 12));
  assert.equal(orchestrator.snapshot().state, "approaching_destination");
  orchestrator.handle({ type: "observation.prompted", session_id: sessionId });
  assert.equal(orchestrator.snapshot().state, "entrance_check");
  assert.equal(orchestrator.handle(button())[0].type, "request_observation");
  orchestrator.handle(observation("vision.entrance", "入口在右前方。"));
  assert.equal(orchestrator.snapshot().state, "inside_restaurant");

  orchestrator.handle({ type: "observation.menu_requested", session_id: sessionId });
  assert.equal(orchestrator.snapshot().state, "menu_reading");
  orchestrator.handle(observation("vision.menu", "识别到十二道菜。"));
  const expressionRequest = orchestrator.handle(speech("看看对面的人表情", "expression_request"))[0];
  assert.equal(orchestrator.snapshot().state, "conversation_assist");
  assert.equal(expressionRequest.type === "request_observation" && expressionRequest.consent, "explicit");
  orchestrator.handle(observation("vision.expression", "对方可能在微笑，但无法确定真实情绪。"));
  assert.equal(orchestrator.snapshot().state, "completed");
  assert.ok(orchestrator.snapshot().last_sequence >= 12);
});

test("capture is not requested until the user confirms at an intersection", () => {
  const isolated = new SessionOrchestrator("session-consent", { now: () => new Date(now) });
  isolated.handle({ type: "destination.input_requested", session_id: "session-consent" });
  const snapshot = isolated.snapshot();
  assert.equal(snapshot.pending_observation, undefined);
});

test("snapshot can be restored and cross-session events are rejected", () => {
  const restored = new SessionOrchestrator(sessionId, { now: () => new Date(now) });
  restored.restore(orchestrator.snapshot());
  assert.equal(restored.snapshot().state, "completed");
  assert.throws(() => restored.handle({ type: "session.completed", session_id: "other-session" }));
});
