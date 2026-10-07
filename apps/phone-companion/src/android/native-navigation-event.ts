import type { AgentEvent } from "../../../../packages/domain/agent/types.ts";

export interface NativeNavigationPayload {
  sessionId: string;
  kind: string;
  detail?: string;
  remainDistanceM?: number;
  weak?: boolean;
}

export function nativeNavigationEvent(
  native: NativeNavigationPayload, eventId: string, sequence: number, occurredAt: string,
): AgentEvent {
  if (!native.sessionId || !eventId || !Number.isSafeInteger(sequence) || sequence < 0) {
    throw new Error("invalid native navigation identity");
  }
  const base = {
    eventId, sessionId: native.sessionId, sequence, occurredAt,
    source: "navigation" as const, sourceDetail: "navigation" as const,
  };
  switch (native.kind) {
    case "started":
      return { ...base, type: "navigation.started", payload: { route_state: "active", provider: "amap" } };
    case "navigation_text":
      if (!native.detail?.trim()) throw new Error("navigation text is empty");
      return { ...base, type: "navigation.approaching_maneuver", payload: {
        route_state: "active", instruction: native.detail.trim(), provider: "amap",
      } };
    case "progress":
      if (typeof native.remainDistanceM !== "number" || !Number.isFinite(native.remainDistanceM) ||
          native.remainDistanceM < 0) throw new Error("navigation progress distance is invalid");
      return { ...base, type: "navigation.approaching_maneuver", payload: {
        route_state: "active", distance_m: native.remainDistanceM, provider: "amap",
      } };
    case "off_route":
      return { ...base, type: "navigation.off_route", payload: { route_state: "rerouting", provider: "amap" } };
    case "rerouted":
      return { ...base, type: "navigation.rerouting", payload: { route_state: "rerouting", provider: "amap" } };
    case "location_weak":
      return { ...base, type: "navigation.location_quality_changed", payload: {
        route_state: native.weak === false ? "active" : "weak_location",
        location_confidence: native.weak === false ? "medium" : "low", provider: "amap",
      } };
    case "arrived":
      return { ...base, type: "navigation.arrived", payload: { route_state: "arrived", provider: "amap" } };
    case "route_failed":
      return { ...base, type: "navigation.stopped", payload: {
        route_state: "stopped", instruction: native.detail ?? "导航失败", provider: "amap",
      } };
    default:
      throw new Error(`unsupported native navigation callback: ${native.kind}`);
  }
}
