package com.smartcloset.data

import kotlin.math.roundToInt

data class ClosetTargetKey(val season: String, val category: String)

data class ClosetStatistics(
    val total: Int,
    val byCategory: Map<String, Int>,
    val categoryPercent: Map<String, Int>,
    val bySeason: Map<String, Int>,
    val bySeasonAndCategory: Map<ClosetTargetKey, Int>,
    val favoriteCount: Int,
    val goals: Map<ClosetTargetKey, Int>,
    val shortages: Map<ClosetTargetKey, Int>
) {
    companion object {
        val categories = listOf("상의", "하의", "아우터")
        val seasons = listOf("봄", "여름", "가을", "겨울")

        fun calculate(clothes: List<Clothing>, goals: Map<ClosetTargetKey, Int> = emptyMap()): ClosetStatistics {
            val categoryCounts = categories.associateWith { category -> clothes.count { it.category == category } }
            val seasonCounts = seasons.associateWith { season -> clothes.count { season in it.seasons.split(',') } }
            val matrix = buildMap {
                seasons.forEach { season -> categories.forEach { category ->
                    put(ClosetTargetKey(season, category), clothes.count { it.category == category && season in it.seasons.split(',') })
                } }
            }
            val percentages = categoryCounts.mapValues { (_, count) -> if (clothes.isEmpty()) 0 else (count * 100.0 / clothes.size).roundToInt() }
            val shortages = goals.mapNotNull { (key, goal) ->
                if (goal < 0) null else (goal - (matrix[key] ?: 0)).coerceAtLeast(0).takeIf { it > 0 }?.let { key to it }
            }.toMap()
            return ClosetStatistics(
                total = clothes.size,
                byCategory = categoryCounts,
                categoryPercent = percentages,
                bySeason = seasonCounts,
                bySeasonAndCategory = matrix,
                favoriteCount = clothes.count { it.favorite },
                goals = goals.filterValues { it >= 0 },
                shortages = shortages
            )
        }
    }
}
