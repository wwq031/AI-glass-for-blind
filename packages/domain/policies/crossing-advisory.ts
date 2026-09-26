import type { ToolResult } from "../agent/types.ts";

export type CrossingAction = "wait" | "recheck" | "proceed_with_caution" | "cannot_determine";
export interface CrossingEvidence {
  trafficSignal?: "red" | "yellow" | "green" | "unknown";
  directionMatch?: "yes" | "no" | "unknown";
  vehicleApproaching?: "yes" | "no" | "unknown";
  confidence?: "high" | "medium" | "low" | "unknown";
  validUntil?: string;
  now: string;
}
export interface CrossingAdvisory { action: CrossingAction; speech: string }

/** Policy output is an aid, never authorization or a guarantee to cross. */
export function adviseCrossing(evidence: CrossingEvidence): CrossingAdvisory {
  const { trafficSignal, directionMatch, vehicleApproaching, confidence, validUntil, now } = evidence;
  const fresh = !!validUntil && Number.isFinite(Date.parse(validUntil)) &&
    Number.isFinite(Date.parse(now)) && Date.parse(validUntil) > Date.parse(now);
  if (!fresh || directionMatch !== "yes" ||
      !["high", "medium", "low"].includes(confidence ?? "")) {
    return { action: "cannot_determine", speech: "请先停下。我无法确认当前路口情况，这只是辅助信息，请向可信的人求助或重新确认。" };
  }
  if (trafficSignal === "red" || vehicleApproaching === "yes") {
    return { action: "wait", speech: "请先停下等待。观察到红灯或接近车辆；这只是辅助信息，请继续自行确认。" };
  }
  if (!["red", "yellow", "green"].includes(trafficSignal ?? "") ||
      !["yes", "no"].includes(vehicleApproaching ?? "")) {
    return { action: "cannot_determine", speech: "请先停下。我无法确认当前路口情况，这只是辅助信息，请向可信的人求助或重新确认。" };
  }
  if (confidence !== "high" || trafficSignal === "yellow") {
    return { action: "recheck", speech: "请先停下，当前观察把握不足，建议重新检查。这只是辅助信息。" };
  }
  return { action: "proceed_with_caution", speech: "观察到对应方向绿灯且未发现接近车辆。此判断仅供辅助，请谨慎确认周围环境，不保证通行安全。" };
}

/** Only registered Provider facts are accepted; a missing fact never becomes a negative observation. */
export function adviseCrossingFromResult(result: ToolResult, now: string): CrossingAdvisory {
  if (result.toolId !== "observation.request" || (result.status !== "succeeded" && result.status !== "partial")) {
    return adviseCrossing({ now });
  }
  const fact = (name: string) => result.facts.find((item) => item.name === name);
  const signal = fact("traffic_signal.state");
  const direction = fact("traffic_signal.direction_match");
  const vehicle = fact("vehicle.activity");
  const fresh = (item: typeof signal) => !!item?.validUntil && Number.isFinite(Date.parse(item.validUntil)) &&
    Date.parse(item.validUntil) > Date.parse(now);
  const confirmed = (item: typeof signal, value: string) => item?.value === value &&
    (item.confidence === "high" || item.confidence === "medium") && fresh(item);
  // A fresh, aligned hazard is enough to say wait; missing unrelated facts must not hide danger.
  if (confirmed(direction, "yes") && (confirmed(signal, "red") || confirmed(vehicle, "yes"))) {
    return { action: "wait", speech: "请先停下等待。观察到红灯或接近车辆；这只是辅助信息，请继续自行确认。" };
  }
  const values = [signal, direction, vehicle];
  const latestExpiry = values.every((item) => item?.validUntil && Number.isFinite(Date.parse(item.validUntil))) ?
    new Date(Math.min(...values.map((item) => Date.parse(item!.validUntil!)))).toISOString() : undefined;
  const confidence = values.every((item) => item?.confidence === "high") ? "high" :
    values.some((item) => item?.confidence === "unknown") ? "unknown" : "low";
  const advisory = adviseCrossing({
    trafficSignal: signal?.confidence === "low" || signal?.confidence === "unknown" ? "unknown" : signal?.value as CrossingEvidence["trafficSignal"],
    directionMatch: direction?.value as CrossingEvidence["directionMatch"],
    vehicleApproaching: vehicle?.confidence === "low" || vehicle?.confidence === "unknown" ? "unknown" : vehicle?.value as CrossingEvidence["vehicleApproaching"],
    confidence, validUntil: latestExpiry, now,
  });
  return result.status === "partial" && advisory.action === "proceed_with_caution" ?
    { action: "recheck", speech: "请先停下，本次观察不完整，建议重新检查。这只是辅助信息。" } : advisory;
}
