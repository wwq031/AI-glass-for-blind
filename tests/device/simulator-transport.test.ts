import assert from "node:assert/strict";
import test from "node:test";

import { DeviceEventMapper } from "../../apps/glasses-agent/src/device-event-mapper.ts";
import type { DeviceEvent } from "../../packages/providers/device/device-transport.ts";
import { SimulatorTransport } from "../../packages/testkit/simulator-transport.ts";

const mapper = new DeviceEventMapper({
  now: () => new Date("2026-09-22T00:00:00Z"),
  idFactory: () => "event-1",
});

test("simulator injects normalized button, capture, and disconnect events", async () => {
  const transport = new SimulatorTransport();
  const received: DeviceEvent[] = [];
  transport.subscribe((event) => received.push(event));
  await transport.connect();

  transport.inject(mapper.map("session-1", { kind: "button", buttonId: "primary", pressKind: "short" }));
  transport.inject(
    mapper.map("session-1", { kind: "capture_completed", requestId: "request-1", mediaId: "media-1" }),
  );
  transport.inject(mapper.map("session-1", { kind: "disconnected", reason: "link_lost", retryable: true }));

  assert.deepEqual(received.map(({ type }) => type), [
    "button.pressed",
    "capture.completed",
    "device.disconnected",
  ]);
  assert.deepEqual(received[0].payload, { button_id: "primary", press_kind: "short" });
});

test("simulator records commands without exposing a concrete transport", async () => {
  const transport = new SimulatorTransport();
  await transport.connect();
  await transport.sendCommand({
    schema_version: "1.0",
    command_id: "command-1",
    session_id: "session-1",
    type: "capture.requested",
    issued_at: "2026-09-22T00:00:00Z",
    payload: { request_id: "request-1", capability_id: "vision.entrance" },
  });
  assert.equal(transport.commands[0].type, "capture.requested");
});

test("simulator rejects sends while disconnected", async () => {
  const transport = new SimulatorTransport();
  await assert.rejects(() =>
    transport.sendCommand({
      schema_version: "1.0",
      command_id: "command-1",
      session_id: "session-1",
      type: "speech.play",
      issued_at: "2026-09-22T00:00:00Z",
      payload: { effect_id: "effect-1" },
    }),
  );
});
