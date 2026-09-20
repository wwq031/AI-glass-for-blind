/**
 * 通用地理类型，被导航、定位等 provider 共用。
 */

/** 地理坐标点（经度/纬度），供应商无关 */
export interface GeoPoint {
  lng: number;
  lat: number;
}
