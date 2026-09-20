/**
 * 导航提供者接口与合同类型。
 *
 * 这里的每个类型都对齐 `packages/contracts/schemas/` 下对应的 JSON Schema，
 * 字段名与约束保持一致。领域层只依赖这些"干净类型"，不依赖高德 SDK 或任何具体供应商。
 */

import type { GeoPoint } from "../geo.ts";

export type { GeoPoint };

/** 对应 destination-query.schema.json —— 一次语音目的地搜索请求 */
export interface DestinationQuery {
  schema_version: string;
  query_id: string;
  session_id: string;
  /** 用户说的原始话，例如"人民公园" */
  transcript: string;
  locale: string;
  requested_at: string;
}

/** 对应 destination-candidates.schema.json 里的 candidates 项 */
export interface DestinationCandidate {
  candidate_id: string;
  name: string;
  address?: string;
  category?: string;
  distance_m?: number;
  /** 候选坐标（新增可选字段，对应 schema 的 location） */
  location?: GeoPoint;
  provider: string;
}

/** 对应 destination-candidates.schema.json —— 候选列表 */
export interface DestinationCandidates {
  schema_version: string;
  query_id: string;
  session_id: string;
  generated_at: string;
  candidates: DestinationCandidate[];
}

/** 对应 navigation-event.schema.json 的 payload */
export interface NavigationEventPayload {
  route_state: "active" | "rerouting" | "weak_location" | "arrived" | "stopped";
  instruction?: string;
  distance_m?: number;
  location_confidence?: "high" | "medium" | "low" | "unknown";
  provider?: string;
  intersection_id?: string;
  maneuver?: "straight" | "left" | "right" | "u_turn" | "unknown";
  travel_heading_deg?: number;
}

/** 对应 navigation-event.schema.json 的 type 枚举 */
export type NavigationEventType =
  | "navigation.started"
  | "navigation.approaching_maneuver"
  | "navigation.intersection_approaching"
  | "navigation.crosswalk_approaching"
  | "navigation.off_route"
  | "navigation.rerouting"
  | "navigation.location_quality_changed"
  | "navigation.arrived"
  | "navigation.stopped";

/** 对应 navigation-event.schema.json —— 导航事件 */
export interface NavigationEvent {
  schema_version: string;
  event_id: string;
  session_id: string;
  sequence: number;
  occurred_at: string;
  source: "navigation";
  type: NavigationEventType;
  payload: NavigationEventPayload;
}

/** 步行路线的一步（高德 direction/walking 的 step 归一化后的结果） */
export interface WalkingStep {
  /** 一步人话指令，例如"沿示例路向东步行200米" */
  instruction: string;
  /** 道路名 */
  road: string;
  /** 这一步的距离（米） */
  distance_m: number;
  /** 方向，例如"东" */
  orientation: string;
  /** 动作，例如"左转""右转""直行" */
  action: string;
}

/** 整条步行路线 */
export interface WalkingRoute {
  /** 全程距离（米） */
  distance_m: number;
  /** 预计耗时（秒） */
  duration_sec: number;
  steps: WalkingStep[];
}

/**
 * 导航提供者接口。
 *
 * 对齐 `packages/contracts/interfaces.md` 的 NavigationProvider。
 * 这是第一步的最小切片，先实现"找地方 + 算步行路线"；
 * confirm / start / stop / currentState / subscribe 后续按需补上。
 * 只产生"导航事实"，不负责播报、不负责 Agent 决策。
 */
/** 搜索选项 */
export interface SearchOptions {
  /** 以该坐标为中心搜索，返回结果按距离排序；传"我的位置"即可只搜附近的地点 */
  near?: GeoPoint;
}

export interface NavigationProvider {
  /** 把语音目的地转为候选 POI（高德：place/text） */
  search(query: DestinationQuery, options?: SearchOptions): Promise<DestinationCandidates>;

  /** 计算步行路线（高德：direction/walking） */
  walkingRoute(origin: GeoPoint, destination: GeoPoint): Promise<WalkingRoute>;
}
