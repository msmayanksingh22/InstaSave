package com.instadown.assist.download

import android.content.Context
import android.util.Log
import com.instadown.assist.model.AutomationResult
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.service.InstaAccessibilityService

/**
 * Concurrency-safe and idempotent coordinator for Clipboard-based media acquisition.
 *
 * Rules:
 * 1. New URL: If valid and not currently processing -> ALLOW.
 * 2. Same URL copied again while processing -> REJECT_ALREADY_PROCESSING (no second transaction).
 * 3. Same URL after successful completion -> REJECT_ALREADY_COMPLETED (unless fresh copy timestamp).
 * 4. Different URL arrives while processing -> DEFER_QUEUED (safely deferred, no corruption).
 * 5. Activity resumes while processing -> do not start duplicate pipeline.
 * 6. Failure before meaningful start -> do not mark as permanently processed.
 */
class ClipboardDownloadCoordinator(private val context: Context? = null) {

    companion object {
        private const val TAG = "InstaDownloaderPOC"
        private const val MAX_SLIDE_OBSERVATION_AGE_MS = 60_000L // 60 seconds
    }

    enum class ProcessDecision {
        ALLOW,
        REJECT_INVALID,
        REJECT_ALREADY_PROCESSING,
        REJECT_ALREADY_COMPLETED,
        DEFER_QUEUED
    }

    sealed class SlideEvaluationResult {
        data class Valid(val targetIndex: Int, val reason: String) : SlideEvaluationResult()
        data class Ambiguous(val reason: String) : SlideEvaluationResult()
    }

    // Thread-safe lock for state transitions
    private val stateLock = Any()

    @Volatile
    var currentlyProcessingClipboardUrl: String? = null
        private set

    @Volatile
    var pendingClipboardUrl: String? = null
        private set

    @Volatile
    var pendingClipboardTimestamp: Long = 0L
        private set

    val isProcessing: Boolean
        get() = currentlyProcessingClipboardUrl != null

    /**
     * Evaluates whether an incoming clipboard URL can be processed under concurrency rules.
     */
    fun evaluateProcessDecision(
        cleanUrl: String,
        clipTimestamp: Long,
        lastProcessedUrl: String?,
        lastProcessedTimestamp: Long
    ): ProcessDecision = synchronized(stateLock) {
        if (cleanUrl.isBlank()) {
            return ProcessDecision.REJECT_INVALID
        }

        // Rule 2 & 5: If this exact URL is already in-flight, do not start a duplicate
        if (currentlyProcessingClipboardUrl == cleanUrl) {
            Log.d(TAG, "ClipboardDecision: URL is already processing: $cleanUrl")
            return ProcessDecision.REJECT_ALREADY_PROCESSING
        }

        // Rule 4: If another URL is currently downloading, queue the new URL
        if (currentlyProcessingClipboardUrl != null) {
            pendingClipboardUrl = cleanUrl
            pendingClipboardTimestamp = clipTimestamp
            Log.i(TAG, "ClipboardDecision: Another transaction active ($currentlyProcessingClipboardUrl). Deferring $cleanUrl")
            return ProcessDecision.DEFER_QUEUED
        }

        // Rule 3: If this URL was already successfully completed, verify if it's a new copy event
        if (cleanUrl == lastProcessedUrl) {
            // Only re-process if the clipboard timestamp indicates a newer copy action
            if (clipTimestamp > 0L && clipTimestamp <= lastProcessedTimestamp) {
                Log.d(TAG, "ClipboardDecision: URL was already completed and clipboard timestamp has not changed ($clipTimestamp <= $lastProcessedTimestamp)")
                return ProcessDecision.REJECT_ALREADY_COMPLETED
            } else if (clipTimestamp == 0L) {
                // If timestamp is unavailable and URL matches last processed, prevent repeat auto-download
                Log.d(TAG, "ClipboardDecision: URL was already completed and no fresh timestamp present")
                return ProcessDecision.REJECT_ALREADY_COMPLETED
            }
        }

        return ProcessDecision.ALLOW
    }

    fun evaluateProcessDecision(
        cleanUrl: String,
        clipTimestamp: Long,
        prefs: InstaSavePreferences
    ): ProcessDecision = evaluateProcessDecision(
        cleanUrl = cleanUrl,
        clipTimestamp = clipTimestamp,
        lastProcessedUrl = prefs.lastProcessedClipboardUrl,
        lastProcessedTimestamp = prefs.lastProcessedClipboardTimestamp
    )

    /**
     * Marks the transaction as actively processing.
     */
    fun startProcessing(cleanUrl: String) = synchronized(stateLock) {
        currentlyProcessingClipboardUrl = cleanUrl
        Log.i(TAG, "ClipboardProcessing: Started for $cleanUrl")
    }

    /**
     * Marks the transaction as successfully completed and persists state.
     */
    fun onProcessingSuccess(
        cleanUrl: String,
        clipTimestamp: Long,
        prefs: InstaSavePreferences? = null
    ) = synchronized(stateLock) {
        if (currentlyProcessingClipboardUrl == cleanUrl) {
            currentlyProcessingClipboardUrl = null
        }
        val recordedTs = if (clipTimestamp > 0L) clipTimestamp else System.currentTimeMillis()
        prefs?.lastProcessedClipboardUrl = cleanUrl
        prefs?.lastProcessedClipboardTimestamp = recordedTs
        Log.i(TAG, "ClipboardProcessing: Success for $cleanUrl (recorded timestamp=$recordedTs)")
    }

    /**
     * Marks the transaction as failed.
     * Crucial: Does NOT mark URL as permanently processed so retries are possible.
     */
    fun onProcessingFailed(cleanUrl: String) = synchronized(stateLock) {
        if (currentlyProcessingClipboardUrl == cleanUrl) {
            currentlyProcessingClipboardUrl = null
        }
        Log.w(TAG, "ClipboardProcessing: Failed for $cleanUrl (URL not permanently marked as processed)")
    }

    /**
     * Retrieves and clears any queued pending URL.
     */
    fun pollPending(): Pair<String, Long>? = synchronized(stateLock) {
        val url = pendingClipboardUrl ?: return null
        val ts = pendingClipboardTimestamp
        pendingClipboardUrl = null
        pendingClipboardTimestamp = 0L
        return url to ts
    }

    /**
     * Resets in-memory state (useful for tests or teardown).
     */
    fun resetState() = synchronized(stateLock) {
        currentlyProcessingClipboardUrl = null
        pendingClipboardUrl = null
        pendingClipboardTimestamp = 0L
    }

    /**
     * Corroborates container-bound slide evidence for CarouselBehavior.CURRENT_ITEM.
     *
     * Invariants:
     * - Must be bound to the selected post container
     * - Slide indicator must match total resolved carousel item count
     * - Must have recent observation timestamp
     * - Inconsistent or external indicators -> SAFE REFUSAL (0 items, never fall back to Entire)
     */
    fun resolveCurrentSlideIndex(
        autoResult: AutomationResult?,
        totalMediaItems: Int,
        observedSlide: InstaAccessibilityService.ObservedSlideInfo?,
        currentTimeMs: Long = System.currentTimeMillis()
    ): SlideEvaluationResult {
        if (totalMediaItems <= 1) {
            // Single image or reel: not a carousel, slide 0 is always valid
            return SlideEvaluationResult.Valid(0, "Single media item")
        }

        val containerSlide = autoResult?.carouselSlideIndex
        val containerTotal = autoResult?.carouselTotalSlides

        // 1. Evaluate container-bound slide evidence from PostDetector
        val isContainerEvidenceValid = containerSlide != null &&
                containerTotal != null &&
                containerTotal == totalMediaItems &&
                containerSlide in 0 until totalMediaItems

        // 2. Evaluate recent observed slide evidence from accessibility event stream
        val isObservationFresh = observedSlide != null &&
                (currentTimeMs - observedSlide.timestamp) in 0..MAX_SLIDE_OBSERVATION_AGE_MS
        val isObservationTotalMatch = observedSlide != null &&
                observedSlide.totalSlides == totalMediaItems
        val isObservationIndexValid = observedSlide != null &&
                observedSlide.currentSlideIndex in 0 until totalMediaItems

        val isObservedEvidenceValid = isObservationFresh && isObservationTotalMatch && isObservationIndexValid

        // 3. Corroborate signals
        if (isContainerEvidenceValid && isObservedEvidenceValid) {
            val cIdx = containerSlide!!
            val oIdx = observedSlide!!.currentSlideIndex
            return if (cIdx == oIdx) {
                SlideEvaluationResult.Valid(cIdx, "Corroborated: Container and observation agree on slide ${cIdx + 1}/$totalMediaItems")
            } else {
                // If observation is very recent (< 5s), user may have swiped right before triggering
                if ((currentTimeMs - observedSlide.timestamp) < 5_000L) {
                    SlideEvaluationResult.Valid(oIdx, "Recent user swipe observed (${oIdx + 1}/$totalMediaItems)")
                } else {
                    SlideEvaluationResult.Ambiguous("Inconsistent slide indicators: container=$cIdx vs observed=$oIdx for total=$totalMediaItems")
                }
            }
        }

        if (isContainerEvidenceValid) {
            return SlideEvaluationResult.Valid(
                containerSlide!!,
                "Container-bound slide indicator verified: ${containerSlide + 1}/$totalMediaItems"
            )
        }

        if (isObservedEvidenceValid) {
            return SlideEvaluationResult.Valid(
                observedSlide!!.currentSlideIndex,
                "Fresh observed slide indicator verified: ${observedSlide.currentSlideIndex + 1}/$totalMediaItems"
            )
        }

        // Ambiguous or missing evidence -> SAFE REFUSAL
        val reason = when {
            containerTotal != null && containerTotal != totalMediaItems ->
                "Container slide total ($containerTotal) does not match resolved media count ($totalMediaItems)"
            observedSlide != null && observedSlide.totalSlides != totalMediaItems ->
                "Observed slide total (${observedSlide.totalSlides}) does not match resolved media count ($totalMediaItems)"
            observedSlide != null && !isObservationFresh ->
                "Observed slide is stale (${(currentTimeMs - observedSlide.timestamp) / 1000}s old)"
            else ->
                "No container-associated slide indicator found"
        }

        Log.w(TAG, "resolveCurrentSlideIndex: SAFE REFUSAL - $reason")
        return SlideEvaluationResult.Ambiguous(reason)
    }
}
