package com.leqi.experiment.phonebt

import android.content.Context
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.poisearch.PoiSearch

/**
 * 高德搜索 SDK 的地点搜索（同步调用，必须在后台线程跑）。
 *
 * 只负责“拿真实定位换候选点”，不做决策、不播报。返回的候选一定带经纬度，
 * 没有坐标的结果直接丢掉——后面要用坐标去算步行路线。
 *
 * 本类是与 [AmapSearch] 一一对应的 Kotlin 适配层：SDK 调用、错误传播和数据形状都保持一致，
 * 不含任何业务状态机或 Agent 决策。
 */
object AmapSearchAdapter {

    /** 最多报给用户几个候选。候选越多，语音确认越难，所以压到 3 个。 */
    const val MAX_CANDIDATES = 3
    /** 搜索半径，米。 */
    private const val SEARCH_RADIUS_M = 30_000

    /**
     * 一个候选点。
     *
     * 字段用 `@JvmField` 暴露成同名的公开字段，保持 Java 侧 `candidate.name` 这种直接读法不变。
     */
    class Candidate(
        /**
         * 高德可能不给 poiId；原来就不做校验，所以这里保持可空，不引入新的空指针。
         */
        @JvmField val poiId: String?,
        @JvmField val name: String,
        @JvmField val address: String,
        @JvmField val latitude: Double,
        @JvmField val longitude: Double,
        @JvmField val distanceM: Int,
    ) {

        /** 播报用：名称 + 距离；高德没给距离时不编一个。 */
        fun spokenForm(index: Int): String {
            if (distanceM > 0) {
                return "第${index}个，${name}，距离约 ${distanceM} 米"
            }
            return "第${index}个，${name}"
        }
    }

    /**
     * 用真实定位做一次关键字搜索。
     *
     * @throws AMapException 高德搜索失败时原样抛出，由调用方决定怎么播报。
     */
    @JvmStatic
    @Throws(AMapException::class)
    fun search(context: Context, keyword: String, latitude: Double, longitude: Double): List<Candidate> {
        val query = PoiSearch.Query(keyword, "", "")
        query.setPageSize(20)
        query.setPageNum(1)
        query.setCityLimit(false)
        query.setDistanceSort(true)
        query.setLocation(LatLonPoint(latitude, longitude))
        val poiSearch = PoiSearch(context, query)
        poiSearch.setBound(
            PoiSearch.SearchBound(LatLonPoint(latitude, longitude), SEARCH_RADIUS_M, true),
        )
        val result = poiSearch.searchPOI()
        val candidates = ArrayList<Candidate>()
        if (result == null || result.getPois() == null) return candidates
        for (item in result.getPois()) {
            if (item == null) continue
            val point = item.getLatLonPoint()
            val name = item.getTitle()
            if (point == null || name == null || name.trim().isEmpty()) continue
            if (!isUsableCoordinate(point.getLatitude(), point.getLongitude())) continue
            var address = item.getSnippet()
            if (address == null || address.trim().isEmpty()) address = item.getAdName()
            candidates.add(
                Candidate(
                    item.getPoiId(),
                    name.trim(),
                    address?.trim() ?: "",
                    point.getLatitude(),
                    point.getLongitude(),
                    item.getDistance(),
                ),
            )
            if (candidates.size >= MAX_CANDIDATES) break
        }
        return candidates
    }

    /** 高德在无坐标时会给出 (0,0) 或越界值，这类结果不能拿去算路线。 */
    private fun isUsableCoordinate(latitude: Double, longitude: Double): Boolean {
        return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 &&
            !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001)
    }
}
