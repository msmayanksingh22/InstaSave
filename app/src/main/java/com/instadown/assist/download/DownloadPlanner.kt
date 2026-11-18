package com.instadown.assist.download

import android.util.Log
import com.instadown.assist.download.model.DownloadErrorCode
import com.instadown.assist.download.model.DownloadItem
import com.instadown.assist.download.model.DownloadStatus
import com.instadown.assist.resolver.model.MediaType
import com.instadown.assist.resolver.model.PostType
import com.instadown.assist.resolver.model.ResolveResult

object DownloadPlanner {

    private const val TAG = "DownloadPlanner"

    data class PlanResult(
        val success: Boolean,
        val shortcode: String?,
        val postType: PostType?,
        val items: List<DownloadItem>,
        val error: String? = null,
        val errorCode: DownloadErrorCode? = null
    )

    /**
     * Evaluates all 6 safety preconditions and constructs a deterministic download plan.
     */
    fun createPlan(
        expectedShortcode: String,
        resolveResult: ResolveResult,
        selectedIndices: Set<Int>? = null
    ): PlanResult {
        // 1. Check syntax & input validity
        if (expectedShortcode.isBlank()) {
            return PlanResult(
                success = false,
                shortcode = null,
                postType = null,
                items = emptyList(),
                error = "Requested shortcode is blank",
                errorCode = DownloadErrorCode.INSTAGRAM_URL_CAPTURE_FAILED
            )
        }

        // 2. Check resolver result status
        if (!resolveResult.success) {
            return PlanResult(
                success = false,
                shortcode = expectedShortcode,
                postType = resolveResult.postType,
                items = emptyList(),
                error = resolveResult.error?.message ?: "Media resolution failed",
                errorCode = DownloadErrorCode.RESOLUTION_FAILED
            )
        }

        // 3. Identity Verification Gate: captured shortcode == resolved shortcode
        val resolvedShortcode = resolveResult.shortcode
        if (!expectedShortcode.equals(resolvedShortcode, ignoreCase = true)) {
            Log.e(TAG, "SAFETY GATE REJECTION: Expected shortcode '$expectedShortcode' != resolved shortcode '$resolvedShortcode'")
            return PlanResult(
                success = false,
                shortcode = expectedShortcode,
                postType = resolveResult.postType,
                items = emptyList(),
                error = "Identity mismatch: captured post '$expectedShortcode' != resolved post '$resolvedShortcode'",
                errorCode = DownloadErrorCode.IDENTITY_VERIFICATION_FAILED
            )
        }

        // 4. Verify media payload exists
        val rawItems = resolveResult.items
        if (rawItems.isEmpty()) {
            return PlanResult(
                success = false,
                shortcode = expectedShortcode,
                postType = resolveResult.postType,
                items = emptyList(),
                error = "No media items returned in resolved post",
                errorCode = DownloadErrorCode.MEDIA_VALIDATION_FAILED
            )
        }

        val postType = resolveResult.postType ?: when {
            rawItems.size > 1 -> PostType.CAROUSEL
            rawItems.any { it.mediaType == MediaType.VIDEO } -> PostType.REEL
            else -> PostType.PHOTO
        }

        // 5. Build ordered items with deterministic naming and index verification
        val plannedItems = mutableListOf<DownloadItem>()
        val isCarousel = rawItems.size > 1

        if (selectedIndices != null && selectedIndices.isEmpty()) {
            return PlanResult(
                success = false,
                shortcode = expectedShortcode,
                postType = postType,
                items = emptyList(),
                error = "No items selected for download",
                errorCode = DownloadErrorCode.VERIFICATION_FAILED
            )
        }

        for (i in rawItems.indices) {
            val resolvedItem = rawItems[i]

            // Carousel safety gate: verify strict sequence
            if (isCarousel && resolvedItem.index != i) {
                Log.e(TAG, "SAFETY GATE REJECTION: Carousel index sequence broken at $i (got ${resolvedItem.index})")
                return PlanResult(
                    success = false,
                    shortcode = expectedShortcode,
                    postType = postType,
                    items = emptyList(),
                    error = "Carousel sequence ordering violation at index $i",
                    errorCode = DownloadErrorCode.VERIFICATION_FAILED
                )
            }

            // If a specific subset was selected, skip unselected slides
            if (selectedIndices != null && !selectedIndices.contains(i)) {
                continue
            }

            // Verify media URL is valid
            val mediaUrl = resolvedItem.mediaUrl
            if (mediaUrl.isBlank()) {
                return PlanResult(
                    success = false,
                    shortcode = expectedShortcode,
                    postType = postType,
                    items = emptyList(),
                    error = "Item $i has empty media URL",
                    errorCode = DownloadErrorCode.MEDIA_VALIDATION_FAILED
                )
            }

            val ext = when (resolvedItem.mediaType) {
                MediaType.VIDEO -> ".mp4"
                MediaType.IMAGE -> ".jpg"
            }

            val fileName = if (isCarousel) {
                val indexPad = String.format("%02d", i + 1)
                "Instagram_${expectedShortcode}_$indexPad$ext"
            } else {
                "Instagram_${expectedShortcode}$ext"
            }

            val mimeType = when (resolvedItem.mediaType) {
                MediaType.VIDEO -> "video/mp4"
                MediaType.IMAGE -> "image/jpeg"
            }

            plannedItems.add(
                DownloadItem(
                    index = i,
                    mediaType = resolvedItem.mediaType,
                    sourceUrl = mediaUrl,
                    fileName = fileName,
                    mimeType = mimeType,
                    width = resolvedItem.width,
                    height = resolvedItem.height,
                    durationMs = resolvedItem.durationMs,
                    status = DownloadStatus.QUEUED,
                    bytesDownloaded = 0L,
                    totalBytes = null,
                    localUri = null,
                    error = null
                )
            )
        }

        Log.i(TAG, "Constructed download plan for $expectedShortcode: ${plannedItems.size} items (PostType=$postType)")
        return PlanResult(
            success = true,
            shortcode = expectedShortcode,
            postType = postType,
            items = plannedItems
        )
    }
}
