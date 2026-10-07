import type { AgentTurnInput } from "./llm-agent.ts";

function isSensitiveKey(key: string): boolean {
  const normalized = key.replace(/[_-]/g, "").toLowerCase();
  return /(token|secret|authorization|apikey|password|credential)/.test(normalized)
    || /^(raw)?(bluetooth|ble)(raw)?(packets?|bytes|data)$/.test(normalized)
    || /^raw(media|audio|image|video)/.test(normalized)
    || /(media|audio|image|video).*(bytes|buffer|blob|base64|data)$/.test(normalized);
}

function sanitize(value: unknown, ancestors: WeakSet<object> = new WeakSet()): unknown {
  if (value instanceof ArrayBuffer || ArrayBuffer.isView(value)) return undefined;
  if (value !== null && typeof value === "object") {
    if (ancestors.has(value)) return undefined;
    ancestors.add(value);
    try {
      if (Array.isArray(value)) {
        return value.map((item) => sanitize(item, ancestors)).filter((item) => item !== undefined);
      }
      return Object.fromEntries(
        Object.entries(value)
          .filter(([key]) => !isSensitiveKey(key))
          .map(([key, item]) => [key, sanitize(item, ancestors)])
          .filter(([, item]) => item !== undefined)
      );
    } finally {
      ancestors.delete(value);
    }
  }
  return value;
}

/**
 * The one sanitizer every model-bound payload passes through. A caller that keeps state which will
 * later be handed to the model — the conversation trace, for instance — runs it through here as
 * well, so nothing binary or secret is ever held, not only never sent.
 */
export function sanitizeForModel<T>(value: T): T {
  return sanitize(value) as T;
}

export function buildAgentTurnInput(
  input: AgentTurnInput,
  urgentSkillId?: string,
): AgentTurnInput {
  const context = sanitizeForModel(input);
  if (urgentSkillId !== undefined) context.session.urgentSkillId = urgentSkillId;
  return context;
}
