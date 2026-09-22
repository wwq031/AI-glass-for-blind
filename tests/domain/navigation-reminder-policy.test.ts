import assert from "node:assert/strict";
import test from "node:test";

import { SpeechPriorityPolicy } from "../../packages/domain/policies/speech-priority-policy.ts";
import {
  NavigationReminderPolicy,
  type SpeechEffect,
} from "../../packages/domain/reminder/navigation-reminder-policy.ts";
import type { NavigationEvent, NavigationEventType } from "../../packages/providers/navigation/navigation-provider.ts";

const now = new Date("2026-09-22T00:00:00Z");
const policy = new NavigationReminderPolicy({ now: () => now, idFactory: () => "effect-1" });

function navigationEvent(type: NavigationEventType): NavigationEvent {
  return {
    schema_version: "1.0",
    event_id: "event-1",
    session_id: "session-1",
    sequence: 1,
    occurred_at: now.toISOString(),
    source: "navigation",
    type,
    payload: { route_state: type === "navigation.arrived" ? "arrived" : "active", distance_m: 20 },
  };
}

test("intersection reminder asks for explicit observation confirmation", () => {
  const effect = policy.create(navigationEvent("navigation.intersection_approaching"));
  assert.equal(effect?.priority, "high");
  assert.match(effect?.text ?? "", /请按键或说检查/);
});

test("off-route reminder is non-interruptible risk speech", () => {
  const effect = policy.create(navigationEvent("navigation.off_route"));
  assert.equal(effect?.source, "risk");
  assert.equal(effect?.priority, "critical");
  assert.equal(effect?.interruptible, false);
});

test("arrival reminder requests entrance observation", () => {
  const effect = policy.create(navigationEvent("navigation.arrived"));
  assert.match(effect?.text ?? "", /观察入口/);
});

test("speech arbitration orders risk before navigation before detail", () => {
  const queue = new SpeechPriorityPolicy();
  const effect = (priority: SpeechEffect["priority"], source: SpeechEffect["source"]): SpeechEffect => ({
    schema_version: "1.0",
    effect_id: `${source}-${priority}`,
    session_id: "session-1",
    text: priority,
    priority,
    source,
    interruptible: true,
    expires_at: "2026-09-22T00:01:00Z",
  });
  queue.enqueue(effect("detail", "observation"));
  queue.enqueue(effect("high", "navigation"));
  queue.enqueue(effect("critical", "risk"));

  assert.equal(queue.next(now)?.source, "risk");
  assert.equal(queue.next(now)?.source, "navigation");
  assert.equal(queue.next(now)?.priority, "detail");
});

test("expired speech is discarded", () => {
  const queue = new SpeechPriorityPolicy();
  queue.enqueue({
    schema_version: "1.0",
    effect_id: "expired",
    session_id: "session-1",
    text: "old",
    priority: "critical",
    source: "risk",
    interruptible: false,
    expires_at: "2026-09-21T23:59:59Z",
  });
  assert.equal(queue.next(now), undefined);
});
