package com.instadown.assist.data.db

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.instadown.assist.data.StorageAccountant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

sealed interface DeleteStorageResult {
    data class Completed(val deletedCount: Int, val alreadyMissingCount: Int) : DeleteStorageResult
    data class RequiresUserConsent(
        val downloadId: Long,
        val intentSender: IntentSender,
        val pendingItemIds: List<Long>,
        val alreadyDeletedCount: Int
    ) : DeleteStorageResult
    data class Failed(val reason: String) : DeleteStorageResult
}

object SelfDeleteGuard {
    private val inFlightUris = ConcurrentHashMap.newKeySet<String>()

    fun markDeleting(uri: String) { inFlightUris.add(uri) }
    fun unmarkDeleting(uri: String) { inFlightUris.remove(uri) }
    fun isSelfDeleting(uri: String?): Boolean = uri != null && inFlightUris.contains(uri)

    inline fun <T> withSelfDeleting(uris: Collection<String>, block: () -> T): T {
        uris.forEach { markDeleting(it) }
        return try {
            block()
        } finally {
            uris.forEach { unmarkDeleting(it) }
        }
    }
}

class StorageDeletionCoordinator(
    private val context: Context,
    private val dbHelper: InstaSaveDatabaseHelper,
    private val onEventEmitted: ((DownloadTransactionEvent) -> Unit)? = null
) {
    companion object {
        private const val TAG = "StorageDeletionCoord"
    }

    /**
     * Deletes physical MediaStore files while preserving the parent archive row and archive thumbnail.
     * Safely handles Android 11-16 ownership loss (e.g. after reinstall) by returning an IntentSender
     * for MediaStore.createDeleteRequest.
     */
    suspend fun deleteDeviceStorageOnly(downloadId: Long): DeleteStorageResult = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val resolver = context.contentResolver
        val items = queryActiveItems(db, downloadId)

        val confirmedGone = mutableListOf<Pair<Long, String?>>()
        val consentNeededUris = mutableListOf<Uri>()
        val consentNeededItemIds = mutableListOf<Long>()
        val guardedUris = mutableSetOf<String>()
        var deletedCount = 0
        var alreadyMissingCount = 0

        try {
            for ((itemId, uriStr) in items) {
                if (uriStr.isNullOrBlank()) {
                    confirmedGone.add(itemId to null)
                    alreadyMissingCount++
                    continue
                }

                val uri = try { Uri.parse(uriStr) } catch (_: Exception) { null }
                if (uri == null) {
                    confirmedGone.add(itemId to uriStr)
                    alreadyMissingCount++
                    continue
                }

                guardedUris.add(uriStr)
                SelfDeleteGuard.markDeleting(uriStr)
                try {
                    val rowsRemoved = resolver.delete(uri, null, null)
                    if (rowsRemoved > 0) {
                        confirmedGone.add(itemId to uriStr)
                        deletedCount++
                    } else if (!uriStillExists(uri)) {
                        confirmedGone.add(itemId to uriStr)
                        alreadyMissingCount++
                    } else if (isSupportedForBatchDelete(uri)) {
                        // File exists, but OS denied silent delete (e.g. post-reinstall ownership loss on Android 11-16)
                        consentNeededUris.add(uri)
                        consentNeededItemIds.add(itemId)
                    }
                } catch (_: SecurityException) {
                    if (isSupportedForBatchDelete(uri)) {
                        consentNeededUris.add(uri)
                        consentNeededItemIds.add(itemId)
                    }
                } catch (_: IllegalArgumentException) {
                    // Malformed or already purged MediaStore row
                    confirmedGone.add(itemId to uriStr)
                    alreadyMissingCount++
                }
            }

            // Atomically mark only confirmed deleted/missing rows while guard is still active
            if (confirmedGone.isNotEmpty()) {
                markItemsExternallyDeleted(db, confirmedGone, downloadId)
            }
        } finally {
            guardedUris.forEach { SelfDeleteGuard.unmarkDeleting(it) }
        }

        if (consentNeededUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent: PendingIntent = MediaStore.createDeleteRequest(resolver, consentNeededUris)
                return@withContext DeleteStorageResult.RequiresUserConsent(
                    downloadId = downloadId,
                    intentSender = pendingIntent.intentSender,
                    pendingItemIds = consentNeededItemIds,
                    alreadyDeletedCount = deletedCount
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating batch delete request: ${e.message}")
            }
        }

        StorageAccountant.invalidateCache()
        notifyStorageDeleted(downloadId)

        DeleteStorageResult.Completed(deletedCount, alreadyMissingCount)
    }

    /**
     * Call this right after ActivityResultContracts.StartIntentSenderForResult returns RESULT_OK.
     */
    suspend fun finalizeConsentedDeletion(downloadId: Long, itemIds: List<Long>) = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) return@withContext
        val db = dbHelper.writableDatabase
        markItemsExternallyDeleted(db, itemIds.map { it to null }, downloadId)
        StorageAccountant.invalidateCache()
        notifyStorageDeleted(downloadId)
    }

    private fun markItemsExternallyDeleted(
        db: android.database.sqlite.SQLiteDatabase,
        items: List<Pair<Long, String?>>,
        downloadId: Long
    ) {
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(InstaSaveDatabaseHelper.COL_ITEM_STATUS, ItemStatus.EXTERNAL_DELETED.name)
                putNull(InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI)
            }
            for ((id, uriStr) in items) {
                if (uriStr != null) {
                    db.update(
                        InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS,
                        values,
                        "${InstaSaveDatabaseHelper.COL_ITEM_ID} = ? AND (${InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI} = ? OR ${InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI} IS NULL)",
                        arrayOf(id.toString(), uriStr)
                    )
                } else {
                    db.update(
                        InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS,
                        values,
                        "${InstaSaveDatabaseHelper.COL_ITEM_ID} = ?",
                        arrayOf(id.toString())
                    )
                }
            }

            // Check if any active items remain for this download
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM ${InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS} WHERE ${InstaSaveDatabaseHelper.COL_ITEM_DOWNLOAD_ID} = ? AND ${InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI} IS NOT NULL AND ${InstaSaveDatabaseHelper.COL_ITEM_STATUS} = ?",
                arrayOf(downloadId.toString(), ItemStatus.AVAILABLE.name)
            )
            val activeRemaining = cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (activeRemaining == 0) {
                val dlValues = ContentValues().apply {
                    put(InstaSaveDatabaseHelper.COL_DL_STATUS, TransactionStatus.EXTERNAL_DELETED.name)
                }
                db.update(
                    InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
                    dlValues,
                    "${InstaSaveDatabaseHelper.COL_DL_ID} = ?",
                    arrayOf(downloadId.toString())
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun queryActiveItems(
        db: android.database.sqlite.SQLiteDatabase,
        downloadId: Long
    ): List<Pair<Long, String?>> {
        val result = mutableListOf<Pair<Long, String?>>()
        db.rawQuery(
            "SELECT ${InstaSaveDatabaseHelper.COL_ITEM_ID}, ${InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI} FROM ${InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS} WHERE ${InstaSaveDatabaseHelper.COL_ITEM_DOWNLOAD_ID} = ?",
            arrayOf(downloadId.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(cursor.getLong(0) to cursor.getString(1))
            }
        }
        return result
    }

    private fun uriStillExists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
            it.moveToFirst()
        } ?: false
    }.getOrDefault(false)

    /**
     * MediaStore.createDeleteRequest() only supports Images, Video, and Audio tables.
     * Guard against MediaStore.Downloads URIs which throw IllegalArgumentException on Android 11-15.
     */
    private fun isSupportedForBatchDelete(uri: Uri): Boolean {
        val path = uri.path.orEmpty()
        return uri.authority == MediaStore.AUTHORITY &&
            (path.contains("/images/") || path.contains("/video/") || path.contains("/audio/"))
    }

    private fun notifyStorageDeleted(downloadId: Long) {
        onEventEmitted?.invoke(
            DownloadTransactionEvent(
                transactionId = "STORAGE_DELETED",
                status = TransactionStatus.EXTERNAL_DELETED,
                shortcode = "",
                postType = "",
                sourceUrl = "",
                completedCount = 0,
                failedCount = 0,
                totalBytes = 0,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
