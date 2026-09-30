import type { Confidence, Fact } from "../../../../packages/providers/observation/observation-provider.ts";
import type { OcrClient, OcrLine } from "../../../../apps/gateway/src/observation/ocr-adapter.ts";
import type { VlmAnalysis, VlmClient } from "../../../../apps/gateway/src/observation/vlm-adapter.ts";
import { createBrowserArgumentValidator } from "./contract-schemas.ts";

/**
 * The device's local vision API: one capture reference and one prompt go out, one JSON string comes
 * back. It is deliberately dumb — it does not know this repository's contracts, and it never
 * decides anything. The structured Fact/ObservationResult shape is imposed here, by the caller.
 */
export type VisionObserve = (request: { mediaRef: string; prompt: string }) => Promise<unknown>;

const CONFIDENCES: readonly Confidence[] = ["high", "medium", "low", "unknown"];

/**
 * Fact names each capability may report, with the vocabulary the existing policies read. A vision
 * answer can only ever become one of these names: an unrecognised field is dropped rather than
 * passed on as an invented fact.
 */
const CAPABILITY_FACTS: Record<string, readonly string[]> = {
  "vision.scene": ["scene.description", "scene.obstacle", "scene.hazard"],
  "vision.entrance": ["entrance.description", "entrance.steps", "entrance.door_side", "entrance.hazard"],
  "vision.menu": ["menu.text_line", "menu.summary", "menu.price"],
  "vision.expression": ["expression.visible_state", "expression.description"],
  "vision.traffic_signal": [
    "traffic_signal.state", "traffic_signal.direction_match", "crosswalk.present", "vehicle.activity",
  ],
};

/** The values the crossing policy understands; anything else is not a fact it may act on. */
const TRAFFIC_SIGNAL_STATES = ["red", "yellow", "green", "unknown"];
const DIRECTION_MATCH = ["yes", "no", "unknown"];
const VEHICLE_ACTIVITY = ["yes", "no", "unknown"];
const CROSSWALK_PRESENT = [true, false];

const FACT_RULES: Record<string, readonly unknown[] | undefined> = {
  "traffic_signal.state": TRAFFIC_SIGNAL_STATES,
  "traffic_signal.direction_match": DIRECTION_MATCH,
  "vehicle.activity": VEHICLE_ACTIVITY,
  "crosswalk.present": CROSSWALK_PRESENT,
};

const TRAFFIC_CAPABILITY = "vision.traffic_signal";
/** What a crossing decision acts on, and what an untrusted capture must not state as observed. */
const SAFETY_FACT_NAMES = ["traffic_signal.state", "traffic_signal.direction_match", "vehicle.activity"];

/** Longest clock skew a capture timestamp may carry and still be called trusted. */
const CAPTURE_SKEW_MS = 5_000;

const SHARED_RULES = [
  "只输出一个 JSON 对象，不要 Markdown，不要解释。",
  '字段：{"summary": 一句话中文总结, "confidence": "high"|"medium"|"low"|"unknown", "facts": [{"name": 事实名, "value": 事实值, "confidence": 同上}], "limitations": [字符串]}。',
  "看不到、不确定、画面模糊时就降低 confidence 并留空 facts；不要猜测，不要编造。不得给出任何安全通行保证。",
];

const PROMPTS: Record<string, string> = {
  "vision.scene": `描述这一帧里的场景，facts 只用这些名字：scene.description(字符串), scene.obstacle(字符串), scene.hazard(字符串)。`,
  "vision.entrance": `描述画面中的入口：门的位置、台阶、扶手、障碍。facts 只用这些名字：entrance.description(字符串), entrance.steps(整数或字符串), entrance.door_side("left"|"right"|"center"|"unknown"), entrance.hazard(字符串)。`,
  "vision.expression": `只描述画面中单帧可见的表情特征，不要推测情绪或意图。facts 只用这些名字：expression.visible_state(字符串), expression.description(字符串)。`,
  "vision.traffic_signal": `只报告画面中可见的交通信号、斑马线与车辆。facts 只用这些名字：traffic_signal.state("red"|"yellow"|"green"|"unknown"), traffic_signal.direction_match("yes"|"no"|"unknown"), crosswalk.present(true|false), vehicle.activity("yes"|"no"|"unknown")。` +
    `direction_match 只在画面本身能证明摄像头正对行进方向时才填 "yes" 或 "no"；只凭行进方向数值不足以判断，无法确认一律用 "unknown"。无法确认的其他字段也一律用 "unknown"。`,
};

const OCR_PROMPT =
  "识别画面中的全部文字，按阅读顺序逐行输出。" +
  '只输出一个 JSON 对象：{"lines":[{"text":字符串,"confidence":0到1之间的小数}]}，不要解释，不要翻译。';

/** The device answered a string this adapter could not read as JSON at all. */
const UNSTRUCTURED = Symbol("vision.unstructured");

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function parseJsonString(value: unknown): unknown {
  if (typeof value !== "string") return value;
  const text = value.trim();
  const fenced = /^```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```$/i.exec(text);
  try {
    return JSON.parse(fenced ? fenced[1]! : text);
  } catch {
    return undefined;
  }
}

function confidence(value: unknown): Confidence {
  return CONFIDENCES.includes(value as Confidence) ? value as Confidence : "unknown";
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

let factValidator: ((fact: Record<string, unknown>) => boolean) | undefined;

/** The shared fact.schema.json compiled once per bundle, not once per observation. */
function isValidFact(fact: Record<string, unknown>): boolean {
  factValidator ??= (() => {
    const validate = createBrowserArgumentValidator();
    return (candidate: Record<string, unknown>) => validate("fact.schema.json", candidate).valid;
  })();
  return factValidator(fact);
}

/** A heading is only usable as this device's travel direction when it is a real compass bearing. */
function isHeadingDeg(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value < 360;
}

/**
 * The navigation context the model may be told about: the intersection the device said the user is
 * approaching, and the direction the user is travelling. Both are values this device established;
 * neither says anything about where the camera is pointing.
 */
function verifiedNavigationContext(context: unknown): string | undefined {
  if (!isRecord(context)) return undefined;
  const intersectionId = isNonEmptyString(context.intersection_id) ? context.intersection_id.trim() : undefined;
  const headingDeg = isHeadingDeg(context.travel_heading_deg) ? context.travel_heading_deg : undefined;
  if (intersectionId === undefined && headingDeg === undefined) return undefined;
  const parts = [
    intersectionId === undefined ? undefined : `路口=${intersectionId}`,
    headingDeg === undefined ? undefined : `行进方向=${headingDeg}°`,
  ].filter(isNonEmptyString);
  return `导航上下文（设备已确认，仅描述本段路线）：${parts.join("，")}。` +
    `这只是行进方向，不代表摄像头朝向；direction_match 仍只按画面证据填写。`;
}

/**
 * When the device's own report says the frame was captured, that instant is when the observation
 * happened — not the moment inference finished. A capture with no readable timestamp, or one whose
 * freshness window has already closed, is not evidence a safety-critical fact may rest on.
 */
function captureInstant(
  context: unknown,
  now: number,
  validUntilMs: number,
): { iso: string; trusted: boolean; expired: boolean } | undefined {
  const raw = isRecord(context) ? context.captured_at : undefined;
  if (!isNonEmptyString(raw)) return undefined;
  const parsed = Date.parse(raw);
  if (!Number.isFinite(parsed)) return undefined;
  const trusted = parsed <= now + CAPTURE_SKEW_MS;
  return { iso: new Date(parsed).toISOString(), trusted, expired: parsed + validUntilMs <= now };
}

/**
 * Turns a decoded vision payload into the repository's Fact contract.
 * Every fact is schema-validated and constrained to its capability's vocabulary, so the caller can
 * never turn a free-form sentence into a fact the crossing policy would act on.
 *
 * Two things a crossing decision must never gain from this function: a direction match inferred
 * from a heading number instead of from the picture, and a traffic state reported from a frame
 * whose capture time this device could not trust.
 */
export function normalizeVisionFacts(
  capabilityId: string,
  payload: unknown,
  options: {
    validUntil?: string;
    evidence?: string[];
    /** Set only when validated camera evidence shows the camera faced the travel heading. */
    directionEvidence?: string;
    /** Set when the capture instant is missing, unreadable or already stale. */
    untrustedCapture?: boolean;
  } = {},
): { facts: Fact[]; invalid: boolean } {
  const allowed = CAPABILITY_FACTS[capabilityId] ?? [];
  const entries = isRecord(payload) && Array.isArray(payload.facts) ? payload.facts : [];
  const facts: Fact[] = [];
  let invalid = false;
  for (const entry of entries) {
    if (!isRecord(entry)) { invalid = true; continue; }
    const name = entry.name;
    if (typeof name !== "string" || !allowed.includes(name)) { invalid = true; continue; }
    const rules = FACT_RULES[name];
    let value = entry.value;
    const extraEvidence: string[] = [];
    if (capabilityId === TRAFFIC_CAPABILITY && options.untrustedCapture) {
      // The frame may be old, and its own timestamp is the only thing that could have said how old.
      // A crossing fact from it is not observed evidence: it is unknown, and a crosswalk this
      // device cannot date is not reported at all rather than reported as absent.
      if (name === "crosswalk.present") { invalid = true; continue; }
      if (SAFETY_FACT_NAMES.includes(name)) value = "unknown";
    }
    // A direction match is only meaningful against validated camera evidence. A travel heading is
    // not that evidence: it says which way the user is walking, never which way the camera faced,
    // so a "yes" the model volunteered beside a heading stays "unknown".
    if (name === "traffic_signal.direction_match") {
      if (options.directionEvidence) extraEvidence.push(options.directionEvidence);
      else value = "unknown";
    }
    if (rules && !rules.includes(value)) { invalid = true; continue; }
    if (value === undefined || value === null || value === "") { invalid = true; continue; }
    const evidence = [...(options.evidence ?? []), ...extraEvidence];
    const fact: Record<string, unknown> = {
      name, value, confidence: confidence(entry.confidence), source: "vision",
      ...(evidence.length ? { evidence } : {}),
    };
    if (options.validUntil) fact.valid_until = options.validUntil;
    if (!isValidFact(fact)) { invalid = true; continue; }
    facts.push(fact as unknown as Fact);
  }
  return { facts, invalid };
}

/**
 * Native vision client. It speaks the device API (`vision.observe` with `{mediaRef, prompt}`), asks
 * for the repository's JSON shape, and refuses to hand the adapters anything it could not parse.
 * The older prompt-less device build answered `{summary}`; that answer is a placeholder sentence
 * with no facts, which the adapters treat as "needs retake" rather than as evidence.
 */
export function createNativeVisionClient(
  observe: VisionObserve,
  options: { validUntilMs?: number; now?: () => number } = {},
): { vlm: VlmClient; ocr: OcrClient } {
  const now = options.now ?? (() => Date.now());
  const validUntilMs = options.validUntilMs ?? 60_000;

  const call = async (mediaRef: string, prompt: string): Promise<unknown> => {
    // The port hands back a raw JSON string. What cannot be read as JSON is marked as such instead
    // of being passed on as a payload, so no unparsed text can ever reach the fact vocabulary.
    const parsed = parseJsonString(await observe({ mediaRef, prompt }));
    return parsed === undefined ? UNSTRUCTURED : parsed;
  };

  const vlm: VlmClient = {
    async analyze(capabilityId, mediaRefs, context): Promise<VlmAnalysis> {
      const mediaRef = mediaRefs[0];
      if (typeof mediaRef !== "string" || !mediaRef) {
        return { summary: "没有可用的图像。", confidence: "unknown", facts: [], limitations: ["capture_missing"] };
      }
      const navigationContext = capabilityId === TRAFFIC_CAPABILITY
        ? verifiedNavigationContext(context) : undefined;
      const prompt = [SHARED_RULES.join("\n"), PROMPTS[capabilityId] ?? "", navigationContext ?? ""]
        .filter((part) => part.length > 0).join("\n");
      const payload = await call(mediaRef, prompt);
      if (!isRecord(payload) || typeof payload.summary !== "string" || !Array.isArray(payload.facts)) {
        // Either the device answered a sentence it could not structure, or it answered text this
        // adapter could not parse. Both are "no facts": a summary is never evidence by itself.
        const legacy = payload === UNSTRUCTURED ? undefined : (isRecord(payload) ? payload.summary : undefined);
        return {
          summary: typeof legacy === "string" && legacy ? legacy : "设备没有返回可用的观察结果。",
          confidence: "unknown",
          facts: [],
          limitations: [payload === UNSTRUCTURED ? "vision_result_unparsed" : "vision_result_not_structured"],
        };
      }
      const inferenceAt = now();
      const capture = captureInstant(context, inferenceAt, validUntilMs);
      // Facts expire from the moment the device captured the frame; without a timestamp the newest
      // honest bound is the inference itself, and a safety-critical capability refuses even that.
      const validUntil = capture
        ? new Date(Date.parse(capture.iso) + validUntilMs).toISOString()
        : new Date(inferenceAt + validUntilMs).toISOString();
      const untrustedCapture = !capture || !capture.trusted || capture.expired;
      const { facts, invalid } = normalizeVisionFacts(capabilityId, payload, {
        validUntil,
        untrustedCapture: capabilityId === TRAFFIC_CAPABILITY && untrustedCapture,
        ...(capture ? { evidence: [`capture:${capture.iso}`] } : {}),
        // No validated camera-orientation evidence exists in this build, so a direction match is
        // never unlocked here — not by the travel heading, and not by the model's own answer.
      });
      const limitations = Array.isArray(payload.limitations)
        ? payload.limitations.filter(isNonEmptyString) : [];
      if (invalid) limitations.push("vision_fields_rejected");
      if (capabilityId === TRAFFIC_CAPABILITY && untrustedCapture) {
        limitations.push(capture?.expired ? "capture_expired" : "capture_time_untrusted");
      } else if (capture?.expired) {
        limitations.push("capture_expired");
      }
      // A traffic observation this device could not date is not a confident one, whatever the model
      // called it: its facts are the conservative "unknown" it was given, so the result is one the
      // adapters report as needing a retake rather than as a reading.
      const reported = capabilityId === TRAFFIC_CAPABILITY && untrustedCapture
        ? "unknown" as Confidence : confidence(payload.confidence);
      return {
        summary: payload.summary,
        confidence: facts.length === 0 ? "unknown" : reported,
        facts,
        limitations,
      };
    },
  };

  const ocr: OcrClient = {
    async recognize(mediaRef): Promise<OcrLine[]> {
      const payload = await call(mediaRef, OCR_PROMPT);
      const lines = isRecord(payload) && Array.isArray(payload.lines) ? payload.lines : [];
      return lines.flatMap((line) => {
        if (!isRecord(line) || typeof line.text !== "string" || !line.text.trim()) return [];
        const value = typeof line.confidence === "number" && Number.isFinite(line.confidence)
          ? Math.min(Math.max(line.confidence, 0), 1) : 0;
        return [{ text: line.text, confidence: value }];
      });
    },
  };

  return { vlm, ocr };
}
