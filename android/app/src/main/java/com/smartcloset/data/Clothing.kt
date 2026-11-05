package com.smartcloset.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class ClothingCategory { 상의, 하의, 아우터 }
enum class Season { 봄, 여름, 가을, 겨울 }

@Entity(tableName = "clothing")
data class Clothing(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val photoPath: String,
    val category: String,
    val subcategory: String?,
    val seasons: String,
    val color: String?,
    val material: String?,
    val purchaseDate: String?,
    val favorite: Boolean = false,
    val memo: String?,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ClothingDao {
    @Query("SELECT * FROM clothing WHERE (:favoritesOnly = 0 OR favorite = 1) AND (:category = '' OR category = :category) AND (:season = '' OR seasons LIKE '%' || :season || '%') AND (:color = '' OR color = :color) AND (:query = '' OR name LIKE '%' || :query || '%') ORDER BY createdAt DESC")
    fun observe(query: String, category: String, season: String, color: String, favoritesOnly: Boolean): Flow<List<Clothing>>

    @Query("SELECT * FROM clothing WHERE id = :id") fun observeById(id: Long): Flow<Clothing?>
    @Query("SELECT * FROM clothing WHERE id IN (:ids)") suspend fun getByIds(ids: List<Long>): List<Clothing>
    @Query("SELECT * FROM clothing ORDER BY createdAt DESC") fun observeAll(): Flow<List<Clothing>>
    @Insert suspend fun insert(item: Clothing): Long
    @Update suspend fun update(item: Clothing)
    @Query("DELETE FROM clothing WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM clothing") fun observeCount(): Flow<Int>
    @Query("SELECT photoPath FROM clothing") suspend fun allPhotoPaths(): List<String>
}

@Database(entities = [Clothing::class, WearHistory::class, Outfit::class], version = 3, exportSchema = true)
abstract class ClosetDatabase : RoomDatabase() {
    abstract fun clothingDao(): ClothingDao
    abstract fun wearHistoryDao(): WearHistoryDao
    abstract fun outfitDao(): OutfitDao
    companion object {
        @Volatile private var INSTANCE: ClosetDatabase? = null
        fun get(context: android.content.Context): ClosetDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, ClosetDatabase::class.java, "smart-closet.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
        }
    }
}
