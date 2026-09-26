import type { AgentPlan, SkillRegistry } from "./types.ts";

export type PlanValidation =
  | { ok: true }
  | {
      ok: false;
      code: "unknown_skill" | "tool_not_allowed" | "consent_required" | "policy_required" | "too_many_actions";
      actionIndex: number;
    };

export interface ExecutionPermissions {
  observationConsent: "none" | "explicit" | "preauthorized";
}

function containsOrigin(value: unknown, seen = new WeakSet<object>()): boolean {
  if (value === null || typeof value !== "object" || seen.has(value)) return false;
  seen.add(value);

  return Object.entries(value).some(([key, nested]) => {
    const parts = key.toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
    return parts.some((part) => part === "origin" || part.endsWith("origin")) ||
      containsOrigin(nested, seen);
  });
}

export function validatePlan(
  registry: SkillRegistry,
  plan: AgentPlan,
  permissions: ExecutionPermissions,
): PlanValidation {
  if (plan.actions.length > 4) {
    return { ok: false, code: "too_many_actions", actionIndex: 4 };
  }

  for (const [actionIndex, action] of plan.actions.entries()) {
    if (action.kind !== "tool_call") continue;

    const skill = registry.get(action.skillId);
    if (!skill) return { ok: false, code: "unknown_skill", actionIndex };

    if (action.toolId === "navigation.start" ||
        !skill.allowedTools.includes(action.toolId) ||
        containsOrigin(action.arguments)) {
      return { ok: false, code: "tool_not_allowed", actionIndex };
    }

    if (action.toolId === "observation.request") {
      if (permissions.observationConsent === "none") {
        return { ok: false, code: "consent_required", actionIndex };
      }
      if (action.skillId === "crossing_advisory" &&
          action.arguments.capability_id !== "vision.traffic_signal") {
        return { ok: false, code: "policy_required", actionIndex };
      }
    }
  }

  return { ok: true };
}
