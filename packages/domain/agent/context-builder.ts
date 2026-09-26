import type { AgentTurnInput } from "./llm-agent.ts";

function isSensitiveKey(key: string): boolean {
  const normalized = key.replace(/[_-]/g, "").toLowerCase();
  return normalized === "token"
    || normalized === "secret"
    || normalized === "authorization"
    || normalized === "mediabytes"
    || normalized === "rawmedia"
    || normalized === "rawmediabytes"
    || normalized === "rawaudiobytes"
    || normalized === "rawimagebytes"
    || normalized === "rawvideobytes"
    || /^(raw)?(bluetooth|ble)(raw)?packets?$/.test(normalized);
}

function sanitize(value: unknown): unknown {
  if (value instanceof ArrayBuffer || ArrayBuffer.isView(value)) return undefined;
  if (Array.isArray(value)) {
    return value.map(sanitize).filter((item) => item !== undefined);
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .filter(([key]) => !isSensitiveKey(key))
        .map(([key, item]) => [key, sanitize(item)])
        .filter(([, item]) => item !== undefined)
    );
  }
  return value;
}

export function buildAgentTurnInput(
  input: AgentTurnInput,
  urgentSkillId?: string,
): AgentTurnInput {
  const context = sanitize(input) as AgentTurnInput;
  if (urgentSkillId !== undefined) context.session.urgentSkillId = urgentSkillId;
  return context;
}
