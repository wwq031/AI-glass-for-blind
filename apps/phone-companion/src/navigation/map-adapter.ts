/**
 * 高德地图适配器（AmapNavigationProvider）。
 *
 * 通过高德"Web 服务 API"（纯 HTTP）实现 NavigationProvider 接口：
 *   - 找地方：v3/place/text（POI 关键词搜索）
 *   - 算路线：v3/direction/walking（步行路径规划）
 *
 * 只负责把高德的返回结果归一化成仓库合同类型，不播报、不做 Agent 决策。
 * 未来切换到 Android 原生 SDK 时，只需替换本文件，接口保持不变。
 */

import type {
  DestinationCandidates,
  DestinationQuery,
  GeoPoint,
  NavigationProvider,
  SearchOptions,
  WalkingRoute,
  WalkingStep,
} from "../../../../packages/providers/navigation/navigation-provider.ts";

export interface AmapConfig {
  /** 高德开放平台申请的 Web 服务 key */
  apiKey: string;
  /** 默认走官方地址，测试时可覆盖 */
  baseUrl?: string;
}

export class AmapNavigationProvider implements NavigationProvider {
  private readonly apiKey: string;
  private readonly baseUrl: string;

  constructor(config: AmapConfig) {
    if (!config.apiKey.trim()) {
      throw new Error("AmapNavigationProvider 需要 apiKey，请先申请高德 Web 服务 key");
    }
    this.apiKey = config.apiKey.trim();
    this.baseUrl = config.baseUrl ?? "https://restapi.amap.com";
  }

  async search(query: DestinationQuery, options?: SearchOptions): Promise<DestinationCandidates> {
    const params: Record<string, string> = {
      key: this.apiKey,
      keywords: query.transcript,
      output: "json",
      offset: "5",
    };
    // 传"我的位置"让高德按距离排序，只返回附近的地点
    if (options?.near) {
      params.location = `${options.near.lng},${options.near.lat}`;
    }
    const url = `${this.baseUrl}/v3/place/text?${new URLSearchParams(params)}`;

    const data = await this.request<AmapPlaceResponse>(url, "POI 搜索");

    const candidates = (data.pois ?? []).slice(0, 5).map((poi, i) => ({
      candidate_id: poi.id || `amap-${i + 1}`,
      name: poi.name || "",
      address: poi.address || undefined,
      category: poi.type || undefined,
      distance_m: poi.distance ? Number(poi.distance) : undefined,
      location: parseLocation(poi.location),
      provider: "amap",
    }));

    return {
      schema_version: "1.0",
      query_id: query.query_id,
      session_id: query.session_id,
      generated_at: new Date().toISOString(),
      candidates,
    };
  }

  async walkingRoute(
    origin: { lng: number; lat: number },
    destination: { lng: number; lat: number }
  ): Promise<WalkingRoute> {
    const url = `${this.baseUrl}/v3/direction/walking?${new URLSearchParams({
      key: this.apiKey,
      origin: `${origin.lng},${origin.lat}`,
      destination: `${destination.lng},${destination.lat}`,
    })}`;

    const data = await this.request<AmapDirectionResponse>(url, "步行路径规划");

    const path = data.route?.paths?.[0];
    if (!path) {
      throw new Error("高德未返回可用的步行路线");
    }

    const steps: WalkingStep[] = (path.steps ?? []).map((s) => ({
      instruction: s.instruction || "",
      road: s.road || "",
      distance_m: Number(s.distance ?? 0),
      orientation: s.orientation || "",
      action: s.action || "",
    }));

    return {
      distance_m: Number(path.distance ?? 0),
      duration_sec: Number(path.duration ?? 0),
      steps,
    };
  }

  /** 统一发请求、检查 HTTP 状态和高德业务状态码 */
  private async request<T extends { status: string; info: string }>(
    url: string,
    label: string
  ): Promise<T> {
    const res = await fetch(url);
    if (!res.ok) {
      throw new Error(`高德${label}请求失败：HTTP ${res.status}`);
    }
    const data = (await res.json()) as T;
    if (data.status !== "1") {
      throw new Error(`高德${label}返回错误：${data.info}（code ${data.status}）`);
    }
    return data;
  }
}

/* 高德响应类型（只声明用得到的最小字段） */

interface AmapPlaceResponse {
  status: string;
  info: string;
  pois?: AmapPoi[];
}

interface AmapPoi {
  id?: string;
  name?: string;
  address?: string;
  type?: string;
  distance?: string;
  /** 高德返回的坐标，格式 "经度,纬度" */
  location?: string;
}

/** 把高德的 "lng,lat" 字符串解析成供应商无关的 GeoPoint */
function parseLocation(location?: string): GeoPoint | undefined {
  if (!location) return undefined;
  const [lng, lat] = location.split(",").map(Number);
  if (Number.isNaN(lng) || Number.isNaN(lat)) return undefined;
  return { lng, lat };
}

interface AmapDirectionResponse {
  status: string;
  info: string;
  route?: { paths?: AmapPath[] };
}

interface AmapPath {
  distance?: string;
  duration?: string;
  steps?: AmapStep[];
}

interface AmapStep {
  instruction?: string;
  road?: string;
  distance?: string;
  orientation?: string;
  action?: string;
}
