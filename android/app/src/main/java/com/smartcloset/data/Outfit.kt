package com.smartcloset.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import kotlinx.coroutines.flow.Flow

/** A saved combination points only to clothes that exist in the closet. Deleting any
 * referenced garment removes the saved combination through the foreign-key cascade. */
@Entity(
    tableName = "outfits",
    foreignKeys = [
        ForeignKey(entity = Clothing::class, parentColumns = ["id"], childColumns = ["topClothingId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Clothing::class, parentColumns = ["id"], childColumns = ["bottomClothingId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Clothing::class, parentColumns = ["id"], childColumns = ["outerClothingId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index(value = ["topClothingId"]), Index(value = ["bottomClothingId"]), Index(value = ["outerClothingId"])]
)
data class Outfit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val topClothingId: Long,
    val bottomClothingId: Long,
    val outerClothingId: Long? = null,
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface OutfitDao {
    @Query("SELECT * FROM outfits ORDER BY createdAt DESC") fun observeAll(): Flow<List<Outfit>>
    @Query("SELECT * FROM outfits WHERE favorite = 1 ORDER BY createdAt DESC") fun observeFavorites(): Flow<List<Outfit>>
    @Query("SELECT * FROM outfits WHERE id = :id") fun observeById(id: Long): Flow<Outfit?>
    @Query("SELECT * FROM outfits WHERE topClothingId = :top AND bottomClothingId = :bottom AND outerClothingId IS :outer LIMIT 1") suspend fun findCombination(top: Long, bottom: Long, outer: Long?): Outfit?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(outfit: Outfit): Long
    @Query("UPDATE outfits SET favorite = :favorite WHERE id = :id") suspend fun setFavorite(id: Long, favorite: Boolean)
    @Query("DELETE FROM outfits WHERE id = :id") suspend fun delete(id: Long)
}

sealed interface SaveOutfitResult {
    data class Saved(val id: Long) : SaveOutfitResult
    data class Duplicate(val existing: Outfit) : SaveOutfitResult
}

class OutfitRepository(context: Context) {
    private val db = ClosetDatabase.get(context)
    val outfits = db.outfitDao().observeAll()
    val favorites = db.outfitDao().observeFavorites()
    fun observe(id: Long) = db.outfitDao().observeById(id)

    suspend fun save(name: String, top: Long, bottom: Long, outer: Long?, favorite: Boolean): SaveOutfitResult = db.withTransaction {
        require(name.isNotBlank()) { "코디 이름을 입력해 주세요." }
        val ids = listOfNotNull(top, bottom, outer)
        val clothes = db.clothingDao().getByIds(ids)
        require(clothes.size == ids.size) { "현재 옷장에 없는 의류가 코디에 포함되어 있습니다." }
        require(clothes.first { it.id == top }.category == "상의") { "코디의 첫 번째 의류는 상의여야 합니다." }
        require(clothes.first { it.id == bottom }.category == "하의") { "코디의 두 번째 의류는 하의여야 합니다." }
        require(outer == null || clothes.first { it.id == outer }.category == "아우터") { "선택한 추가 의류는 아우터여야 합니다." }
        val dao = db.outfitDao()
        val existing = dao.findCombination(top, bottom, outer)
        if (existing != null) SaveOutfitResult.Duplicate(existing)
        else SaveOutfitResult.Saved(dao.insert(Outfit(name = name.trim(), topClothingId = top, bottomClothingId = bottom, outerClothingId = outer, favorite = favorite)))
    }
    suspend fun toggleFavorite(outfit: Outfit) = db.outfitDao().setFavorite(outfit.id, !outfit.favorite)
    suspend fun delete(id: Long) = db.outfitDao().delete(id)
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `outfits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `topClothingId` INTEGER NOT NULL, `bottomClothingId` INTEGER NOT NULL, `outerClothingId` INTEGER, `favorite` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`topClothingId`) REFERENCES `clothing`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`bottomClothingId`) REFERENCES `clothing`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`outerClothingId`) REFERENCES `clothing`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outfits_topClothingId` ON `outfits` (`topClothingId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outfits_bottomClothingId` ON `outfits` (`bottomClothingId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_outfits_outerClothingId` ON `outfits` (`outerClothingId`)")
    }
}
