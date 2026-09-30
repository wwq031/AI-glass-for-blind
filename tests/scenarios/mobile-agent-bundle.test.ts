import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { runInNewContext } from "node:vm";
import test from "node:test";
import { build } from "esbuild";

test("browser bundle runs the repository Agent on a contract cancel event", async () => {
  const bundled = await build({
    entryPoints: ["./apps/phone-companion/src/android/agent-browser.ts"],
    bundle: true,
    platform: "browser",
    format: "iife",
    target: "chrome120",
    write: false,
  });
  const messages: Array<Record<string, unknown>> = [];
  const sandbox = {
    window: {
      LeQiNative: { send: (json: string) => { messages.push(JSON.parse(json)); } },
    },
    crypto: { randomUUID },
    structuredClone,
    Date,
    Promise,
    Map,
    Set,
    WeakSet,
    console,
  };
  runInNewContext(bundled.outputFiles[0]!.text, sandbox);
  const receiver = (sandbox.window as typeof sandbox.window & {
    LeQiAgent: { receive(json: string): void };
  }).LeQiAgent;
  receiver.receive(JSON.stringify({
    id: "request-1",
    type: "event",
    payload: {
      schema_version: "1.0",
      event_id: "cancel-1",
      session_id: "session-1",
      sequence: 1,
      occurred_at: "2026-09-30T02:00:00.000Z",
      source: "user",
      type: "user.cancel",
      payload: {},
    },
  }));
  await new Promise((resolve) => setTimeout(resolve, 20));

  assert.ok(messages.some((message) => message.type === "effect" &&
    (message.payload as { type?: string; payload?: { status?: string } })?.type === "session" &&
    (message.payload as { payload?: { status?: string } }).payload?.status === "cancelled"));
  assert.ok(messages.some((message) => message.type === "event.result" && message.id === "request-1"));
});

test("glasses cancellation becomes a repository Agent cancel event", async () => {
  const bundled = await build({
    entryPoints: ["./apps/phone-companion/src/android/agent-browser.ts"],
    bundle: true, platform: "browser", format: "iife", target: "chrome120", write: false,
  });
  const messages: Array<Record<string, unknown>> = [];
  const sandbox = {
    window: { LeQiNative: { send: (json: string) => { messages.push(JSON.parse(json)); } } },
    crypto: { randomUUID }, structuredClone, Date, Promise, Map, Set, WeakSet, console,
    setTimeout, clearTimeout,
  };
  runInNewContext(bundled.outputFiles[0]!.text, sandbox);
  const receive = (sandbox.window as typeof sandbox.window & {
    LeQiAgent: { receive(json: string): void };
  }).LeQiAgent.receive;
  const tick = () => new Promise((resolve) => setTimeout(resolve, 20));
  receive(JSON.stringify({ id: "button", type: "native.device", payload: {
    kind: "event", type: "probe.glasses", payload: "sprite-button-click",
  } }));
  await tick();
  const prompt = messages.find((message) => message.type === "speech.say");
  assert.ok(prompt);
  receive(JSON.stringify({ id: prompt.id, type: "response", payload: { finished: true } }));
  await tick();
  const arm = messages.find((message) => message.type === "device.send");
  assert.ok(arm);
  receive(JSON.stringify({ id: arm.id, type: "response", payload: { sent: true } }));
  await tick();

  receive(JSON.stringify({ id: "cancel-button", type: "native.device", payload: {
    kind: "event", type: "user.cancel", payload: "long-press",
  } }));
  await tick();
  assert.ok(messages.some((message) => message.type === "effect" &&
    (message.payload as { type?: string; payload?: { status?: string } })?.type === "session" &&
    (message.payload as { payload?: { status?: string } }).payload?.status === "cancelled"));
  for (const request of messages.filter((message) =>
    message.type === "policy.navigation.stop" ||
    (message.type === "device.send" && message.id !== arm.id))) {
    receive(JSON.stringify({ id: request.id, type: "response", payload: { sent: true, stopped: true } }));
  }
  assert.equal((prompt.payload as { allowRepeat?: boolean }).allowRepeat, true);
});
