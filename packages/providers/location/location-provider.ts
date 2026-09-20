/**
 * 定位提供者接口。
 *
 * 负责给出"我的当前坐标"，来源可以是 GPS、基站、Wi-Fi 或假数据。
 * 只输出坐标事实，不做任何导航决策。
 * 真机上可由高德定位 SDK 或系统 LocationManager 实现；开发期用 FakeLocationProvider。
 */

import type { GeoPoint } from "../geo.ts";

export interface LocationProvider {
  /** 获取一次当前坐标 */
  getCurrentLocation(): Promise<GeoPoint>;
}
