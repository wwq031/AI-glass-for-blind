import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const source = readFileSync(new URL("../../apps/glasses-agent/android/src/main/java/com/leqi/experiment/glassesbt/GlassesBtActivity.kt", import.meta.url), "utf8");
const dispatch = source.split("override fun dispatchKeyEvent(event: KeyEvent): Boolean {")[1]?.split("override fun onKeyUp")[0];
const cxrl = readFileSync(new URL("../../apps/device-experiment/android/glasses/src/main/java/com/leqi/experiment/glasses/GlassesActivity.kt", import.meta.url), "utf8");
const phone = readFileSync(new URL("../../apps/device-experiment/android/phone-l/src/main/java/com/leqi/experiment/phonel/CxrlAgentActivity.kt", import.meta.url), "utf8");

test("CXR-L diagnostic handshake disables business commands and key handlers (source contract)", () => {
  assert.match(cxrl, /type == "rgr06\.diagnostics" && .*FLAG_DEBUGGABLE != 0/);
  assert.match(cxrl, /reset\(\); diagnosticOnly = true/);
  assert.match(cxrl, /event\("rgr06\.ready"\)/);
  assert.match(cxrl, /if \(diagnosticOnly\) return\s*when \(type\)/);
  for (const handler of ["onKeyDown", "onKeyUp", "onKeyLongPress"]) {
    assert.ok(cxrl.includes(`if (diagnosticOnly) return super.${handler}(code, event)`));
  }
  assert.match(cxrl, /if \(diagnosticOnly\) return\s*val now/);
});

test("CXR-L raw inputs preserve dispatch and never become phone business events (source contract)", () => {
  const hook = cxrl.split("override fun dispatchKeyEvent(event: KeyEvent): Boolean {")[1]?.split("override fun onKeyDown")[0];
  assert.ok(hook);
  assert.match(hook, /event\("rgr06\.input", trace\)/);
  assert.match(hook, /return super\.dispatchKeyEvent\(event\)/);
  assert.doesNotMatch(hook, /click\(|cancelFromUser\(|return true/);
  const diagnostic = phone.split("private fun startInputDiagnostics(token: String) {")[1]?.split("override fun onActivityResult")[0];
  assert.ok(diagnostic);
  assert.match(diagnostic, /command\("rgr06\.diagnostics"\)/);
  assert.match(diagnostic, /"rgr06\.input"/);
  assert.doesNotMatch(diagnostic, /startForegroundService|CxrlAgentService|envelope\(|voice\.arm|entrance\.arm/);
  assert.match(phone, /if \(diagnosticMode\) \{ startInputDiagnostics\(token\); return \}/);
  assert.match(phone, /diagnosticDevice\?\.close\(\)/);
});

test("input-only diagnostics bypass Bluetooth permission and server startup only in debug (source contract)", () => {
  const create = source.split("override fun onCreate(state: Bundle?) {")[1]?.split("override fun onRequestPermissionsResult")[0];
  assert.ok(create);
  assert.match(create, /val debug = .*FLAG_DEBUGGABLE != 0/);
  assert.match(create, /if \(debug && intent\.getBooleanExtra\("rgr06InputOnly", false\)\) \{[^}]*FLAG_KEEP_SCREEN_ON[^}]*return\s*\}/);
  assert.ok(create.indexOf('intent.getBooleanExtra("rgr06InputOnly"') < create.indexOf("checkSelfPermission"));
});

test("RGR06 diagnostic hook precedes view dispatch and preserves normal handling (source contract)", () => {
  assert.ok(dispatch);
  assert.match(dispatch, /return super\.dispatchKeyEvent\(event\)/);
  assert.ok(dispatch.indexOf('Log.d("Rgr06Input"') < dispatch.indexOf("super.dispatchKeyEvent(event)"));
  assert.doesNotMatch(dispatch, /onPhysicalClick\(|onPhysicalCancel\(|sendEvent\(|sendProbe\(|return true/);
});

test("RGR06 diagnostics are debug-only and limited to observed device names (source contract)", () => {
  assert.match(dispatch, /FLAG_DEBUGGABLE/);
  assert.match(dispatch, /if \(debug && name in setOf\("RGR06", "RGR06 Keyboard", "RGR06 Consumer Control"\)\)/);
  assert.doesNotMatch(dispatch, /descriptor|characters|bluetooth.*address/i);
});

test("RGR06 diagnostics retain Android identity, ordering, duration, repeat and cancellation (source contract)", () => {
  for (const field of ["deviceId", "source", "action", "keyCode", "scanCode", "repeatCount", "downTime", "eventTime", "isCanceled"]) {
    assert.ok(dispatch.includes(`event.${field}`), field);
  }
  assert.match(dispatch, /KeyEvent\.keyCodeToString/);
  assert.match(dispatch, /event\.eventTime - event\.downTime/);
});
