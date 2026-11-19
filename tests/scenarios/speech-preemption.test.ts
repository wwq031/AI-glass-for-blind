import assert from "node:assert/strict";
import test from "node:test";
import { SpeechQueue } from "../../apps/phone-companion/src/android/speech-queue.ts";

test("preemption waits for the device stop acknowledgement before starting the alert", async () => {
  let finishOld!: (ok: boolean) => void;
  let acknowledgeStop!: () => void;
  const spoken: string[] = [];
  const queue = new SpeechQueue({
    speak: async effect => {
      spoken.push(effect.text);
      return effect.text === "普通回答" ? new Promise<boolean>(resolve => { finishOld = resolve; }) : true;
    },
    interrupt: () => new Promise<void>(resolve => { acknowledgeStop = resolve; }),
  });
  const old = queue.enqueue("普通回答", "normal", "s");
  const alert = queue.enqueue("前方右转", "high", "s");
  finishOld(false);
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.deepEqual(spoken, ["普通回答"]);
  acknowledgeStop();
  assert.equal(await old, false);
  assert.equal(await alert, true);
  assert.deepEqual(spoken, ["普通回答", "前方右转"]);
});

test("a turn alert does not interrupt critical safety speech", async () => {
  let finish!: (ok: boolean) => void;
  let stops = 0;
  const queue = new SpeechQueue({
    speak: async effect => effect.priority === "critical" ? new Promise<boolean>(resolve => { finish = resolve; }) : true,
    interrupt: async () => { stops++; },
  });
  const critical = queue.enqueue("请停下", "critical", "s");
  const turn = queue.enqueue("右转", "high", "s");
  assert.equal(stops, 0);
  finish(true);
  assert.equal(await critical, true); assert.equal(await turn, true);
});

test("unsupported preemption preserves successful completion of a legacy prompt", async () => {
  let finish!: (ok: boolean) => void;
  let stops = 0;
  const queue = new SpeechQueue({
    speak: async effect => effect.priority === "normal" ? new Promise<boolean>(resolve => { finish = resolve; }) : true,
    canInterrupt: () => false,
    interrupt: async () => { stops++; },
  });
  const prompt = queue.enqueue("请选择", "normal", "legacy");
  const turn = queue.enqueue("右转", "high", "legacy");
  finish(true);
  assert.equal(await prompt, true);
  assert.equal(await turn, true);
  assert.equal(stops, 0);
});
