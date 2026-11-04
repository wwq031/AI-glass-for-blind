import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { runInNewContext } from "node:vm";
import test from "node:test";

test("CXR-L output contains a working repository Agent and its HTML entry", async () => {
  // 使用临时目录，不覆盖已生成的安装包资源。
  const output = mkdtempSync(join(tmpdir(), "leqi-cxrl-agent-"));
  execFileSync(process.execPath, ["tools/build-mobile-agent.mjs", "--outdir", output]);
  assert.match(readFileSync(join(output, "index.html"), "utf8"), /src="agent.js"/);
  const messages: Array<Record<string, any>> = [];
  const window = { LeQiNative: { send(json: string) { messages.push(JSON.parse(json)); } } };
  runInNewContext(readFileSync(join(output, "agent.js"), "utf8"), {
    window, structuredClone, console,
    // 原生请求在此测试中没有设备响应，不能让其超时定时器阻止测试退出。
    setTimeout: (callback: () => void, ms: number) => setTimeout(callback, ms).unref(),
    clearTimeout,
  });
  assert.ok(messages.some(message => message.type === "ready"));
  (window as any).LeQiAgent.receive(JSON.stringify({
    id: "cxrl-cancel", type: "event", payload: {
      schema_version: "1.0", event_id: "cancel", session_id: "cxrl-session", sequence: 1,
      occurred_at: new Date().toISOString(), source: "user", type: "user.cancel", payload: {},
    },
  }));
  await new Promise(resolve => setTimeout(resolve, 30));
  assert.ok(messages.some(message => message.type === "effect" &&
    message.payload?.type === "session" && message.payload.payload?.status === "cancelled"));
});
