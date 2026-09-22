import type { NavigationEvent } from "../../providers/navigation/navigation-provider.ts";

export interface SpeechEffect {
  schema_version: string;
  effect_id: string;
  session_id: string;
  text: string;
  priority: "critical" | "high" | "normal" | "detail";
  source: "risk" | "navigation" | "observation" | "system" | "user_query";
  interruptible: boolean;
  expires_at: string;
  repeatable?: boolean;
}

export interface ReminderPolicyOptions {
  now?: () => Date;
  idFactory?: () => string;
  ttlMs?: number;
}

export class NavigationReminderPolicy {
  private readonly now: () => Date;
  private readonly idFactory: () => string;
  private readonly ttlMs: number;

  constructor(options: ReminderPolicyOptions = {}) {
    this.now = options.now ?? (() => new Date());
    this.idFactory = options.idFactory ?? (() => crypto.randomUUID());
    this.ttlMs = options.ttlMs ?? 30_000;
  }

  create(event: NavigationEvent): SpeechEffect | undefined {
    const reminder = this.describe(event);
    if (!reminder) return undefined;

    return {
      schema_version: "1.0",
      effect_id: this.idFactory(),
      session_id: event.session_id,
      text: reminder.text,
      priority: reminder.priority,
      source: reminder.source,
      interruptible: reminder.priority !== "critical",
      expires_at: new Date(this.now().getTime() + this.ttlMs).toISOString(),
      repeatable: true,
    };
  }

  private describe(event: NavigationEvent): Pick<SpeechEffect, "text" | "priority" | "source"> | undefined {
    const distance = event.payload.distance_m == null ? "" : `前方约${Math.round(event.payload.distance_m)}米，`;
    const instruction = event.payload.instruction?.trim();

    switch (event.type) {
      case "navigation.started":
        return { text: "导航已开始。", priority: "normal", source: "navigation" };
      case "navigation.approaching_maneuver":
        return instruction
          ? { text: `${distance}${instruction}`, priority: "high", source: "navigation" }
          : undefined;
      case "navigation.intersection_approaching":
      case "navigation.crosswalk_approaching":
        return {
          text: `${distance}即将到达路口，需要检查时请按键或说检查。`,
          priority: "high",
          source: "navigation",
        };
      case "navigation.off_route":
        return { text: "检测到偏离路线，请先停留，正在重新规划。", priority: "critical", source: "risk" };
      case "navigation.rerouting":
        return { text: "正在重新规划路线，请稍候。", priority: "high", source: "navigation" };
      case "navigation.location_quality_changed":
        if (event.payload.location_confidence === "low" || event.payload.location_confidence === "unknown") {
          return { text: "定位信号较弱，请先停留并注意周围环境。", priority: "critical", source: "risk" };
        }
        return undefined;
      case "navigation.arrived":
        return { text: "已到达目的地附近，请按键观察入口。", priority: "high", source: "navigation" };
      case "navigation.stopped":
        return { text: "导航已停止。", priority: "normal", source: "system" };
    }
  }
}
