package com.smartcloset.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ClothingRepository(context: Context) {
    private val dao = ClosetDatabase.get(context).clothingDao()
    private val cacheDir = context.cacheDir
    private val photoDir = File(context.filesDir, "clothing").apply { mkdirs() }
    private val fileMutex = Mutex()
    fun observe(query: String = "", category: String = "", season: String = "", color: String = "", favoritesOnly: Boolean = false) =
        dao.observe(query.trim(), category, season, color.trim(), favoritesOnly)
    fun observeById(id: Long): Flow<Clothing?> = dao.observeById(id)
    fun observeAll(): Flow<List<Clothing>> = dao.observeAll()
    fun observeCount() = dao.observeCount()

    suspend fun cleanupOrphanPhotos() = withContext(Dispatchers.IO) { fileMutex.withLock {
        val referenced = dao.allPhotoPaths().toSet()
        photoDir.listFiles()?.filter { it.isFile && it.name !in referenced }?.forEach { it.delete() }
        cacheDir.listFiles()?.filter { it.isFile && (it.name.startsWith("picked-") || it.name.startsWith("capture-")) }?.forEach { it.delete() }
    } }

    suspend fun save(source: File?, existing: Clothing?, fields: Clothing): Long = withContext(Dispatchers.IO) { fileMutex.withLock {
        val newPath = if (source != null) importPhoto(source) else existing?.photoPath
            ?: throw IllegalArgumentException("사진을 선택해 주세요.")
        val saved = fields.copy(id = existing?.id ?: 0, photoPath = newPath, createdAt = existing?.createdAt ?: fields.createdAt)
        try {
            if (existing == null) dao.insert(saved) else { dao.update(saved); saved.id }
        } catch (t: Throwable) {
            if (source != null) File(photoDir, newPath).delete()
            throw t
        }
        if (source != null && existing != null && existing.photoPath != newPath) File(photoDir, existing.photoPath).delete()
        if (source != null && !source.canonicalPath.startsWith(photoDir.canonicalPath)) source.delete()
        saved.id
    } }

    suspend fun delete(item: Clothing) = withContext(Dispatchers.IO) { fileMutex.withLock {
        dao.delete(item.id)
        File(photoDir, item.photoPath).delete()
    } }

    private fun importPhoto(source: File): String {
        require(source.isFile && source.canRead()) { "선택한 사진을 읽을 수 없습니다." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "이미지 파일을 열 수 없습니다." }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("이미지를 불러올 수 없습니다.")
        val matrix = android.graphics.Matrix()
        val exif = runCatching { ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (exif) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) matrix.postRotate(degrees)
        val normalized = if (degrees != 0f) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() } else bitmap
        val name = "${UUID.randomUUID()}.jpg"
        try {
            FileOutputStream(File(photoDir, name)).use { out ->
                check(normalized.compress(Bitmap.CompressFormat.JPEG, 86, out)) { "사진 저장에 실패했습니다." }
                out.fd.sync()
            }
        } catch (t: Throwable) { File(photoDir, name).delete(); throw t }
        finally { normalized.recycle() }
        name
    }
}
