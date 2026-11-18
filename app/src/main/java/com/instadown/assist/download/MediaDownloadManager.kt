package com.instadown.assist.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.instadown.assist.download.model.*
import com.instadown.assist.resolver.model.MediaType
import com.instadown.assist.resolver.model.PostType
import com.instadown.assist.resolver.model.ResolveResult
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

class MediaDownloadManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient = defaultClient
) {

    companion object {
        private const val TAG = "InstaDownloader"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    /**
     * Executes a planned download transaction.
     *
     * @param plan The validated plan from DownloadPlanner
     * @param reResolveCallback Invoked if a CDN URL expires (HTTP 401/403/410) to obtain a fresh URL
     * @param onProgress Callback invoked during download with fine-grained byte and item progress
     */
    suspend fun executeDownload(
        plan: DownloadPlanner.PlanResult,
        reResolveCallback: (suspend (shortcode: String) -> ResolveResult?)? = null,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val transactionId = UUID.randomUUID().toString().take(8)
        val shortcode = plan.shortcode ?: "unknown"
        val totalItems = plan.items.size

        Log.i(TAG, "=== DOWNLOAD TRANSACTION START ===")
        Log.i(TAG, "transactionId: $transactionId")
        Log.i(TAG, "shortcode: $shortcode")
        Log.i(TAG, "postType: ${plan.postType}")
        Log.i(TAG, "mediaCount: $totalItems")

        if (!plan.success || plan.items.isEmpty()) {
            val result = DownloadResult(
                transactionId = transactionId,
                shortcode = shortcode,
                success = false,
                postType = plan.postType,
                items = emptyList(),
                completedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                totalBytes = 0L,
                elapsedTimeMs = System.currentTimeMillis() - startTime,
                error = plan.error ?: "Empty download plan",
                errorCode = plan.errorCode ?: DownloadErrorCode.UNKNOWN
            )
            logTransactionSummary(result)
            return@withContext result
        }

        // Duplicate Check 1: In-flight race prevention
        val wasInactive = DuplicateDetector.markActive(shortcode)
        if (!wasInactive) {
            Log.w(TAG, "Rejecting download: Post $shortcode is already currently downloading")
            val result = DownloadResult(
                transactionId = transactionId,
                shortcode = shortcode,
                success = false,
                postType = plan.postType,
                items = plan.items.map { it.copy(status = DownloadStatus.SKIPPED_DUPLICATE) },
                completedCount = 0,
                failedCount = 0,
                skippedCount = plan.items.size,
                totalBytes = 0L,
                elapsedTimeMs = System.currentTimeMillis() - startTime,
                error = "Post $shortcode is currently being downloaded. Please wait.",
                errorCode = DownloadErrorCode.SAME_POST_CURRENTLY_DOWNLOADING
            )
            logTransactionSummary(result)
            return@withContext result
        }

        try {
            val processedItems = mutableListOf<DownloadItem>()
            var completedCount = 0
            var failedCount = 0
            var skippedCount = 0
            var totalBytesDownloaded = 0L

            // Carousel transaction behavior: strictly sequential (item 1 -> complete, item 2 -> complete, ...)
            for (i in plan.items.indices) {
                val item = plan.items[i]

                // Duplicate Check 2: Check if this specific item has already been downloaded to Gallery
                val dupStatus = DuplicateDetector.checkStatus(context, shortcode, item.index, item.mediaType)
                if (dupStatus == DuplicateDetector.DuplicateStatus.SAME_POST_ALREADY_DOWNLOADED) {
                    val existingUri = DuplicateDetector.getExistingUri(context, shortcode, item.index, item.mediaType)
                    Log.i(TAG, "Item ${item.index} already present in MediaStore at $existingUri")
                    skippedCount++
                    processedItems.add(
                        item.copy(
                            status = DownloadStatus.COMPLETED,
                            localUri = existingUri,
                            error = null
                        )
                    )
                    continue
                }

                // Initial progress broadcast for this item
                val initialProgress = DownloadProgress(
                    transactionId = transactionId,
                    shortcode = shortcode,
                    currentItemIndex = i,
                    totalItems = totalItems,
                    currentItemBytes = 0L,
                    currentItemTotalBytes = null,
                    currentItemPercent = 0,
                    overallPercent = (i * 100) / totalItems,
                    statusMessage = if (totalItems > 1) "Saving item ${i + 1} of $totalItems..." else "Downloading media..."
                )
                onProgress?.invoke(initialProgress)

                // Download item with bounded retry & URL refresh logic
                val downloadItemResult = downloadSingleItem(
                    item = item,
                    shortcode = shortcode,
                    transactionId = transactionId,
                    totalItems = totalItems,
                    reResolveCallback = reResolveCallback,
                    onItemByteProgress = { bytesRead, expectedTotal ->
                        val itemPercent = if (expectedTotal != null && expectedTotal > 0) {
                            ((bytesRead * 100) / expectedTotal).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        val overallPercent = ((i * 100) + itemPercent) / totalItems
                        val progress = DownloadProgress(
                            transactionId = transactionId,
                            shortcode = shortcode,
                            currentItemIndex = i,
                            totalItems = totalItems,
                            currentItemBytes = bytesRead,
                            currentItemTotalBytes = expectedTotal,
                            currentItemPercent = itemPercent,
                            overallPercent = overallPercent,
                            statusMessage = if (totalItems > 1) "Saving ${i + 1}/$totalItems (${itemPercent}%)" else "Downloading $itemPercent%"
                        )
                        onProgress?.invoke(progress)
                    }
                )

                processedItems.add(downloadItemResult)

                if (downloadItemResult.status == DownloadStatus.COMPLETED) {
                    completedCount++
                    totalBytesDownloaded += downloadItemResult.bytesDownloaded

                    // Record successfully completed item in persistent history
                    downloadItemResult.localUri?.let { uri ->
                        DuplicateDetector.recordCompleted(
                            context = context,
                            shortcode = shortcode,
                            index = item.index,
                            mediaType = item.mediaType,
                            uri = uri,
                            fileName = item.fileName
                        )
                    }
                } else if (downloadItemResult.status == DownloadStatus.FAILED) {
                    failedCount++
                } else if (downloadItemResult.status == DownloadStatus.CANCELLED) {
                    break // Stop entire batch if cancelled
                }
            }

            val totalElapsed = System.currentTimeMillis() - startTime
            val isAllSaved = (completedCount + skippedCount == totalItems) && failedCount == 0
            val isSuccess = isAllSaved || (completedCount > 0 && failedCount == 0)
            val isPartial = (completedCount + skippedCount > 0) && failedCount > 0

            val finalResult = DownloadResult(
                transactionId = transactionId,
                shortcode = shortcode,
                success = isSuccess,
                isPartial = isPartial,
                postType = plan.postType,
                items = processedItems,
                completedCount = completedCount,
                failedCount = failedCount,
                skippedCount = skippedCount,
                totalBytes = totalBytesDownloaded,
                elapsedTimeMs = totalElapsed,
                error = when {
                    !isSuccess && !isPartial && failedCount > 0 ->
                        processedItems.firstOrNull { it.status == DownloadStatus.FAILED }?.error ?: "Download failed"
                    else -> null
                },
                errorCode = when {
                    !isSuccess && !isPartial && failedCount > 0 ->
                        processedItems.firstOrNull { it.status == DownloadStatus.FAILED }?.errorCode ?: DownloadErrorCode.UNKNOWN
                    else -> null
                }
            )

            logTransactionSummary(finalResult)
            return@withContext finalResult

        } finally {
            DuplicateDetector.markInactive(shortcode)
        }
    }

    /**
     * Downloads a single media item with bounded retries and one-time CDN URL refresh on expiration.
     */
    private suspend fun downloadSingleItem(
        item: DownloadItem,
        shortcode: String,
        transactionId: String,
        totalItems: Int,
        reResolveCallback: (suspend (shortcode: String) -> ResolveResult?)?,
        onItemByteProgress: (bytesRead: Long, totalBytes: Long?) -> Unit
    ): DownloadItem = withContext(Dispatchers.IO) {
        var currentUrl = item.sourceUrl
        var attempts = 0
        val maxAttempts = 3
        var hasRefreshedUrl = false
        var lastError: String? = null
        var lastErrorCode: DownloadErrorCode? = null

        while (attempts < maxAttempts) {
            attempts++
            if (!coroutineContext.isActive) {
                return@withContext item.copy(
                    status = DownloadStatus.CANCELLED,
                    error = "Cancelled",
                    errorCode = DownloadErrorCode.CANCELLED
                )
            }

            // Create pending MediaStore entry for this attempt
            val pendingUri = MediaStoreWriter.createPendingEntry(
                context = context,
                fileName = item.fileName,
                mimeType = item.mimeType,
                mediaType = item.mediaType
            )

            if (pendingUri == null) {
                return@withContext item.copy(
                    status = DownloadStatus.FAILED,
                    error = "Failed to create MediaStore entry for ${item.fileName}",
                    errorCode = DownloadErrorCode.STORAGE_ERROR
                )
            }

            try {
                val request = Request.Builder()
                    .url(currentUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .addHeader("Accept", "*/*")
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val statusCode = response.code

                    // Check for CDN URL expiry: HTTP 401, 403, or 410
                    if (statusCode == 401 || statusCode == 403 || statusCode == 410) {
                        Log.w(TAG, "CDN returned HTTP $statusCode on $currentUrl (attempt $attempts)")
                        MediaStoreWriter.deleteIncompleteEntry(context.contentResolver, pendingUri)

                        if (!hasRefreshedUrl && reResolveCallback != null) {
                            Log.i(TAG, "Attempting one-time Phase 2 re-resolution for expired CDN URL (shortcode=$shortcode)...")
                            hasRefreshedUrl = true
                            val refreshedResult = reResolveCallback(shortcode)
                            val freshItem = refreshedResult?.items?.getOrNull(item.index)
                            if (freshItem != null && freshItem.mediaUrl.isNotBlank() && freshItem.mediaUrl != currentUrl) {
                                Log.i(TAG, "Obtained fresh media URL for item ${item.index}; retrying download once...")
                                currentUrl = freshItem.mediaUrl
                                // Continue loop to retry with fresh URL
                                return@use
                            } else {
                                Log.e(TAG, "Failed to obtain a fresh media URL on re-resolution")
                            }
                        }

                        lastError = "CDN media URL expired or unauthorized (HTTP $statusCode)"
                        lastErrorCode = DownloadErrorCode.MEDIA_URL_EXPIRED
                        // Non-retryable if already refreshed
                        return@withContext item.copy(
                            status = DownloadStatus.FAILED,
                            error = lastError,
                            errorCode = lastErrorCode
                        )
                    }

                    // Permanent client errors: 404, 429
                    if (statusCode == 404 || statusCode == 429) {
                        Log.e(TAG, "Permanent HTTP error $statusCode for ${item.fileName}")
                        MediaStoreWriter.deleteIncompleteEntry(context.contentResolver, pendingUri)
                        return@withContext item.copy(
                            status = DownloadStatus.FAILED,
                            error = "HTTP error $statusCode",
                            errorCode = if (statusCode == 404) DownloadErrorCode.HTTP_404 else DownloadErrorCode.HTTP_429
                        )
                    }

                    // Transient server errors: 5xx
                    if (statusCode >= 500) {
                        Log.w(TAG, "Transient HTTP $statusCode from CDN (attempt $attempts of $maxAttempts)")
                        MediaStoreWriter.deleteIncompleteEntry(context.contentResolver, pendingUri)
                        lastError = "Server error HTTP $statusCode"
                        lastErrorCode = DownloadErrorCode.SERVER_ERROR
                        delay(if (attempts == 1) 300L else 700L)
                        return@use
                    }

                    if (!response.isSuccessful) {
                        Log.e(TAG, "Unexpected HTTP status $statusCode for ${item.fileName}")
                        MediaStoreWriter.deleteIncompleteEntry(context.contentResolver, pendingUri)
                        lastError = "HTTP status $statusCode"
                        lastErrorCode = DownloadErrorCode.NETWORK_TIMEOUT
                        return@use
                    }

                    val body = response.body
                    if (body == null) {
                        MediaStoreWriter.deleteIncompleteEntry(context.contentResolver, pendingUri)
                        lastError = "Empty response body from CDN"
                        lastErrorCode = DownloadErrorCode.SERVER_ERROR
                        return@use
                    }

                    val expectedBytes = body.contentLength().takeIf { it > 0 }

                    // Stream directly into MediaStore and verify
                    val writeResult = MediaStoreWriter.streamAndVerify(
                        context = context,
                        uri = pendingUri,
                        inputStream = body.byteStream(),
                        expectedBytes = expectedBytes,
                        mediaType = item.mediaType,
                        onProgress = onItemByteProgress,
                        isCancelled = { !coroutineContext.isActive }
                    )

                    if (writeResult.success && writeResult.isVerified) {
                        Log.i(TAG, "Item ${item.index} successfully written and verified: ${writeResult.bytesWritten} bytes -> $pendingUri")
                        return@withContext item.copy(
                            status = DownloadStatus.COMPLETED,
                            bytesDownloaded = writeResult.bytesWritten,
                            totalBytes = expectedBytes ?: writeResult.bytesWritten,
                            localUri = pendingUri,
                            isVerified = true,
                            error = null
                        )
                    } else {
                        lastError = writeResult.error ?: "File write or verification failed"
                        lastErrorCode = writeResult.errorCode ?: DownloadErrorCode.VERIFICATION_FAILED
                        if (writeResult.errorCode == DownloadErrorCode.CANCELLED) {
                            return@withContext item.copy(
                                status = DownloadStatus.CANCELLED,
                                error = "Download cancelled by user",
                                errorCode = DownloadErrorCode.CANCELLED
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Network IOException on attempt $attempts for ${item.fileName}: ${e.message}")
                MediaStoreWriter.deleteIncompleteEntry(context.contentResolver, pendingUri)
                lastError = "Network error: ${e.message}"
                lastErrorCode = DownloadErrorCode.NETWORK_TIMEOUT
                if (attempts < maxAttempts) {
                    delay(if (attempts == 1) 300L else 700L)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception on attempt $attempts for ${item.fileName}", e)
                MediaStoreWriter.deleteIncompleteEntry(context.contentResolver, pendingUri)
                lastError = "Unexpected error: ${e.message}"
                lastErrorCode = DownloadErrorCode.UNKNOWN
                break
            }
        }

        item.copy(
            status = DownloadStatus.FAILED,
            error = lastError ?: "Failed to download after $attempts attempts",
            errorCode = lastErrorCode ?: DownloadErrorCode.UNKNOWN
        )
    }

    private fun logTransactionSummary(result: DownloadResult) {
        Log.i(TAG, "[RESULT]")
        Log.i(TAG, "completed: ${result.completedCount}")
        Log.i(TAG, "failed: ${result.failedCount}")
        Log.i(TAG, "skipped: ${result.skippedCount}")
        Log.i(TAG, "totalBytes: ${result.totalBytes}")
        Log.i(TAG, "elapsed: ${result.elapsedTimeMs}ms")
        result.items.forEach { item ->
            Log.i(TAG, "[MEDIASTORE] item ${item.index}: uri=${item.localUri}, file=${item.fileName}, bytes=${item.bytesDownloaded}, verified=${item.isVerified}, status=${item.status}")
        }
        val verdict = when {
            result.success -> "SUCCESS"
            result.isPartial -> "PARTIAL_SUCCESS"
            result.skippedCount > 0 && result.completedCount == 0 && result.failedCount == 0 -> "SKIPPED_DUPLICATE"
            else -> "FAILED"
        }
        Log.i(TAG, "[VERDICT] $verdict (error=${result.error})")
        Log.i(TAG, "=== DOWNLOAD TRANSACTION END ===")
    }
}
