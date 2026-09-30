import assert from "node:assert/strict";
import test from "node:test";
import { nativeNavigationEvent } from "../../apps/phone-companion/src/android/native-navigation-event.ts";

test("normalizes actual SDK progress and arrival callbacks into navigation Events", () => {
  const progress = nativeNavigationEvent({ sessionId: "s", kind: "progress", detail: "剩余 200 米", remainDistanceM: 200 },
    "native-1", 5, "2026-09-30T02:00:00Z");
  assert.equal(progress.type, "navigation.approaching_maneuver");
  assert.deepEqual(progress.payload, { route_state: "active", distance_m: 200, provider: "amap" });
  const arrived = nativeNavigationEvent({ sessionId: "s", kind: "arrived", detail: "已到达目的地" },
    "native-2", 6, "2026-09-30T02:00:01Z");
  assert.equal(arrived.type, "navigation.arrived");
  assert.equal(arrived.payload.route_state, "arrived");
});

test("rejects unknown callbacks instead of inventing a navigation Event", () => {
  assert.throws(() => nativeNavigationEvent({ sessionId: "s", kind: "unknown" }, "x", 1, "2026-09-30T02:00:00Z"));
});
