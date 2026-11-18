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
        get() = carouselCount > 1 || items.size > 1

    val availableItemCount: Int
        get() = items.count { it.status == ItemStatus.AVAILABLE && it.mediaUri != null }

    val totalItemCount: Int
        get() = if (isCarousel) maxOf(carouselCount, items.size) else 1

    val isPartiallyDeleted: Boolean
        get() = availableItemCount > 0 && availableItemCount < totalItemCount

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
    val thumbnailPath: String? = null
)

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
