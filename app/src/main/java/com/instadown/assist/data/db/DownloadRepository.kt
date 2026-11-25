package com.instadown.assist.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadRepository private constructor(private val context: Context) {

    private val dbHelper = InstaSaveDatabaseHelper(context.applicationContext)
    private val isReconciling = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        private const val TAG = "InstaSaveRepo"

        @Volatile
        private var INSTANCE: DownloadRepository? = null

        fun getInstance(context: Context): DownloadRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _transactionEvents = kotlinx.coroutines.flow.MutableSharedFlow<DownloadTransactionEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val transactionEvents: kotlinx.coroutines.flow.SharedFlow<DownloadTransactionEvent> = _transactionEvents

    suspend fun recordTransaction(event: DownloadTransactionEvent): Long = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val previous = if (event.shortcode.isBlank()) null else db.query(
                InstaSaveDatabaseHelper.TABLE_DOWNLOADS, null,
                "${InstaSaveDatabaseHelper.COL_DL_SHORTCODE} = ?", arrayOf(event.shortcode),
                null, null, "${InstaSaveDatabaseHelper.COL_DL_ID} DESC", "1"
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val record = parseDownloadRecord(cursor)
                    record.copy(items = getItemsForDownload(db, record.id))
                }
            }
            val merged = event.mergeWith(previous)
            val dlValues = ContentValues().apply {
                put(InstaSaveDatabaseHelper.COL_DL_TX_ID, merged.transactionId)
                put(InstaSaveDatabaseHelper.COL_DL_SHORTCODE, merged.shortcode)
                put(InstaSaveDatabaseHelper.COL_DL_POST_TYPE, merged.postType)
                put(InstaSaveDatabaseHelper.COL_DL_SOURCE_URL, merged.sourceUrl)
                put(InstaSaveDatabaseHelper.COL_DL_CREATED_AT, merged.createdAt)
                put(InstaSaveDatabaseHelper.COL_DL_CAROUSEL_COUNT, merged.carouselCount)
                put(InstaSaveDatabaseHelper.COL_DL_STATUS, merged.status.name)
                put(InstaSaveDatabaseHelper.COL_DL_TOTAL_BYTES, merged.totalBytes)
                put(InstaSaveDatabaseHelper.COL_DL_AUTHOR, merged.authorUsername?.removePrefix("@"))
                put(InstaSaveDatabaseHelper.COL_DL_CAPTION, merged.caption)
                put(InstaSaveDatabaseHelper.COL_DL_THUMBNAIL_PATH, merged.thumbnailPath)
            }
            val dlId = if (previous == null) {
                db.insertOrThrow(InstaSaveDatabaseHelper.TABLE_DOWNLOADS, null, dlValues)
            } else {
                db.update(
                    InstaSaveDatabaseHelper.TABLE_DOWNLOADS, dlValues,
                    "${InstaSaveDatabaseHelper.COL_DL_ID} = ?", arrayOf(previous.id.toString())
                )
                previous.id
            }

            val previousItems = previous?.items.orEmpty().associateBy { it.itemIndex }
            for (item in merged.items) {
                if (previousItems[item.itemIndex] == item) continue
                val itemValues = ContentValues().apply {
                    put(InstaSaveDatabaseHelper.COL_ITEM_DOWNLOAD_ID, dlId)
                    put(InstaSaveDatabaseHelper.COL_ITEM_INDEX, item.itemIndex)
                    put(InstaSaveDatabaseHelper.COL_ITEM_MEDIA_TYPE, item.mediaType)
                    put(InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI, item.mediaUri)
                    put(InstaSaveDatabaseHelper.COL_ITEM_FILE_NAME, item.fileName)
                    put(InstaSaveDatabaseHelper.COL_ITEM_WIDTH, item.width)
                    put(InstaSaveDatabaseHelper.COL_ITEM_HEIGHT, item.height)
                    put(InstaSaveDatabaseHelper.COL_ITEM_SIZE_BYTES, item.sizeBytes)
                    put(InstaSaveDatabaseHelper.COL_ITEM_STATUS, item.status.name)
                }
                if (item.id != 0L) {
                    db.update(
                        InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS, itemValues,
                        "${InstaSaveDatabaseHelper.COL_ITEM_ID} = ?", arrayOf(item.id.toString())
                    )
                } else {
                    // A replacement file gets a new row ID so pending delete consent for the old file stays scoped.
                    db.replaceOrThrow(InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS, null, itemValues)
                }
            }

            db.setTransactionSuccessful()
            com.instadown.assist.data.StorageAccountant.invalidateCache()
            _transactionEvents.tryEmit(event)
            dlId
        } catch (e: Exception) {
            Log.e(TAG, "recordTransaction: Failed to save transaction", e)
            -1L
        } finally {
            db.endTransaction()
        }
    }

    suspend fun recordDownloadResult(
        result: com.instadown.assist.download.model.DownloadResult,
        thumbnailPath: String? = null,
        authorUsername: String? = null,
        caption: String? = null,
        sourceItemCount: Int? = null
    ): Long = withContext(Dispatchers.IO) {
        if (result.transactionId.startsWith("AUTO_FAIL_") ||
            result.transactionId.startsWith("NO_A11Y") ||
            result.errorCode == com.instadown.assist.download.model.DownloadErrorCode.SAME_POST_CURRENTLY_DOWNLOADING ||
            result.errorCode == com.instadown.assist.download.model.DownloadErrorCode.INSTAGRAM_URL_CAPTURE_FAILED ||
            result.errorCode == com.instadown.assist.download.model.DownloadErrorCode.TARGET_SELECTION_FAILED ||
            result.errorCode == com.instadown.assist.download.model.DownloadErrorCode.IDENTITY_VERIFICATION_FAILED ||
            (result.shortcode.isNullOrBlank() && result.items.isEmpty())
        ) {
            Log.i(TAG, "recordDownloadResult: Skipping non-download attempt (${result.transactionId}, errorCode=${result.errorCode})")
            return@withContext -1L
        }

        try {
            val isAlreadyOnDevice = result.skippedCount > 0 && result.completedCount == 0 && result.failedCount == 0
            val status = when {
                result.success || isAlreadyOnDevice -> TransactionStatus.SUCCESS
                result.failedCount > 0 && result.completedCount > 0 -> TransactionStatus.PARTIAL_SUCCESS
                else -> TransactionStatus.FAILED
            }

            val itemEvents = result.items.map { item ->
                val itemStatus = when {
                    item.localUri != null -> ItemStatus.AVAILABLE
                    isAlreadyOnDevice -> ItemStatus.AVAILABLE
                    else -> ItemStatus.FAILED
                }
                ItemEvent(
                    itemIndex = item.index,
                    mediaType = if (item.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO) "VIDEO" else "PHOTO",
                    mediaUri = item.localUri?.toString(),
                    fileName = item.fileName,
                    width = item.width ?: 0,
                    height = item.height ?: 0,
                    sizeBytes = item.bytesDownloaded,
                    status = itemStatus
                )
            }

            // Immediately ensure persistent thumbnail path for all posts
            val shortcode = result.shortcode ?: ""
            var resolvedThumbPath = thumbnailPath
            if (resolvedThumbPath.isNullOrBlank() && shortcode.isNotBlank()) {
                val candidateItem = result.items.firstOrNull { it.localUri != null }
                val uriStr = candidateItem?.localUri?.toString() ?: result.items.firstOrNull()?.sourceUrl
                if (!uriStr.isNullOrBlank()) {
                    val isVid = candidateItem?.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO
                    resolvedThumbPath = extractAndSaveThumbnail(shortcode, uriStr, isVid)
                }
            }

            val event = DownloadTransactionEvent(
                transactionId = result.transactionId,
                status = status,
                shortcode = shortcode,
                postType = result.postType?.name ?: if (result.items.size > 1) "CAROUSEL" else if (result.items.firstOrNull()?.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO) "REEL" else "PHOTO",
                sourceUrl = if (shortcode.isNotBlank()) {
                    "https://www.instagram.com/p/$shortcode/"
                } else {
                    result.items.firstOrNull()?.sourceUrl ?: ""
                },
                completedCount = if (isAlreadyOnDevice) result.skippedCount else result.completedCount,
                failedCount = result.failedCount,
                totalBytes = result.totalBytes,
                timestamp = System.currentTimeMillis(),
                items = itemEvents,
                authorUsername = authorUsername,
                caption = caption,
                thumbnailPath = resolvedThumbPath,
                sourceItemCount = sourceItemCount
            )
            val rowId = recordTransaction(event)
            com.instadown.assist.data.StorageAccountant.invalidateCache()
            rowId
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record download result", e)
            -1L
        }
    }

    /**
     * Extracts and persists a low-resolution thumbnail into context.filesDir/thumbnails/${shortcode}.jpg
     */
    suspend fun extractAndSaveThumbnail(
        shortcode: String,
        uriStr: String?,
        isVideo: Boolean
    ): String? = withContext(Dispatchers.IO) {
        if (shortcode.isBlank() || uriStr.isNullOrBlank()) return@withContext null
        try {
            val thumbDir = java.io.File(context.filesDir, "thumbnails")
            if (!thumbDir.exists()) thumbDir.mkdirs()
            val thumbFile = java.io.File(thumbDir, "$shortcode.jpg")
            if (thumbFile.exists() && thumbFile.length() > 0) {
                return@withContext thumbFile.absolutePath
            }

            val bmp = com.instadown.assist.util.MediaThumbnailLoader.loadThumbnail(
                context = context,
                uriString = uriStr,
                isVideo = isVideo,
                targetWidth = 320,
                targetHeight = 320
            )
            if (bmp != null) {
                java.io.FileOutputStream(thumbFile).use { fos ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos)
                }
                Log.i(TAG, "extractAndSaveThumbnail: Saved thumbnail for $shortcode at ${thumbFile.absolutePath}")
                return@withContext thumbFile.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractAndSaveThumbnail failed for $shortcode: ${e.message}")
        }
        null
    }

    suspend fun updateMetadata(
        shortcode: String,
        authorUsername: String? = null,
        caption: String? = null,
        thumbnailPath: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (shortcode.isBlank()) return@withContext false
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            if (!authorUsername.isNullOrBlank()) put(InstaSaveDatabaseHelper.COL_DL_AUTHOR, authorUsername.removePrefix("@"))
            if (!caption.isNullOrBlank()) put(InstaSaveDatabaseHelper.COL_DL_CAPTION, caption)
            if (!thumbnailPath.isNullOrBlank()) put(InstaSaveDatabaseHelper.COL_DL_THUMBNAIL_PATH, thumbnailPath)
        }
        if (values.size() == 0) return@withContext false
        try {
            val rows = db.update(
                InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
                values,
                "${InstaSaveDatabaseHelper.COL_DL_SHORTCODE} = ?",
                arrayOf(shortcode)
            )
            Log.i(TAG, "updateMetadata: Updated $rows rows for shortcode $shortcode (author=$authorUsername, thumb=$thumbnailPath)")
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "updateMetadata failed for $shortcode: ${e.message}")
            false
        }
    }

    private fun getItemsForDownloads(
        db: android.database.sqlite.SQLiteDatabase,
        downloadIds: List<Long>
    ): Map<Long, List<DownloadItemRecord>> {
        if (downloadIds.isEmpty()) return emptyMap()
        val result = mutableMapOf<Long, MutableList<DownloadItemRecord>>()

        downloadIds.chunked(500).forEach { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            val selection = "${InstaSaveDatabaseHelper.COL_ITEM_DOWNLOAD_ID} IN ($placeholders)"
            val selectionArgs = batch.map { it.toString() }.toTypedArray()

            val cursor = db.query(
                InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS,
                null,
                selection,
                selectionArgs,
                null,
                null,
                "${InstaSaveDatabaseHelper.COL_ITEM_INDEX} ASC"
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val item = parseDownloadItemRecord(c)
                    result.getOrPut(item.downloadId) { mutableListOf() }.add(item)
                }
            }
        }
        return result
    }

    suspend fun getRecentTransactions(limit: Int = 5): List<DownloadRecord> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val rawRecords = mutableListOf<DownloadRecord>()

        val selection = "${InstaSaveDatabaseHelper.COL_DL_STATUS} IN (?, ?) AND ${InstaSaveDatabaseHelper.COL_DL_TX_ID} NOT LIKE ?"
        val selectionArgs = arrayOf(TransactionStatus.SUCCESS.name, TransactionStatus.PARTIAL_SUCCESS.name, "AUTO_FAIL_%")

        val cursor = db.query(
            InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "${InstaSaveDatabaseHelper.COL_DL_CREATED_AT} DESC",
            (limit * 3).toString()
        )

        cursor.use {
            while (it.moveToNext()) {
                rawRecords.add(parseDownloadRecord(it))
            }
        }
        if (rawRecords.isEmpty()) return@withContext emptyList()

        val itemsMap = getItemsForDownloads(db, rawRecords.map { it.id })
        val records = mutableListOf<DownloadRecord>()
        for (dl in rawRecords) {
            val record = dl.copy(items = itemsMap[dl.id] ?: emptyList())
            if (record.availableItemCount > 0 && record.status != TransactionStatus.EXTERNAL_DELETED) {
                records.add(record)
                if (records.size >= limit) break
            }
        }
        records
    }

    suspend fun getAllTransactions(includeFailed: Boolean = false): List<DownloadRecord> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val rawRecords = mutableListOf<DownloadRecord>()

        val selection = if (includeFailed) {
            "${InstaSaveDatabaseHelper.COL_DL_TX_ID} NOT LIKE ?"
        } else {
            "${InstaSaveDatabaseHelper.COL_DL_STATUS} IN (?, ?) AND ${InstaSaveDatabaseHelper.COL_DL_TX_ID} NOT LIKE ?"
        }
        val selectionArgs = if (includeFailed) {
            arrayOf("AUTO_FAIL_%")
        } else {
            arrayOf(TransactionStatus.SUCCESS.name, TransactionStatus.PARTIAL_SUCCESS.name, "AUTO_FAIL_%")
        }

        val cursor = db.query(
            InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "${InstaSaveDatabaseHelper.COL_DL_CREATED_AT} DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                rawRecords.add(parseDownloadRecord(it))
            }
        }
        if (rawRecords.isEmpty()) return@withContext emptyList()

        val itemsMap = getItemsForDownloads(db, rawRecords.map { it.id })
        val records = mutableListOf<DownloadRecord>()
        for (dl in rawRecords) {
            val record = dl.copy(items = itemsMap[dl.id] ?: emptyList())
            if (includeFailed || (record.availableItemCount > 0 && record.status != TransactionStatus.EXTERNAL_DELETED)) {
                records.add(record)
            }
        }
        records
    }

    /**
     * Returns full download history, including items whose files were deleted from device storage.
     * Batches all items in an optimized single query map to eliminate the N+1 query loop (<50ms).
     */
    suspend fun getFullHistory(): List<DownloadRecord> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val rawRecords = mutableListOf<DownloadRecord>()

        val selection = "${InstaSaveDatabaseHelper.COL_DL_TX_ID} NOT LIKE ?"
        val selectionArgs = arrayOf("AUTO_FAIL_%")

        val cursor = db.query(
            InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "${InstaSaveDatabaseHelper.COL_DL_CREATED_AT} DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                rawRecords.add(parseDownloadRecord(it))
            }
        }

        if (rawRecords.isEmpty()) return@withContext emptyList()

        val itemsMap = getItemsForDownloads(db, rawRecords.map { it.id })
        rawRecords.map { dl ->
            dl.copy(items = itemsMap[dl.id] ?: emptyList())
        }
    }

    suspend fun getTransactionById(id: Long): DownloadRecord? = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
            null,
            "${InstaSaveDatabaseHelper.COL_DL_ID} = ?",
            arrayOf(id.toString()),
            null,
            null,
            null
        )
        cursor.use {
            if (it.moveToFirst()) {
                val dl = parseDownloadRecord(it)
                val items = getItemsForDownload(db, dl.id)
                dl.copy(items = items)
            } else {
                null
            }
        }
    }

    val storageDeletionCoordinator by lazy {
        StorageDeletionCoordinator(context, dbHelper) { event ->
            _transactionEvents.tryEmit(event)
        }
    }

    suspend fun deleteDeviceStorageOnly(downloadId: Long): DeleteStorageResult {
        return storageDeletionCoordinator.deleteDeviceStorageOnly(downloadId)
    }

    suspend fun finalizeConsentedDeletion(downloadId: Long, itemIds: List<Long>) {
        storageDeletionCoordinator.finalizeConsentedDeletion(downloadId, itemIds)
    }

    suspend fun deleteDownload(downloadId: Long, deleteFiles: Boolean): DeleteHistoryResult = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val items = getItemsForDownload(db, downloadId)

        if (!deleteFiles) {
            // Remove from history only
            val rows = db.delete(
                InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
                "${InstaSaveDatabaseHelper.COL_DL_ID} = ?",
                arrayOf(downloadId.toString())
            )
            Log.i(TAG, "deleteDownload: Removed history only (rows=$rows, id=$downloadId)")
            return@withContext DeleteHistoryResult(
                historyRemoved = rows > 0,
                filesRequested = 0,
                filesDeleted = 0,
                filesAlreadyMissing = 0,
                filesFailed = 0
            )
        }

        // Delete physical files + remove from history
        var deleted = 0
        var missing = 0
        var failed = 0
        val targetUris = items.mapNotNull { it.mediaUri }

        for (uriStr in targetUris) {
            val uri = try { Uri.parse(uriStr) } catch (e: Exception) { null }
            if (uri == null) {
                missing++
                continue
            }

            try {
                val checkCursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                val exists = checkCursor?.use { it.moveToFirst() } ?: false
                if (!exists) {
                    missing++
                    continue
                }

                val delCount = context.contentResolver.delete(uri, null, null)
                if (delCount > 0) {
                    deleted++
                } else {
                    failed++
                }
            } catch (se: SecurityException) {
                Log.w(TAG, "deleteDownload: SecurityException deleting $uri: ${se.message}")
                failed++
            } catch (e: Exception) {
                Log.w(TAG, "deleteDownload: Failed to delete $uri: ${e.message}")
                failed++
            }
        }

        // Only remove history if all requested files were deleted or already missing
        val historyRemoved = if (failed == 0) {
            val rows = db.delete(
                InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
                "${InstaSaveDatabaseHelper.COL_DL_ID} = ?",
                arrayOf(downloadId.toString())
            )
            rows > 0
        } else {
            false
        }

        Log.i(TAG, "deleteDownload: filesRequested=${targetUris.size}, deleted=$deleted, missing=$missing, failed=$failed, historyRemoved=$historyRemoved")
        com.instadown.assist.data.StorageAccountant.invalidateCache()
        DeleteHistoryResult(
            historyRemoved = historyRemoved,
            filesRequested = targetUris.size,
            filesDeleted = deleted,
            filesAlreadyMissing = missing,
            filesFailed = failed
        )
    }

    suspend fun reconcileWithMediaStore(): Int = withContext(Dispatchers.IO) {
        if (!isReconciling.compareAndSet(false, true)) {
            Log.d(TAG, "reconcileWithMediaStore already running in background, skipping overlapping run")
            return@withContext 0
        }
        try {
            val db = dbHelper.writableDatabase
            var updatedCount = 0
            val affectedDownloadIds = mutableSetOf<Long>()

            // 1. Check all items that have a mediaUri (AVAILABLE, VERIFY_ERROR, or EXTERNAL_DELETED)
            val cursor = db.query(
                InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS,
                arrayOf(
                    InstaSaveDatabaseHelper.COL_ITEM_ID,
                    InstaSaveDatabaseHelper.COL_ITEM_DOWNLOAD_ID,
                    InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI,
                    InstaSaveDatabaseHelper.COL_ITEM_STATUS
                ),
                "${InstaSaveDatabaseHelper.COL_ITEM_STATUS} IN (?, ?, ?) AND ${InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI} IS NOT NULL",
                arrayOf(ItemStatus.AVAILABLE.name, ItemStatus.VERIFY_ERROR.name, ItemStatus.EXTERNAL_DELETED.name),
                null,
                null,
                null
            )

            cursor.use {
                while (it.moveToNext()) {
                    val itemId = it.getLong(it.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_ID))
                    val dlId = it.getLong(it.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_DOWNLOAD_ID))
                    val uriStr = it.getString(it.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI))
                    if (SelfDeleteGuard.isSelfDeleting(uriStr)) {
                        continue
                    }
                    val currentStatus = it.getString(it.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_STATUS))

                    val newStatus = verifyUriState(uriStr)
                    if (newStatus != null && newStatus.name != currentStatus) {
                        val values = ContentValues().apply {
                            put(InstaSaveDatabaseHelper.COL_ITEM_STATUS, newStatus.name)
                        }
                        db.update(
                            InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS,
                            values,
                            "${InstaSaveDatabaseHelper.COL_ITEM_ID} = ?",
                            arrayOf(itemId.toString())
                        )
                        affectedDownloadIds.add(dlId)
                        updatedCount++
                        Log.i(TAG, "reconcile: Item #$itemId ($uriStr) status changed $currentStatus -> $newStatus")
                    }
                }
            }

            // 2. Also check if there are any downloads whose items might have changed state
            val dlCursor = db.query(
                InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
                arrayOf(InstaSaveDatabaseHelper.COL_DL_ID, InstaSaveDatabaseHelper.COL_DL_STATUS),
                "${InstaSaveDatabaseHelper.COL_DL_STATUS} IN (?, ?, ?)",
                arrayOf(TransactionStatus.SUCCESS.name, TransactionStatus.PARTIAL_SUCCESS.name, TransactionStatus.EXTERNAL_DELETED.name),
                null,
                null,
                null
            )
            dlCursor.use {
                while (it.moveToNext()) {
                    affectedDownloadIds.add(it.getLong(it.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_ID)))
                }
            }

            // 3. Reconcile parent transaction records (batched to eliminate N+1 queries)
            var txStatusChangedCount = 0
            if (affectedDownloadIds.isNotEmpty()) {
                val itemsMap = getItemsForDownloads(db, affectedDownloadIds.toList())
                for (dlId in affectedDownloadIds) {
                    val items = itemsMap[dlId] ?: emptyList()
                    if (items.isEmpty()) continue

                    val availableCount = items.count { it.status == ItemStatus.AVAILABLE && it.mediaUri != null }
                    val newTxStatus = when {
                        availableCount == 0 -> TransactionStatus.EXTERNAL_DELETED
                        availableCount < items.size -> TransactionStatus.PARTIAL_SUCCESS
                        else -> TransactionStatus.SUCCESS
                    }

                    val curTxCursor = db.query(
                        InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
                        arrayOf(InstaSaveDatabaseHelper.COL_DL_STATUS),
                        "${InstaSaveDatabaseHelper.COL_DL_ID} = ?",
                        arrayOf(dlId.toString()),
                        null, null, null
                    )
                    val currentTxStatusStr = curTxCursor.use { if (it.moveToFirst()) it.getString(0) else null }

                    if (currentTxStatusStr != null && currentTxStatusStr != newTxStatus.name) {
                        val cv = ContentValues().apply {
                            put(InstaSaveDatabaseHelper.COL_DL_STATUS, newTxStatus.name)
                        }
                        db.update(
                            InstaSaveDatabaseHelper.TABLE_DOWNLOADS,
                            cv,
                            "${InstaSaveDatabaseHelper.COL_DL_ID} = ?",
                            arrayOf(dlId.toString())
                        )
                        txStatusChangedCount++
                        Log.i(TAG, "reconcile: Download #$dlId status changed $currentTxStatusStr -> $newTxStatus ($availableCount/${items.size} available)")
                    }
                }
            }

            // 4. Clean up any historical duplicate rows with same shortcode
            try {
                db.execSQL("""
                    DELETE FROM ${InstaSaveDatabaseHelper.TABLE_DOWNLOADS}
                    WHERE ${InstaSaveDatabaseHelper.COL_DL_ID} NOT IN (
                        SELECT MAX(${InstaSaveDatabaseHelper.COL_DL_ID})
                        FROM ${InstaSaveDatabaseHelper.TABLE_DOWNLOADS}
                        GROUP BY ${InstaSaveDatabaseHelper.COL_DL_SHORTCODE}
                    ) AND ${InstaSaveDatabaseHelper.COL_DL_SHORTCODE} != ''
                """.trimIndent())
            } catch (ignored: Exception) {}

            if (updatedCount > 0 || txStatusChangedCount > 0) {
                com.instadown.assist.data.StorageAccountant.invalidateCache()
                _transactionEvents.tryEmit(
                    DownloadTransactionEvent(
                        transactionId = "RECONCILED",
                        status = TransactionStatus.SUCCESS,
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
            updatedCount
        } finally {
            isReconciling.set(false)
        }
    }

    private fun verifyUriState(uriStr: String?): ItemStatus? {
        if (uriStr.isNullOrBlank()) return ItemStatus.EXTERNAL_DELETED
        val uri = try { Uri.parse(uriStr) } catch (e: Exception) { return ItemStatus.EXTERNAL_DELETED }

        return try {
            // Build projection with IS_TRASHED (API 30+) and IS_PENDING (API 29+)
            val projection = mutableListOf(
                OpenableColumns.DISPLAY_NAME,
                MediaStore.MediaColumns._ID
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                projection.add(MediaStore.MediaColumns.IS_TRASHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projection.add(MediaStore.MediaColumns.IS_PENDING)
            }

            val cursor = context.contentResolver.query(uri, projection.toTypedArray(), null, null, null)
            val exists = cursor?.use { c ->
                if (!c.moveToFirst()) return@use false

                // 1. Primary Check: IS_TRASHED != 0 means user deleted it to Trash
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val trashedIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                    if (trashedIdx >= 0 && c.getInt(trashedIdx) != 0) {
                        Log.i(TAG, "verifyUriState: Item is in MediaStore trash (IS_TRASHED=1): $uri")
                        return@use false
                    }
                }

                // 2. Primary Check: IS_PENDING != 0 means item is not user-visible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pendingIdx = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    if (pendingIdx >= 0 && c.getInt(pendingIdx) != 0) {
                        Log.i(TAG, "verifyUriState: Item is pending in MediaStore (IS_PENDING=1): $uri")
                        return@use false
                    }
                }

                true
            } ?: false

            if (!exists) {
                com.instadown.assist.util.MediaThumbnailLoader.evict(uriStr)
                return ItemStatus.EXTERNAL_DELETED
            }

            // Supporting physical verification: confirm openable file descriptor
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pfd.close()
                ItemStatus.AVAILABLE
            } else {
                com.instadown.assist.util.MediaThumbnailLoader.evict(uriStr)
                ItemStatus.EXTERNAL_DELETED
            }
        } catch (fnf: java.io.FileNotFoundException) {
            com.instadown.assist.util.MediaThumbnailLoader.evict(uriStr)
            ItemStatus.EXTERNAL_DELETED
        } catch (se: SecurityException) {
            Log.w(TAG, "verifyUriState: SecurityException verifying $uri: ${se.message}")
            ItemStatus.VERIFY_ERROR
        } catch (e: Exception) {
            Log.w(TAG, "verifyUriState: Exception verifying $uri: ${e.message}")
            ItemStatus.VERIFY_ERROR
        }
    }

    private fun getItemsForDownload(db: android.database.sqlite.SQLiteDatabase, downloadId: Long): List<DownloadItemRecord> {
        val items = mutableListOf<DownloadItemRecord>()
        val cursor = db.query(
            InstaSaveDatabaseHelper.TABLE_DOWNLOAD_ITEMS,
            null,
            "${InstaSaveDatabaseHelper.COL_ITEM_DOWNLOAD_ID} = ?",
            arrayOf(downloadId.toString()),
            null,
            null,
            "${InstaSaveDatabaseHelper.COL_ITEM_INDEX} ASC"
        )
        cursor.use {
            while (it.moveToNext()) {
                items.add(parseDownloadItemRecord(it))
            }
        }
        return items
    }

    private fun parseDownloadRecord(c: Cursor): DownloadRecord {
        val authorIdx = c.getColumnIndex(InstaSaveDatabaseHelper.COL_DL_AUTHOR)
        val captionIdx = c.getColumnIndex(InstaSaveDatabaseHelper.COL_DL_CAPTION)
        val thumbIdx = c.getColumnIndex(InstaSaveDatabaseHelper.COL_DL_THUMBNAIL_PATH)

        var shortcode = c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_SHORTCODE))
        val sourceUrl = c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_SOURCE_URL))
        val txId = c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_TX_ID))
        if (shortcode.isBlank()) {
            val fromUrl = com.instadown.assist.resolver.UrlNormalizer.normalize(sourceUrl)?.shortcode
            val fromTx = if (txId.startsWith("TX_")) txId.substringAfter("TX_").substringBefore("_") else null
            shortcode = fromUrl ?: fromTx ?: ""
        }

        return DownloadRecord(
            id = c.getLong(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_ID)),
            transactionId = txId,
            shortcode = shortcode,
            postType = c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_POST_TYPE)),
            sourceUrl = sourceUrl,
            createdAt = c.getLong(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_CREATED_AT)),
            carouselCount = c.getInt(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_CAROUSEL_COUNT)),
            status = try {
                TransactionStatus.valueOf(c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_STATUS)))
            } catch (e: Exception) { TransactionStatus.SUCCESS },
            totalBytes = c.getLong(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_DL_TOTAL_BYTES)),
            authorUsername = if (authorIdx >= 0 && !c.isNull(authorIdx)) c.getString(authorIdx) else null,
            caption = if (captionIdx >= 0 && !c.isNull(captionIdx)) c.getString(captionIdx) else null,
            thumbnailPath = if (thumbIdx >= 0 && !c.isNull(thumbIdx)) c.getString(thumbIdx) else null
        )
    }

    private fun parseDownloadItemRecord(c: Cursor): DownloadItemRecord {
        val uriStr = if (!c.isNull(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI))) {
            c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_MEDIA_URI))
        } else null

        return DownloadItemRecord(
            id = c.getLong(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_ID)),
            downloadId = c.getLong(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_DOWNLOAD_ID)),
            itemIndex = c.getInt(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_INDEX)),
            mediaType = c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_MEDIA_TYPE)),
            mediaUri = uriStr,
            fileName = c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_FILE_NAME)),
            width = c.getInt(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_WIDTH)),
            height = c.getInt(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_HEIGHT)),
            sizeBytes = c.getLong(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_SIZE_BYTES)),
            status = try {
                ItemStatus.valueOf(c.getString(c.getColumnIndexOrThrow(InstaSaveDatabaseHelper.COL_ITEM_STATUS)))
            } catch (e: Exception) { ItemStatus.AVAILABLE }
        )
    }
}
