package com.instadown.assist.download

import com.instadown.assist.model.AutomationResult
import com.instadown.assist.service.InstaAccessibilityService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClipboardConcurrencyTest {

    private lateinit var coordinator: ClipboardDownloadCoordinator

    @Before
    fun setUp() {
        coordinator = ClipboardDownloadCoordinator()
        coordinator.resetState()
    }

    @Test
    fun testNewValidUrl_Allowed() {
        val url = "https://www.instagram.com/p/C_sample1/"
        val decision = coordinator.evaluateProcessDecision(
            cleanUrl = url,
            clipTimestamp = 1000L,
            lastProcessedUrl = null,
            lastProcessedTimestamp = 0L
        )
        assertEquals(ClipboardDownloadCoordinator.ProcessDecision.ALLOW, decision)
    }

    @Test
    fun testSameUrlCopiedAgainWhileProcessing_RejectedDuplicate() {
        val url = "https://www.instagram.com/p/C_sample1/"
        coordinator.startProcessing(url)
        assertTrue(coordinator.isProcessing)
        assertEquals(url, coordinator.currentlyProcessingClipboardUrl)

        val decision = coordinator.evaluateProcessDecision(
            cleanUrl = url,
            clipTimestamp = 1100L,
            lastProcessedUrl = null,
            lastProcessedTimestamp = 0L
        )
        assertEquals(ClipboardDownloadCoordinator.ProcessDecision.REJECT_ALREADY_PROCESSING, decision)
    }

    @Test
    fun testActivityResumeWhileProcessing_RejectedDuplicate() {
        val url = "https://www.instagram.com/reel/C_reel123/"
        coordinator.startProcessing(url)

        // Activity resumes and checks clipboard again
        val decisionOnResume = coordinator.evaluateProcessDecision(
            cleanUrl = url,
            clipTimestamp = 1000L,
            lastProcessedUrl = null,
            lastProcessedTimestamp = 0L
        )
        assertEquals(ClipboardDownloadCoordinator.ProcessDecision.REJECT_ALREADY_PROCESSING, decisionOnResume)
    }

    @Test
    fun testSameUrlAfterSuccessfulCompletion_RejectedIfNotNewerTimestamp() {
        val url = "https://www.instagram.com/p/C_sample1/"
        val completionTime = 5000L
        coordinator.startProcessing(url)
        coordinator.onProcessingSuccess(url, clipTimestamp = completionTime)

        assertFalse(coordinator.isProcessing)

        // Same URL evaluated again with unchanged timestamp
        val decision = coordinator.evaluateProcessDecision(
            cleanUrl = url,
            clipTimestamp = completionTime,
            lastProcessedUrl = url,
            lastProcessedTimestamp = completionTime
        )
        assertEquals(ClipboardDownloadCoordinator.ProcessDecision.REJECT_ALREADY_COMPLETED, decision)
    }

    @Test
    fun testSameUrlAfterSuccessfulCompletion_AllowedIfNewerTimestamp() {
        val url = "https://www.instagram.com/p/C_sample1/"
        val firstCopyTime = 5000L
        coordinator.startProcessing(url)
        coordinator.onProcessingSuccess(url, clipTimestamp = firstCopyTime)

        // User copies the same URL again 10 seconds later
        val newCopyTime = 15000L
        val decision = coordinator.evaluateProcessDecision(
            cleanUrl = url,
            clipTimestamp = newCopyTime,
            lastProcessedUrl = url,
            lastProcessedTimestamp = firstCopyTime
        )
        assertEquals(ClipboardDownloadCoordinator.ProcessDecision.ALLOW, decision)
    }

    @Test
    fun testDifferentUrlWhileProcessing_SafelyQueuedAndDeferred() {
        val url1 = "https://www.instagram.com/p/C_first/"
        val url2 = "https://www.instagram.com/p/C_second/"

        coordinator.startProcessing(url1)

        val decision = coordinator.evaluateProcessDecision(
            cleanUrl = url2,
            clipTimestamp = 2000L,
            lastProcessedUrl = null,
            lastProcessedTimestamp = 0L
        )
        assertEquals(ClipboardDownloadCoordinator.ProcessDecision.DEFER_QUEUED, decision)
        assertEquals(url2, coordinator.pendingClipboardUrl)
        assertEquals(2000L, coordinator.pendingClipboardTimestamp)

        // First completes
        coordinator.onProcessingSuccess(url1, clipTimestamp = 1000L)

        // Poll pending
        val pending = coordinator.pollPending()
        assertNotNull(pending)
        assertEquals(url2, pending!!.first)
        assertEquals(2000L, pending.second)
        assertNull(coordinator.pendingClipboardUrl)
    }

    @Test
    fun testFailureBeforeMeaningfulStart_DoesNotMarkPermanentlyProcessed() {
        val url = "https://www.instagram.com/p/C_retryMe/"
        coordinator.startProcessing(url)
        coordinator.onProcessingFailed(url)

        assertFalse(coordinator.isProcessing)
        assertNull(coordinator.currentlyProcessingClipboardUrl)

        // Can immediately retry without being blocked as already completed
        val retryDecision = coordinator.evaluateProcessDecision(
            cleanUrl = url,
            clipTimestamp = 1000L,
            lastProcessedUrl = null,
            lastProcessedTimestamp = 0L
        )
        assertEquals(ClipboardDownloadCoordinator.ProcessDecision.ALLOW, retryDecision)
    }

    @Test
    fun testCurrentItemCorroboration_SingleItemAlwaysValid() {
        val eval = coordinator.resolveCurrentSlideIndex(
            autoResult = null,
            totalMediaItems = 1,
            observedSlide = null
        )
        assertTrue(eval is ClipboardDownloadCoordinator.SlideEvaluationResult.Valid)
        assertEquals(0, (eval as ClipboardDownloadCoordinator.SlideEvaluationResult.Valid).targetIndex)
    }

    @Test
    fun testCurrentItemCorroboration_ContainerAndObservationMatch_Valid() {
        val now = 100_000L
        val dummyResult = AutomationResult(
            postType = "Feed post",
            startTime = "00:00:00",
            shareCandidate = "",
            shareNodeDetails = "",
            shareClickResult = "SUCCESS",
            shareDetectionTimeMs = 10L,
            copyLinkCandidate = "",
            copyLinkNodeDetails = "",
            copyLinkClickResult = "SUCCESS",
            copyLinkDetectionTimeMs = 10L,
            clipboardText = "",
            clipboardMimes = emptyList(),
            validInstagramUrl = true,
            instagramUrl = "https://www.instagram.com/p/C_abc/",
            totalTimeMs = 200L,
            verdict = "PASS",
            failureReason = "",
            formattedReport = "",
            carouselSlideIndex = 1, // slide 2
            carouselTotalSlides = 5
        )

        val observed = InstaAccessibilityService.ObservedSlideInfo(
            currentSlideIndex = 1, // slide 2
            totalSlides = 5,
            timestamp = now - 2000L
        )

        val eval = coordinator.resolveCurrentSlideIndex(
            autoResult = dummyResult,
            totalMediaItems = 5,
            observedSlide = observed,
            currentTimeMs = now
        )

        assertTrue("Should be Valid when container and observation corroborate", eval is ClipboardDownloadCoordinator.SlideEvaluationResult.Valid)
        assertEquals(1, (eval as ClipboardDownloadCoordinator.SlideEvaluationResult.Valid).targetIndex)
    }

    @Test
    fun testCurrentItemCorroboration_ObservationOnlyWithMatchingTotalAndFreshTime_Valid() {
        val now = 100_000L
        val observed = InstaAccessibilityService.ObservedSlideInfo(
            currentSlideIndex = 3, // slide 4
            totalSlides = 6,
            timestamp = now - 5000L // 5 seconds ago
        )

        val eval = coordinator.resolveCurrentSlideIndex(
            autoResult = null,
            totalMediaItems = 6,
            observedSlide = observed,
            currentTimeMs = now
        )

        assertTrue(eval is ClipboardDownloadCoordinator.SlideEvaluationResult.Valid)
        assertEquals(3, (eval as ClipboardDownloadCoordinator.SlideEvaluationResult.Valid).targetIndex)
    }

    @Test
    fun testCurrentItemCorroboration_InconsistentCounts_SafeRefusal() {
        val now = 100_000L
        val dummyResult = AutomationResult(
            postType = "Feed post",
            startTime = "00:00:00",
            shareCandidate = "",
            shareNodeDetails = "",
            shareClickResult = "SUCCESS",
            shareDetectionTimeMs = 10L,
            copyLinkCandidate = "",
            copyLinkNodeDetails = "",
            copyLinkClickResult = "SUCCESS",
            copyLinkDetectionTimeMs = 10L,
            clipboardText = "",
            clipboardMimes = emptyList(),
            validInstagramUrl = true,
            instagramUrl = "https://www.instagram.com/p/C_abc/",
            totalTimeMs = 200L,
            verdict = "PASS",
            failureReason = "",
            formattedReport = "",
            carouselSlideIndex = 1,
            carouselTotalSlides = 4 // container says 4
        )

        val eval = coordinator.resolveCurrentSlideIndex(
            autoResult = dummyResult,
            totalMediaItems = 8, // resolved post actually has 8
            observedSlide = null,
            currentTimeMs = now
        )

        assertTrue("Inconsistent slide count MUST trigger Safe Refusal", eval is ClipboardDownloadCoordinator.SlideEvaluationResult.Ambiguous)
    }

    @Test
    fun testCurrentItemCorroboration_StaleObservation_SafeRefusal() {
        val now = 100_000L
        val observed = InstaAccessibilityService.ObservedSlideInfo(
            currentSlideIndex = 2,
            totalSlides = 5,
            timestamp = now - 120_000L // 2 minutes old (stale)
        )

        val eval = coordinator.resolveCurrentSlideIndex(
            autoResult = null,
            totalMediaItems = 5,
            observedSlide = observed,
            currentTimeMs = now
        )

        assertTrue("Stale observation MUST trigger Safe Refusal", eval is ClipboardDownloadCoordinator.SlideEvaluationResult.Ambiguous)
    }

    @Test
    fun testCurrentItemCorroboration_NoIndicator_SafeRefusal() {
        val eval = coordinator.resolveCurrentSlideIndex(
            autoResult = null,
            totalMediaItems = 5,
            observedSlide = null
        )

        assertTrue("No indicator MUST trigger Safe Refusal, NEVER Entire Carousel", eval is ClipboardDownloadCoordinator.SlideEvaluationResult.Ambiguous)
    }
}
