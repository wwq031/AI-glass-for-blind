import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import test from "node:test";

/**
 * Static host-contract checks for the phone runtime foreground service.
 *
 * The phone runtime (repository Agent WebView, local model, offline TTS, AMap navigation,
 * Bluetooth transport, bounded media registry) is Kotlin, so it cannot be executed here.
 * What CAN be checked without a device is the contract the Android layer must keep:
 * who owns the runtime, how the foreground service is declared, that adapters no longer hold
 * an Activity, that Bluetooth reconnect is bounded and never re-arms capture, that startup
 * readiness is a real check, and that no key or media byte is logged or shipped.
 *
 * These assertions are deliberately about structure, not wording: they fail when ownership
 * or a protocol boundary moves, and they stay quiet for comment and formatting changes.
 */

const MODULE_DIR = "apps/phone-companion/android";
const SOURCE_DIR = `${MODULE_DIR}/src/main/java/com/leqi/experiment/phonebt`;
const MANIFEST = `${MODULE_DIR}/src/main/AndroidManifest.xml`;

/**
 * Strip comments so documentation prose cannot satisfy or break a structural assertion.
 *
 * The line-comment rule refuses to start on a `/` as well as on a `:`, otherwise it would match
 * the second `//` of a `file:///…` URL inside a string literal and swallow the rest of the line.
 */
function code(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/(^|[^:/])\/\/[^\n]*/g, "$1");
}

function kotlin(name: string): string {
  return code(readFileSync(`${SOURCE_DIR}/${name}.kt`, "utf8"));
}

function sourceFiles(): Array<{ name: string; text: string }> {
  return readdirSync(SOURCE_DIR)
    .filter((name) => name.endsWith(".kt"))
    .map((name) => ({ name, text: readFileSync(`${SOURCE_DIR}/${name}`, "utf8") }));
}

/** The body of one member, up to the next member at the same indentation. */
function memberBody(source: string, signature: string): string {
  const start = source.indexOf(signature);
  assert.notEqual(start, -1, `missing member: ${signature}`);
  const indent = " ".repeat(start - source.lastIndexOf("\n", start) - 1);
  const rest = source.slice(start + signature.length);
  const next = rest.search(new RegExp(`\n${indent}(override |private |fun |internal |@)`));
  return next === -1 ? rest : rest.slice(0, next);
}

test("phone runtime is owned by the foreground service, not by the Activity", () => {
  const service = kotlin("AgentHostService");
  const activity = kotlin("AgentProofActivity");

  assert.match(service, /class AgentHostService : Service\(\)/);
  for (const owner of [
    "AgentRuntimeWebView(",
    "GemmaLocalBridge(",
    "OfflineTtsBridge(",
    "AgentSpeechOutput(",
    "AgentNavigationDevice(",
    "GlassesTransport(",
  ]) {
    assert.ok(service.includes(owner), `the service must own ${owner}`);
    assert.ok(!activity.includes(owner), `the Activity must not own ${owner}`);
  }
  assert.match(service, /LinkedHashMap<String, ByteArray>/, "the bounded media registry belongs to the service");

  // The Activity observes and issues commands; it never keeps a runtime of its own.
  assert.match(activity, /bindService\(/);
  assert.match(activity, /\.observe\(listener\)/);
  assert.match(activity, /releaseObserver\(listener\)/);
  assert.ok(!activity.includes("onDestroy"), "the Activity must not tear the session down");
  assert.ok(!activity.includes("stopService("), "the Activity must not stop the runtime on its own");

  // And the service must not retain an Activity: the token Activity appears only in the
  // notification's content intent, never as a field or parameter type.
  assert.ok(!service.includes("import android.app.Activity"));
  assert.ok(!/private (val|var) \w+: Activity\b/.test(service));
});

test("foreground service lifecycle is declared stoppable in source (static check)", () => {
  const service = kotlin("AgentHostService");
  const manifest = readFileSync(MANIFEST, "utf8");

  assert.match(service, /return START_NOT_STICKY/);
  // A null or unknown Intent (process restart) must tear the runtime down instead of silently
  // resuming the previous session, capture consent or navigation.
  const fallback = memberBody(service, "override fun onStartCommand");
  const unknownAction = fallback.slice(fallback.indexOf("else ->"));
  assert.ok(unknownAction.includes("stopHost()"), "an empty Intent must stop and tear down");
  assert.ok(!unknownAction.includes("ensureRuntime()"), "an empty Intent must not build a keepalive runtime");

  // stopSelf alone is not a teardown: while an Activity is still bound Android keeps the service
  // alive and onDestroy never runs, so stopHost must clean up itself.
  const stopHost = memberBody(service, "private fun stopHost");
  assert.ok(stopHost.includes("cleanup()"), "stop must release resources without waiting for onDestroy");
  assert.ok(stopHost.indexOf("notifyTerminal(") < stopHost.indexOf("cleanup()"),
    "observers must hear the terminal state before the cleanup drops them");
  assert.match(memberBody(service, "private fun notifyTerminal"), /stopped = true/,
    "the terminal status is what tells the UI to unbind");

  assert.match(service, /cleanedUp\.compareAndSet\(false, true\)/, "cleanup must be idempotent");
  assert.match(service, /PARTIAL_WAKE_LOCK/);
  const cleanup = memberBody(service, "private fun cleanup()");
  assert.match(cleanup, /releaseWakeLock\(\)/, "cleanup must release the wake lock");
  assert.match(cleanup, /transport\?\.close\(\)/, "cleanup must cancel Bluetooth reconnect");
  assert.match(cleanup, /navigation\?\.close\(\)/, "cleanup must cancel navigation callbacks");
  assert.match(cleanup, /detachSpeech\(\)\?\.close\(\)/,
    "speech must be atomically detached and closed so ownership never races");
  assert.match(cleanup, /agent\?\.close\(\)/);
  // The model and TTS engines are closed on their own thread: their close contends with a
  // possibly minutes-long inference, and the main thread must not wait for that.
  assert.ok(!/model\?\.close\(\)|tts\?\.close\(\)/.test(cleanup),
    "blocking engine close must not run on the teardown caller's thread");
  assert.match(cleanup, /teardown\.execute \{ closeEngines\(/, "engines must be closed off the caller's thread");
  assert.match(service, /private fun closeEngines\(modelBridge: GemmaLocalBridge\?, ttsBridge: OfflineTtsBridge\?\)/);
  assert.match(cleanup, /takeModel\(\)/, "the teardown thread must be the one that owns the close");
  assert.match(cleanup, /takeTts\(\)/);

  // Observers are the only Activity-shaped reference; unbinding must drop them all.
  assert.match(memberBody(service, "override fun onUnbind"), /observers\.clear\(\)/);

  assert.match(manifest, /<service\s+android:name="\.AgentHostService"[\s\S]*?android:exported="false"/);
  assert.match(manifest, /android:foregroundServiceType="connectedDevice\|location"/);
  for (const permission of [
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
    "android.permission.FOREGROUND_SERVICE_LOCATION",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.WAKE_LOCK",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.ACCESS_FINE_LOCATION",
  ]) {
    assert.ok(manifest.includes(permission), `manifest must declare ${permission}`);
  }
  // The notification's stop action is what makes the foreground service user-stoppable.
  assert.match(service, /ACTION_STOP/);
  assert.match(service, /Notification\.Action\.Builder/);
});

test("device adapters take a Context so no Activity is retained", () => {
  for (const name of [
    "AgentRuntimeWebView",
    "AgentNavigationDevice",
    "PhoneLocationAdapter",
    "AmapNaviAdapter",
    "GlassesTransport",
  ]) {
    const source = kotlin(name);
    assert.ok(!source.includes("import android.app.Activity"), `${name} must not import Activity`);
    assert.ok(!/\bactivity: Activity\b/.test(source), `${name} must not take an Activity parameter`);
    assert.ok(!/private val activity\b/.test(source), `${name} must not hold an Activity`);
  }
  assert.match(kotlin("PhoneLocationAdapter"), /fun requestFresh\(context: Context, callback: Callback\)/);
  assert.match(kotlin("PhoneLocationAdapter"), /fun hasPermission\(context: Context\): Boolean/);
  assert.match(kotlin("AmapNaviAdapter"), /class AmapNaviAdapter\(\s*private val context: Context,/);
  assert.match(kotlin("AgentNavigationDevice"), /class AgentNavigationDevice\(private val context: Context\)/);
  // WebView and UI callbacks go through the main Looper, not through an Activity.
  assert.match(kotlin("AgentRuntimeWebView"), /Handler\(Looper\.getMainLooper\(\)\)/);
  assert.match(kotlin("AgentHostService"), /AgentRuntimeWebView\(this\)/);
});

test("Bluetooth reconnect is bounded, generation-guarded and never re-arms capture", () => {
  const transport = kotlin("GlassesTransport");

  assert.match(transport, /const val MAX_ATTEMPTS = \d+/, "reconnect must be bounded");
  assert.match(transport, /attempt >= MAX_ATTEMPTS/, "the connector must give up after the bounded attempts");
  assert.match(transport, /private fun retryDelayMs\(attempt: Int\): Long/, "reconnect must back off");
  assert.match(transport, /minOf\(BASE_RETRY_MS shl step, MAX_RETRY_MS\)/);
  assert.match(transport, /val generation = AtomicLong\(0\)/, "stale listeners must be identified by generation");
  assert.match(transport, /private fun isCurrent\(epoch: Long\)/);
  assert.match(transport, /onReconnectExhausted/, "the UI must learn that reconnect gave up");
  assert.match(memberBody(transport, "fun close()"), /stopped = true/, "close must prevent reconnect");

  // Only one socket at a time: a new run invalidates and closes the previous one first.
  assert.match(transport, /private fun beginRun\(\): Long[\s\S]*?closeSocket\(\)/);

  // Reconnecting is a transport concern: it must never re-arm recording or capture, and it must
  // not replay a previously sent message to make the reconnect look seamless.
  assert.ok(!transport.includes("voice.arm") && !transport.includes("entrance.arm"));
  const connector = memberBody(transport, "private fun runConnector");
  assert.ok(!connector.includes("send("), "reconnect must not replay a previous message");
  assert.ok(!connector.includes("sendBinary("), "reconnect must not replay previous media");

  // A drop after a connection that really came up is a new drop: it must be reported again,
  // otherwise a reconnect that succeeds and then fails leaves the Agent believing the glasses
  // are still online for the rest of the run.
  const bump = "connectedRuns.incrementAndGet()";
  const attempt = memberBody(transport, "private fun tryAttempt");
  assert.ok(attempt.includes(bump), "a successful connection must be counted");
  assert.ok(attempt.indexOf(bump) < attempt.indexOf("readFrames("),
    "the count must be taken when the link comes up, before the read loop ends");
  assert.match(connector, /connectedRuns\.get\(\) != runsBefore/,
    "a drop after a successful connection must re-arm the disconnect report");
});

test("reconnect progress stays local and only a real drop reaches the Agent", () => {
  const service = kotlin("AgentHostService");
  const reconnecting = memberBody(service, "override fun onReconnecting");
  assert.ok(!reconnecting.includes("forwardDevice"), "reconnect progress must not become a device event");
  assert.ok(!reconnecting.includes("sendEnvelope"), "reconnect progress must not become a device event");
  assert.match(reconnecting, /publishStatus\(\)/);

  const disconnected = memberBody(service, "override fun onDisconnected");
  assert.match(disconnected, /forwardDevice\(JSONObject\(\)\.put\("kind", KIND_DISCONNECTED\)/);
  assert.match(service, /const val KIND_DISCONNECTED = "disconnected"/);
  assert.match(service, /const val KIND_CONNECTED = "connected"/);
  assert.match(service, /const val KIND_EVENT = "event"/);
  // The Agent bundle's envelope vocabulary is unchanged.
  for (const type of ["native.device", "native.speech", "native.photo", "native.navigation"]) {
    assert.ok(service.includes(`"${type}"`), `the service must keep emitting ${type}`);
  }
});

test("startup readiness genuinely loads model and TTS and gates capture on the result", () => {
  const readiness = kotlin("RuntimeReadiness");
  const service = kotlin("AgentHostService");
  const gemma = kotlin("GemmaLocalBridge");
  const tts = kotlin("OfflineTtsBridge");

  // Missing files must be a reportable answer, not an exception the caller has to guess at.
  assert.match(gemma, /fun findModelFile\(context: Context\): File\?/);
  assert.match(gemma, /fun missingModelMessage\(context: Context\): String/);
  assert.match(tts, /fun findModelDir\(context: Context\): File\?/);
  assert.match(tts, /fun missingModelFiles\(context: Context\): List<String>/);
  assert.match(readiness, /GemmaLocalBridge\.missingModelMessage\(context\)/);
  assert.match(readiness, /OfflineTtsBridge\.missingModelFiles\(context\)/);

  for (const check of [
    /AmapPrivacy\.isAgreed\(context\)/,
    /PhoneLocationAdapter\.hasPermission\(context\)/,
    /Manifest\.permission\.BLUETOOTH_CONNECT/,
    /BluetoothAdapter\.getDefaultAdapter\(\)/,
  ]) {
    assert.match(readiness, check, `readiness must check ${check}`);
  }
  // Nothing is "ready" while an item is still pending: no premature ready claim.
  assert.match(readiness, /items\.none \{ it\.level != Level\.OK \}/);
  // A paired-but-disconnected glasses link is not a passed check, and neither is "still checking".
  assert.match(readiness, /private fun bluetoothItem\(link: LinkState, glassesState: String\): Item/);
  assert.match(readiness, /LinkState\.CONNECTED -> Item\(Capability\.BLUETOOTH, Level\.OK/);
  assert.match(readiness, /LinkState\.CONNECTING -> Item\(Capability\.BLUETOOTH, Level\.PENDING/);
  assert.match(readiness, /LinkState\.DOWN -> Item\(Capability\.BLUETOOTH, Level\.BLOCKED/);
  assert.match(service, /private fun currentReport\(\): RuntimeReadiness\.Report[\s\S]*?link = glassesLink/,
    "the report must be built from the live link state, not from the pairing list");

  // Pending prerequisites block capture too, and speech is part of the capture chain.
  const capture = memberBody(readiness, "fun captureBlockedReason()");
  for (const capability of ["Capability.AGENT", "Capability.MODEL", "Capability.SPEECH", "Capability.BLUETOOTH"]) {
    assert.ok(capture.includes(capability), `capture must be blocked by ${capability}`);
  }
  assert.match(capture, /notReady\(it\)/, "a pending item must block capture, not only a failed one");
  assert.match(memberBody(readiness, "private fun notReady"),
    /it\.level != Level\.OK/, "notReady must cover PENDING as well as BLOCKED");
  // Navigation gets its own gate on the map capability.
  assert.match(memberBody(readiness, "fun navigationBlockedReason()"), /Capability\.MAP/);
  assert.match(memberBody(service, "private fun handlePolicyNavigationStart"),
    /navigationBlockedReason\(\)/);

  // The service really loads both engines on the worker thread, and reports the outcome.
  const checks = memberBody(service, "private fun runStartupChecks()");
  assert.equal((checks.match(/bridge\.initialize\(\)/g) ?? []).length, 2,
    "startup must load both the local model and offline TTS");
  assert.match(checks, /RuntimeReadiness\.Level\.BLOCKED/);
  // A blocking load that finishes after the teardown must close its own bridge instead of
  // registering it into an already-collected runtime.
  assert.equal((checks.match(/if \(!adopt(Model|Tts)\(bridge\)\)/g) ?? []).length, 2,
    "both loads must hand the bridge over atomically and close it when the runtime is gone");
  assert.match(memberBody(service, "private fun ensureRuntime()"), /submit \{ runStartupChecks\(\) \}/,
    "loading must not block the main thread");
  const submit = memberBody(service, "private fun submit");
  assert.match(submit, /cleanedUp\.get\(\)/, "a queued callback must not enqueue work after teardown");
  assert.match(submit, /catch \(error: RejectedExecutionException\)/,
    "a shut-down pool must not throw back into a transport callback");

  // A session may not be armed when the capture pipeline cannot work.
  const deviceSend = memberBody(service, "private fun handleDeviceSend");
  const gate = deviceSend.indexOf("captureBlockedReason()");
  const write = deviceSend.indexOf("glassesTransport().send(type, text)");
  assert.notEqual(gate, -1, "capture arming must consult the readiness report");
  assert.notEqual(write, -1);
  assert.ok(gate < write, "the readiness gate must run before anything is written to the glasses");
  assert.match(deviceSend, /TYPE_VOICE_ARM/);
  assert.match(deviceSend, /TYPE_ENTRANCE_ARM/);
});

test("no key value or media byte is logged, shipped in the module or sent to the Agent", () => {
  const files = sourceFiles();
  assert.ok(files.length > 0);

  for (const file of files) {
    // A raw 32-hex AMap-style key must never appear in the module sources.
    assert.ok(!/[0-9a-fA-F]{32}/.test(file.text), `${file.name} must not embed a key literal`);
  }

  // The readiness report answers "is the key configured?" without ever materialising the value.
  const readiness = readFileSync(`${SOURCE_DIR}/RuntimeReadiness.kt`, "utf8");
  assert.ok(!/\bLog\./.test(readiness), "the readiness report must not log");
  assert.ok(!/\bprintln\(/.test(readiness), "the readiness report must not print");

  // Media bytes stay in memory: they are never put into a JSON envelope, only their size or ref is.
  const service = kotlin("AgentHostService");
  assert.ok(!/put\([^)]*\b(jpeg|wav)\b/.test(service), "media bytes must not enter an Agent envelope");
  assert.match(service, /put\("mediaRef", mediaRef\)/);
  assert.match(service, /storeMedia\(jpeg\)/);
});
