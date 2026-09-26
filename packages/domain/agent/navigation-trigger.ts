import type { AgentEvent } from "./types.ts";

export interface NavigationSkillContext {
  urgentSkillId?: "crossing_advisory";
  navigation?: { intersectionId?: string; distanceM?: number; travelHeadingDeg?: number };
}

/** A route event contributes planning context; capture remains an explicit planned, authorized action. */
export function skillContextFromNavigationEvent(event: AgentEvent): NavigationSkillContext {
  if (event.source !== "navigation" ||
      (event.type !== "navigation.intersection_approaching" && event.type !== "navigation.crosswalk_approaching")) return {};
  const payload = event.payload;
  return {
    urgentSkillId: "crossing_advisory",
    navigation: {
      ...(typeof payload.intersection_id === "string" ? { intersectionId: payload.intersection_id } : {}),
      ...(typeof payload.distance_m === "number" && Number.isFinite(payload.distance_m) ? { distanceM: payload.distance_m } : {}),
      ...(typeof payload.travel_heading_deg === "number" && Number.isFinite(payload.travel_heading_deg) ? { travelHeadingDeg: payload.travel_heading_deg } : {}),
    },
  };
}
