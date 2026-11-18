package com.instadown.assist.download.model

import android.net.Uri
import com.instadown.assist.resolver.model.MediaType
import com.instadown.assist.resolver.model.PostType

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED_DUPLICATE
}

enum class DownloadErrorCode {
    TARGET_SELECTION_FAILED,
    INSTAGRAM_URL_CAPTURE_FAILED,
    RESOLUTION_FAILED,
    IDENTITY_VERIFICATION_FAILED,
    MEDIA_VALIDATION_FAILED,
    MEDIA_URL_EXPIRED,
    HTTP_401,
    HTTP_403,
    HTTP_404,
    HTTP_429,
    SERVER_ERROR,
    NETWORK_TIMEOUT,
    STORAGE_ERROR,
    VERIFICATION_FAILED,
    SAME_POST_ALREADY_DOWNLOADED,
    SAME_POST_CURRENTLY_DOWNLOADING,
    DUPLICATE,
    CANCELLED,
    UNKNOWN
}

data class DownloadItem(
    val index: Int,
    val mediaType: MediaType,
    val sourceUrl: String,
    val fileName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val localUri: Uri? = null,
    val error: String? = null,
    val errorCode: DownloadErrorCode? = null,
    val isVerified: Boolean = false
)

data class DownloadProgress(
    val transactionId: String,
    val shortcode: String,
    val currentItemIndex: Int,
    val totalItems: Int,
    val currentItemBytes: Long,
    val currentItemTotalBytes: Long?,
    val currentItemPercent: Int,
    val overallPercent: Int,
    val statusMessage: String
)

data class DownloadResult(
    val transactionId: String,
    val shortcode: String?,
    val success: Boolean,
    val isPartial: Boolean = false,
    val postType: PostType?,
    val items: List<DownloadItem>,
    val completedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val totalBytes: Long,
    val elapsedTimeMs: Long,
    val error: String? = null,
    val errorCode: DownloadErrorCode? = null
)
