package com.smartcloset.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "wear_history",
    foreignKeys = [ForeignKey(entity = Clothing::class, parentColumns = ["id"], childColumns = ["clothingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["clothingId", "wornDate"], unique = true), Index(value = ["wornDate"])]
)
data class WearHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clothingId: Long,
    /** ISO-8601 calendar date (yyyy-MM-dd), independent of time-zone changes. */
    val wornDate: String
)

data class WearRecordSummary(val wearCount: Int, val lastWornDate: String?)

@Dao
interface WearHistoryDao {
    @Query("SELECT * FROM wear_history WHERE clothingId = :clothingId ORDER BY wornDate DESC, id DESC")
    fun observeForClothing(clothingId: Long): Flow<List<WearHistory>>

    @Query("SELECT * FROM wear_history ORDER BY wornDate DESC, id DESC")
    fun observeAll(): Flow<List<WearHistory>>

    @Query("SELECT * FROM wear_history WHERE id = :id")
    suspend fun getById(id: Long): WearHistory?

    @Query("SELECT COUNT(*) AS wearCount, MAX(wornDate) AS lastWornDate FROM wear_history WHERE clothingId = :clothingId")
    fun observeSummary(clothingId: Long): Flow<WearRecordSummary>

    @Query("SELECT COUNT(*) FROM wear_history WHERE clothingId = :clothingId")
    suspend fun countForClothing(clothingId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: WearHistory): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<WearHistory>): List<Long>

    @Update(onConflict = OnConflictStrategy.IGNORE)
    suspend fun update(record: WearHistory): Int

    @Query("DELETE FROM wear_history WHERE id = :id")
    suspend fun delete(id: Long)
}

/** Adds the wear table without rebuilding or modifying the existing clothing table. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `wear_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `clothingId` INTEGER NOT NULL, `wornDate` TEXT NOT NULL, FOREIGN KEY(`clothingId`) REFERENCES `clothing`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_wear_history_clothingId_wornDate` ON `wear_history` (`clothingId`, `wornDate`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_wear_history_wornDate` ON `wear_history` (`wornDate`)")
    }
}

