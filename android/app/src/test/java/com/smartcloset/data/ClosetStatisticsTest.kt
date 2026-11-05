package com.smartcloset.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ClosetStatisticsTest {
    private fun clothing(category: String, seasons: String, favorite: Boolean = false) = Clothing(
        name = "테스트 의류", photoPath = "sample.jpg", category = category,
        subcategory = null, seasons = seasons, color = null, material = null,
        purchaseDate = null, favorite = favorite, memo = null
    )

    @Test fun emptyClosetHasZeroCountsAndPercentages() {
        val result = ClosetStatistics.calculate(emptyList())
        assertEquals(0, result.total)
        assertEquals(0, result.favoriteCount)
        assertEquals(mapOf("상의" to 0, "하의" to 0, "아우터" to 0), result.byCategory)
        assertEquals(mapOf("상의" to 0, "하의" to 0, "아우터" to 0), result.categoryPercent)
        assertEquals(0, result.bySeason.values.sum())
    }

    @Test fun categoryPercentageUsesAllRegisteredClothesAsDenominator() {
        val result = ClosetStatistics.calculate(listOf(clothing("상의", "봄"), clothing("상의", "가을"), clothing("하의", "여름", true)))
        assertEquals(3, result.total)
        assertEquals(2, result.byCategory["상의"])
        assertEquals(67, result.categoryPercent["상의"])
        assertEquals(33, result.categoryPercent["하의"])
        assertEquals(1, result.favoriteCount)
    }

    @Test fun topsOnlyClosetHasFullTopShareAndZeroForOtherCategories() {
        val result = ClosetStatistics.calculate(listOf(clothing("상의", "봄"), clothing("상의", "여름")))
        assertEquals(2, result.total)
        assertEquals(2, result.byCategory["상의"])
        assertEquals(100, result.categoryPercent["상의"])
        assertEquals(0, result.byCategory["하의"])
        assertEquals(0, result.categoryPercent["아우터"])
    }

    @Test fun multiSeasonClothingCountsInEachSelectedSeasonAndCombination() {
        val result = ClosetStatistics.calculate(listOf(clothing("아우터", "봄,가을")))
        assertEquals(1, result.total)
        assertEquals(1, result.bySeason["봄"])
        assertEquals(1, result.bySeason["가을"])
        assertEquals(2, result.bySeason.values.sum())
        assertEquals(1, result.bySeasonAndCategory[ClosetTargetKey("가을", "아우터")])
    }

    @Test fun shortageIsClampedAndUnsetGoalIsNotReported() {
        val clothes = listOf(clothing("아우터", "가을"), clothing("상의", "봄"))
        val goals = mapOf(
            ClosetTargetKey("가을", "아우터") to 2,
            ClosetTargetKey("봄", "상의") to 0,
            ClosetTargetKey("여름", "하의") to 3,
            ClosetTargetKey("겨울", "하의") to 0
        )
        val result = ClosetStatistics.calculate(clothes, goals)
        assertEquals(1, result.shortages[ClosetTargetKey("가을", "아우터")])
        assertEquals(null, result.shortages[ClosetTargetKey("봄", "상의")])
        assertEquals(3, result.shortages[ClosetTargetKey("여름", "하의")])
        assertEquals(null, result.shortages[ClosetTargetKey("겨울", "하의")])
        assertEquals(null, result.shortages[ClosetTargetKey("봄", "아우터")])
    }
}
