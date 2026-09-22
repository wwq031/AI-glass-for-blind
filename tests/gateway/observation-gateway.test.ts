import assert from "node:assert/strict";
import test from "node:test";

import { ObservationGateway } from "../../apps/gateway/src/observation/observation-gateway.ts";
import { OcrObservationAdapter } from "../../apps/gateway/src/observation/ocr-adapter.ts";
import { VlmObservationAdapter } from "../../apps/gateway/src/observation/vlm-adapter.ts";
import type { ObservationRequest } from "../../packages/providers/observation/observation-provider.ts";

const capabilityIds = [
  "vision.scene",
  "vision.entrance",
  "vision.menu",
  "vision.expression",
  "vision.traffic_signal",
];
const registrations = capabilityIds.map((id) => ({ id, provider: "vision-router", consent: "explicit" as const }));
const vlm = new VlmObservationAdapter({
  async analyze(capabilityId) {
    return {
      summary: "已生成结构化观察结果。",
      confidence: "high",
      facts: [{ name: `${capabilityId}.visible`, value: true, confidence: "high", source: "fake-vlm" }],
    };
  },
});
const ocr = new OcrObservationAdapter({
  async recognize() {
    return [{ text: "清汤面 18元", confidence: 0.95 }];
  },
});
const analyzers = new Map(capabilityIds.map((id) => [id, id === "vision.menu" ? ocr : vlm]));
const gateway = new ObservationGateway(registrations, analyzers);

function request(capabilityId: string, consent: ObservationRequest["consent"] = "explicit"): ObservationRequest {
  return {
    schema_version: "1.0",
    session_id: "session-1",
    request_id: `request-${capabilityId}`,
    capability_id: capabilityId,
    trigger: "user_button",
    media_refs: ["media://image-1"],
    consent,
  };
}

test("all five registered capabilities route without scenario branches", () => {
  assert.deepEqual(capabilityIds.map((id) => gateway.supports(id)), [true, true, true, true, true]);
});

test("OCR menu output is normalized into facts", async () => {
  const result = await gateway.observe(request("vision.menu"));
  assert.equal(result.status, "succeeded");
  assert.equal(result.facts[0].name, "menu.text_line");
  assert.equal(result.facts[0].value, "清汤面 18元");
});

test("VLM output preserves the requested capability and structured facts", async () => {
  const result = await gateway.observe(request("vision.entrance"));
  assert.equal(result.capability_id, "vision.entrance");
  assert.equal(result.facts[0].source, "fake-vlm");
});

test("explicit-consent capability is rejected without explicit consent", async () => {
  const result = await gateway.observe(request("vision.expression", "preauthorized"));
  assert.equal(result.status, "failed");
  assert.deepEqual(result.facts, []);
});

test("unregistered capability returns an unsupported result", async () => {
  const result = await gateway.observe(request("vision.unknown"));
  assert.equal(result.status, "unsupported");
});
