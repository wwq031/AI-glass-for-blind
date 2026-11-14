import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { runInNewContext } from "node:vm";
import test from "node:test";

test("CXR-L reconnect retires the Agent before replacing the native connection (source contract)", () => {
  const service = readFileSync("apps/device-experiment/android/phone-l/src/main/java/com/leqi/experiment/phonel/CxrlAgentService.kt", "utf8");
  const start = service.split("override fun onStartCommand")[1]!.split("private fun status")[0]!.replace(/\/\/[^\r\n]*/g, "");
  assert.match(start, /retire\(\);?\s*envelope\("native.device", JSONObject\(\)\.put\("kind", "disconnected"\)\.put\("reason", "重新连接"\)\)\s*device.connect\(token\)/);
});

test("CXR-L screens direct users to the verified phone stop control", () => {
  for (const name of ["CxrlAgentActivity", "CxrlAgentService"]) {
    const source = readFileSync(`apps/device-experiment/android/phone-l/src/main/java/com/leqi/experiment/phonel/${name}.kt`, "utf8");
    assert.doesNotMatch(source, /长按取消/);
    assert.match(source, /停止任务与连接/);
  }
});

test("CXR-L diagnostics expose failure and speech stages only in debug without media payloads", () => {
  const service = readFileSync("apps/device-experiment/android/phone-l/src/main/java/com/leqi/experiment/phonel/CxrlAgentService.kt", "utf8");
  const logger = service.split("private fun diagnostic")[1]!.split("override fun onBind")[0]!;
  assert.match(logger, /FLAG_DEBUGGABLE != 0/);
  assert.match(logger, /Log.d\("LeqiAgentDiag"/);
  for (const stage of ["agent.error", "speech.request", "tts.start", "tts.complete", "audio.send.start", "audio.send.complete"]) {
    assert.ok(service.includes(`diagnostic("${stage}"`), stage);
  }
  assert.match(service, /diagnostic\(type, "playId=\$tag"\)/);
  const calls = service.split("\n").filter(line => line.includes("diagnostic("));
  assert.ok(calls.every(line => !/\$text\b|\$pcm\b|\$raw\b|\$token\b/.test(line)));
});

test("CXR-L text comparison is explicitly enabled per connection and traces accepted text", () => {
  const service = readFileSync("apps/device-experiment/android/phone-l/src/main/java/com/leqi/experiment/phonel/CxrlAgentService.kt", "utf8");
  const activity = readFileSync("apps/device-experiment/android/phone-l/src/main/java/com/leqi/experiment/phonel/CxrlAgentActivity.kt", "utf8");
  assert.match(activity, /private var textDiagnostics = false/);
  assert.match(activity, /putExtra\("textDiagnostics", textDiagnostics\)/);
  assert.match(service, /textTrace.enabled = intent.getBooleanExtra\("textDiagnostics", false\)/);
  for (const stage of ["asr", "speech", "tts.part"]) {
    assert.ok(service.includes(`textTrace.record("${stage}"`));
  }
  assert.match(service, /override fun onDestroy\(\) \{\s*shutdown\(\)/);
  assert.match(service, /private fun shutdown\(\) \{[\s\S]*?textTrace.enabled = false/);
});

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
