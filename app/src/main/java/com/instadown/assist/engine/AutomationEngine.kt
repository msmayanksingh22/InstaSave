package com.instadown.assist.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.instadown.assist.data.ReliabilityTracker
import com.instadown.assist.model.AutomationResult
import com.instadown.assist.model.AutomationState
import com.instadown.assist.model.CandidateNode
import com.instadown.assist.model.PostContainer
import com.instadown.assist.model.StateTransition
import com.instadown.assist.model.TransactionSnapshot
import com.instadown.assist.util.AppLogger
import com.instadown.assist.util.ClipboardReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AutomationEngine(
    private val service: AccessibilityService,
    private val getRootsProvider: () -> List<AccessibilityNodeInfo>,
    private val gestureDispatcher: (GestureDescription, AccessibilityService.GestureResultCallback?, Handler?) -> Boolean,
    private val globalActionBackDispatcher: () -> Boolean
) {
    companion object {
        private const val TAG = "InstaDownloaderPOC"
        private const val HARD_TIMEOUT_MS = 4500L
        private const val POLL_INTERVAL_MS = 50L
        private const val COOLDOWN_MS = 500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isExecuting = AtomicBoolean(false)
    private var lastCompletionTime = 0L

    fun isRunning(): Boolean = isExecuting.get()

    private class AutomationContext(
        val postType: String,
        val timestampStr: String,
        val startTimeMs: Long,
        val baselineClip: String = "",
        val baselineTimestamp: Long = 0L,
        val onRequestFocus: ((Boolean) -> Unit)?,
        val onStatusUpdate: ((String) -> Unit)?,
        val onComplete: ((AutomationResult) -> Unit)?
    ) {
        val stateTransitions = mutableListOf<StateTransition>()
        val phaseTimings = mutableMapOf<String, Long>()
        var currentState: AutomationState = AutomationState.IDLE
        var stateStartTime: Long = startTimeMs

        fun transition(newState: AutomationState, reason: String = "", details: String = "") {
            val now = System.currentTimeMillis()
            val duration = now - stateStartTime
            val t = StateTransition(
                fromState = currentState,
                toState = newState,
                timestampMs = now,
                durationMs = duration,
                reason = reason,
                diagnosticDetails = details
            )
            stateTransitions.add(t)
            AppLogger.i(TAG, "STATE: ${t.format()}")
            currentState = newState
            stateStartTime = now
        }
    }

    /**
     * Entry point: Executes the entire adaptive automation flow.
     */
    fun startAutomation(
        postType: String,
        triggerBounds: Rect? = null,
        triggerPoint: PointF? = null,
        recentInteractionNode: AccessibilityNodeInfo? = null,
        onRequestFocus: ((Boolean) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
        onComplete: ((AutomationResult) -> Unit)? = null
    ) {
        val now = System.currentTimeMillis()
        if (now - lastCompletionTime < COOLDOWN_MS) {
            val fail = AutomationResult(
                postType = postType,
                startTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)),
                postContainerSummary = "",
                postFingerprint = "",
                shareCandidate = "None",
                shareNodeDetails = "None",
                shareCandidateScore = 0,
                shareRunnerUpScore = 0,
                shareScoreMargin = 0,
                shareClickResult = "SKIPPED_COOLDOWN",
                shareDetectionTimeMs = 0L,
                revealOperationsCount = 0,
                gestureCoordinates = "",
                copyLinkCandidate = "None",
                copyLinkNodeDetails = "None",
                copyCandidateScore = 0,
                copyRunnerUpScore = 0,
                copyScoreMargin = 0,
                copyLinkClickResult = "SKIPPED_COOLDOWN",
                copyLinkDetectionTimeMs = 0L,
                clipboardText = "",
                clipboardMimes = emptyList(),
                validInstagramUrl = false,
                instagramUrl = "",
                stateTransitions = emptyList(),
                phaseTimings = emptyMap(),
                totalTimeMs = 0L,
                verdict = "COOLDOWN_ACTIVE",
                failureReason = "Operation throttled: Cooldown period of ${COOLDOWN_MS}ms active.",
                formattedReport = "Operation throttled: Cooldown period of ${COOLDOWN_MS}ms active."
            )
            onComplete?.invoke(fail)
            return
        }

        if (!isExecuting.compareAndSet(false, true)) {
            AppLogger.w(TAG, "startAutomation: Rejected - transaction already active")
            return
        }

        val startTime = System.currentTimeMillis()
        val timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startTime))

        // Capture Transaction Snapshot without modifying clipboard
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val baselineClip = try {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(service)?.toString()?.trim() ?: ""
        } catch (e: Exception) { "" }
        val baselineTimestamp = try {
            clipboard.primaryClipDescription?.timestamp ?: startTime
        } catch (e: Exception) { startTime }

        val ctx = AutomationContext(
            postType = postType,
            timestampStr = timestampStr,
            startTimeMs = startTime,
            baselineClip = baselineClip,
            baselineTimestamp = baselineTimestamp,
            onRequestFocus = onRequestFocus,
            onStatusUpdate = onStatusUpdate,
            onComplete = onComplete
        )

        fun finalizeTransaction(result: AutomationResult) {
            isExecuting.set(false)
            lastCompletionTime = System.currentTimeMillis()
            ReliabilityTracker.addResult(service, result)
            ctx.onComplete?.invoke(result)
        }

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        val (viewportWidth, viewportHeight) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val dm = service.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }

        var snapshot = TransactionSnapshot(
            startTimeMs = startTime,
            foregroundPackage = "com.instagram.android",
            foregroundActivity = "MainActivity",
            postFingerprint = null,
            postBounds = null,
            baselineClipboardText = baselineClip,
            baselineClipboardTimestamp = baselineTimestamp
        )
        AppLogger.i(TAG, "TransactionSnapshot: ${snapshot.summary()}")

        // State: IDENTIFY_POST
        AppLogger.i(TAG, "startAutomation: triggerBounds=$triggerBounds, triggerPoint=$triggerPoint, recentNode=$recentInteractionNode")
        ctx.transition(AutomationState.IDENTIFY_POST, "Resolving intended post container")
        ctx.onStatusUpdate?.invoke("Identifying target post...")

        val roots = getRootsProvider()
        if (roots.isEmpty()) {
            ctx.transition(AutomationState.FAILED, "No accessible windows found")
            val fail = buildFailure(ctx, "No accessible window roots available.")
            finalizeTransaction(fail)
            return
        }

        val primaryRoot = roots.firstOrNull { it.packageName == "com.instagram.android" } ?: roots.first()
        val prelocatedShares = try {
            ShareLocator.locateShare(roots, null, viewportWidth, viewportHeight).allCandidates
        } catch (e: Exception) {
            emptyList()
        }
        AppLogger.i(TAG, "IDENTIFY_POST: Pre-located ${prelocatedShares.size} visible share candidate(s) on screen.")

        val resolution = PostDetector.resolveIntendedPost(
            root = primaryRoot,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            triggerBounds = triggerBounds,
            triggerPoint = triggerPoint,
            recentInteractionNode = recentInteractionNode,
            visibleShareNodes = prelocatedShares
        )

        ctx.phaseTimings["identifyPost"] = System.currentTimeMillis() - ctx.stateStartTime

        // Safety Gate: Check if target resolution is ambiguous
        if (resolution.isAmbiguous || resolution.intendedPost == null) {
            ctx.transition(
                AutomationState.AMBIGUOUS_TARGET,
                "Intended post ambiguity",
                "${resolution.ambiguityReason}, Margin=${resolution.scoreMargin}"
            )
            val fail = buildFailure(
                ctx = ctx,
                reason = "AMBIGUOUS_TARGET: Cannot uniquely resolve intended post. ${resolution.ambiguityReason}. Failed safely to prevent wrong post selection."
            )
            finalizeTransaction(fail)
            return
        }

        val activePost = resolution.intendedPost
        val postFingerprint = activePost.createFingerprint()
        snapshot = snapshot.copy(
            postFingerprint = postFingerprint,
            postBounds = activePost.bounds
        )

        // State: LOCATE_SHARE
        ctx.transition(AutomationState.LOCATE_SHARE, "Evaluating Share candidates with margin analysis")
        ctx.onStatusUpdate?.invoke("Locating Share...")
        val shareLocateStart = System.currentTimeMillis()

        val shareEval = ShareLocator.locateShare(getRootsProvider(), activePost, viewportWidth, viewportHeight)
        ctx.phaseTimings["locateShare"] = System.currentTimeMillis() - shareLocateStart

        // Check for Ambiguity
        if (shareEval.isAmbiguous) {
            ctx.transition(
                AutomationState.AMBIGUOUS_TARGET,
                "Share candidate ambiguity",
                "Best=${shareEval.bestCandidate?.totalScore}, RunnerUp=${shareEval.runnerUpCandidate?.totalScore}, Margin=${shareEval.scoreMargin}"
            )
            val fail = buildFailure(
                ctx = ctx,
                reason = "Share control ambiguity detected: score margin (${shareEval.scoreMargin}) < ${ShareLocator.MIN_MARGIN_THRESHOLD} or confidence < ${ShareLocator.MIN_CONFIDENCE_THRESHOLD}. Failed safely to prevent incorrect click.",
                postContainer = activePost,
                shareEval = shareEval
            )
            finalizeTransaction(fail)
            return
        }

        if (shareEval.bestCandidate == null) {
            // Check if Share sheet is already open
            val directCopyEval = CopyLinkLocator.locateCopyLink(getRootsProvider(), viewportWidth, viewportHeight)
            if (directCopyEval.bestCandidate != null && !directCopyEval.isAmbiguous) {
                AppLogger.i(TAG, "Share sheet already open. Bypassing Share click and proceeding directly to Copy Link.")
                executeCopyLinkStage(
                    ctx = ctx,
                    activePost = activePost,
                    shareCandidateDesc = "Already Open",
                    shareNodeDetails = "Share surface already visible",
                    shareCandidateScore = 150,
                    shareRunnerUpScore = 0,
                    shareMargin = 150,
                    shareEval = shareEval,
                    finalizeTransaction = ::finalizeTransaction
                )
                return
            }

            ctx.transition(AutomationState.FAILED, "Share candidate not found")
            val fail = buildFailure(
                ctx = ctx,
                reason = "Share control could not be detected for the dominant post. Ensure post actions are in view.",
                postContainer = activePost,
                shareEval = shareEval
            )
            finalizeTransaction(fail)
            return
        }

        var selectedShare = shareEval.bestCandidate!!
        var revealCount = 0

        // Handle Bounded Reveal if Share is clipped or below viewport
        if (shareEval.isOffScreenOrClipped && shareEval.dynamicRevealPlan != null) {
            ctx.transition(AutomationState.REVEAL_SHARE, "Performing dynamic reveal scroll for Share")
            ctx.onStatusUpdate?.invoke("Revealing Share...")
            revealCount++

            val plan = shareEval.dynamicRevealPlan
            val path = Path().apply {
                moveTo(plan.startX, plan.startY)
                lineTo(plan.endX, plan.endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
                .build()

            gestureDispatcher(gesture, null, null)

            // State-based wait: wait 250ms for scroll to settle, then re-validate active post fingerprint
            mainHandler.postDelayed({
                val newRoots = getRootsProvider()
                val postAfterReveal = PostDetector.findDominantPost(
                    newRoots.firstOrNull { it.packageName == "com.instagram.android" } ?: newRoots.first(),
                    viewportWidth,
                    viewportHeight
                )
                val newFingerprint = postAfterReveal?.createFingerprint()

                // Re-validation: Check for post drift
                if (newFingerprint == null || !newFingerprint.matches(postFingerprint)) {
                    ctx.transition(
                        AutomationState.FAILED,
                        "Post drift detected after reveal scroll",
                        "Original: ${postFingerprint.summary()}, New: ${newFingerprint?.summary()}"
                    )
                    val fail = buildFailure(
                        ctx = ctx,
                        reason = "Active post drifted after reveal scroll. Aborted safely to prevent clicking unrelated post.",
                        postContainer = activePost,
                        shareEval = shareEval
                    )
                    finalizeTransaction(fail)
                    return@postDelayed
                }

                // Re-locate Share candidate after reveal
                val reShareEval = ShareLocator.locateShare(getRootsProvider(), postAfterReveal, viewportWidth, viewportHeight)
                if (reShareEval.bestCandidate == null || reShareEval.isAmbiguous) {
                    ctx.transition(AutomationState.FAILED, "Share relocation failed after reveal")
                    val fail = buildFailure(
                        ctx = ctx,
                        reason = "Failed to reliably locate Share control after reveal scroll.",
                        postContainer = postAfterReveal,
                        shareEval = reShareEval
                    )
                    finalizeTransaction(fail)
                    return@postDelayed
                }

                selectedShare = reShareEval.bestCandidate!!
                executeShareClickStage(
                    ctx = ctx,
                    selectedShare = selectedShare,
                    revealCount = revealCount,
                    gestureCoordStr = "dy=${plan.deltaY} (${plan.startY}->${plan.endY})",
                    activePost = postAfterReveal ?: activePost,
                    shareEval = reShareEval,
                    finalizeTransaction = ::finalizeTransaction
                )
            }, 250)
            return
        }

        executeShareClickStage(
            ctx = ctx,
            selectedShare = selectedShare,
            revealCount = revealCount,
            gestureCoordStr = "",
            activePost = activePost,
            shareEval = shareEval,
            finalizeTransaction = ::finalizeTransaction
        )
    }

    private fun executeShareClickStage(
        ctx: AutomationContext,
        selectedShare: CandidateNode,
        revealCount: Int,
        gestureCoordStr: String,
        activePost: PostContainer?,
        shareEval: ShareLocator.ShareEvaluationResult,
        finalizeTransaction: (AutomationResult) -> Unit
    ) {
        val displayMetrics = service.resources.displayMetrics
        val viewportWidth = displayMetrics.widthPixels
        val viewportHeight = displayMetrics.heightPixels

        // State: CLICK_SHARE
        ctx.transition(AutomationState.CLICK_SHARE, "Dispatching controlled click on Share control", selectedShare.summary())
        ctx.onStatusUpdate?.invoke("Opening Share...")
        val shareClickStart = System.currentTimeMillis()

        val clickSuccess = performControlledClick(selectedShare, viewportWidth, viewportHeight)
        ctx.phaseTimings["clickShare"] = System.currentTimeMillis() - shareClickStart

        if (!clickSuccess) {
            ctx.transition(AutomationState.FAILED, "Share click action failed")
            val fail = buildFailure(
                ctx = ctx,
                reason = "Failed to dispatch click on Share candidate: ${selectedShare.summary()}",
                postContainer = activePost,
                shareEval = shareEval
            )
            finalizeTransaction(fail)
            return
        }

        // State: WAIT_FOR_SHARE_SURFACE
        ctx.transition(AutomationState.WAIT_FOR_SHARE_SURFACE, "State-based wait for Instagram Share surface")
        ctx.onStatusUpdate?.invoke("Waiting for Share...")
        val surfaceWaitStart = System.currentTimeMillis()

        waitForShareSurface(
            timeoutMs = HARD_TIMEOUT_MS,
            intervalMs = POLL_INTERVAL_MS,
            onSurfaceDetected = {
                ctx.phaseTimings["waitForShareSurface"] = System.currentTimeMillis() - surfaceWaitStart
                executeCopyLinkStage(
                    ctx = ctx,
                    activePost = activePost,
                    shareCandidateDesc = selectedShare.summary(),
                    shareNodeDetails = selectedShare.details(),
                    shareCandidateScore = selectedShare.totalScore,
                    shareRunnerUpScore = shareEval.runnerUpCandidate?.totalScore ?: 0,
                    shareMargin = shareEval.scoreMargin,
                    shareEval = shareEval,
                    finalizeTransaction = finalizeTransaction
                )
            },
            onTimeout = {
                ctx.phaseTimings["waitForShareSurface"] = System.currentTimeMillis() - surfaceWaitStart
                ctx.transition(AutomationState.FAILED, "Share surface timeout")
                val fail = buildFailure(
                    ctx = ctx,
                    reason = "Instagram Share surface did not appear within ${HARD_TIMEOUT_MS}ms after clicking Share.",
                    postContainer = activePost,
                    shareEval = shareEval
                )
                finalizeTransaction(fail)
            }
        )
    }

    private fun executeCopyLinkStage(
        ctx: AutomationContext,
        activePost: PostContainer?,
        shareCandidateDesc: String,
        shareNodeDetails: String,
        shareCandidateScore: Int,
        shareRunnerUpScore: Int,
        shareMargin: Int,
        shareEval: ShareLocator.ShareEvaluationResult,
        finalizeTransaction: (AutomationResult) -> Unit
    ) {
        val displayMetrics = service.resources.displayMetrics
        val viewportWidth = displayMetrics.widthPixels
        val viewportHeight = displayMetrics.heightPixels

        // State: LOCATE_COPY_LINK
        ctx.transition(AutomationState.LOCATE_COPY_LINK, "Locating Copy Link candidate in Share surface")
        ctx.onStatusUpdate?.invoke("Finding Copy Link...")
        val copyLocateStart = System.currentTimeMillis()

        val copyEval = CopyLinkLocator.locateCopyLink(getRootsProvider(), viewportWidth, viewportHeight)
        ctx.phaseTimings["locateCopyLink"] = System.currentTimeMillis() - copyLocateStart

        if (copyEval.isAmbiguous) {
            ctx.transition(
                AutomationState.AMBIGUOUS_TARGET,
                "Copy Link candidate ambiguity",
                "Best=${copyEval.bestCandidate?.totalScore}, RunnerUp=${copyEval.runnerUpCandidate?.totalScore}, Margin=${copyEval.scoreMargin}"
            )
            val fail = buildFailure(
                ctx = ctx,
                reason = "Copy Link candidate ambiguity detected: margin (${copyEval.scoreMargin}) < ${CopyLinkLocator.MIN_MARGIN_THRESHOLD} or confidence < ${CopyLinkLocator.MIN_CONFIDENCE_THRESHOLD}. Failed safely to prevent incorrect click.",
                postContainer = activePost,
                shareEval = shareEval,
                copyEval = copyEval
            )
            finalizeTransaction(fail)
            return
        }

        var selectedCopy = copyEval.bestCandidate
        var revealCount = 0

        // If Copy Link is clipped or off-screen, execute dynamic row reveal
        if (copyEval.isClippedAtEdge && copyEval.dynamicRowRevealPlan != null) {
            ctx.transition(AutomationState.REVEAL_COPY_LINK, "Executing dynamic row swipe to center Copy Link")
            ctx.onStatusUpdate?.invoke("Revealing Copy Link...")
            revealCount++

            val plan = copyEval.dynamicRowRevealPlan
            val path = Path().apply {
                moveTo(plan.startX, plan.startY)
                lineTo(plan.endX, plan.endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
                .build()

            gestureDispatcher(gesture, null, null)

            // State-based wait: 220ms for row animation to settle, then re-locate
            mainHandler.postDelayed({
                val reCopyEval = CopyLinkLocator.locateCopyLink(getRootsProvider(), viewportWidth, viewportHeight)
                if (reCopyEval.bestCandidate == null || reCopyEval.isAmbiguous) {
                    ctx.transition(AutomationState.FAILED, "Copy Link relocation failed after row swipe")
                    val fail = buildFailure(
                        ctx = ctx,
                        reason = "Failed to reliably locate Copy Link candidate after row reveal swipe.",
                        postContainer = activePost,
                        shareEval = shareEval,
                        copyEval = reCopyEval
                    )
                    finalizeTransaction(fail)
                    return@postDelayed
                }

                val finalRelocatedCopy = reCopyEval.bestCandidate!!
                executeCopyLinkClick(
                    ctx = ctx,
                    selectedCopy = finalRelocatedCopy,
                    activePost = activePost,
                    shareCandidateDesc = shareCandidateDesc,
                    shareNodeDetails = shareNodeDetails,
                    shareCandidateScore = shareCandidateScore,
                    shareRunnerUpScore = shareRunnerUpScore,
                    shareMargin = shareMargin,
                    shareEval = shareEval,
                    copyEval = reCopyEval,
                    revealCount = revealCount,
                    finalizeTransaction = finalizeTransaction
                )
            }, 220)
            return
        }

        // Adaptive row scrolling: If Copy Link is not in initial frame, scroll horizontal action row to reveal it
        if (selectedCopy == null) {
            ctx.transition(AutomationState.REVEAL_COPY_LINK, "Copy Link not in initial frame; scrolling horizontal action row to reveal")
            ctx.onStatusUpdate?.invoke("Searching for Copy Link...")
            revealCount++

            val rowBounds = copyEval.shareRowBounds
            val startX = if (rowBounds != null) {
                (rowBounds.right - 80).toFloat().coerceIn(150f, (viewportWidth - 50).toFloat())
            } else {
                viewportWidth * 0.85f
            }
            val endX = if (rowBounds != null) {
                (rowBounds.left + 80).toFloat().coerceIn(50f, (viewportWidth - 150).toFloat())
            } else {
                viewportWidth * 0.15f
            }
            val swipeY = if (rowBounds != null) {
                rowBounds.centerY().toFloat()
            } else {
                viewportHeight * 0.80f
            }

            val path = Path().apply {
                moveTo(startX, swipeY)
                lineTo(endX, swipeY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
                .build()

            gestureDispatcher(gesture, null, null)

            mainHandler.postDelayed({
                val reCopyEval = CopyLinkLocator.locateCopyLink(getRootsProvider(), viewportWidth, viewportHeight)
                if (reCopyEval.bestCandidate != null && !reCopyEval.isAmbiguous) {
                    AppLogger.i(TAG, "executeCopyLinkStage: Copy Link revealed after exploratory swipe: ${reCopyEval.bestCandidate.summary()}")
                    executeCopyLinkClick(
                        ctx = ctx,
                        selectedCopy = reCopyEval.bestCandidate,
                        activePost = activePost,
                        shareCandidateDesc = shareCandidateDesc,
                        shareNodeDetails = shareNodeDetails,
                        shareCandidateScore = shareCandidateScore,
                        shareRunnerUpScore = shareRunnerUpScore,
                        shareMargin = shareMargin,
                        shareEval = shareEval,
                        copyEval = reCopyEval,
                        revealCount = revealCount,
                        finalizeTransaction = finalizeTransaction
                    )
                } else {
                    // Try second swipe in case Copy Link was positioned further to the right
                    val secondPath = Path().apply {
                        moveTo(startX, swipeY)
                        lineTo(endX, swipeY)
                    }
                    val secondGesture = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(secondPath, 0, 180))
                        .build()

                    gestureDispatcher(secondGesture, null, null)

                    mainHandler.postDelayed({
                        val finalCopyEval = CopyLinkLocator.locateCopyLink(getRootsProvider(), viewportWidth, viewportHeight)
                        if (finalCopyEval.bestCandidate != null && !finalCopyEval.isAmbiguous) {
                            AppLogger.i(TAG, "executeCopyLinkStage: Copy Link revealed after second swipe: ${finalCopyEval.bestCandidate.summary()}")
                            executeCopyLinkClick(
                                ctx = ctx,
                                selectedCopy = finalCopyEval.bestCandidate,
                                activePost = activePost,
                                shareCandidateDesc = shareCandidateDesc,
                                shareNodeDetails = shareNodeDetails,
                                shareCandidateScore = shareCandidateScore,
                                shareRunnerUpScore = shareRunnerUpScore,
                                shareMargin = shareMargin,
                                shareEval = shareEval,
                                copyEval = finalCopyEval,
                                revealCount = revealCount + 1,
                                finalizeTransaction = finalizeTransaction
                            )
                        } else {
                            ctx.transition(AutomationState.FAILED, "Copy Link not found in Share surface after exploratory scrolls")
                            val fail = buildFailure(
                                ctx = ctx,
                                reason = "Copy Link control could not be detected in the Share surface after scrolling action row.",
                                postContainer = activePost,
                                shareEval = shareEval,
                                copyEval = finalCopyEval
                            )
                            finalizeTransaction(fail)
                        }
                    }, 240)
                }
            }, 240)
            return
        }

        val finalCopy = selectedCopy
        executeCopyLinkClick(
            ctx = ctx,
            selectedCopy = finalCopy,
            activePost = activePost,
            shareCandidateDesc = shareCandidateDesc,
            shareNodeDetails = shareNodeDetails,
            shareCandidateScore = shareCandidateScore,
            shareRunnerUpScore = shareRunnerUpScore,
            shareMargin = shareMargin,
            shareEval = shareEval,
            copyEval = copyEval,
            revealCount = revealCount,
            finalizeTransaction = finalizeTransaction
        )
    }

    private fun executeCopyLinkClick(
        ctx: AutomationContext,
        selectedCopy: CandidateNode,
        activePost: PostContainer?,
        shareCandidateDesc: String,
        shareNodeDetails: String,
        shareCandidateScore: Int,
        shareRunnerUpScore: Int,
        shareMargin: Int,
        shareEval: ShareLocator.ShareEvaluationResult,
        copyEval: CopyLinkLocator.CopyLinkEvaluationResult,
        revealCount: Int,
        finalizeTransaction: (AutomationResult) -> Unit
    ) {
        val displayMetrics = service.resources.displayMetrics
        val viewportWidth = displayMetrics.widthPixels
        val viewportHeight = displayMetrics.heightPixels

        // State: CLICK_COPY_LINK
        ctx.transition(AutomationState.CLICK_COPY_LINK, "Dispatching controlled click on Copy Link", selectedCopy.summary())
        ctx.onStatusUpdate?.invoke("Copying link...")
        val copyClickStart = System.currentTimeMillis()

        val copyClicked = performControlledClick(selectedCopy, viewportWidth, viewportHeight)
        ctx.phaseTimings["clickCopyLink"] = System.currentTimeMillis() - copyClickStart

        if (!copyClicked) {
            ctx.transition(AutomationState.FAILED, "Copy link click failed")
            val fail = buildFailure(
                ctx = ctx,
                reason = "Failed to dispatch click on Copy Link candidate: ${selectedCopy.summary()}",
                postContainer = activePost,
                shareEval = shareEval,
                copyEval = copyEval
            )
            finalizeTransaction(fail)
            return
        }

        // State: WAIT_FOR_CLIPBOARD
        ctx.transition(AutomationState.WAIT_FOR_CLIPBOARD, "Awaiting new clipboard content")
        ctx.onStatusUpdate?.invoke("Reading clipboard...")
        ctx.onRequestFocus?.invoke(true)
        val clipWaitStart = System.currentTimeMillis()

        val initialClip = ClipboardReader.readCurrentClip(service)
        val priorUrl = if (initialClip.isValidInstagramUrl) initialClip.cleanUrl else ctx.baselineClip
        val priorTimestamp = if (initialClip.timestamp > 0) initialClip.timestamp else ctx.baselineTimestamp
        AppLogger.i(TAG, "executeCopyLinkClick: priorUrl='$priorUrl', priorTimestamp=$priorTimestamp, clickStart=$copyClickStart")

        pollClipboardForFreshUrl(
            timeoutMs = HARD_TIMEOUT_MS,
            intervalMs = POLL_INTERVAL_MS,
            priorUrl = priorUrl,
            priorTimestamp = priorTimestamp,
            clickTimestamp = copyClickStart,
            onResult = { clipResult ->
                ctx.phaseTimings["clipboardCapture"] = System.currentTimeMillis() - clipWaitStart

                // State: VALIDATE_URL
                ctx.transition(AutomationState.VALIDATE_URL, "Validating captured clipboard URL", clipResult.cleanUrl)
                val isValid = clipResult.isValidInstagramUrl

                // Immediately release overlay focus so Instagram window regains focus for dismissal
                ctx.onRequestFocus?.invoke(false)

                if (!isValid) {
                    ctx.transition(AutomationState.FAILED, "Invalid or empty Instagram URL captured")
                    val fail = buildFailure(
                        ctx = ctx,
                        reason = if (clipResult.text.isBlank()) "Clipboard remained empty after Copy Link." else "Captured text ('${clipResult.text.take(35)}') did not match Instagram post regex.",
                        postContainer = activePost,
                        shareEval = shareEval,
                        copyEval = copyEval
                    )
                    finalizeTransaction(fail)
                    return@pollClipboardForFreshUrl
                }

                // State: DISMISS_SHARE (Tiered dismissal)
                ctx.transition(AutomationState.DISMISS_SHARE, "Dismissing Share surface via tiered restoration")
                ctx.onStatusUpdate?.invoke("Restoring view...")
                val dismissStart = System.currentTimeMillis()

                // Allow 120ms for window manager to restore active focus to Instagram before BACK
                mainHandler.postDelayed({
                    executeTieredDismissal {
                        ctx.phaseTimings["dismissShare"] = System.currentTimeMillis() - dismissStart

                        // State: RESTORE_INSTAGRAM
                        ctx.transition(AutomationState.RESTORE_INSTAGRAM, "Verifying restored foreground Instagram state")
                        val totalDuration = System.currentTimeMillis() - ctx.startTimeMs
                        ctx.transition(AutomationState.SUCCESS, "Automation transaction completed successfully", "Total: ${totalDuration}ms")

                        val result = AutomationResult(
                            postType = ctx.postType,
                            startTime = ctx.timestampStr,
                            postContainerSummary = activePost?.summary() ?: "PostContainer",
                            postFingerprint = activePost?.createFingerprint()?.summary() ?: "",
                            shareCandidate = shareCandidateDesc,
                            shareNodeDetails = shareNodeDetails,
                            shareCandidateScore = shareCandidateScore,
                            shareRunnerUpScore = shareRunnerUpScore,
                            shareScoreMargin = shareMargin,
                            shareClickResult = "SUCCESS",
                            shareDetectionTimeMs = ctx.phaseTimings["locateShare"] ?: 0L,
                            revealOperationsCount = revealCount,
                            gestureCoordinates = "",
                            copyLinkCandidate = selectedCopy.summary(),
                            copyLinkNodeDetails = selectedCopy.details(),
                            copyCandidateScore = selectedCopy.totalScore,
                            copyRunnerUpScore = copyEval.runnerUpCandidate?.totalScore ?: 0,
                            copyScoreMargin = copyEval.scoreMargin,
                            copyLinkClickResult = "SUCCESS",
                            copyLinkDetectionTimeMs = ctx.phaseTimings["locateCopyLink"] ?: 0L,
                            clipboardText = clipResult.text,
                            clipboardMimes = clipResult.mimeTypes,
                            validInstagramUrl = true,
                            instagramUrl = clipResult.cleanUrl,
                            stateTransitions = ctx.stateTransitions.map { it.format() },
                            phaseTimings = ctx.phaseTimings,
                            totalTimeMs = totalDuration,
                            verdict = "PASS",
                            failureReason = "Successfully executed safety-verified Share -> Copy Link automation.",
                            formattedReport = formatReport(
                                postType = ctx.postType,
                                startTime = ctx.timestampStr,
                                postContainer = activePost,
                                shareCandidate = shareCandidateDesc,
                                shareNodeDetails = shareNodeDetails,
                                shareScore = shareCandidateScore,
                                shareMargin = shareMargin,
                                copyCandidate = selectedCopy.summary(),
                                copyNodeDetails = selectedCopy.details(),
                                copyScore = selectedCopy.totalScore,
                                copyMargin = copyEval.scoreMargin,
                                clipResult = clipResult,
                                phaseTimings = ctx.phaseTimings,
                                totalTimeMs = totalDuration,
                                stateTransitions = ctx.stateTransitions,
                                verdict = "PASS"
                            ),
                            carouselSlideIndex = activePost?.carouselSlideIndex,
                            carouselTotalSlides = activePost?.carouselTotalSlides
                        )
                        finalizeTransaction(result)
                    }
                }, 120)
            }
        )
    }

    /**
     * Tiered Share UI dismissal:
     * Tier 1: Targeted Cancel/Close button in Share sheet
     * Tier 2: ACTION_DISMISS on sheet window root
     * Tier 3: Single GLOBAL_ACTION_BACK fallback
     */
    private fun executeTieredDismissal(onDismissComplete: () -> Unit) {
        val roots = getRootsProvider()
        val targeted = CopyLinkLocator.findDismissalTarget(roots)

        if (targeted != null) {
            AppLogger.i(TAG, "executeTieredDismissal: Tier 1 - Clicking targeted dismissal button: ${targeted.strategy}")
            val clicked = targeted.node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    (targeted.node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            if (!clicked && targeted.bounds.width() > 0) {
                val clickPath = Path().apply { moveTo(targeted.bounds.centerX().toFloat(), targeted.bounds.centerY().toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
                    .build()
                gestureDispatcher(gesture, null, null)
            }
            mainHandler.postDelayed({ onDismissComplete() }, 150)
            return
        }

        // Tier 2: Check for dismiss action on dialog root
        for (root in roots) {
            if (root.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) {
                AppLogger.i(TAG, "executeTieredDismissal: Tier 2 - Invoking ACTION_DISMISS on root")
                if (root.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                    mainHandler.postDelayed({ onDismissComplete() }, 150)
                    return
                }
            }
        }

        // Tier 3: Single GLOBAL_ACTION_BACK
        AppLogger.i(TAG, "executeTieredDismissal: Tier 3 - Dispatching single GLOBAL_ACTION_BACK")
        globalActionBackDispatcher()
        mainHandler.postDelayed({ onDismissComplete() }, 180)
    }

    /**
     * Controlled click: ACTION_CLICK -> verify -> gesture fallback
     */
    private fun performControlledClick(
        candidate: CandidateNode,
        viewportWidth: Int,
        viewportHeight: Int
    ): Boolean {
        val node = candidate.node

        // Attempt 1: Direct ACTION_CLICK on node
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AppLogger.i(TAG, "performControlledClick: Direct ACTION_CLICK succeeded on ${node.className}")
            return true
        }

        // Attempt 2: ACTION_CLICK on clickable ancestor
        candidate.clickableAncestor?.let { ancestor ->
            if (ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLogger.i(TAG, "performControlledClick: Ancestor ACTION_CLICK succeeded on ${ancestor.className}")
                return true
            }
        }

        // Attempt 3: Dynamic gesture tap fallback at clamped center coordinates
        val bounds = candidate.bounds
        if (bounds.width() > 0 && bounds.height() > 0) {
            val safeX = bounds.centerX().toFloat().coerceIn(40f, (viewportWidth - 40).toFloat())
            val safeY = bounds.centerY().toFloat().coerceIn(40f, (viewportHeight - 40).toFloat())
            AppLogger.i(TAG, "performControlledClick: Dispatching dynamic gesture tap at ($safeX, $safeY)")
            val clickPath = Path().apply { moveTo(safeX, safeY) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
                .build()
            return gestureDispatcher(gesture, null, null)
        }

        return false
    }

    private fun waitForShareSurface(
        timeoutMs: Long,
        intervalMs: Long,
        onSurfaceDetected: () -> Unit,
        onTimeout: () -> Unit
    ) {
        val startWait = System.currentTimeMillis()
        val checkRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startWait
                val roots = getRootsProvider()
                val viewportHeight = service.resources.displayMetrics.heightPixels

                // Check for evidence of Share surface
                for (root in roots) {
                    val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
                    queue.add(root)

                    while (!queue.isEmpty()) {
                        val node = queue.poll() ?: break
                        val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                        val desc = (node.contentDescription ?: "").toString().lowercase(Locale.ROOT)
                        val text = (node.text ?: "").toString().lowercase(Locale.ROOT)
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)

                        val hasShareSurfaceSignal = resId.contains("reshare_row") ||
                                resId.contains("direct_external") ||
                                resId.contains("external_share") ||
                                resId.contains("action_sheet") ||
                                resId.contains("bottom_sheet") ||
                                resId.contains("share_sheet") ||
                                resId.contains("direct_share") ||
                                resId.contains("drag_handle") ||
                                desc.contains("copy link") ||
                                text.contains("copy link") ||
                                resId.contains("copy_link") ||
                                resId.endsWith(":id/copy") ||
                                desc.contains("add to story") ||
                                text.contains("add to story") ||
                                desc.contains("share to") ||
                                text.contains("share to") ||
                                (desc.contains("search") && bounds.top > viewportHeight * 0.2) ||
                                (text.contains("search") && bounds.top > viewportHeight * 0.2) ||
                                (desc.contains("send") && bounds.bottom > viewportHeight * 0.4)

                        if (hasShareSurfaceSignal) {
                            AppLogger.i(TAG, "waitForShareSurface: Detected surface signal in ${elapsed}ms: id=$resId, desc='$desc', text='$text'")
                            onSurfaceDetected()
                            return
                        }

                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { queue.add(it) }
                        }
                    }
                }

                if (elapsed < timeoutMs) {
                    mainHandler.postDelayed(this, intervalMs)
                } else {
                    onTimeout()
                }
            }
        }
        mainHandler.post(checkRunnable)
    }

    private fun pollClipboardForFreshUrl(
        timeoutMs: Long,
        intervalMs: Long,
        priorUrl: String,
        priorTimestamp: Long,
        clickTimestamp: Long,
        onResult: (ClipboardReader.ClipResult) -> Unit
    ) {
        val startPoll = System.currentTimeMillis()
        val checkRunnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startPoll
                val clipResult = ClipboardReader.readCurrentClip(service)

                val hasNewerTimestamp = clipResult.timestamp > priorTimestamp && clipResult.timestamp >= (clickTimestamp - 200L)
                val hasDifferentUrl = priorUrl.isNotEmpty() && clipResult.cleanUrl.isNotEmpty() && clipResult.cleanUrl != priorUrl
                val isFreshFirstUrl = priorUrl.isEmpty() && clipResult.cleanUrl.isNotEmpty() && (clipResult.timestamp == 0L || clipResult.timestamp >= (clickTimestamp - 200L))
                val isSameUrlRefreshed = clipResult.cleanUrl.isNotEmpty() && clipResult.cleanUrl == priorUrl && elapsed >= 300L

                val isFresh = (hasNewerTimestamp || hasDifferentUrl || isFreshFirstUrl || isSameUrlRefreshed) && clipResult.isValidInstagramUrl

                if (isFresh) {
                    AppLogger.i(TAG, "pollClipboard: Fresh Instagram URL captured in ${elapsed}ms: ${clipResult.cleanUrl} (clipTime=${clipResult.timestamp}, priorTime=$priorTimestamp, clickTime=$clickTimestamp)")
                    onResult(clipResult)
                    return
                }

                if (elapsed < timeoutMs) {
                    mainHandler.postDelayed(this, intervalMs)
                } else {
                    AppLogger.w(TAG, "pollClipboard: Timeout (${timeoutMs}ms) expired. Final clip valid=${clipResult.isValidInstagramUrl}, cleanUrl='${clipResult.cleanUrl}'")
                    onResult(clipResult)
                }
            }
        }
        mainHandler.post(checkRunnable)
    }

    private fun buildFailure(
        ctx: AutomationContext,
        reason: String,
        postContainer: PostContainer? = null,
        shareEval: ShareLocator.ShareEvaluationResult? = null,
        copyEval: CopyLinkLocator.CopyLinkEvaluationResult? = null
    ): AutomationResult {
        val totalMs = System.currentTimeMillis() - ctx.startTimeMs
        return AutomationResult(
            postType = ctx.postType,
            startTime = ctx.timestampStr,
            postContainerSummary = postContainer?.summary() ?: "",
            postFingerprint = postContainer?.createFingerprint()?.summary() ?: "",
            shareCandidate = shareEval?.bestCandidate?.summary() ?: "None",
            shareNodeDetails = shareEval?.bestCandidate?.details() ?: "None",
            shareCandidateScore = shareEval?.bestCandidate?.totalScore ?: 0,
            shareRunnerUpScore = shareEval?.runnerUpCandidate?.totalScore ?: 0,
            shareScoreMargin = shareEval?.scoreMargin ?: 0,
            shareClickResult = if (shareEval?.bestCandidate != null) "ATTEMPTED" else "NOT_FOUND",
            shareDetectionTimeMs = ctx.phaseTimings["locateShare"] ?: 0L,
            copyLinkCandidate = copyEval?.bestCandidate?.summary() ?: "None",
            copyLinkNodeDetails = copyEval?.bestCandidate?.details() ?: "None",
            copyCandidateScore = copyEval?.bestCandidate?.totalScore ?: 0,
            copyRunnerUpScore = copyEval?.runnerUpCandidate?.totalScore ?: 0,
            copyScoreMargin = copyEval?.scoreMargin ?: 0,
            copyLinkClickResult = "FAILED",
            copyLinkDetectionTimeMs = ctx.phaseTimings["locateCopyLink"] ?: 0L,
            clipboardText = "",
            clipboardMimes = emptyList(),
            validInstagramUrl = false,
            instagramUrl = "",
            stateTransitions = ctx.stateTransitions.map { it.format() },
            phaseTimings = ctx.phaseTimings,
            totalTimeMs = totalMs,
            verdict = "FAIL",
            failureReason = reason,
            formattedReport = "=== INSTAGRAM AUTOMATION FAILURE ===\nReason: $reason\nPost: ${postContainer?.summary()}\nTransitions:\n" + ctx.stateTransitions.joinToString("\n") { " - ${it.format()}" }
        )
    }

    private fun formatReport(
        postType: String,
        startTime: String,
        postContainer: PostContainer?,
        shareCandidate: String,
        shareNodeDetails: String,
        shareScore: Int,
        shareMargin: Int,
        copyCandidate: String,
        copyNodeDetails: String,
        copyScore: Int,
        copyMargin: Int,
        clipResult: ClipboardReader.ClipResult,
        phaseTimings: Map<String, Long>,
        totalTimeMs: Long,
        stateTransitions: List<StateTransition>,
        verdict: String
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== INSTAGRAM AUTOMATION REPORT ===")
        sb.appendLine("Post Type: $postType")
        sb.appendLine("Start Time: $startTime")
        sb.appendLine("Active Post: ${postContainer?.summary() ?: "N/A"}")
        sb.appendLine("Fingerprint: ${postContainer?.createFingerprint()?.summary() ?: "N/A"}")
        sb.appendLine("\n[SHARE CONTROL]")
        sb.appendLine("Candidate: $shareCandidate (Score: $shareScore, Margin: $shareMargin)")
        sb.appendLine("Details: $shareNodeDetails")
        sb.appendLine("Detection Time: ${phaseTimings["locateShare"] ?: 0}ms")
        sb.appendLine("\n[COPY LINK CONTROL]")
        sb.appendLine("Candidate: $copyCandidate (Score: $copyScore, Margin: $copyMargin)")
        sb.appendLine("Details: $copyNodeDetails")
        sb.appendLine("Detection Time: ${phaseTimings["locateCopyLink"] ?: 0}ms")
        sb.appendLine("\n[CLIPBOARD]")
        sb.appendLine("URL: ${clipResult.cleanUrl}")
        sb.appendLine("Valid Instagram URL: ${if (clipResult.isValidInstagramUrl) "YES" else "NO"}")
        sb.appendLine("\n[PHASE TIMINGS]")
        phaseTimings.forEach { (phase, dur) ->
            sb.appendLine(" - $phase: ${dur}ms")
        }
        sb.appendLine("Total Duration: ${totalTimeMs}ms")
        sb.appendLine("\n[STATE TRANSITIONS]")
        stateTransitions.forEach { sb.appendLine(" - ${it.format()}") }
        sb.appendLine("\n[VERDICT]: $verdict")
        return sb.toString()
    }
}
