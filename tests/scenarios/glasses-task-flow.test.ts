import assert from "node:assert/strict";
import test from "node:test";
import { GlassesTaskFlow } from "../../apps/phone-companion/src/android/glasses-task-flow.ts";

/**
 * The glasses window bookkeeping owns exactly two things: the live conversation and whichever
 * hardware window — recorder or camera — is open under which fresh tag. It owns no task steps, so
 * these cases only ever assert about windows, tags, generations and consumption.
 */

function live(): GlassesTaskFlow {
  const flow = new GlassesTaskFlow();
  flow.start("live-1");
  return flow;
}

test("a conversation starts once and stays live until it is cancelled", () => {
  const flow = new GlassesTaskFlow();
  assert.equal(flow.live, false);
  assert.equal(flow.sessionId, undefined);

  assert.equal(flow.start("live-1"), "live-1");
  assert.equal(flow.live, true);
  assert.equal(flow.sessionId, "live-1");
  assert.throws(() => flow.start("live-2"), /already live/);

  // Finishing a task is not modelled here at all: nothing but a cancel ends the session.
  assert.equal(flow.live, true);
  flow.cancel();
  assert.equal(flow.live, false);
  assert.equal(flow.sessionId, undefined);
  assert.equal(flow.start("live-2"), "live-2");
});

test("a cancel revokes the open windows and bumps the generation", () => {
  const flow = live();
  const before = flow.generation;
  flow.armVoice();
  flow.cancel();

  assert.equal(flow.generation, before + 1, "a cancel must retire every operation of the old generation");
  assert.equal(flow.armedVoicePurpose(), undefined);
  assert.equal(flow.armedCaptureTag(), undefined);
  assert.equal(flow.matchesVoice("voice:1"), false);
});

test("a recorder window is armed under a fresh purpose and consumed exactly once", () => {
  const flow = live();
  const first = flow.armVoice();
  assert.match(first, /^voice:\d+$/);
  assert.equal(flow.armedVoicePurpose(), first);
  assert.equal(flow.matchesVoice(first), true);
  assert.equal(flow.matchesVoice("voice:999"), false);

  assert.equal(flow.acceptSpeech(first), true, "audio of the open window must be accepted");
  assert.equal(flow.armedVoicePurpose(), undefined, "the window closes as it is consumed");
  assert.equal(flow.acceptSpeech(first), false, "one window authorises exactly one recording");
});

test("arming the camera closes the recorder and the other way round", () => {
  const flow = live();
  const purpose = flow.armVoice();
  const tag = flow.armCapture("vision.entrance");

  assert.equal(flow.armedVoicePurpose(), undefined, "the glasses hold one window at a time");
  assert.equal(flow.acceptSpeech(purpose), false, "the closed recorder window accepts nothing");
  assert.match(tag, /^capture:\d+$/);

  const nextPurpose = flow.armVoice();
  assert.equal(flow.armedCaptureTag(), undefined, "arming the recorder closes the camera window");
  assert.notEqual(nextPurpose, purpose, "a reopened recorder window gets its own purpose");
});

test("a retry retires the expired purpose so late audio from it cannot be counted", () => {
  const flow = live();
  const expired = flow.armVoice();
  const retry = flow.rearmVoice();

  assert.ok(retry, "a live window must be handed back for the retry");
  assert.notEqual(retry, expired);
  assert.equal(flow.matchesVoice(expired), false, "audio of the expired window must be refused");
  assert.equal(flow.acceptSpeech(expired), false);
  assert.equal(flow.acceptSpeech(retry!), true, "the retry window is the only live one");
});

test("re-arming with no window open opens nothing", () => {
  const flow = live();
  assert.equal(flow.rearmVoice(), undefined);
  assert.equal(flow.rearmCapture(), undefined);
  assert.equal(flow.armedVoicePurpose(), undefined);
  assert.equal(flow.armedCaptureTag(), undefined);
});

test("a capture window needs the capability it authorises", () => {
  const flow = live();
  assert.throws(() => flow.armCapture(""), /capability/);

  flow.armCapture("vision.menu");
  assert.equal(flow.armedCaptureCapability(), "vision.menu");
  assert.equal(flow.armedCaptureTag(), "capture:1");
});

test("an unconfirmed capture window authorises no photo", () => {
  const flow = live();
  const tag = flow.armCapture("vision.entrance");

  assert.equal(flow.acceptPhoto(tag), undefined, "a photo without the press must not be accepted");
  assert.equal(flow.armedCaptureTag(), tag, "the window stays open waiting for its press");

  assert.equal(flow.confirmCapture(tag), true);
  assert.deepEqual(flow.acceptPhoto(tag), { capabilityId: "vision.entrance" });
  assert.equal(flow.armedCaptureTag(), undefined, "one confirmation authorises exactly one photo");
  assert.equal(flow.acceptPhoto(tag), undefined, "the consumed window accepts nothing further");
});

test("a confirmation naming a retired tag authorises nothing — not even a later photo of that tag", () => {
  const flow = live();
  const retired = flow.armCapture("vision.entrance");
  const retry = flow.rearmCapture();
  assert.ok(retry);
  assert.notEqual(retry, retired);

  assert.equal(flow.confirmCapture(retired), false, "the retired tag is not this window");
  assert.equal(flow.acceptPhoto(retry!), undefined, "the live window is still unconfirmed");
  assert.equal(flow.acceptPhoto(retired), undefined, "a photo of the retired tag is refused");

  assert.equal(flow.confirmCapture(retry!), true);
  assert.deepEqual(flow.acceptPhoto(retry!), { capabilityId: "vision.entrance" });
});

test("a retry keeps the capability the window was opened for", () => {
  const flow = live();
  flow.armCapture("vision.traffic_signal");
  const retry = flow.rearmCapture();

  assert.equal(flow.armedCaptureCapability(), "vision.traffic_signal",
    "the capability under consent is fixed by the request, and a retry cannot change it");
  assert.equal(flow.confirmCapture(retry!), true);
  assert.deepEqual(flow.acceptPhoto(retry!), { capabilityId: "vision.traffic_signal" });
});

test("closing a window revokes it without bumping the generation", () => {
  const flow = live();
  const before = flow.generation;
  flow.armCapture("vision.scene");
  flow.closeCapture();
  assert.equal(flow.generation, before, "closing one window is not a cancel of the conversation");

  const purpose = flow.armVoice();
  flow.closeVoice();
  assert.equal(flow.matchesVoice(purpose), false);
  assert.equal(flow.acceptPhoto("capture:1"), undefined);
});

test("tags are never reused across a cancel", () => {
  const flow = live();
  const before = flow.armCapture("vision.entrance");
  flow.cancel();
  flow.start("live-2");
  const after = flow.armCapture("vision.entrance");

  assert.notEqual(after, before, "a tag from a cancelled conversation must never come back");
  assert.equal(flow.confirmCapture(before), false);
  assert.equal(flow.acceptPhoto(before), undefined);
});
