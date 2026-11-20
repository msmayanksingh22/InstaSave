package com.instadown.assist.engine

import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.instadown.assist.model.CandidateNode
import com.instadown.assist.model.PostContainer
import com.instadown.assist.util.AppLogger
import java.util.ArrayDeque
import java.util.Locale

object ShareLocator {
    private const val TAG = "InstaDownloaderPOC"

    const val MIN_CONFIDENCE_THRESHOLD = 120
    const val MIN_MARGIN_THRESHOLD = 35

    data class ShareEvaluationResult(
        val bestCandidate: CandidateNode?,
        val runnerUpCandidate: CandidateNode?,
        val allCandidates: List<CandidateNode>,
        val scoreMargin: Int,
        val isAmbiguous: Boolean,
        val isOffScreenOrClipped: Boolean,
        val dynamicRevealPlan: RevealPlan?
    )

    data class RevealPlan(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val deltaY: Int
    )

    fun locateShare(
        roots: List<AccessibilityNodeInfo>,
        activePost: PostContainer?,
        viewportWidth: Int,
        viewportHeight: Int
    ): ShareEvaluationResult {
        val topInset = (viewportHeight * 0.05f).toInt().coerceIn(40, 220)
        val bottomInset = (viewportHeight * 0.06f).toInt().coerceIn(40, 220)
        val usableViewport = Rect(0, topInset, viewportWidth, viewportHeight - bottomInset)
        val allCandidates = mutableListOf<CandidateNode>()

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)

            while (!queue.isEmpty()) {
                val node = queue.poll() ?: break
                val pkg = (node.packageName ?: "").toString()
                val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                val desc = (node.contentDescription ?: "").toString()
                val text = (node.text ?: "").toString()
                val className = (node.className ?: "").toString()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // Skip non-Instagram nodes
                if (pkg != "com.instagram.android") {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                    continue
                }

                // Hard exclusion: System status bar area
                if (bounds.top < topInset) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                    continue
                }

                // Hard exclusion: Bottom navigation bar area (allow right-hand side share buttons on Reels even if near bottom)
                if (bounds.top >= viewportHeight - bottomInset && bounds.right < viewportWidth * 0.70f) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                    continue
                }

                // Hard exclusion: Horizontally off-screen (e.g. ViewPager offscreen pages)
                if (bounds.left < -50 || bounds.left >= viewportWidth || bounds.right <= 0 || bounds.right > viewportWidth + 50 || bounds.width() <= 0) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                    continue
                }

                val lowerDesc = desc.lowercase(Locale.ROOT)
                val lowerText = text.lowercase(Locale.ROOT)

                val positiveSignals = mutableListOf<Pair<String, Int>>()
                val negativePenalties = mutableListOf<Pair<String, Int>>()

                // 1. Content description signals
                if (lowerDesc == "send post" || lowerDesc == "share post" || lowerDesc == "share reel" || lowerDesc == "send reel") {
                    positiveSignals.add("Exact share description ('$lowerDesc')" to 80)
                } else if (lowerDesc.startsWith("send post") || lowerDesc.startsWith("share post")) {
                    positiveSignals.add("Prefixed share description" to 70)
                } else if (lowerDesc.contains("share") || lowerDesc.contains("send post") || lowerDesc.contains("send reel")) {
                    positiveSignals.add("Contains share/send in desc" to 50)
                }

                // 2. Resource ID signals
                if (resId.contains("row_feed_button_share")) {
                    positiveSignals.add("Known feed share button id" to 70)
                } else if (resId.contains("direct_share") || resId.contains("button_share")) {
                    positiveSignals.add("Generic share button id" to 55)
                }

                // 3. Text signals
                if (lowerText == "share" || lowerText == "share post" || lowerText == "share reel") {
                    positiveSignals.add("Exact share text" to 60)
                } else if (lowerText.contains("share")) {
                    positiveSignals.add("Contains share in text" to 40)
                }

                // STRICT GATE: Node must contain at least one positive share keyword signal
                if (positiveSignals.isNotEmpty()) {
                    // Clickability bonus
                    var hasClickableParent = false
                    var clickableAncestor: AccessibilityNodeInfo? = null
                    if (node.isClickable) {
                        positiveSignals.add("Node is clickable" to 25)
                    } else {
                        var p = node.parent
                        while (p != null) {
                            if (p.isClickable) {
                                hasClickableParent = true
                                clickableAncestor = p
                                positiveSignals.add("Clickable ancestor (${p.className})" to 20)
                                break
                            }
                            p = p.parent
                        }
                    }

                    // Visibility bonus
                    if (node.isVisibleToUser) {
                        positiveSignals.add("Visible to user" to 15)
                    }

                    // Post container association
                    if (activePost != null) {
                        val postBounds = activePost.bounds
                        if (postBounds.contains(bounds.centerX(), bounds.centerY())) {
                            positiveSignals.add("Inside active PostContainer" to 50)
                        } else if (bounds.top >= postBounds.top && bounds.top <= postBounds.bottom + 650) {
                            // Standard feed layout: Action buttons row is situated immediately below the media/CTA
                            positiveSignals.add("In action row below active PostContainer" to 50)
                        } else if (bounds.bottom <= postBounds.top) {
                            // Situated above active post -> belongs to preceding post
                            negativePenalties.add("Above active PostContainer (preceding post)" to 80)
                        } else {
                            // Situated far below active post -> belongs to following post
                            negativePenalties.add("Far below active PostContainer (following post)" to 80)
                        }
                    } else {
                        // Fallback: reward proximity to center of viewport
                        val centerDist = Math.abs(bounds.centerY() - usableViewport.centerY()).toFloat()
                        val centerBonus = ((1f - (centerDist / usableViewport.height().toFloat()).coerceIn(0f, 1f)) * 35f).toInt()
                        positiveSignals.add("Viewport center proximity" to centerBonus)
                    }

                    // Bounds sanity
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        positiveSignals.add("Valid bounds" to 10)
                    } else {
                        negativePenalties.add("Zero dimensions" to 50)
                    }

                    val totalPos = positiveSignals.sumOf { it.second }
                    val totalNeg = negativePenalties.sumOf { it.second }
                    val finalScore = (totalPos - totalNeg).coerceAtLeast(0)

                    if (finalScore >= 50) {
                        val actionsList = node.actionList.map { it.id }
                        val parentCls = node.parent?.className?.toString() ?: ""
                        val candidate = CandidateNode(
                            node = node,
                            text = text,
                            desc = desc,
                            resId = resId,
                            className = className,
                            bounds = Rect(bounds),
                            visibleToUser = node.isVisibleToUser,
                            isClickable = node.isClickable,
                            hasClickableAncestor = hasClickableParent,
                            clickableAncestor = clickableAncestor,
                            windowId = node.windowId,
                            packageName = pkg,
                            actions = actionsList,
                            parentClassName = parentCls,
                            neighborCount = node.parent?.childCount ?: 0,
                            nearestText = "",
                            positiveSignals = positiveSignals,
                            negativePenalties = negativePenalties,
                            totalScore = finalScore,
                            postContainer = activePost
                        )
                        allCandidates.add(candidate)
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }

        allCandidates.sortByDescending { it.totalScore }

        // Deduplicate candidates that represent the same physical control (e.g. ViewGroup container vs inner ImageView)
        val distinctCandidates = mutableListOf<CandidateNode>()
        for (cand in allCandidates) {
            val isDuplicate = distinctCandidates.any { existing ->
                val sameCenter = Math.abs(existing.bounds.centerX() - cand.bounds.centerX()) < 60 &&
                        Math.abs(existing.bounds.centerY() - cand.bounds.centerY()) < 60
                val overlapping = Rect.intersects(existing.bounds, cand.bounds) &&
                        (existing.bounds.contains(cand.bounds) || cand.bounds.contains(existing.bounds))
                sameCenter || overlapping
            }
            if (!isDuplicate) {
                distinctCandidates.add(cand)
            }
        }

        val best = distinctCandidates.firstOrNull()
        val runnerUp = if (distinctCandidates.size > 1) distinctCandidates[1] else null
        val margin = if (best != null && runnerUp != null) {
            best.totalScore - runnerUp.totalScore
        } else if (best != null) {
            best.totalScore
        } else {
            0
        }

        // Safety Gate: Must meet absolute confidence and margin thresholds
        val isAmbiguous = best != null && (best.totalScore < MIN_CONFIDENCE_THRESHOLD || (runnerUp != null && margin < MIN_MARGIN_THRESHOLD))

        // Check if best candidate is clipped or below viewport
        var isClipped = false
        var revealPlan: RevealPlan? = null

        if (best != null && !isAmbiguous) {
            val candidateBounds = best.bounds
            val isBelowViewport = candidateBounds.bottom > usableViewport.bottom || candidateBounds.top > usableViewport.bottom - 60
            val isPartiallyClipped = candidateBounds.bottom > usableViewport.bottom - 40 || candidateBounds.top < usableViewport.top

            if (isBelowViewport || isPartiallyClipped) {
                isClipped = true
                val deltaY = (candidateBounds.bottom - usableViewport.bottom + 180).coerceIn(150, 450)
                val startX = (viewportWidth / 2).toFloat()
                val startY = ((usableViewport.centerY() + deltaY / 2)).toFloat().coerceIn(600f, (viewportHeight - 350).toFloat())
                val endY = startY - deltaY
                revealPlan = RevealPlan(
                    startX = startX,
                    startY = startY,
                    endX = startX,
                    endY = endY,
                    deltaY = deltaY
                )
                AppLogger.i(TAG, "ShareLocator: Share button is clipped at $candidateBounds. Calculated dynamic reveal: dy=$deltaY ($startY -> $endY)")
            }
        }

        AppLogger.i(TAG, "ShareLocator: Candidates found: ${allCandidates.size}, Best=${best?.summary()}, Margin=$margin, Ambiguous=$isAmbiguous, Clipped=$isClipped")

        return ShareEvaluationResult(
            bestCandidate = best,
            runnerUpCandidate = runnerUp,
            allCandidates = allCandidates,
            scoreMargin = margin,
            isAmbiguous = isAmbiguous,
            isOffScreenOrClipped = isClipped,
            dynamicRevealPlan = revealPlan
        )
    }
}
