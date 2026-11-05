package com.smartcloset.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WearHistoryMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "smart-closet.db"
    private val today = LocalDate.parse("2025-03-10")

    @Before fun createLegacyV1Database() {
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS `clothing` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `photoPath` TEXT NOT NULL, `category` TEXT NOT NULL, `subcategory` TEXT, `seasons` TEXT NOT NULL, `color` TEXT, `material` TEXT, `purchaseDate` TEXT, `favorite` INTEGER NOT NULL, `memo` TEXT, `createdAt` INTEGER NOT NULL)")
            db.execSQL("INSERT INTO `clothing` (`id`,`name`,`photoPath`,`category`,`subcategory`,`seasons`,`color`,`material`,`purchaseDate`,`favorite`,`memo`,`createdAt`) VALUES (42,'기존 셔츠','old-shirt.jpg','상의',NULL,'봄,가을',NULL,NULL,NULL,0,'기존 메모',1735689600000)")
            db.version = 1
        } finally { db.close() }
    }

    @After fun cleanUp() {
        runCatching { ClosetDatabase.get(context).close() }
        context.deleteDatabase(databaseName)
    }

    @Test fun v1ClothingSurvivesMigrationAndWearHistoryPoliciesHold() = runBlocking {
        val migrated = Room.databaseBuilder(context, ClosetDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        val oldClothing = migrated.clothingDao().getByIds(listOf(42)).single()
        assertEquals("기존 셔츠", oldClothing.name)
        assertEquals("old-shirt.jpg", oldClothing.photoPath)
        assertEquals("상의", oldClothing.category)
        assertEquals("봄,가을", oldClothing.seasons)
        assertEquals("기존 메모", oldClothing.memo)
        assertEquals(1735689600000L, oldClothing.createdAt)
        assertEquals(3, migrated.openHelper.writableDatabase.version)
        migrated.close()

        val repository = WearHistoryRepository(context, Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
        val clothingDao = ClosetDatabase.get(context).clothingDao()
        val wearDao = ClosetDatabase.get(context).wearHistoryDao()
        val firstDay = today.minusDays(4)
        val secondDay = today.minusDays(1)

        assertEquals(WearSaveStatus.ADDED, repository.add(42, firstDay).status)
        assertEquals(WearSaveStatus.ALREADY_RECORDED, repository.add(42, firstDay).status)
        assertEquals(WearSaveStatus.ADDED, repository.add(42, secondDay).status)
        val before = wearDao.observeForClothing(42).first()
        assertEquals(WearSaveStatus.DATE_ALREADY_RECORDED, repository.updateDate(before.first().id, firstDay).status)
        repository.delete(before.first().id)
        val summary = wearDao.observeSummary(42).first()
        assertEquals(1, summary.wearCount)
        assertEquals(firstDay.toString(), summary.lastWornDate)
        val remaining = wearDao.observeForClothing(42).first().single()
        val correctedDay = today.minusDays(3)
        assertEquals(WearSaveStatus.UPDATED, repository.updateDate(remaining.id, correctedDay).status)
        assertEquals(correctedDay.toString(), wearDao.observeSummary(42).first().lastWornDate)
        repository.delete(remaining.id)
        val emptySummary = wearDao.observeSummary(42).first()
        assertEquals(0, emptySummary.wearCount)
        assertNull(emptySummary.lastWornDate)

        try {
            repository.add(42, today.plusDays(1))
            fail("future date should be rejected")
        } catch (_: IllegalArgumentException) { }

        val secondId = clothingDao.insert(Clothing(name = "기록용 하의", photoPath = "pants.jpg", category = "하의", subcategory = null, seasons = "겨울", color = null, material = null, purchaseDate = today.minusDays(2).toString(), memo = null))
        val savedId = when (val saved = OutfitRepository(context).save("테스트 코디", 42, secondId, null, true)) {
            is SaveOutfitResult.Saved -> saved.id
            is SaveOutfitResult.Duplicate -> { fail("first combination should save"); -1L }
        }
        assertTrue(savedId > 0)
        assertTrue(OutfitRepository(context).save("중복", 42, secondId, null, false) is SaveOutfitResult.Duplicate)
        assertEquals(WearSaveStatus.NEEDS_PURCHASE_CONFIRMATION, repository.add(secondId, today.minusDays(3)).status)
        assertEquals(WearSaveStatus.ADDED, repository.add(secondId, today.minusDays(3), confirmBeforePurchase = true).status)

        val batchDate = today.minusDays(2)
        val batch = repository.addMany(listOf(42, secondId), batchDate)
        assertEquals(1, batch.newlyRecorded)
        assertEquals(1, batch.alreadyRecorded)
        val repeated = repository.addMany(listOf(42, secondId, secondId), batchDate)
        assertEquals(0, repeated.newlyRecorded)
        assertEquals(2, repeated.alreadyRecorded)
        val existingBeforeInvalidBatch = wearDao.countForClothing(42)
        try {
            repository.addMany(listOf(42, Long.MAX_VALUE), today)
            fail("unknown clothing id should reject the batch")
        } catch (_: IllegalArgumentException) { }
        assertEquals(existingBeforeInvalidBatch, wearDao.countForClothing(42))

        clothingDao.delete(secondId)
        assertEquals(0, wearDao.countForClothing(secondId))
        assertNull(ClosetDatabase.get(context).outfitDao().observeById(savedId).first())
    }
}
