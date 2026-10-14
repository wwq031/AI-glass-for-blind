import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const stages = ['confirmed', 'image_callback', 'image_validated', 'mock_start', 'mock_end',
  'display_call_start', 'display_call_end', 'human_visible'];
const outcomes = ['pending', 'failed', 'display_sent', 'human_confirmed'];
const failures = ['consent', 'capture', 'decode', 'mock', 'display', 'visibility', 'lifecycle'];
const pairs = {
  confirm_to_image_callback: ['confirmed', 'image_callback'],
  image_callback_to_validated: ['image_callback', 'image_validated'],
  mock: ['mock_start', 'mock_end'],
  display_api: ['display_call_start', 'display_call_end'],
  confirm_to_human_confirmation: ['confirmed', 'human_visible'],
};
const versionFields = ['app_version', 'source_revision', 'apk_sha256', 'sdk_version', 'phone_model',
  'phone_api', 'glasses_firmware', 'official_app_package', 'official_app_version', 'official_app_version_code'];

export function latencyStats(samples) {
  if (!samples.length) return { n: 0, p50_ms: null, p95_ms: null, max_ms: null };
  const values = [...samples].sort((a, b) => a - b);
  return { n: values.length, p50_ms: values[Math.ceil(values.length * .5) - 1],
    p95_ms: values[Math.ceil(values.length * .95) - 1], max_ms: values.at(-1) };
}

export function summarizeExports(documents) {
  const groups = new Map();
  const seen = new Set();
  for (const document of documents) {
    if (document?.schema_version !== '1.0' || document.route !== 'CXR-L' || document.mode !== 'mock' ||
      document.clock !== 'phone_elapsed_realtime_ms' || !Array.isArray(document.runs))
      throw new Error('Unsupported route, mode, clock or record schema');
    for (const run of document.runs) {
      const versions = Object.fromEntries(versionFields.map(key => [key, run.metadata?.[key] ?? document.metadata?.[key] ?? 'unrecorded']));
      if (typeof run.session_id !== 'string' || !run.session_id || typeof run.request_id !== 'string' || !run.request_id ||
        !['cold', 'warm', 'unspecified'].includes(run.start_kind) || !outcomes.includes(run.outcome) ||
        !run.timestamps_ms || typeof run.timestamps_ms !== 'object') throw new Error('Invalid request record');
      const times = run.timestamps_ms;
      if (!Number.isSafeInteger(times.confirmed) || times.confirmed < 0 ||
        Object.keys(times).some(key => !stages.includes(key))) throw new Error('Invalid stage timestamps');
      for (let i = 1; i < stages.length; i++) {
        const current = times[stages[i]];
        if (current !== undefined && (!Number.isSafeInteger(current) || current < 0 ||
          times[stages[i - 1]] === undefined || current < times[stages[i - 1]]))
          throw new Error('Missing predecessor or non-monotonic timestamp');
      }
      if ((run.outcome === 'human_confirmed' && times.human_visible === undefined) ||
        (run.outcome === 'display_sent' && times.display_call_end === undefined) ||
        (run.outcome === 'failed' && !failures.includes(run.failure_stage)))
        throw new Error('Outcome lacks corresponding evidence');
      const identity = JSON.stringify([versions, run.session_id, run.request_id]);
      if (seen.has(identity)) throw new Error('Duplicate request across exports; select non-overlapping samples');
      seen.add(identity);
      const key = JSON.stringify([versions, run.start_kind]);
      if (!groups.has(key)) groups.set(key, { versions, start_kind: run.start_kind, total: 0,
        human_confirmed: 0, display_sent_only: 0, pending: 0, failed: 0, failures: {},
        samples: Object.fromEntries(Object.keys(pairs).map(name => [name, []])) });
      const group = groups.get(key);
      group.total++;
      if (run.outcome === 'human_confirmed') group.human_confirmed++;
      else if (run.outcome === 'display_sent') group.display_sent_only++;
      else if (run.outcome === 'pending') group.pending++;
      else { group.failed++; group.failures[run.failure_stage] = (group.failures[run.failure_stage] ?? 0) + 1; }
      for (const [name, [start, end]] of Object.entries(pairs)) {
        if (times[start] !== undefined && times[end] !== undefined) group.samples[name].push(times[end] - times[start]);
      }
    }
  }
  return [...groups.values()].map(({ samples, ...group }) => ({ ...group,
    human_confirmation_rate: group.human_confirmed / group.total,
    latency: Object.fromEntries(Object.entries(samples).map(([name, values]) => [name, latencyStats(values)])) }));
}

export function renderReport(groups) {
  const safe = value => String(value).replace(/[|`\r\n]/g, ' ');
  const lines = ['# CXR-L Mock 通信样本报告', '',
    '此报告汇总手机单调时钟与人工确认记录，仍需核对设备和外部证据。发送成功不等于眼镜可见；人工确认时间包含反应时间，不是精确显示延迟。Mock 耗时不是模型性能。', ''];
  for (const group of groups) {
    lines.push(`## ${safe(group.start_kind)} / APK ${safe(group.versions.app_version)} / ${safe(group.versions.source_revision)}`, '',
      `确认拍照样本 ${group.total}；人工确认 ${group.human_confirmed}（${(group.human_confirmation_rate * 100).toFixed(1)}%）；仅发送 ${group.display_sent_only}；失败 ${group.failed}；待完成 ${group.pending}。`, '',
      `SDK ${safe(group.versions.sdk_version)}；眼镜固件 ${safe(group.versions.glasses_firmware)}；官方 App ${safe(group.versions.official_app_version)}；APK SHA-256 ${safe(group.versions.apk_sha256)}。`, '',
      '| 手机阶段 | 样本数 | P50 ms | P95 ms | 最大 ms |', '| --- | ---: | ---: | ---: | ---: |');
    for (const [name, stats] of Object.entries(group.latency))
      lines.push(`| ${name} | ${stats.n} | ${stats.p50_ms ?? '—'} | ${stats.p95_ms ?? '—'} | ${stats.max_ms ?? '—'} |`);
    lines.push('', `失败阶段：${Object.entries(group.failures).map(([stage, count]) => `${stage}=${count}`).join(', ') || '无已记录失败'}。`, '');
  }
  return lines.join('\n');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv.length < 3) throw new Error('Usage: node tools/summarize-cxrl-mvp.mjs records.json [records2.json]');
    const documents = process.argv.slice(2).map(path => JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, '')));
    process.stdout.write(renderReport(summarizeExports(documents)) + '\n');
  } catch (error) { process.stderr.write(error.message + '\n'); process.exitCode = 1; }
}
