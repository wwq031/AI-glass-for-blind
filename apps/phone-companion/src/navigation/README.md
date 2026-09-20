# 导航最小纵向切片（手机端 × 高德地图）

这是"盲人指路"的第一步可运行切片，对应仓库文档里"负责人 B：导航"的最小闭环：

```
输入目的地 → 找候选 POI → 算步行路线 → 输出指路人话
```

## 怎么跑

```bash
pnpm install   # 第一次先装依赖
pnpm dev       # 没有高德 key 时走内置假数据，直接就能看到指路文本
```

## 接真实高德（申请到 key 之后）

最简单的方式——把 key 写进项目根目录的 `.env` 文件：

1. 复制 `.env.example` 为 `.env`；
2. 把 key 填到等号右边：`AMAP_KEY=你的key`；
3. 运行 `pnpm dev`。

（`.env` 已被 `.gitignore` 忽略，不会把 key 提交进仓库。也可以改用环境变量 `AMAP_KEY`，效果一样。）

## 文件说明

| 文件 | 作用 |
|---|---|
| `map-adapter.ts` | 高德 Web 服务 API 适配器（POI 搜索 + 步行路径规划），未来可替换成 Android SDK 实现，接口不变 |
| `demo.ts` | 最小闭环演示入口 |
| `packages/providers/navigation/navigation-provider.ts` | `NavigationProvider` 接口与合同类型（对齐 `packages/contracts` 的 JSON Schema） |
| `packages/testkit/fake-navigation-provider.ts` | 无 key / 无网络时的假数据替身 |

## 已知缺口

- ✅ 已补：候选 POI 的 `location` 经纬度字段（`destination-candidates.schema.json` + 适配器 + 假数据已同步）。
- ⏳ 待补：**起点坐标**目前仍是演示写死的坐标。真实产品中起点应来自手机定位模块（`location-service`），这是下一步要接的能力。
