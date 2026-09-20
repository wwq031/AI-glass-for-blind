/**
 * 假导航提供者（FakeNavigationProvider）。
 *
 * 没有高德 key、没有网络、没有真机时，用写死的数据跑通完整链路。
 * 它和真实高德适配器实现同一个 NavigationProvider 接口，业务代码无感切换。
 */

import type {
  DestinationCandidates,
  DestinationQuery,
  NavigationProvider,
  SearchOptions,
  WalkingRoute,
} from "../providers/navigation/navigation-provider.ts";

export class FakeNavigationProvider implements NavigationProvider {
  async search(query: DestinationQuery, _options?: SearchOptions): Promise<DestinationCandidates> {
    return {
      schema_version: "1.0",
      query_id: query.query_id,
      session_id: query.session_id,
      generated_at: new Date().toISOString(),
      candidates: [
        {
          candidate_id: "poi-001",
          name: "人民公园",
          address: "示例路 1 号",
          category: "公园",
          distance_m: 820,
          location: { lng: 116.406, lat: 39.912 },
          provider: "fake",
        },
        {
          candidate_id: "poi-002",
          name: "人民公园(南门)",
          address: "示例路 5 号",
          category: "公园",
          distance_m: 900,
          location: { lng: 116.4072, lat: 39.9112 },
          provider: "fake",
        },
      ],
    };
  }

  async walkingRoute(
    _origin: { lng: number; lat: number },
    _destination: { lng: number; lat: number }
  ): Promise<WalkingRoute> {
    return {
      distance_m: 820,
      duration_sec: 720,
      steps: [
        {
          instruction: "从起点沿示例路向东步行 200 米",
          road: "示例路",
          distance_m: 200,
          orientation: "东",
          action: "直行",
        },
        {
          instruction: "在人民路口右转",
          road: "人民路",
          distance_m: 20,
          orientation: "南",
          action: "右转",
        },
        {
          instruction: "沿人民路向南步行 600 米，到达人民公园",
          road: "人民路",
          distance_m: 600,
          orientation: "南",
          action: "直行",
        },
      ],
    };
  }
}
