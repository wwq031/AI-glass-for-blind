# Experimental Dual-App Integration Plan

> **For agentic workers:** Execute in this worktree one task at a time. Keep the existing uncommitted Agent runtime and scenario files untouched.

**Goal:** Put our own phone application and our own Rokid AIUI application on the connected devices and prove that they exchange ordered events under one session before attaching the complete navigation loop.

**Architecture:** The glasses exchange key, audio, image and playback data directly with our phone app through the Rokid CXR connection initiated over Bluetooth. The phone owns `session_id`, event ordering, location and model calls. The CXR SDK may negotiate a separate media link; the experiment must record its actual behavior. No independent glasses Wi-Fi connection or cloud session relay is required. ADB is for diagnostics only and does not count as the business transport.

**Tech Stack:** Existing Node/TypeScript monorepo; Android SDK and Java 17 for the phone APK; Rokid AIUI JavaScript/Ink for the glasses AIX project.

---

## Experiment boundary

This phase proves own-app packaging, startup, permissions, CXR pairing and event transfer. It does not count as the approved full navigation acceptance. A model service, map credentials, real continuous navigation, camera capture, model ASR/TTS, and entrance recognition must be connected before that claim.

## 2026-09-29 device checkpoint

The actual experimental Android source is under `apps/device-experiment/android/`. Both debug APKs built, installed, and launched on the connected Honor phone and Rokid glasses. A BLE scan from our phone app found the glasses after the glasses app requested pairing mode; GATT discovery and the CXR connection-info callback succeeded. A diagnostic second-stage connection with empty authorization input returned `SN_CHECK_FAILED` on the phone after the glasses briefly reported a connected phone. The current phone APK stops with an explicit missing-authorization message instead of attempting an empty connection. No two-way custom message or full navigation loop has passed.

Read-only reinspection of the existing official phone APK archive and eye-side AIX/CXR service found no standalone `.lc` file or reusable client secret in the visible resources. The protected phone application may keep authorization elsewhere; that is not evidence that our APK is authorized. See `research/phone-apk-analysis/README.md` and `apps/device-experiment/android/README.md`.

Later the same evening, our `phone-bt` and `glasses-bt` APKs established a direct RFCOMM session on the paired devices without CXR-M credentials. Phone-to-glasses and glasses-to-phone probe events passed; a physical function-key press reached the phone, and a fresh phone location fix reached the glasses. The earlier CXR-M checkpoint remains accurate for that SDK path. CXR-L authorization succeeded but its data-service callback did not. Media transfer and the full navigation task have not passed; consult the experiment README for exact observations.

## Tasks

### 1. CXR SDK feasibility and session protocol

Files: `apps/phone-companion/src/transport/`, `tests/device/`, and Android SDK configuration.

- [ ] Locate the original CXR test artifact and verify which SDK/version, device, pairing and data callbacks it exercised.
- [ ] Resolve the public Rokid CXR-M artifact and compare its API with this glasses firmware; keep SDK credentials out of Git.
- [ ] Write a focused event-ordering/session-isolation test before implementing the transport adapter.
- [ ] Verify own-app Bluetooth/CXR connection, an event in each direction, and the actual media channel before claiming transport success.

### 2. Phone APK

Files: `apps/phone-companion/android/AndroidManifest.xml`, `apps/phone-companion/android/src/.../MainActivity.java`, `apps/phone-companion/android/build.ps1`.

- [ ] Build an installable `com.leqi.assistive.experiment` APK from the installed Android SDK without embedding service credentials.
- [ ] Expose only experiment configuration on the phone screen: pairing status, session ID and last received event.
- [ ] Request foreground location permission; emit a location event only after explicit test start and a fresh location fix. Stop on user request or Activity exit.
- [ ] Install on the Honor phone; verify package name, launch and network result with device logs.

### 3. Glasses AIUI project

Files: `apps/glasses-agent/aiui/app.json`, `app.js`, `AGENTS.md`, `pages/experiment/index.ink`.

- [ ] Create a local-importable AIUI project using the observed Ink structure and official page-event API.
- [ ] Send `button.pressed` on a deliberate `GlobalHook`/`Enter` release through the verified glasses-side bridge; receive a phone command. Log the exact key code; do not infer that every button maps to the same action.
- [ ] Generate an AIX through the supported AIUI build route, install/run the project on the Rokid glasses and record whether it can exchange CXR data with our phone app. If AIUI cannot use the required CXR bridge, build a small Android glasses-side bridge without replacing the JSUI user flow.

### 4. Two-device proof

- [ ] Record CXR pairing path, app/package versions and UTC times.
- [ ] From the glasses press a key and observe it in the phone app; send a fresh phone location and observe it in the glasses app. Confirm sequence ordering and isolation with a second session ID.
- [ ] Save a concise experiment result with observed evidence and failures. Lack of independent glasses Wi-Fi is not a blocker for this design.

### 5. Continue to approved full navigation loop

After the dual-app transport proof, wire existing Amap place/route code, real continuous location/navigation events, model ASR/TTS, camera/media and observation provider into the approved design. Do not use simulated inputs in its final acceptance.
