package com.instadown.assist.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.instadown.assist.model.CandidateNode
import com.instadown.assist.util.AppLogger
import java.util.ArrayDeque
import java.util.Locale

object CopyLinkLocator {
    private const val TAG = "InstaDownloaderPOC"

    const val MIN_CONFIDENCE_THRESHOLD = 120
    const val MIN_MARGIN_THRESHOLD = 35

    data class CopyLinkEvaluationResult(
        val bestCandidate: CandidateNode?,
        val runnerUpCandidate: CandidateNode?,
        val allCandidates: List<CandidateNode>,
        val scoreMargin: Int,
        val isAmbiguous: Boolean,
        val isClippedAtEdge: Boolean,
        val dynamicRowRevealPlan: RowRevealPlan?,
        val shareRowBounds: Rect? = null
    )

    data class RowRevealPlan(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val rowResId: String
    )

    data class DismissalTarget(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val strategy: String // "CANCEL_BUTTON", "CLOSE_ICON", "DISMISS_ACTION"
    )

    fun locateCopyLink(
        roots: List<AccessibilityNodeInfo>,
        viewportWidth: Int,
        viewportHeight: Int
    ): CopyLinkEvaluationResult {
        val allCandidates = mutableListOf<CandidateNode>()
        var reshareRowBounds: Rect? = null
        var reshareRowId = ""

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

                // Check for horizontal reshare row container (adaptive for any screen aspect ratio)
                val isHorizontalRow = resId.contains("reshare_row") ||
                        resId.contains("external_share") ||
                        resId.contains("direct_external") ||
                        resId.contains("share_target") ||
                        resId.contains("action_row") ||
                        ((className.contains("RecyclerView") || className.contains("HorizontalScrollView") || className.contains("ViewGroup")) &&
                         bounds.top > (viewportHeight * 0.45f) && bounds.bottom <= viewportHeight &&
                         bounds.width() >= viewportWidth * 0.70f && bounds.height() in 60..550)

                if (isHorizontalRow && reshareRowBounds == null && bounds.width() > 0 && bounds.height() > 0) {
                    reshareRowBounds = Rect(bounds)
                    reshareRowId = resId
                    AppLogger.d(TAG, "CopyLinkLocator: Identified horizontal action row: $bounds (id=$resId, cls=$className)")
                }

                // Exclude system status bar area
                if (bounds.top < 180) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                    continue
                }

                // Allowed packages: Instagram or Android IntentResolver / Chooser
                val isAllowedPkg = pkg == "com.instagram.android" || pkg.contains("intentresolver") || pkg.contains("chooser")
                if (!isAllowedPkg) {
                    for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
                    continue
                }

                val lowerDesc = desc.lowercase(Locale.ROOT)
                val lowerText = text.lowercase(Locale.ROOT)

                val positiveSignals = mutableListOf<Pair<String, Int>>()
                val negativePenalties = mutableListOf<Pair<String, Int>>()

                // 1. Content description signals
                if (lowerDesc == "copy link") {
                    positiveSignals.add("Exact copy link desc" to 100)
                } else if (lowerDesc.contains("copy link") || lowerDesc.contains("copylink")) {
                    positiveSignals.add("Substring copy link desc" to 85)
                } else if (lowerDesc == "copy") {
                    positiveSignals.add("Exact copy desc" to 60)
                } else if (lowerDesc.contains("copy")) {
                    positiveSignals.add("Contains copy in desc" to 45)
                }

                // 2. Text signals
                if (lowerText == "copy link") {
                    positiveSignals.add("Exact copy link text" to 100)
                } else if (lowerText.contains("copy link") || lowerText.contains("copylink")) {
                    positiveSignals.add("Substring copy link text" to 85)
                } else if (lowerText == "copy") {
                    positiveSignals.add("Exact copy text" to 60)
                } else if (lowerText.contains("copy")) {
                    positiveSignals.add("Contains copy in text" to 45)
                }

                // 3. Resource ID signals
                if (resId.contains("copy_link") || resId.contains("copylink")) {
                    positiveSignals.add("Exact copy link resource id" to 75)
                } else if (resId.endsWith(":id/copy") || resId.contains("_copy")) {
                    positiveSignals.add("Copy resource id" to 60)
                }

                // STRICT GATE: Must contain positive "copy" keyword signal
                if (positiveSignals.isNotEmpty()) {
                    var hasClickableParent = false
                    var clickableAncestor: AccessibilityNodeInfo? = null
                    if (node.isClickable) {
                        positiveSignals.add("Node is clickable" to 30)
                    } else {
                        var p = node.parent
                        while (p != null) {
                            if (p.isClickable) {
                                hasClickableParent = true
                                clickableAncestor = p
                                positiveSignals.add("Clickable parent (${p.className})" to 20)
                                break
                            }
                            p = p.parent
                        }
                    }

                    if (className.contains("ImageView") || className.contains("Button") || className.contains("FrameLayout")) {
                        positiveSignals.add("Standard action button class" to 20)
                    }
                    if (node.isVisibleToUser) {
                        positiveSignals.add("Visible to user" to 15)
                    }

                    if (reshareRowBounds != null && reshareRowBounds.contains(bounds.centerX(), bounds.centerY())) {
                        positiveSignals.add("Inside horizontal reshare row" to 25)
                    }

                    if (bounds.width() > 0 && bounds.height() > 0) {
                        positiveSignals.add("Valid bounds" to 10)
                    } else {
                        negativePenalties.add("Zero bounds" to 50)
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
                            totalScore = finalScore
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

        AppLogger.i(TAG, "CopyLinkLocator: allCandidates count=${allCandidates.size}")
        for ((idx, cand) in allCandidates.withIndex()) {
            AppLogger.d(TAG, "  [Raw Candidate #$idx]: desc='${cand.desc}', text='${cand.text}', resId='${cand.resId}', cls='${cand.className}', bounds=${cand.bounds}, score=${cand.totalScore}")
        }

        // Deduplicate candidates that represent the same physical control (e.g. icon + text label in same column/parent)
        val distinctCandidates = mutableListOf<CandidateNode>()
        for (cand in allCandidates) {
            val isDuplicate = distinctCandidates.any { existing ->
                val sameCenter = Math.abs(existing.bounds.centerX() - cand.bounds.centerX()) < 60 &&
                        Math.abs(existing.bounds.centerY() - cand.bounds.centerY()) < 60
                val overlapping = Rect.intersects(existing.bounds, cand.bounds) &&
                        (existing.bounds.contains(cand.bounds) || cand.bounds.contains(existing.bounds))
                val sameColumn = Math.abs(existing.bounds.centerX() - cand.bounds.centerX()) < 80 &&
                        Math.abs(existing.bounds.centerY() - cand.bounds.centerY()) < 350
                val sameParent = existing.node.parent != null && existing.node.parent == cand.node.parent
                sameCenter || overlapping || sameColumn || sameParent
            }
            if (!isDuplicate) {
                distinctCandidates.add(cand)
            }
        }

        AppLogger.i(TAG, "CopyLinkLocator: distinctCandidates count=${distinctCandidates.size}")
        for ((idx, cand) in distinctCandidates.withIndex()) {
            AppLogger.d(TAG, "  [Distinct Candidate #$idx]: desc='${cand.desc}', text='${cand.text}', resId='${cand.resId}', cls='${cand.className}', bounds=${cand.bounds}, score=${cand.totalScore}")
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

        val isSameCopyAction = best != null && runnerUp != null &&
                ((best.desc.contains("copy", ignoreCase = true) || best.text.contains("copy", ignoreCase = true) || best.resId.contains("copy", ignoreCase = true)) &&
                 (runnerUp.desc.contains("copy", ignoreCase = true) || runnerUp.text.contains("copy", ignoreCase = true) || runnerUp.resId.contains("copy", ignoreCase = true)))

        val isAmbiguous = best != null && (best.totalScore < MIN_CONFIDENCE_THRESHOLD || (runnerUp != null && !isSameCopyAction && margin < MIN_MARGIN_THRESHOLD))

        var isClipped = false
        var rowRevealPlan: RowRevealPlan? = null

        if (best != null && !isAmbiguous) {
            val candidateBounds = best.bounds
            // If Copy Link is near the right screen boundary or partially cut off
            if (candidateBounds.right >= viewportWidth - 80 || candidateBounds.left >= viewportWidth - 180) {
                isClipped = true
                val startX = if (reshareRowBounds != null) {
                    (reshareRowBounds.right - 100).toFloat().coerceIn(150f, (viewportWidth - 50).toFloat())
                } else {
                    (viewportWidth * 0.85f)
                }
                val endX = if (reshareRowBounds != null) {
                    (reshareRowBounds.left + 100).toFloat().coerceIn(50f, (viewportWidth - 150).toFloat())
                } else {
                    (viewportWidth * 0.15f)
                }
                val y = if (reshareRowBounds != null) {
                    reshareRowBounds.centerY().toFloat()
                } else {
                    candidateBounds.centerY().toFloat().coerceIn(100f, (viewportHeight - 100).toFloat())
                }
                rowRevealPlan = RowRevealPlan(startX, y, endX, y, reshareRowId)
                AppLogger.i(TAG, "CopyLinkLocator: Copy link is clipped at ${candidateBounds.right}px. Dynamic row swipe derived from ${reshareRowBounds ?: "fallback"}: ($startX -> $endX at y=$y)")
            }
        }

        AppLogger.i(TAG, "CopyLinkLocator: Candidates found: ${allCandidates.size}, Best=${best?.summary()}, Margin=$margin, Ambiguous=$isAmbiguous, Clipped=$isClipped, RowBounds=$reshareRowBounds")

        return CopyLinkEvaluationResult(
            bestCandidate = best,
            runnerUpCandidate = runnerUp,
            allCandidates = allCandidates,
            scoreMargin = margin,
            isAmbiguous = isAmbiguous,
            isClippedAtEdge = isClipped,
            dynamicRowRevealPlan = rowRevealPlan,
            shareRowBounds = reshareRowBounds
        )
    }

    /**
     * Finds targeted Share UI dismissal control (Cancel button, close icon) within the active share sheet.
     */
    fun findDismissalTarget(roots: List<AccessibilityNodeInfo>): DismissalTarget? {
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)

            while (!queue.isEmpty()) {
                val node = queue.poll() ?: break
                val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                val desc = (node.contentDescription ?: "").toString().lowercase(Locale.ROOT)
                val text = (node.text ?: "").toString().lowercase(Locale.ROOT)
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // Tier 1 targets: Cancel button or close icon
                val isCancelButton = text == "cancel" || desc == "cancel" || resId.contains("close") || resId.contains("cancel")
                if (isCancelButton && (node.isClickable || node.parent?.isClickable == true) && bounds.width() > 0 && bounds.height() > 0) {
                    AppLogger.i(TAG, "CopyLinkLocator: Found targeted Share sheet Cancel/Close control: text='$text', desc='$desc', id=$resId, bounds=$bounds")
                    return DismissalTarget(node, bounds, "CANCEL_BUTTON")
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return null
    }
}
