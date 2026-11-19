package com.instadown.assist.download

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.instadown.assist.download.model.*
import com.instadown.assist.model.AutomationResult
import com.instadown.assist.resolver.CompositeInstagramResolver
import com.instadown.assist.resolver.InstagramResolver
import com.instadown.assist.resolver.UrlNormalizer
import com.instadown.assist.resolver.model.ResolveResult
import com.instadown.assist.service.InstaAccessibilityService
import com.instadown.assist.util.DownloadNotificationManager
import kotlinx.coroutines.*

class InstagramDownloadCoordinator(
    private val context: Context,
    val resolver: InstagramResolver = CompositeInstagramResolver(context),
    private val downloadManager: MediaDownloadManager = MediaDownloadManager(context)
) {

    companion object {
        private const val TAG = "DownloadCoordinator"
    }

    private val coordinatorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Executes the complete one-tap download flow from Instagram UI:
     * 1. Automates Share -> Copy Link via InstaAccessibilityService
     * 2. Immediately dismisses the Share surface and restores Instagram
     * 3. Resolves post metadata in background
     * 4. Downloads media streams to MediaStore in background
     * 5. Emits real-time progress callbacks to update the floating overlay button
     */
    fun startOneTapDownload(
        postTypeCategory: String,
        triggerBounds: Rect? = null,
        triggerPoint: PointF? = null,
        onRequestFocus: ((Boolean) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
        onProgress: ((DownloadProgress) -> Unit)? = null,
        onComplete: ((DownloadResult) -> Unit)? = null
    ) {
        val service = InstaAccessibilityService.instance
        if (service == null) {
            val fail = DownloadResult(
                transactionId = "NO_A11Y",
                shortcode = null,
                success = false,
                postType = null,
                items = emptyList(),
                completedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                totalBytes = 0L,
                elapsedTimeMs = 0L,
                error = "Accessibility service is not active",
                errorCode = DownloadErrorCode.TARGET_SELECTION_FAILED
            )
            onComplete?.invoke(fail)
            return
        }

        onStatusUpdate?.invoke("Locating post...")

        // Step 1: Execute Accessibility Automation
        service.startCopyLinkAutomation(
            postType = postTypeCategory,
            triggerBounds = triggerBounds,
            triggerPoint = triggerPoint,
            onRequestFocus = onRequestFocus,
            onStatusUpdate = onStatusUpdate,
            onComplete = { autoResult ->
                // Step 2: At this point, the AutomationEngine has ALREADY dismissed the Share sheet
                // and restored Instagram! Now we proceed with background resolution and download.
                coordinatorScope.launch(Dispatchers.IO) {
                    handleAutomationResult(
                        autoResult = autoResult,
                        onStatusUpdate = onStatusUpdate,
                        onProgress = onProgress,
                        onComplete = onComplete
                    )
                }
            }
        )
    }

    /**
     * Handles background resolution and download after URL has been safely captured.
     */
    private suspend fun handleAutomationResult(
        autoResult: AutomationResult,
        onStatusUpdate: ((String) -> Unit)?,
        onProgress: ((DownloadProgress) -> Unit)?,
        onComplete: ((DownloadResult) -> Unit)?
    ) {
        if (autoResult.verdict != "PASS" || !autoResult.validInstagramUrl || autoResult.instagramUrl.isBlank()) {
            com.instadown.assist.overlay.FloatingHudBus.emit(
                com.instadown.assist.overlay.FloatingHudState.Error("", "Instagram URL capture failed")
            )
            val fail = DownloadResult(
                transactionId = "AUTO_FAIL_${System.currentTimeMillis() % 10000}",
                shortcode = null,
                success = false,
                postType = null,
                items = emptyList(),
                completedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                totalBytes = 0L,
                elapsedTimeMs = autoResult.totalTimeMs,
                error = autoResult.failureReason.ifBlank { "Failed to capture post URL from Instagram" },
                errorCode = DownloadErrorCode.INSTAGRAM_URL_CAPTURE_FAILED
            )
            try {
                val e2eJson = org.json.JSONObject().apply {
                    put("transactionId", fail.transactionId)
                    put("timestamp", System.currentTimeMillis())
                    put("interactiveLatencyMs", autoResult.totalTimeMs)
                    put("downloadLatencyMs", 0)
                    put("totalTimeMs", autoResult.totalTimeMs)
                    put("postTypeCategory", autoResult.postType)
                    put("selectedPostFingerprint", autoResult.postFingerprint)
                    put("selectedPostSummary", autoResult.postContainerSummary)
                    put("capturedUrl", autoResult.instagramUrl)
                    put("resolvedShortcode", "")
                    put("postType", null as String?)
                    put("shareCandidateScore", autoResult.shareCandidateScore)
                    put("shareScoreMargin", autoResult.shareScoreMargin)
                    put("copyCandidateScore", autoResult.copyCandidateScore)
                    put("copyScoreMargin", autoResult.copyScoreMargin)
                    put("mediaItemCount", 0)
                    put("completedCount", 0)
                    put("failedCount", 0)
                    put("skippedCount", 0)
                    put("totalBytes", 0)
                    put("success", false)
                    put("isPartial", false)
                    put("error", fail.error)
                    put("errorCode", fail.errorCode?.name)
                    put("items", org.json.JSONArray())
                }
                Log.i("E2E_TELEMETRY", "RESULT_JSON: $e2eJson")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize failure E2E telemetry", e)
            }
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke("FAIL: ${fail.error?.take(25)}")
                onComplete?.invoke(fail)
            }
            return
        }

        val autoShortcode = UrlNormalizer.normalize(autoResult.instagramUrl)?.shortcode ?: ""
        com.instadown.assist.overlay.FloatingHudBus.emit(
            com.instadown.assist.overlay.FloatingHudState.LinkDetected(autoShortcode)
        )

        // 1. Resolve media metadata first so actual post type and item count are known
        withContext(Dispatchers.Main) {
            onStatusUpdate?.invoke("Resolving post...")
            com.instadown.assist.overlay.FloatingHudBus.emit(
                com.instadown.assist.overlay.FloatingHudState.Resolving(autoShortcode, "Resolving Post...")
            )
        }
        val resolveStart = System.currentTimeMillis()
        val resolveResult = resolver.resolve(autoResult.instagramUrl, validateCdn = false)
        val resolveLatency = System.currentTimeMillis() - resolveStart
        Log.i(TAG, "[RESOLVE] success=${resolveResult.success}, latency=${resolveLatency}ms, items=${resolveResult.items.size}")

        if (!resolveResult.success) {
            val fail = DownloadResult(
                transactionId = "RESOLVE_FAIL",
                shortcode = UrlNormalizer.normalize(autoResult.instagramUrl)?.shortcode,
                success = false,
                postType = resolveResult.postType,
                items = emptyList(),
                completedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                totalBytes = 0L,
                elapsedTimeMs = resolveLatency,
                error = resolveResult.error?.message ?: "Failed to resolve media metadata",
                errorCode = DownloadErrorCode.RESOLUTION_FAILED
            )
            com.instadown.assist.overlay.FloatingHudBus.emit(
                com.instadown.assist.overlay.FloatingHudState.Error(
                    shortcode = autoShortcode,
                    message = "Could not resolve post"
                )
            )
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke("FAIL: ${fail.error?.take(25)}")
                onComplete?.invoke(fail)
            }
            return
        }

        val prefs = com.instadown.assist.overlay.InstaSavePreferences(context)
        val behavior = prefs.carouselDownloadBehavior
        var targetIndices: Set<Int>? = null

        // 2. Carousel behavior evaluation if post has multiple items (N > 1)
        // Single images and reels (N = 1) always download directly across all 3 carousel behaviors
        if (resolveResult.items.size > 1) {
            when (behavior) {
                com.instadown.assist.overlay.CarouselDownloadBehavior.ASK -> {
                    val clipboardCoordinator = ClipboardDownloadCoordinator(context)
                    val observedSlide = com.instadown.assist.service.InstaAccessibilityService.lastObservedSlide
                    val slideEval = clipboardCoordinator.resolveCurrentSlideIndex(
                        autoResult = autoResult,
                        totalMediaItems = resolveResult.items.size,
                        observedSlide = observedSlide
                    )
                    val highConfidenceSlideIndex = (slideEval as? ClipboardDownloadCoordinator.SlideEvaluationResult.Valid)?.targetIndex

                    withContext(Dispatchers.Main) {
                        onStatusUpdate?.invoke("Carousel detected")
                        DownloadNotificationManager.showCarouselPromptNotification(
                            context = context,
                            url = autoResult.instagramUrl,
                            itemCount = resolveResult.items.size,
                            highConfidenceSlideIndex = highConfidenceSlideIndex
                        )
                    }

                    val promptPostedResult = DownloadResult(
                        transactionId = "ASK_PROMPT_POSTED",
                        shortcode = resolveResult.shortcode,
                        success = false,
                        postType = resolveResult.postType,
                        items = emptyList(),
                        completedCount = 0,
                        failedCount = 0,
                        skippedCount = 0,
                        totalBytes = 0L,
                        elapsedTimeMs = resolveLatency,
                        error = null
                    )
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(promptPostedResult)
                    }
                    return
                }
                com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM -> {
                    val clipboardCoordinator = ClipboardDownloadCoordinator(context)
                    val observedSlide = com.instadown.assist.service.InstaAccessibilityService.lastObservedSlide
                    val slideEval = clipboardCoordinator.resolveCurrentSlideIndex(
                        autoResult = autoResult,
                        totalMediaItems = resolveResult.items.size,
                        observedSlide = observedSlide
                    )

                    when (slideEval) {
                        is ClipboardDownloadCoordinator.SlideEvaluationResult.Valid -> {
                            Log.i(TAG, "CURRENT_ITEM corroborated slide index=${slideEval.targetIndex} (${slideEval.reason})")
                            targetIndices = setOf(slideEval.targetIndex)
                        }
                        is ClipboardDownloadCoordinator.SlideEvaluationResult.Ambiguous -> {
                            Log.w(TAG, "CURRENT_ITEM ambiguous slide: ${slideEval.reason}. SAFE REFUSAL.")
                            val fail = DownloadResult(
                                transactionId = "SAFE_REFUSAL_AMBIGUOUS_SLIDE",
                                shortcode = resolveResult.shortcode,
                                success = false,
                                postType = resolveResult.postType,
                                items = emptyList(),
                                completedCount = 0,
                                failedCount = 0,
                                skippedCount = 0,
                                totalBytes = 0L,
                                elapsedTimeMs = resolveLatency,
                                error = "Safe Refusal: Couldn't identify the current slide (${slideEval.reason})",
                                errorCode = DownloadErrorCode.TARGET_SELECTION_FAILED
                            )
                            withContext(Dispatchers.Main) {
                                onStatusUpdate?.invoke("REFUSED: Ambiguous slide")
                                onComplete?.invoke(fail)
                            }
                            return
                        }
                    }
                }
                com.instadown.assist.overlay.CarouselDownloadBehavior.ENTIRE_CAROUSEL -> {
                    targetIndices = null
                }
            }
        }

        // Direct download execution with pre-resolved result
        val downloadResult = executeDirectDownload(
            url = autoResult.instagramUrl,
            selectedIndices = targetIndices,
            preResolvedResult = resolveResult,
            onStatusUpdate = onStatusUpdate,
            onProgress = onProgress
        )

        // Log comprehensive End-to-End JSON telemetry
        try {
            val e2eJson = org.json.JSONObject().apply {
                put("transactionId", downloadResult.transactionId)
                put("timestamp", System.currentTimeMillis())
                put("interactiveLatencyMs", autoResult.totalTimeMs)
                put("downloadLatencyMs", downloadResult.elapsedTimeMs)
                put("totalTimeMs", autoResult.totalTimeMs + downloadResult.elapsedTimeMs)
                put("postTypeCategory", autoResult.postType)
                put("selectedPostFingerprint", autoResult.postFingerprint)
                put("selectedPostSummary", autoResult.postContainerSummary)
                put("capturedUrl", autoResult.instagramUrl)
                put("resolvedShortcode", downloadResult.shortcode)
                put("postType", downloadResult.postType?.name)
                put("shareCandidateScore", autoResult.shareCandidateScore)
                put("shareScoreMargin", autoResult.shareScoreMargin)
                put("copyCandidateScore", autoResult.copyCandidateScore)
                put("copyScoreMargin", autoResult.copyScoreMargin)
                put("mediaItemCount", downloadResult.items.size)
                put("completedCount", downloadResult.completedCount)
                put("failedCount", downloadResult.failedCount)
                put("skippedCount", downloadResult.skippedCount)
                put("totalBytes", downloadResult.totalBytes)
                put("success", downloadResult.success)
                put("isPartial", downloadResult.isPartial)
                put("error", downloadResult.error)
                put("errorCode", downloadResult.errorCode?.name)
                put("items", org.json.JSONArray().apply {
                    for (item in downloadResult.items) {
                        put(org.json.JSONObject().apply {
                            put("index", item.index)
                            put("mediaType", item.mediaType.name)
                            put("fileName", item.fileName)
                            put("mimeType", item.mimeType)
                            put("width", item.width)
                            put("height", item.height)
                            put("bytesDownloaded", item.bytesDownloaded)
                            put("localUri", item.localUri?.toString())
                            put("isVerified", item.isVerified)
                            put("status", item.status.name)
                        })
                    }
                })
            }
            Log.i("E2E_TELEMETRY", "RESULT_JSON: $e2eJson")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize E2E telemetry", e)
        }

        withContext(Dispatchers.Main) {
            onComplete?.invoke(downloadResult)
        }
    }

    /**
     * Executes standalone resolution and download for an Instagram URL.
     * Can be invoked directly from tests or UI activities.
     */
    suspend fun executeDirectDownload(
        url: String,
        selectedIndices: Set<Int>? = null,
        recordInDb: Boolean = true,
        preResolvedResult: com.instadown.assist.resolver.model.ResolveResult? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): DownloadResult = withContext(Dispatchers.IO) {
        val normalized = UrlNormalizer.normalize(url)
        if (normalized == null) {
            return@withContext DownloadResult(
                transactionId = "INVALID_URL",
                shortcode = null,
                success = false,
                postType = null,
                items = emptyList(),
                completedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                totalBytes = 0L,
                elapsedTimeMs = 0L,
                error = "Could not parse shortcode from URL: $url",
                errorCode = DownloadErrorCode.INSTAGRAM_URL_CAPTURE_FAILED
            )
        }

        val shortcode = normalized.shortcode

        com.instadown.assist.overlay.FloatingHudBus.emit(
            com.instadown.assist.overlay.FloatingHudState.Resolving(shortcode)
        )

        // 1. Resolve media metadata (or reuse preResolvedResult if provided)
        val resolveStart = System.currentTimeMillis()
        val resolveResult = if (preResolvedResult != null && preResolvedResult.success) {
            preResolvedResult
        } else {
            withContext(Dispatchers.Main) { onStatusUpdate?.invoke("Resolving post...") }
            resolver.resolve(url, validateCdn = false)
        }
        val resolveLatency = System.currentTimeMillis() - resolveStart
        Log.i(TAG, "[RESOLVE] success=${resolveResult.success}, latency=${resolveLatency}ms, items=${resolveResult.items.size}")

        if (!resolveResult.success) {
            val fail = DownloadResult(
                transactionId = "RESOLVE_FAIL",
                shortcode = shortcode,
                success = false,
                postType = resolveResult.postType,
                items = emptyList(),
                completedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                totalBytes = 0L,
                elapsedTimeMs = resolveLatency,
                error = resolveResult.error?.message ?: "Failed to resolve media metadata",
                errorCode = DownloadErrorCode.RESOLUTION_FAILED
            )
            com.instadown.assist.overlay.FloatingHudBus.emit(
                com.instadown.assist.overlay.FloatingHudState.Error(
                    shortcode = shortcode,
                    message = fail.error ?: "Resolution failed"
                )
            )
            return@withContext fail
        }

        // 2. Formulate download plan and enforce all 6 safety preconditions
        withContext(Dispatchers.Main) { onStatusUpdate?.invoke("Planning download...") }
        val plan = DownloadPlanner.createPlan(
            expectedShortcode = shortcode,
            resolveResult = resolveResult,
            selectedIndices = selectedIndices
        )

        if (!plan.success) {
            val fail = DownloadResult(
                transactionId = "PLAN_FAIL",
                shortcode = shortcode,
                success = false,
                postType = plan.postType,
                items = emptyList(),
                completedCount = 0,
                failedCount = 0,
                skippedCount = 0,
                totalBytes = 0L,
                elapsedTimeMs = resolveLatency,
                error = plan.error ?: "Download safety checks failed",
                errorCode = plan.errorCode ?: DownloadErrorCode.VERIFICATION_FAILED
            )
            com.instadown.assist.overlay.FloatingHudBus.emit(
                com.instadown.assist.overlay.FloatingHudState.Error(
                    shortcode = shortcode,
                    message = fail.error ?: "Safety verification failed"
                )
            )
            return@withContext fail
        }

        // 3. Execute streaming downloads to MediaStore with re-resolve fallback on expired URLs
        withContext(Dispatchers.Main) {
            onStatusUpdate?.invoke(if (plan.items.size > 1) "Saving 1/${plan.items.size}..." else "Downloading...")
        }

        val result = downloadManager.executeDownload(
            plan = plan,
            reResolveCallback = { expiredShortcode ->
                Log.i(TAG, "Re-resolving expired shortcode $expiredShortcode...")
                val freshResult = resolver.resolve("https://www.instagram.com/p/$expiredShortcode/", validateCdn = false)
                if (freshResult.success) freshResult else null
            },
            onProgress = { progress ->
                com.instadown.assist.overlay.FloatingHudBus.emit(
                    com.instadown.assist.overlay.FloatingHudState.Downloading(
                        shortcode = shortcode,
                        percent = progress.overallPercent,
                        currentItem = progress.currentItemIndex + 1,
                        totalItems = progress.totalItems,
                        message = progress.statusMessage
                    )
                )
                mainHandler.post {
                    onStatusUpdate?.invoke(progress.statusMessage)
                    onProgress?.invoke(progress)
                }
            }
        )

        if (result.success) {
            com.instadown.assist.overlay.FloatingHudBus.emit(
                com.instadown.assist.overlay.FloatingHudState.Success(
                    shortcode = shortcode,
                    message = if (result.completedCount > 1) "Saved ${result.completedCount} items" else "Saved to Gallery",
                    totalItems = result.completedCount
                )
            )
        } else {
            com.instadown.assist.overlay.FloatingHudBus.emit(
                com.instadown.assist.overlay.FloatingHudState.Error(
                    shortcode = shortcode,
                    message = result.error ?: "Download failed"
                )
            )
        }

        if (recordInDb) {
            val repo = com.instadown.assist.data.db.DownloadRepository.getInstance(context)
            // Immediately extract / generate a low-resolution persistent thumbnail into filesDir/thumbnails/${shortcode}.jpg
            val candidateItem = result.items.firstOrNull { it.localUri != null }
            var thumbPath = if (candidateItem != null && shortcode.isNotBlank()) {
                repo.extractAndSaveThumbnail(
                    shortcode = shortcode,
                    uriStr = candidateItem.localUri.toString(),
                    isVideo = candidateItem.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO
                )
            } else null

            // Fallback: If local file wasn't generated yet or item already existed, save from resolved preview thumbnail
            if (thumbPath.isNullOrBlank() && shortcode.isNotBlank()) {
                val remoteThumb = resolveResult.items.firstOrNull()?.thumbnailUrl ?: resolveResult.items.firstOrNull()?.mediaUrl
                if (!remoteThumb.isNullOrBlank()) {
                    thumbPath = repo.extractAndSaveThumbnail(
                        shortcode = shortcode,
                        uriStr = remoteThumb,
                        isVideo = resolveResult.items.firstOrNull()?.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO
                    )
                }
            }

            repo.recordDownloadResult(result, thumbnailPath = thumbPath)

            // Auto audio extraction for Reels if enabled in preferences
            val prefs = com.instadown.assist.overlay.InstaSavePreferences(context)
            if (prefs.isAudioExtractionEnabled && result.success) {
                coordinatorScope.launch(Dispatchers.IO) {
                    result.items.filter { it.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO && it.localUri != null }.forEach { vidItem ->
                        try {
                            com.instadown.assist.download.ReelAudioExtractor.extractAudio(
                                context = context,
                                videoUri = vidItem.localUri!!,
                                shortcode = shortcode
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "Auto audio extraction notice: ${e.message}")
                        }
                    }
                }
            }

            // Asynchronously fetch creator handle & thumbnail in background
            coordinatorScope.launch(Dispatchers.IO) {
                try {
                    com.instadown.assist.util.InstagramMetadataFetcher.fetchAndStoreMetadata(context, shortcode)
                } catch (e: Exception) {
                    Log.d(TAG, "Background metadata fetch notice: ${e.message}")
                }
            }
        }

        return@withContext result
    }
}
