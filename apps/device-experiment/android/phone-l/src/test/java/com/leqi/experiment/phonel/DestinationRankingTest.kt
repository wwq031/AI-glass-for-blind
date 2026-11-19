package com.leqi.experiment.phonel

import com.leqi.experiment.phonebt.DestinationRanking
import org.junit.Assert.*
import org.junit.Test

class DestinationRankingTest {
    @Test fun barePlacePrefersMatchingTransitAndLandmarksButExplicitHotelWins() {
        val places = listOf(
            DestinationRanking.Place("新街口金陵酒店", "酒店住宿", 20),
            DestinationRanking.Place("新街口站", "地铁站", 400),
            DestinationRanking.Place("新街口广场", "地标", 300),
            DestinationRanking.Place("其他地铁站", "地铁站", 10),
        )
        assertEquals(listOf(1, 2, 0, 3), DestinationRanking.order("新街口", places))
        assertEquals(0, DestinationRanking.order("新街口金陵酒店", places).first())
        assertEquals(1, DestinationRanking.order("新街口地铁站", places).first())
    }
}
