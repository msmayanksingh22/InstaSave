package com.instadown.assist.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.instadown.assist.data.db.StorageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object StorageAccountant {
    private const val TAG = "StorageAccountant"

    @Volatile
    private var cachedSummary: StorageSummary? = null
    @Volatile
    private var lastCalculationTime: Long = 0L
    private const val CACHE_TTL_MS = 15_000L // 15 seconds

    suspend fun getStorageSummary(context: Context, forceRefresh: Boolean = false): StorageSummary = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedSummary != null && (now - lastCalculationTime < CACHE_TTL_MS)) {
            return@withContext cachedSummary!!
        }

        var totalCount = 0
        var totalBytes = 0L
        var photoCount = 0
        var videoCount = 0

        val notTrashedClause = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            " AND (${MediaStore.MediaColumns.IS_TRASHED} = 0 OR ${MediaStore.MediaColumns.IS_TRASHED} IS NULL)"
        } else ""

        val notPendingClause = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            " AND (${MediaStore.MediaColumns.IS_PENDING} = 0 OR ${MediaStore.MediaColumns.IS_PENDING} IS NULL)"
        } else ""

        val baseFilters = "$notTrashedClause$notPendingClause"

        // Candidate paths for media (configured + legacy)
        val imagePaths = listOf(
            "Pictures/InstaSave/%",
            "Pictures/InstaDown/%",
            "DCIM/InstaSave/%",
            "Download/InstaSave/%"
        )

        val videoPaths = listOf(
            "Pictures/InstaSave/%",
            "Pictures/InstaDown/%",
            "Movies/InstaSave/%",
            "Movies/InstaDown/%",
            "DCIM/InstaSave/%",
            "Download/InstaSave/%"
        )

        // 1. Query Images
        try {
            val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE)
            val pathClauses = imagePaths.joinToString(" OR ") { "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" }
            val selection = "($pathClauses)$baseFilters"
            val selectionArgs = imagePaths.toTypedArray()

            context.contentResolver.query(imageUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                while (cursor.moveToNext()) {
                    val bytes = cursor.getLong(sizeCol)
                    totalBytes += bytes
                    photoCount++
                    totalCount++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query image storage: ${e.message}")
        }

        // 2. Query Videos
        try {
            val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE)
            val pathClauses = videoPaths.joinToString(" OR ") { "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" }
            val selection = "($pathClauses)$baseFilters"
            val selectionArgs = videoPaths.toTypedArray()

            context.contentResolver.query(videoUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val bytes = cursor.getLong(sizeCol)
                    totalBytes += bytes
                    videoCount++
                    totalCount++
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query video storage: ${e.message}")
        }

        val summary = StorageSummary(
            totalItems = totalCount,
            totalBytes = totalBytes,
            photosCount = photoCount,
            videosCount = videoCount
        )

        cachedSummary = summary
        lastCalculationTime = now
        Log.i(TAG, "Calculated storage: ${summary.totalItems} items (${summary.formattedSize})")
        summary
    }

    fun invalidateCache() {
        cachedSummary = null
        lastCalculationTime = 0L
    }
}
