import test from 'node:test';
import assert from 'node:assert/strict';
import { latencyStats, summarizeExports, renderReport } from '../../tools/summarize-cxrl-mvp.mjs';

function record(id: string, kind = 'warm', outcome = 'human_confirmed') {
  return { session_id: `session-${id}`, request_id: id, start_kind: kind, outcome,
    timestamps_ms: { confirmed: 100, image_callback: 120, image_validated: 130,
      mock_start: 131, mock_end: 132, display_call_start: 133, display_call_end: 140, human_visible: 200 } };
}
function document(runs: unknown[]) {
  return { schema_version: '1.0', route: 'CXR-L', mode: 'mock', clock: 'phone_elapsed_realtime_ms',
    metadata: { app_version: '0.2.0', source_revision: 'abcdef0', apk_sha256: 'a'.repeat(64) }, runs };
}
test('nearest-rank percentiles do not mutate samples or invent empty measurements', () => {
  const samples = [40, 10, 30, 20];
  assert.deepEqual(latencyStats(samples), { n: 4, p50_ms: 20, p95_ms: 40, max_ms: 40 });
  assert.deepEqual(samples, [40, 10, 30, 20]);
  assert.equal(latencyStats([]).p95_ms, null);
});
test('cold/warm runs and APK versions are never pooled', () => {
  const first = document([record('1', 'cold'), record('2', 'warm')]);
  const second = document([record('3', 'warm')]); second.metadata.source_revision = '1234567';
  assert.equal(summarizeExports([first, second]).length, 3);
});
test('per-request version snapshots take priority over export-time device versions', () => {
  const snapshot = document([
    { ...record('1'), metadata: { glasses_firmware: 'v1' } },
    { ...record('2'), metadata: { glasses_firmware: 'v2' } },
  ]);
  const groups = summarizeExports([{ ...snapshot, metadata: { ...snapshot.metadata, glasses_firmware: 'v3' } }]);
  assert.deepEqual(groups.map(group => group.versions.glasses_firmware).sort(), ['v1', 'v2']);
});
test('display API acceptance is separate from human confirmation and failures', () => {
  const sent = record('2', 'warm', 'display_sent'); delete (sent.timestamps_ms as any).human_visible;
  const failed = { ...record('3', 'warm', 'failed'), failure_stage: 'decode', timestamps_ms: { confirmed: 100, image_callback: 120 } };
  const [group] = summarizeExports([document([record('1'), sent, failed])]);
  assert.equal(group.human_confirmed, 1); assert.equal(group.display_sent_only, 1);
  assert.equal(group.failed, 1); assert.equal(group.human_confirmation_rate, 1 / 3);
  assert.equal(group.latency.confirm_to_human_confirmation.n, 1);
  assert.match(renderReport([group]), /不是精确显示延迟/);
});
test('duplicate requests, reversed clock and missing visible evidence are rejected', () => {
  assert.throws(() => summarizeExports([document([record('1'), record('1')])]), /Duplicate/);
  const backwards = record('1'); backwards.timestamps_ms.image_callback = 90;
  assert.throws(() => summarizeExports([document([backwards])]), /monotonic/);
  const unsupported = document([record('1')]); unsupported.clock = 'glasses_clock';
  assert.throws(() => summarizeExports([unsupported]), /clock/);
  const missing = record('1'); delete (missing.timestamps_ms as any).human_visible;
  assert.throws(() => summarizeExports([document([missing])]), /evidence/);
});

test('Gemma inference and Mock timings stay separate even with matching APK metadata', () => {
  const mock = record('mock');
  const inference = record('gemma') as any;
  inference.timestamps_ms.inference_start = inference.timestamps_ms.mock_start;
  inference.timestamps_ms.inference_end = inference.timestamps_ms.mock_end;
  delete inference.timestamps_ms.mock_start; delete inference.timestamps_ms.mock_end;
  const groups = summarizeExports([document([mock]), { ...document([inference]), mode: 'gemma' }]);
  assert.equal(groups.length, 2);
  assert.equal(groups.find(group => group.mode === 'gemma').latency.inference.n, 1);
  assert.equal(groups.find(group => group.mode === 'gemma').latency.mock, undefined);
  assert.throws(() => summarizeExports([{ ...document([mock]), mode: 'gemma' }]), /stage/);
});
