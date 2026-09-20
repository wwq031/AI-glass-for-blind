/**
 * 假定位提供者（FakeLocationProvider）。
 *
 * 没有真机 GPS 时，返回写死的演示坐标。与真实定位实现共用 LocationProvider 接口。
 */

import type { GeoPoint } from "../providers/geo.ts";
import type { LocationProvider } from "../providers/location/location-provider.ts";

export class FakeLocationProvider implements LocationProvider {
  private readonly point: GeoPoint;

  constructor(point: GeoPoint = { lng: 116.397428, lat: 39.90923 }) {
    this.point = point;
  }

  async getCurrentLocation(): Promise<GeoPoint> {
    // 返回副本，避免外部修改内部状态
    return { ...this.point };
  }
}
