import type { AgentPlan, SkillRegistry } from "./types.ts";
import toolManifest from "../../providers/registry/tool-registry.json" with { type: "json" };
import capabilityManifest from "../../contracts/capabilities/registry.json" with { type: "json" };

const toolExposure = new Map(toolManifest.tools.map((tool) => [tool.tool_id, tool.exposure]));

export function isPlanToolExposureAllowed(toolId: string, exposure: string | undefined): boolean {
  if (toolId === "navigation.start") return false;
  return exposure === "model" || (toolId === "observation.request" && exposure === "policy");
}

export function isCapabilityCompatibleWithSkill(
  capabilities: readonly { id: string; compatible_skills?: readonly string[] }[],
  capabilityId: unknown,
  skillId: string,
): boolean {
  return typeof capabilityId === "string" && capabilities.some((capability) =>
    capability.id === capabilityId && capability.compatible_skills?.includes(skillId));
}

export type PlanValidation =
  | { ok: true }
  | {
      ok: false;
      code: "invalid_plan" | "unknown_skill" | "tool_not_allowed" | "consent_required" | "policy_required" | "too_many_actions";
      actionIndex: number;
    };

export interface ExecutionPermissions {
  observationConsent: "none" | "explicit" | "preauthorized";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isNonemptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function hasOnlyFields(value: Record<string, unknown>, allowed: readonly string[]): boolean {
  return Object.keys(value).every((key) => allowed.includes(key));
}

function isValidAction(action: unknown): boolean {
  if (!isRecord(action)) return false;

  switch (action.kind) {
    case "tool_call":
      return isNonemptyString(action.skillId) && isNonemptyString(action.toolId) &&
        /^[a-z][a-z0-9_]*$/.test(action.skillId) &&
        /^[a-z][a-z0-9]*(\.[a-z0-9_-]+)+$/.test(action.toolId) &&
        isRecord(action.arguments) &&
        hasOnlyFields(action, ["kind", "skillId", "toolId", "arguments"]);
    case "speak":
      return isNonemptyString(action.text) &&
        ["critical", "high", "normal", "detail"].includes(action.priority as string) &&
        hasOnlyFields(action, ["kind", "text", "priority"]);
    case "wait":
      return Array.isArray(action.eventTypes) && action.eventTypes.length > 0 &&
        action.eventTypes.every(isNonemptyString) &&
        new Set(action.eventTypes).size === action.eventTypes.length &&
        hasOnlyFields(action, ["kind", "eventTypes"]);
    case "complete":
      return isNonemptyString(action.reason) && hasOnlyFields(action, ["kind", "reason"]);
    default:
      return false;
  }
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
  if (!isRecord(plan) || !Array.isArray(plan.actions) ||
      !isNonemptyString(plan.planId) || !isNonemptyString(plan.sessionId) ||
      !isNonemptyString(plan.eventId) || !isNonemptyString(plan.goal) ||
      !isNonemptyString(plan.createdAt) ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/i.test(plan.createdAt) ||
      Number.isNaN(Date.parse(plan.createdAt)) ||
      (plan.responseDraft !== undefined && typeof plan.responseDraft !== "string")) {
    return { ok: false, code: "invalid_plan", actionIndex: 0 };
  }

  if (plan.actions.length > 4) {
    return { ok: false, code: "too_many_actions", actionIndex: 4 };
  }

  const planFields = Object.fromEntries(Object.entries(plan).filter(([key]) => key !== "actions"));
  if (containsOrigin(planFields)) {
    return { ok: false, code: "tool_not_allowed", actionIndex: 0 };
  }
  if (!hasOnlyFields(plan, ["planId", "sessionId", "eventId", "goal", "actions", "responseDraft", "createdAt"])) {
    return { ok: false, code: "invalid_plan", actionIndex: 0 };
  }

  for (const [actionIndex, action] of plan.actions.entries()) {
    if (containsOrigin(action)) {
      return { ok: false, code: "tool_not_allowed", actionIndex };
    }
    if (!isValidAction(action)) {
      return { ok: false, code: "invalid_plan", actionIndex };
    }
    if (action.kind !== "tool_call") continue;

    const skill = registry.get(action.skillId);
    if (!skill) return { ok: false, code: "unknown_skill", actionIndex };

    const exposure = toolExposure.get(action.toolId);
    if (!skill.allowedTools.includes(action.toolId) ||
        !isPlanToolExposureAllowed(action.toolId, exposure)) {
      return { ok: false, code: "tool_not_allowed", actionIndex };
    }

    if (action.toolId === "observation.request") {
      if (permissions?.observationConsent !== "explicit" &&
          permissions?.observationConsent !== "preauthorized") {
        return { ok: false, code: "consent_required", actionIndex };
      }
      const capability = capabilityManifest.capabilities.find(
        ({ id }) => id === action.arguments.capability_id,
      );
      if (!capability) return { ok: false, code: "policy_required", actionIndex };

      if (capability.id === "vision.traffic_signal" &&
          (action.skillId !== "crossing_advisory" || skill.requiredPolicy !== "crossing-advisory")) {
        return { ok: false, code: "policy_required", actionIndex };
      }
      if (action.skillId === "crossing_advisory" &&
          capability.id !== "vision.traffic_signal") {
        return { ok: false, code: "policy_required", actionIndex };
      }
      if (action.skillId === "read_text" &&
          !isCapabilityCompatibleWithSkill(capabilityManifest.capabilities, action.arguments.capability_id, "read_text")) {
        return { ok: false, code: "policy_required", actionIndex };
      }
    }
  }

  return { ok: true };
}
