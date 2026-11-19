package com.leqi.experiment.phonebt

/** Ranking is deterministic; the model receives the same ordered, unabridged POI metadata. */
object DestinationRanking {
    data class Place(val name: String, val category: String, val distanceM: Int)
    private val transitSuffix = Regex("(地铁站|地铁|公交站|公交|车站|站)$")
    private val business = Regex("酒店|宾馆|旅馆|餐厅|饭店|超市|商场|医院|学校|公司|银行|小区|大厦")
    private fun normalized(text: String) = text.lowercase().replace(Regex("[\\s（）()·]"), "")

    fun order(query: String, places: List<Place>): List<Int> {
        val target = normalized(query)
        val base = target.replace(transitSuffix, "").ifBlank { target }
        val explicitBusiness = business.containsMatchIn(target)
        fun match(place: Place): Int {
            val name = normalized(place.name)
            return when {
                name == target || name.replace(transitSuffix, "") == base -> 3
                name.startsWith(base) -> 2
                name.contains(base) -> 1
                else -> 0
            }
        }
        fun category(place: Place): Int {
            if (explicitBusiness) return 0
            return when {
                Regex("地铁|公交|车站|交通设施|150[0-9]").containsMatchIn(place.category) -> 2
                Regex("地标|广场|风景名胜|地名地址|110[0-9]|190[0-9]").containsMatchIn(place.category) -> 1
                else -> 0
            }
        }
        return places.indices.sortedWith(compareByDescending<Int> { match(places[it]) }
            .thenByDescending { category(places[it]) }
            .thenBy { places[it].distanceM.takeIf { distance -> distance >= 0 } ?: Int.MAX_VALUE }
            .thenBy { it })
    }
}
