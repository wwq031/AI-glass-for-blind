# 导航定位最小纵向切片（手机端 × 高德地图）

> 本文是"盲人指路"第一条可运行切片的架构说明，是 [`file-delivery-matrix.md`](../team/file-delivery-matrix.md) 里"负责人 B：导航"的落地起点。它验证了从"说出目的地"到"输出指路语音"整条链路可行，为后续接语音、接真机打底。

## 1. 它做什么

把盲人指路拆成四个可替换的步骤：

```text
① 定位"我的位置"        LocationProvider.getCurrentLocation()
        ↓  {lng, lat}
② 找附近的地方          NavigationProvider.search(query, { near: 我的位置 })
        ↓  候选列表（含经纬度）
③ 算步行路线            NavigationProvider.walkingRoute(我的位置, 目的地)
        ↓  路线步骤
④ 说人话                demo 把步骤逐句打印（将来由 SpeechEffect 播报）
```

关键设计：**先定位、再就近搜索**。搜目的地时把"我在哪"传给高德，让它只返回附近的地点并按距离排序——这是盲人场景的硬需求（用户搜"人民公园"要的是身边那个，不是全国任意一个）。

## 2. 数据流与对应合同

| 步骤 | 输入 | 输出 | 对应合同 Schema |
|---|---|---|---|
| 语音目的地 | 用户说的话 | `DestinationQuery` | `destination-query.schema.json` |
| 候选列表 | `DestinationQuery` + `near` | `DestinationCandidates` | `destination-candidates.schema.json` |
| 导航事件 | 路线步骤 | `NavigationEvent` | `navigation-event.schema.json` |
| 坐标点 | — | `GeoPoint` | 通用几何类型（无独立 Schema） |

所有类型字段与 Schema 一一对应，`packages/domain` 只依赖这些"干净类型"，不依赖高德 SDK。

## 3. 文件清单

| 文件 | 层 | 职责 |
|---|---|---|
| `packages/providers/geo.ts` | 接口 | 通用 `GeoPoint`（导航、定位共用） |
| `packages/providers/location/location-provider.ts` | 接口 | `LocationProvider`：给"我的坐标" |
| `packages/providers/navigation/navigation-provider.ts` | 接口 | `NavigationProvider` + 合同类型 + `SearchOptions` |
| `apps/phone-companion/src/navigation/map-adapter.ts` | 实现 | 高德 Web 服务 API 适配器（`place/text` 搜 POI、`direction/walking` 算步行） |
| `packages/testkit/fake-navigation-provider.ts` | 替身 | 无 key/无网络时的假候选与假路线 |
| `packages/testkit/fake-location-provider.ts` | 替身 | 假定位（返回演示坐标，真机换 GPS） |
| `apps/phone-companion/src/navigation/demo.ts` | 演示 | 串起四步的最小闭环入口 |

**可替换性**：真实高德适配器与假替身实现同一接口，切换只改配置（`.env` 里填 key 即走真实高德，不填走假数据）。真机上，`map-adapter.ts` 可换成 Android 地图 SDK，`fake-location-provider.ts` 可换成高德定位 SDK / 系统定位，业务代码不动。

## 4. 接口

```ts
// 定位
interface LocationProvider {
  getCurrentLocation(): Promise<GeoPoint>;
}

// 导航
interface NavigationProvider {
  search(query: DestinationQuery, options?: SearchOptions): Promise<DestinationCandidates>;
  walkingRoute(origin: GeoPoint, destination: GeoPoint): Promise<WalkingRoute>;
}

interface SearchOptions {
  /** 以该坐标为中心搜索，结果按距离排序 */
  near?: GeoPoint;
}
```

## 5. 合同变更（本切片引入）

- `destination-candidates.schema.json` 的候选新增可选字段 `location: {lng, lat}`（带经纬度范围校验），并同步更新了 `examples/destination-candidates.json`。这是向后兼容的新增字段。

## 6. 运行方式

```bash
pnpm install     # 首次装依赖
pnpm dev         # 走真实高德（需 .env 配好 AMAP_KEY）或内置假数据
pnpm typecheck   # 类型检查
```

Key 配置：复制 `.env.example` 为 `.env`，填 `AMAP_KEY=你的key`。`.env` 已被 `.gitignore` 忽略。

## 7. 已知缺口

1. **起点坐标仍是假定位**：`demo.ts` 用 `FakeLocationProvider` 返回写死坐标；真机阶段换成高德定位 SDK / 系统定位。
2. **候选"距离"字段不准**：高德 `place/text` 关键字搜索时 `distance` 返回 0（只有"周边搜索"才计算真实距离），当前在展示层对 0 做了隐藏。若需要真实距离，可换 `place/around` 接口或 `inputtips`。
3. **尚未接入语音**：目的地目前是代码里写死的字符串，下一步应接入 `speech-input.schema.json`，让用户"说出来"。
4. **无自动化测试**：适配器返回格式尚未用单测锁死。

## 8. 下一步

- 接语音输入（盲人场景最该补的一环）；
- 给 `map-adapter` / `fake` 加单元测试；
- 真机阶段接入高德定位 SDK，替换 `FakeLocationProvider`。
