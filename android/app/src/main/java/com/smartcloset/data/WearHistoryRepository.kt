package com.smartcloset.data

import android.content.Context
import androidx.room.withTransaction
import java.time.Clock
import java.time.LocalDate

enum class WearSaveStatus { ADDED, ALREADY_RECORDED, NEEDS_PURCHASE_CONFIRMATION, UPDATED, DATE_ALREADY_RECORDED }
data class WearSaveResult(val status: WearSaveStatus)
data class WearBatchResult(val newlyRecorded: Int, val alreadyRecorded: Int, val needsPurchaseConfirmation: Boolean = false)

class WearHistoryRepository(context: Context, private val clock: Clock = Clock.systemDefaultZone()) {
    private val database = ClosetDatabase.get(context)
    private val wearDao = database.wearHistoryDao()
    private val clothingDao = database.clothingDao()

    fun observeForClothing(clothingId: Long) = wearDao.observeForClothing(clothingId)
    fun observeAll() = wearDao.observeAll()
    fun observeSummary(clothingId: Long) = wearDao.observeSummary(clothingId)

    suspend fun add(clothingId: Long, date: LocalDate, confirmBeforePurchase: Boolean = false): WearSaveResult {
        validateDate(date)
        return database.withTransaction {
            val clothing = clothingDao.getByIds(listOf(clothingId)).firstOrNull()
                ?: throw IllegalArgumentException("의류를 찾을 수 없습니다.")
            if (isBeforePurchase(clothing, date) && !confirmBeforePurchase) return@withTransaction WearSaveResult(WearSaveStatus.NEEDS_PURCHASE_CONFIRMATION)
            val inserted = wearDao.insert(WearHistory(clothingId = clothingId, wornDate = date.toString()))
            WearSaveResult(if (inserted == -1L) WearSaveStatus.ALREADY_RECORDED else WearSaveStatus.ADDED)
        }
    }

    suspend fun updateDate(recordId: Long, date: LocalDate, confirmBeforePurchase: Boolean = false): WearSaveResult {
        validateDate(date)
        return database.withTransaction {
            val existing = wearDao.getById(recordId) ?: throw IllegalArgumentException("착용 기록을 찾을 수 없습니다.")
            val clothing = clothingDao.getByIds(listOf(existing.clothingId)).firstOrNull()
                ?: throw IllegalArgumentException("의류를 찾을 수 없습니다.")
            if (isBeforePurchase(clothing, date) && !confirmBeforePurchase) return@withTransaction WearSaveResult(WearSaveStatus.NEEDS_PURCHASE_CONFIRMATION)
            val updated = wearDao.update(existing.copy(wornDate = date.toString()))
            WearSaveResult(if (updated == 0) WearSaveStatus.DATE_ALREADY_RECORDED else WearSaveStatus.UPDATED)
        }
    }

    suspend fun delete(recordId: Long) = wearDao.delete(recordId)

    /** All requested unique clothes are written in one SQLite transaction or none are. */
    suspend fun addMany(clothingIds: List<Long>, date: LocalDate, confirmBeforePurchase: Boolean = false): WearBatchResult {
        validateDate(date)
        val uniqueIds = clothingIds.distinct()
        if (uniqueIds.isEmpty()) return WearBatchResult(0, 0)
        return database.withTransaction {
            val clothes = clothingDao.getByIds(uniqueIds)
            require(clothes.size == uniqueIds.size) { "요청에 현재 옷장에 없는 의류가 포함되어 있습니다." }
            if (clothes.any { isBeforePurchase(it, date) } && !confirmBeforePurchase) {
                return@withTransaction WearBatchResult(0, 0, needsPurchaseConfirmation = true)
            }
            val inserted = wearDao.insertAll(uniqueIds.map { WearHistory(clothingId = it, wornDate = date.toString()) })
            val newCount = inserted.count { it != -1L }
            WearBatchResult(newlyRecorded = newCount, alreadyRecorded = uniqueIds.size - newCount)
        }
    }

    private fun validateDate(date: LocalDate) {
        require(!date.isAfter(LocalDate.now(clock))) { "미래 날짜에는 착용 기록을 추가할 수 없습니다." }
    }

    private fun isBeforePurchase(clothing: Clothing, date: LocalDate): Boolean {
        val purchaseDate = clothing.purchaseDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return date.isBefore(purchaseDate)
    }
}
