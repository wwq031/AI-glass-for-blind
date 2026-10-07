import type { AgentEvent } from "./types.ts";

export interface NavigationSkillContext {
  urgentSkillId?: "crossing_advisory";
  rejected?: true;
  navigation?: { intersectionId?: string; distanceM?: number; travelHeadingDeg?: number };
}

export const DEFAULT_NAVIGATION_TRIGGER_MAX_AGE_MS = 15_000;

export function isCrossingApproachEvent(event: AgentEvent): boolean {
  return event.source === "navigation" &&
    (event.type === "navigation.intersection_approaching" || event.type === "navigation.crosswalk_approaching");
}

/** A route event contributes planning context; capture remains an explicit planned, authorized action. */
export function skillContextFromNavigationEvent(
  event: AgentEvent,
  options: { now?: string; maxAgeMs?: number } = {},
): NavigationSkillContext {
  if (!isCrossingApproachEvent(event)) return {};
  const now = Date.parse(options.now ?? new Date().toISOString());
  const occurredAt = Date.parse(event.occurredAt);
  const maxAgeMs = options.maxAgeMs ?? DEFAULT_NAVIGATION_TRIGGER_MAX_AGE_MS;
  const payload = event.payload;
  if (!Number.isFinite(now) || !Number.isFinite(occurredAt) ||
      !Number.isFinite(maxAgeMs) || maxAgeMs < 0 ||
      now < occurredAt || now - occurredAt > maxAgeMs ||
      typeof payload.intersection_id !== "string" || payload.intersection_id.trim().length === 0 ||
      typeof payload.travel_heading_deg !== "number" || !Number.isFinite(payload.travel_heading_deg) ||
      payload.travel_heading_deg < 0 || payload.travel_heading_deg >= 360 ||
      (payload.distance_m !== undefined &&
        (typeof payload.distance_m !== "number" || !Number.isFinite(payload.distance_m) || payload.distance_m < 0))) {
    return { rejected: true };
  }
  return {
    urgentSkillId: "crossing_advisory",
    navigation: {
      intersectionId: payload.intersection_id,
      ...(typeof payload.distance_m === "number" && Number.isFinite(payload.distance_m) ? { distanceM: payload.distance_m } : {}),
      travelHeadingDeg: payload.travel_heading_deg,
    },
  };
}
