package com.smartcloset.data

import org.junit.Assert.assertEquals
import org.junit.AssertTrue
import org.junit.Test
import kotlin.random.Random

class OutfitRecommendationTest {
    private fun clothing(id: Long, category: String, seasons: String = "봄", favorite: Boolean = false) = Clothing(
        id = id, name = "의류 $id", photoPath = "photo-$id.jpg", category = category,
        subcategory = null, seasons = seasons, color = null, material = null,
        purchaseDate = null, favorite = favorite, memo = null
    )

    @Test fun missingRequiredCategoriesAreReported() {
        val engine = OutfitRecommendationEngine()
        assertEquals(OutfitGeneration.MissingTop, engine.generate(listOf(clothing(2, "하의")), OutfitOptions(), Random(1)))
        assertEquals(OutfitGeneration.MissingBottom, engine.generate(listOf(clothing(1, "상의")), OutfitOptions(), Random(1)))
    }

    @Test fun onlyRegisteredGarmentsAreCombinedAndRepeatedCombinationIsAvoided() {
        val clothes = listOf(clothing(1, "상의"), clothing(2, "상의", favorite = true), clothing(3, "하의"))
        val engine = OutfitRecommendationEngine()
        val first = engine.generate(clothes, OutfitOptions("봄"), Random(3)) as OutfitGeneration.Ready
        assertTrue(first.outfit.top.id in setOf(1L, 2L))
        assertEquals(3L, first.outfit.bottom.id)
        assertEquals(2, first.alternatives)
        val second = engine.generate(clothes, OutfitOptions("봄"), Random(3), setOf(first.outfit.key)) as OutfitGeneration.Ready
        assertTrue(second.outfit.key != first.outfit.key)
    }

    @Test fun temperatureBandsAreConfigurableAndDeterministic() {
        val engine = OutfitRecommendationEngine(RecommendationRules(winterMaxC = 5.0, springMaxC = 15.0, autumnMaxC = 22.0))
        assertEquals("겨울", engine.seasonForTemperature(5.0))
        assertEquals("봄", engine.seasonForTemperature(10.0))
        assertEquals("가을", engine.seasonForTemperature(18.0))
        assertEquals("여름", engine.seasonForTemperature(23.0))
    }

    @Test fun oneCombinationIsReportedAndSeededRandomIsRepeatable() {
        val items = listOf(clothing(1, "상의"), clothing(2, "하의"))
        val engine = OutfitRecommendationEngine()
        val first = engine.generate(items, OutfitOptions(), Random(17), chooseRandom = true) as OutfitGeneration.Ready
        val again = engine.generate(items, OutfitOptions(), Random(17), chooseRandom = true) as OutfitGeneration.Ready
        assertEquals(1, first.alternatives)
        assertEquals(first.outfit.key, again.outfit.key)
    }
}
