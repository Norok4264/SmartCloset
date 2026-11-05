package com.smartcloset.data

import kotlin.random.Random

data class OutfitCombination(val top: Clothing, val bottom: Clothing, val outer: Clothing? = null) {
    val key: String get() = "${top.id}:${bottom.id}:${outer?.id ?: 0}"
}

data class RecommendationRules(
    val winterMaxC: Double = 8.0,
    val springMaxC: Double = 17.0,
    val autumnMaxC: Double = 24.0,
    val favoriteBonus: Int = 5,
    val recentlyWornPenalty: Int = 3,
    val recentWearWindowDays: Int = 7
)

data class OutfitOptions(val preferredSeason: String? = null, val recentClothingIds: Set<Long> = emptySet(), val includeOuter: Boolean = false)

sealed interface OutfitGeneration {
    data class Ready(val outfit: OutfitCombination, val alternatives: Int) : OutfitGeneration
    data object MissingTop : OutfitGeneration
    data object MissingBottom : OutfitGeneration
    data object NoCombination : OutfitGeneration
}

/** Rule based generation, deliberately independent from Compose and persistence. */
class OutfitRecommendationEngine(private val rules: RecommendationRules = RecommendationRules()) {
    fun generate(clothes: List<Clothing>, options: OutfitOptions, random: Random, previousKeys: Set<String> = emptySet(), chooseRandom: Boolean = false): OutfitGeneration {
        val tops = clothes.filter { it.category == "상의" }
        val bottoms = clothes.filter { it.category == "하의" }
        if (tops.isEmpty()) return OutfitGeneration.MissingTop
        if (bottoms.isEmpty()) return OutfitGeneration.MissingBottom
        val outers: List<Clothing?> = if (options.includeOuter) clothes.filter { it.category == "아우터" }.map { it as Clothing? }.ifEmpty { listOf(null) } else listOf(null)
        val valid = tops.flatMap { top -> bottoms.flatMap { bottom -> outers.map { outer -> OutfitCombination(top, bottom, outer) } } }
        if (valid.isEmpty()) return OutfitGeneration.NoCombination
        val candidates = valid.filter { it.key !in previousKeys }.ifEmpty { valid }
        val ranked = candidates.sortedByDescending { score(it.top, options) + score(it.bottom, options) + (it.outer?.let { outer -> score(outer, options) } ?: 0) }
        val selected = if (chooseRandom) candidates[random.nextInt(candidates.size)] else ranked.first()
        return OutfitGeneration.Ready(selected, valid.size)
    }

    private fun score(item: Clothing, options: OutfitOptions): Int {
        var score = if (options.preferredSeason != null && options.preferredSeason in item.seasons.split(',')) 100 else 0
        if (item.favorite) score += rules.favoriteBonus
        if (item.id in options.recentClothingIds) score -= rules.recentlyWornPenalty
        return score
    }

    fun seasonForTemperature(celsius: Double): String = when {
        celsius <= rules.winterMaxC -> "겨울"
        celsius <= rules.springMaxC -> "봄"
        celsius <= rules.autumnMaxC -> "가을"
        else -> "여름"
    }
}
