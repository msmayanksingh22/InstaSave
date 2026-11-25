package com.instadown.assist.data.db

enum class TransactionStatus {
    SUCCESS,
    PARTIAL_SUCCESS,
    FAILED,
    CANCELLED,
    DUPLICATE,
    EXTERNAL_DELETED
}

enum class ItemStatus {
    PENDING,
    AVAILABLE,
    EXTERNAL_DELETED,
    VERIFY_ERROR,
    FAILED,
    CANCELLED,
    SKIPPED_DUPLICATE
}

data class DownloadRecord(
    val id: Long = 0L,
    val transactionId: String,
    val shortcode: String,
    val postType: String,
    val sourceUrl: String,
    val createdAt: Long,
    val carouselCount: Int,
    val status: TransactionStatus,
    val totalBytes: Long,
    val items: List<DownloadItemRecord> = emptyList(),
    val authorUsername: String? = null,
    val caption: String? = null,
    val thumbnailPath: String? = null
) {
    val formattedDate: String
        get() = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
            .format(java.util.Date(createdAt))

    val representativeUri: String?
        get() = items.firstOrNull { it.mediaUri != null && it.status == ItemStatus.AVAILABLE }?.mediaUri
            ?: thumbnailPath

    val isCarousel: Boolean
        get() = postType.equals("CAROUSEL", ignoreCase = true) || carouselCount > 1 || items.size > 1

    val availableItemCount: Int
        get() = items.count { it.status == ItemStatus.AVAILABLE && it.mediaUri != null }

    val totalItemCount: Int
        get() = if (isCarousel) maxOf(carouselCount, items.size) else 1

    val isPartiallyDeleted: Boolean
        get() = availableItemCount in 1 until items.size

    val userFacingTitle: String
        get() = when {
            !authorUsername.isNullOrBlank() -> "@${authorUsername.removePrefix("@")}"
            isCarousel -> "Carousel • $totalItemCount items"
            items.any { it.mediaType.equals("VIDEO", ignoreCase = true) } -> "Instagram Reel"
            else -> "Instagram Photo"
        }

    val isDeletedFromDevice: Boolean
        get() = status == TransactionStatus.EXTERNAL_DELETED || availableItemCount == 0

    val targetDownloadUrl: String
        get() = when {
            shortcode.isNotBlank() -> {
                if (postType.equals("REEL", ignoreCase = true)) {
                    "https://www.instagram.com/reel/$shortcode/"
                } else {
                    "https://www.instagram.com/p/$shortcode/"
                }
            }
            sourceUrl.contains("instagram.com/") -> sourceUrl
            else -> ""
        }

    val userFacingStatus: String
        get() = when {
            status == TransactionStatus.EXTERNAL_DELETED || availableItemCount == 0 -> "Deleted from device"
            isPartiallyDeleted -> "$availableItemCount / $totalItemCount available"
            status == TransactionStatus.PARTIAL_SUCCESS -> "$availableItemCount / $totalItemCount saved"
            isCarousel -> "$availableItemCount / $totalItemCount available"
            else -> "Saved"
        }
}

data class DownloadItemRecord(
    val id: Long = 0L,
    val downloadId: Long = 0L,
    val itemIndex: Int,
    val mediaType: String, // "PHOTO" or "VIDEO"
    val mediaUri: String?, // ContentResolver MediaStore URI string, nullable
    val fileName: String,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val status: ItemStatus = ItemStatus.PENDING
)

data class ItemEvent(
    val itemIndex: Int,
    val mediaType: String,
    val mediaUri: String?,
    val fileName: String,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val status: ItemStatus = ItemStatus.AVAILABLE
)

data class DownloadTransactionEvent(
    val transactionId: String,
    val status: TransactionStatus,
    val shortcode: String,
    val postType: String,
    val sourceUrl: String,
    val completedCount: Int,
    val failedCount: Int,
    val totalBytes: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val items: List<ItemEvent> = emptyList(),
    val authorUsername: String? = null,
    val caption: String? = null,
    val thumbnailPath: String? = null,
    val sourceItemCount: Int? = null
)

/** A save may cover any subset of a carousel, including a retry of an already saved slide. */
internal fun DownloadTransactionEvent.mergeWith(previous: DownloadRecord?): DownloadRecord {
    val byIndex = previous?.items.orEmpty().associateBy { it.itemIndex }.toMutableMap()
    for (item in items) {
        val prior = byIndex[item.itemIndex]
        if (item.mediaUri == null && prior != null) continue
        val sameFile = prior?.takeIf { it.mediaUri == item.mediaUri }
        byIndex[item.itemIndex] = DownloadItemRecord(
            id = sameFile?.id ?: 0L, downloadId = previous?.id ?: 0L,
            itemIndex = item.itemIndex, mediaType = item.mediaType, mediaUri = item.mediaUri,
            fileName = item.fileName, width = item.width.takeIf { it > 0 } ?: sameFile?.width ?: 0,
            height = item.height.takeIf { it > 0 } ?: sameFile?.height ?: 0,
            sizeBytes = item.sizeBytes.takeIf { it > 0 } ?: sameFile?.sizeBytes ?: 0L,
            status = if (item.mediaUri != null) ItemStatus.AVAILABLE else item.status
        )
    }
    val merged = byIndex.values.sortedBy { it.itemIndex }
    val available = merged.count { it.status == ItemStatus.AVAILABLE && it.mediaUri != null }
    val mergedStatus = when {
        merged.isEmpty() -> previous?.status ?: status
        available == merged.size -> TransactionStatus.SUCCESS
        available > 0 -> TransactionStatus.PARTIAL_SUCCESS
        merged.any { it.status == ItemStatus.EXTERNAL_DELETED } -> TransactionStatus.EXTERNAL_DELETED
        else -> status
    }
    val count = maxOf(previous?.carouselCount ?: 1, sourceItemCount ?: 1, (merged.lastOrNull()?.itemIndex ?: 0) + 1)
    val bytes = merged.sumOf { it.sizeBytes }
    return DownloadRecord(
        id = previous?.id ?: 0L, transactionId = transactionId, shortcode = shortcode,
        postType = if (count > 1 || previous?.isCarousel == true) "CAROUSEL" else postType,
        sourceUrl = sourceUrl.ifBlank { previous?.sourceUrl.orEmpty() }, createdAt = timestamp,
        carouselCount = count, status = mergedStatus,
        totalBytes = if (bytes > 0) bytes else maxOf(totalBytes, previous?.totalBytes ?: 0L),
        items = merged, authorUsername = authorUsername ?: previous?.authorUsername,
        caption = caption ?: previous?.caption, thumbnailPath = thumbnailPath ?: previous?.thumbnailPath
    )
}

data class DeleteHistoryResult(
    val historyRemoved: Boolean,
    val filesRequested: Int,
    val filesDeleted: Int,
    val filesAlreadyMissing: Int,
    val filesFailed: Int
) {
    val isCompleteSuccess: Boolean
        get() = historyRemoved && (filesFailed == 0)
}

data class StorageSummary(
    val totalItems: Int,
    val totalBytes: Long,
    val photosCount: Int,
    val videosCount: Int
) {
    val formattedSize: String
        get() {
            if (totalBytes <= 0) return "0 MB"
            val mb = totalBytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
            } else {
                String.format(java.util.Locale.US, "%.1f MB", mb)
            }
        }
}
