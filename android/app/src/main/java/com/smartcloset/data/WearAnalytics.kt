package com.smartcloset.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class WearAnalysisSettings(
    val recentDays: Int = 30,
    val noWearDays: Int = 60,
    val noWearAfterRegistrationDays: Int = 30
)

data class ClothingWearStats(val clothing: Clothing, val wearCount: Int, val lastWornDate: LocalDate?)
data class RecentWear(val clothing: Clothing, val wornDate: LocalDate)
data class PurchaseWearStats(val clothing: Clothing, val daysSincePurchase: Long, val wearCount: Int, val lastWornDate: LocalDate?)

data class WearAnalytics(
    val totalRecords: Int,
    val perClothing: Map<Long, ClothingWearStats>,
    val mostWorn: List<ClothingWearStats>,
    val recentWears: List<RecentWear>,
    val noWearWithinPeriod: List<ClothingWearStats>,
    val registeredWithoutWear: List<Clothing>,
    val purchaseHistory: List<PurchaseWearStats>
) {
    companion object {
        fun calculate(
            clothes: List<Clothing>,
            records: List<WearHistory>,
            settings: WearAnalysisSettings,
            today: LocalDate,
            zoneId: ZoneId = ZoneId.of("UTC")
        ): WearAnalytics {
            val clothingById = clothes.associateBy { it.id }
            val validRecords = records.mapNotNull { record ->
                val date = runCatching { LocalDate.parse(record.wornDate) }.getOrNull()
                if (date != null && !date.isAfter(today) && clothingById.containsKey(record.clothingId)) record to date else null
            }
            val grouped = validRecords.groupBy { it.first.clothingId }
            val perClothing = clothes.associate { clothing ->
                val dates = grouped[clothing.id].orEmpty().map { it.second }
                clothing.id to ClothingWearStats(clothing, dates.size, dates.maxOrNull())
            }
            val frequent = perClothing.values.filter { it.wearCount > 0 }
                .sortedWith(compareByDescending<ClothingWearStats> { it.wearCount }.thenBy { it.clothing.name })
            val recentStart = today.minusDays((settings.recentDays.coerceAtLeast(1) - 1).toLong())
            val recent = validRecords.asSequence()
                .filter { (_, date) -> !date.isBefore(recentStart) }
                .mapNotNull { (record, date) -> clothingById[record.clothingId]?.let { RecentWear(it, date) } }
                .sortedWith(compareByDescending<RecentWear> { it.wornDate }.thenBy { it.clothing.name })
                .toList()
            val noWearStart = today.minusDays((settings.noWearDays.coerceAtLeast(1) - 1).toLong())
            val noWear = perClothing.values.filter { stats -> stats.lastWornDate?.isBefore(noWearStart) == true }
                .sortedBy { it.clothing.name }
            val registrationStart = today.minusDays(settings.noWearAfterRegistrationDays.coerceAtLeast(1).toLong())
            val unrecorded = clothes.filter { clothing ->
                clothing.id !in grouped && runCatching {
                    val created = Instant.ofEpochMilli(clothing.createdAt).atZone(zoneId).toLocalDate()
                    !created.isAfter(registrationStart) && !created.isAfter(today)
                }.getOrDefault(false)
            }.sortedBy { it.name }
            val purchases = clothes.mapNotNull { clothing ->
                val purchaseDate = clothing.purchaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                if (purchaseDate.isAfter(today)) return@mapNotNull null
                val stats = perClothing[clothing.id] ?: ClothingWearStats(clothing, 0, null)
                PurchaseWearStats(clothing, ChronoUnit.DAYS.between(purchaseDate, today), stats.wearCount, stats.lastWornDate)
            }.sortedWith(compareByDescending<PurchaseWearStats> { it.daysSincePurchase }.thenBy { it.clothing.name })
            return WearAnalytics(validRecords.size, perClothing, frequent, recent, noWear, unrecorded, purchases)
        }
    }
}
