import type { AgentEvent, AgentPlan, Effect, EventSource, EventSourceDetail, PlanAction } from "./types.ts";

export const DEFAULT_SCHEMA_VERSION = "1.0";

type ContractSource = EventSourceDetail;

export interface AgentPlanContract {
  schema_version: string;
  plan_id: string;
  session_id: string;
  event_id: string;
  goal: string;
  actions: unknown[];
  created_at: string;
  response_draft?: string;
}

export interface AgentEventContract {
  schema_version: string;
  event_id: string;
  session_id: string;
  sequence: number;
  occurred_at: string;
  source: ContractSource;
  type: string;
  trace_id?: string;
  payload: Record<string, unknown>;
}

export interface EffectContract {
  schema_version: string;
  effect_id: string;
  session_id: string;
  plan_id?: string;
  type: Effect["type"];
  created_at: string;
  payload: Record<string, unknown>;
}

const contractToRuntimeSource: Record<ContractSource, EventSource> = {
  glasses: "device",
  phone: "device",
  transport: "device",
  device: "device",
  speech: "user",
  user: "user",
  navigation: "navigation",
  motion: "motion",
  vision: "provider",
  provider: "provider",
  agent: "system",
  system: "system",
  storage: "system",
  simulator: "system",
};

const runtimeToContractSource: Record<EventSource, ContractSource> = {
  user: "user",
  system: "system",
  navigation: "navigation",
  motion: "motion",
  provider: "provider",
  device: "device",
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function record(value: unknown, label: string): Record<string, unknown> {
  if (!isRecord(value)) throw new TypeError(`${label} must be an object`);
  return value;
}

function nonemptyString(value: unknown, label: string): string {
  if (typeof value !== "string" || value.length === 0) {
    throw new TypeError(`${label} must be a non-empty string`);
  }
  return value;
}

function dateTime(value: unknown, label: string): string {
  const parsed = nonemptyString(value, label);
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/i.test(parsed) ||
      Number.isNaN(Date.parse(parsed))) {
    throw new TypeError(`${label} must be an ISO date-time`);
  }
  return parsed;
}

function schemaVersion(value: unknown, label: string): string {
  const parsed = nonemptyString(value, label);
  if (!/^\d+\.\d+$/.test(parsed)) throw new TypeError(`${label} must be a major.minor version`);
  return parsed;
}

function onlyKeys(value: Record<string, unknown>, allowed: readonly string[], label: string): void {
  const unexpected = Object.keys(value).find((key) => !allowed.includes(key));
  if (unexpected) throw new TypeError(`${label} contains unsupported field: ${unexpected}`);
}

function source(value: unknown): EventSource {
  if (typeof value !== "string" || !Object.hasOwn(contractToRuntimeSource, value)) {
    throw new TypeError("event.source is not a supported contract source");
  }
  return contractToRuntimeSource[value as ContractSource];
}

function parsePlanAction(value: unknown, index: number): PlanAction {
  const label = `plan.actions[${index}]`;
  const action = record(value, label);

  switch (action.kind) {
    case "tool_call":
      onlyKeys(action, ["kind", "skill_id", "tool_id", "arguments"], label);
      if (typeof action.skill_id !== "string" || !/^[a-z][a-z0-9_]*$/.test(action.skill_id)) {
        throw new TypeError(`${label}.skill_id is invalid`);
      }
      if (typeof action.tool_id !== "string" || !/^[a-z][a-z0-9]*(\.[a-z0-9_-]+)+$/.test(action.tool_id)) {
        throw new TypeError(`${label}.tool_id is invalid`);
      }
      return {
        kind: "tool_call",
        skillId: action.skill_id,
        toolId: action.tool_id,
        arguments: record(action.arguments, `${label}.arguments`),
      };
    case "speak":
      onlyKeys(action, ["kind", "text", "priority"], label);
      if (!(["critical", "high", "normal", "detail"] as unknown[]).includes(action.priority)) {
        throw new TypeError(`${label}.priority is invalid`);
      }
      return {
        kind: "speak",
        text: nonemptyString(action.text, `${label}.text`),
        priority: action.priority as Extract<PlanAction, { kind: "speak" }>['priority'],
      };
    case "wait":
      onlyKeys(action, ["kind", "event_types"], label);
      if (!Array.isArray(action.event_types) || action.event_types.length === 0 ||
          !action.event_types.every((eventType) => typeof eventType === "string" && eventType.length > 0) ||
          new Set(action.event_types).size !== action.event_types.length) {
        throw new TypeError(`${label}.event_types must be a non-empty unique string array`);
      }
      return { kind: "wait", eventTypes: [...action.event_types] as string[] };
    case "complete":
      onlyKeys(action, ["kind", "reason"], label);
      return { kind: "complete", reason: nonemptyString(action.reason, `${label}.reason`) };
    default:
      throw new TypeError(`${label}.kind is unsupported`);
  }
}

export function decodeAgentPlan(value: unknown): AgentPlan {
  const wire = record(value, "agent plan");
  onlyKeys(wire, [
    "schema_version", "plan_id", "session_id", "event_id", "goal", "actions", "created_at", "response_draft",
  ], "agent plan");
  schemaVersion(wire.schema_version, "agent plan.schema_version");
  if (!Array.isArray(wire.actions)) throw new TypeError("agent plan.actions must be an array");
  if (wire.response_draft !== undefined && typeof wire.response_draft !== "string") {
    throw new TypeError("agent plan.response_draft must be a string when provided");
  }

  const plan: AgentPlan = {
    planId: nonemptyString(wire.plan_id, "agent plan.plan_id"),
    sessionId: nonemptyString(wire.session_id, "agent plan.session_id"),
    eventId: nonemptyString(wire.event_id, "agent plan.event_id"),
    goal: nonemptyString(wire.goal, "agent plan.goal"),
    actions: wire.actions.map(parsePlanAction),
    createdAt: dateTime(wire.created_at, "agent plan.created_at"),
  };
  if (wire.response_draft !== undefined) plan.responseDraft = wire.response_draft;
  return plan;
}

export function decodeAgentEvent(value: unknown): AgentEvent {
  const wire = record(value, "event envelope");
  onlyKeys(wire, [
    "schema_version", "event_id", "session_id", "sequence", "occurred_at", "source", "type", "trace_id", "payload",
  ], "event envelope");
  schemaVersion(wire.schema_version, "event envelope.schema_version");
  if (!Number.isSafeInteger(wire.sequence) || (wire.sequence as number) < 0) {
    throw new TypeError("event envelope.sequence must be a non-negative safe integer");
  }
  if (wire.trace_id !== undefined && typeof wire.trace_id !== "string") {
    throw new TypeError("event envelope.trace_id must be a string");
  }

  const event: AgentEvent = {
    eventId: nonemptyString(wire.event_id, "event envelope.event_id"),
    sessionId: nonemptyString(wire.session_id, "event envelope.session_id"),
    sequence: wire.sequence as number,
    source: source(wire.source),
    type: nonemptyString(wire.type, "event envelope.type"),
    occurredAt: dateTime(wire.occurred_at, "event envelope.occurred_at"),
    payload: record(wire.payload, "event envelope.payload"),
  };
  if (wire.trace_id !== undefined) event.traceId = wire.trace_id;
  event.sourceDetail = wire.source as ContractSource;
  return event;
}

export function encodeAgentEvent(
  event: AgentEvent,
  version = DEFAULT_SCHEMA_VERSION,
): AgentEventContract {
  const contract: AgentEventContract = {
    schema_version: schemaVersion(version, "schema_version"),
    event_id: nonemptyString(event.eventId, "event.eventId"),
    session_id: nonemptyString(event.sessionId, "event.sessionId"),
    sequence: validateSequence(event.sequence),
    occurred_at: dateTime(event.occurredAt, "event.occurredAt"),
    source: event.sourceDetail === undefined
      ? contractSourceForRuntime(event.source)
      : validateSourceDetail(event.sourceDetail, event.source),
    type: nonemptyString(event.type, "event.type"),
    payload: record(event.payload, "event.payload"),
  };
  if (event.traceId !== undefined) contract.trace_id = nonemptyString(event.traceId, "event.traceId");
  return contract;
}

export function encodeEffect(effect: Effect, version = DEFAULT_SCHEMA_VERSION): EffectContract {
  if (!(["speech", "haptic", "device_command", "navigation", "session"] as unknown[]).includes(effect.type)) {
    throw new TypeError("effect.type is unsupported");
  }
  const contract: EffectContract = {
    schema_version: schemaVersion(version, "schema_version"),
    effect_id: nonemptyString(effect.effectId, "effect.effectId"),
    session_id: nonemptyString(effect.sessionId, "effect.sessionId"),
    type: effect.type,
    created_at: dateTime(effect.createdAt, "effect.createdAt"),
    payload: record(effect.payload, "effect.payload"),
  };
  if (effect.planId !== undefined) contract.plan_id = nonemptyString(effect.planId, "effect.planId");
  return contract;
}

export function decodeEffect(value: unknown): Effect {
  const wire = record(value, "effect envelope");
  onlyKeys(wire, ["schema_version", "effect_id", "session_id", "plan_id", "type", "created_at", "payload"], "effect envelope");
  schemaVersion(wire.schema_version, "effect envelope.schema_version");
  if (!("speech haptic device_command navigation session".split(" ")).includes(wire.type as string)) {
    throw new TypeError("effect envelope.type is unsupported");
  }
  const effect: Effect = {
    effectId: nonemptyString(wire.effect_id, "effect envelope.effect_id"),
    sessionId: nonemptyString(wire.session_id, "effect envelope.session_id"),
    type: wire.type as Effect["type"],
    createdAt: dateTime(wire.created_at, "effect envelope.created_at"),
    payload: record(wire.payload, "effect envelope.payload"),
  };
  if (wire.plan_id !== undefined) effect.planId = nonemptyString(wire.plan_id, "effect envelope.plan_id");
  return effect;
}

function validateSequence(value: unknown): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new TypeError("event.sequence must be a non-negative safe integer");
  }
  return value as number;
}

function contractSourceForRuntime(value: unknown): ContractSource {
  if (typeof value !== "string" || !Object.hasOwn(runtimeToContractSource, value)) {
    throw new TypeError("event.source is not a supported runtime source");
  }
  return runtimeToContractSource[value as EventSource];
}

function validateSourceDetail(value: unknown, normalizedSource: EventSource): ContractSource {
  const detail = sourceDetail(value);
  if (contractToRuntimeSource[detail] !== normalizedSource) {
    throw new TypeError("event.sourceDetail does not match event.source");
  }
  return detail;
}

function sourceDetail(value: unknown): ContractSource {
  if (typeof value !== "string" || !Object.hasOwn(contractToRuntimeSource, value)) {
    throw new TypeError("event.sourceDetail is not a supported contract source");
  }
  return value as ContractSource;
}
