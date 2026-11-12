import { decodeAgentPlan } from "../../../../packages/domain/agent/contract-codecs.ts";
import type { AgentPlan } from "../../../../packages/domain/agent/types.ts";

export interface PlanMetadata {
  planId: string;
  sessionId: string;
  eventId: string;
  createdAt: string;
}

/** 模型失败与语音识别失败分开；只把已知错误代号翻译为提示。 */
export function modelFailureSpeech(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  const code = /\bcloud_[a-z_]+\b/.exec(message)?.[0];
  switch (code) {
    case "cloud_auth": case "cloud_key_missing": case "cloud_key_unreadable":
      return "DeepSeek 密钥不可用，请在手机的对话模型设置中检查。";
    case "cloud_balance": return "DeepSeek 余额不足，请检查账户，或在手机切回免费模式。";
    case "cloud_busy": return "DeepSeek 当前繁忙，请稍后再试。";
    case "cloud_timeout": return "DeepSeek 回答超时，请稍后再试。";
    case "cloud_network": return "暂时连不上 DeepSeek，请检查手机网络。";
    case "cloud_http": return "DeepSeek 服务请求失败，请稍后再试。";
    case "cloud_format": return "模型回答格式有误，这次没有执行操作，请重试。";
    default: return "模型这次没有生成可用的回答，请重试。";
  }
}

export function parseModelPlanResponse(response: string, metadata?: PlanMetadata): AgentPlan {
  const text = response.trim();
  const fenced = /^```(?:json)?[ \t]*\r?\n([\s\S]*?)\r?\n```$/i.exec(text);
  const json = fenced ? fenced[1]! : text;
  const proposed: unknown = JSON.parse(json);
  if (!metadata || proposed === null || typeof proposed !== "object" || Array.isArray(proposed)) {
    return decodeAgentPlan(proposed);
  }
  return decodeAgentPlan({
    ...proposed,
    schema_version: "1.0",
    plan_id: metadata.planId,
    session_id: metadata.sessionId,
    event_id: metadata.eventId,
    created_at: metadata.createdAt,
  });
}

export async function generateValidatedPlan(
  prompt: string,
  generate: (prompt: string) => Promise<string>,
  metadata?: PlanMetadata,
): Promise<AgentPlan> {
  const first = await generate(prompt);
  try {
    return parseModelPlanResponse(first, metadata);
  } catch (error) {
    const repairPrompt = [
      prompt,
      "上一轮输出未通过 AgentPlan 合同校验。只修正 JSON 格式和动作字段类型，保持原意，不添加解释。",
      `校验错误：${String(error)}`,
      `上一轮输出：${first.slice(0, 4000)}`,
      metadata
        ? '再次强调：只输出 goal 和 actions；speak.priority 必须是 "critical"、"high"、"normal"、"detail" 之一。'
        : '再次强调：schema_version 必须是字符串 "1.0"；speak.priority 必须是 "critical"、"high"、"normal"、"detail" 之一。',
    ].join("\n");
    return parseModelPlanResponse(await generate(repairPrompt), metadata);
  }
}
