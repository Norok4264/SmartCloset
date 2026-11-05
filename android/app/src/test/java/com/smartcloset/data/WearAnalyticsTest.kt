package com.smartcloset.data

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearAnalyticsTest {
    private val today = LocalDate.parse("2025-06-30")
    private fun clothing(id: Long, name: String, created: LocalDate, purchaseDate: String? = null) = Clothing(
        id = id, name = name, photoPath = "$id.jpg", category = "상의", subcategory = null,
        seasons = "여름", color = null, material = null, purchaseDate = purchaseDate,
        memo = null, createdAt = created.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    )
    private fun record(id: Long, clothingId: Long, date: LocalDate) = WearHistory(id, clothingId, date.toString())

    @Test fun computesFrequencyRecentNoWearRegistrationGraceAndKnownPurchaseDurations() {
        val active = clothing(1, "자주 입는 셔츠", today.minusDays(100), today.minusDays(80).toString())
        val oldButUnworn = clothing(2, "오래 미착용 셔츠", today.minusDays(100))
        val newNoRecord = clothing(3, "최근 등록 셔츠", today.minusDays(10))
        val newlyPurchased = clothing(4, "새 바지", today.minusDays(5), today.minusDays(3).toString())
        val longRegisteredNoRecord = clothing(5, "오래 등록된 미기록 셔츠", today.minusDays(30))
        val records = listOf(
            record(1, 1, today.minusDays(2)), record(2, 1, today.minusDays(1)),
            record(3, 1, today.minusDays(61)), record(4, 2, today.minusDays(60))
        )
        val result = WearAnalytics.calculate(
            listOf(active, oldButUnworn, newNoRecord, newlyPurchased, longRegisteredNoRecord), records,
            WearAnalysisSettings(recentDays = 30, noWearDays = 60, noWearAfterRegistrationDays = 30),
            today, ZoneOffset.UTC
        )

        assertEquals(4, result.totalRecords)
        assertEquals(3, result.perClothing[1]?.wearCount)
        assertEquals(today.minusDays(1), result.perClothing[1]?.lastWornDate)
        assertEquals(1L, result.mostWorn.first().clothing.id)
        assertEquals(2, result.recentWears.size)
        assertEquals(listOf(2L), result.noWearWithinPeriod.map { it.clothing.id })
        assertEquals(listOf(5L), result.registeredWithoutWear.map { it.id })
        val purchase = result.purchaseHistory.single { it.clothing.id == 1L }
        assertEquals(80L, purchase.daysSincePurchase)
        assertEquals(3, purchase.wearCount)
        assertFalse(result.purchaseHistory.any { it.clothing.id == 2L })
    }

    @Test fun futureInvalidOrDeletedClothingRecordsDoNotAffectCalculatedHistory() {
        val onlyClothing = clothing(8, "남은 옷", today, null)
        val result = WearAnalytics.calculate(
            listOf(onlyClothing),
            listOf(record(1, 8, today), record(2, 8, today.plusDays(1)), record(3, 99, today)),
            WearAnalysisSettings(), today, ZoneOffset.UTC
        )
        assertEquals(1, result.totalRecords)
        assertEquals(today, result.perClothing[8]?.lastWornDate)
        assertNull(result.perClothing[8]?.clothing?.purchaseDate)
        assertTrue(result.purchaseHistory.isEmpty())
    }
}
