package com.instadown.assist.engine

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.instadown.assist.model.PostContainer
import java.util.ArrayDeque
import java.util.Locale

object PostDetector {
    private const val TAG = "InstaDownloaderPOC"

    const val MIN_TARGET_CONFIDENCE = 75f
    const val MIN_TARGET_MARGIN = 25f

    data class TargetResolutionResult(
        val intendedPost: PostContainer?,
        val activePost: PostContainer?,
        val allCandidates: List<PostContainer>,
        val scoreMargin: Float,
        val isAmbiguous: Boolean,
        val ambiguityReason: String = ""
    )

    /**
     * Legacy/convenience method: returns the dominant or intended post.
     */
    fun findDominantPost(
        root: AccessibilityNodeInfo,
        viewportWidth: Int,
        viewportHeight: Int
    ): PostContainer? {
        val result = resolveIntendedPost(root, viewportWidth, viewportHeight)
        return result.intendedPost ?: result.activePost
    }

    /**
     * Resolves the user's INTENDED post when one or more Instagram posts are visible.
     * Prioritizes floating button spatial association, action-row visibility, and accessibility focus over visual dominance.
     */
    fun resolveIntendedPost(
        root: AccessibilityNodeInfo,
        viewportWidth: Int,
        viewportHeight: Int,
        triggerBounds: Rect? = null,
        triggerPoint: PointF? = null,
        recentInteractionNode: AccessibilityNodeInfo? = null,
        visibleShareNodes: List<com.instadown.assist.model.CandidateNode> = emptyList()
    ): TargetResolutionResult {
        val topInset = (viewportHeight * 0.05f).toInt().coerceIn(40, 220)
        val bottomInset = (viewportHeight * 0.06f).toInt().coerceIn(40, 220)
        val usableViewport = Rect(0, topInset, viewportWidth, viewportHeight - bottomInset)
        val viewportArea = usableViewport.width() * usableViewport.height().toFloat()
        if (viewportArea <= 0) {
            return TargetResolutionResult(null, null, emptyList(), 0f, true, "Invalid viewport dimensions")
        }

        val isPortrait = viewportHeight >= viewportWidth
        val minContainerWidth = if (isPortrait) (viewportWidth * 0.70f).toInt() else (viewportWidth * 0.35f).toInt()
        val minContainerHeight = (usableViewport.height() * 0.15f).toInt()
        val maxContainerHeight = (viewportHeight * 1.05f).toInt()

        val rawCandidates = mutableListOf<PostContainer>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (!queue.isEmpty()) {
            val node = queue.poll() ?: break
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val resId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val className = (node.className ?: "").toString()

            // Hard exclusion: Scroll/List container holding multiple posts is NEVER an individual post
            val isScrollListContainer = className.contains("RecyclerView") ||
                    className.contains("ListView") ||
                    className.contains("ViewPager") ||
                    className.contains("ScrollView") ||
                    resId.endsWith(":id/list") ||
                    resId.contains("recycler_view") ||
                    resId.contains("tab_view_pager")

            if (isScrollListContainer) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
                continue
            }

            // Potential post containers in Instagram native layouts
            val isSpecificPostId = resId.contains("feed_item") ||
                    resId.contains("media_container") ||
                    resId.contains("row_feed") ||
                    resId.contains("clip") ||
                    resId.contains("reel") ||
                    resId.contains("post_container") ||
                    resId.contains("carousel") ||
                    resId.contains("media_group")

            val isPotentialGenericContainer = (className.contains("ViewGroup") || className.contains("FrameLayout") || className.contains("TextureView")) &&
                    bounds.width() >= minContainerWidth &&
                    bounds.height() in minContainerHeight..maxContainerHeight

            // Normalized Reel container check: Fullscreen or tall 9:16 aspect ratio relative to viewport
            val isReelContainer = (isSpecificPostId && (resId.contains("reel") || resId.contains("clip"))) ||
                    (bounds.height() >= usableViewport.height() * 0.65f && (bounds.height().toFloat() / bounds.width().coerceAtLeast(1).toFloat()) >= 1.35f)

            val isPotentialContainer = isSpecificPostId || isPotentialGenericContainer || isReelContainer

            if (isPotentialContainer) {
                val container = inspectPostContainer(node, bounds, usableViewport, viewportArea)
                if (container != null && container.dominanceRatio > 0.10f) {
                    rawCandidates.add(container)
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // Fallback if no container matched, or if pre-located Share button is not enclosed by raw candidates
        val hasShareEnclosed = visibleShareNodes.firstOrNull()?.let { share ->
            rawCandidates.any { cand -> cand.bounds.contains(share.bounds.centerX(), share.bounds.centerY()) }
        } ?: true

        if (rawCandidates.isEmpty() || !hasShareEnclosed) {
            val rootBounds = Rect()
            root.getBoundsInScreen(rootBounds)
            val fullScreenContainer = inspectPostContainer(root, rootBounds, usableViewport, viewportArea)
            if (fullScreenContainer != null) {
                rawCandidates.add(fullScreenContainer)
            }
        }

        if (rawCandidates.isEmpty()) {
            Log.w(TAG, "PostDetector: No valid post container detected.")
            return TargetResolutionResult(null, null, emptyList(), 0f, true, "No candidate post detected on screen")
        }

        // Deduplicate nested containers that represent the same physical post.
        // If two containers have >= 60% vertical overlap, they belong to the same post hierarchy.
        // Retain the one with more complete action row/share button or higher featureScore.
        val deduplicated = mutableListOf<PostContainer>()
        for (candidate in rawCandidates.sortedByDescending { it.score }) {
            val duplicateIndex = deduplicated.indexOfFirst { existing ->
                val overlapTop = Math.max(candidate.bounds.top, existing.bounds.top)
                val overlapBottom = Math.min(candidate.bounds.bottom, existing.bounds.bottom)
                val overlapHeight = Math.max(0, overlapBottom - overlapTop)
                val minHeight = Math.min(candidate.bounds.height(), existing.bounds.height())
                minHeight > 0 && (overlapHeight.toFloat() / minHeight.toFloat()) >= 0.60f
            }
            if (duplicateIndex == -1) {
                deduplicated.add(candidate)
            } else {
                val existing = deduplicated[duplicateIndex]
                if ((!existing.hasShare && candidate.hasShare) || (existing.username.isBlank() && candidate.username.isNotBlank())) {
                    deduplicated[duplicateIndex] = candidate
                }
            }
        }

        // Spatially link pre-located Share buttons from ShareLocator to post containers
        val candidatesWithShare = deduplicated.map { cand ->
            if (!cand.hasShare && visibleShareNodes.isNotEmpty()) {
                val matchedShare = visibleShareNodes.firstOrNull { share ->
                    val sy = share.bounds.centerY()
                    sy >= cand.bounds.top - 80 && sy <= cand.bounds.bottom + 250
                }
                if (matchedShare != null) {
                    cand.copy(
                        hasShare = true,
                        hasLike = true,
                        shareNodeBounds = matchedShare.bounds,
                        actionRowBounds = matchedShare.bounds
                    )
                } else cand
            } else cand
        }

        // ACTIVE_POST: The post that mathematically appears most visually dominant (legacy metric)
        val activePost = candidatesWithShare.maxByOrNull { it.score }

        // Evaluate INTENDED_POST using the 5-layer TargetConfidence model
        val evaluatedCandidates = candidatesWithShare.map { cand ->
            evaluateTargetConfidence(cand, triggerBounds, triggerPoint, recentInteractionNode, usableViewport)
        }.sortedByDescending { it.targetConfidenceScore }

        val best = evaluatedCandidates.firstOrNull()
        val runnerUp = if (evaluatedCandidates.size > 1) evaluatedCandidates[1] else null
        val margin = if (best != null && runnerUp != null) {
            best.targetConfidenceScore - runnerUp.targetConfidenceScore
        } else if (best != null) {
            best.targetConfidenceScore
        } else {
            0f
        }

        var isAmbiguous = false
        var ambiguityReason = ""

        if (evaluatedCandidates.size > 1) {
            if (best == null || best.targetConfidenceScore < MIN_TARGET_CONFIDENCE) {
                isAmbiguous = true
                ambiguityReason = "Best candidate score (${best?.targetConfidenceScore ?: 0f}) below threshold ($MIN_TARGET_CONFIDENCE)"
            } else if (margin < MIN_TARGET_MARGIN) {
                isAmbiguous = true
                ambiguityReason = "Candidate margin ($margin) below safety threshold ($MIN_TARGET_MARGIN) between top 2 posts: '${best.username}' vs '${runnerUp?.username}'"
            }
        } else if (evaluatedCandidates.size == 1) {
            if (best != null && best.targetConfidenceScore < 40f) {
                isAmbiguous = true
                ambiguityReason = "Single candidate score (${best.targetConfidenceScore}) too low"
            }
        }

        val finalizedBest = best?.copy(
            isTargetAmbiguous = isAmbiguous,
            targetMargin = margin
        )

        Log.i(TAG, "PostDetector: Evaluated ${evaluatedCandidates.size} post(s). Best='${best?.username}' (TargetScore=%.1f, Margin=%.1f, Ambiguous=$isAmbiguous, Dominance=%.0f%%)"
            .format(best?.targetConfidenceScore ?: 0f, margin, (best?.dominanceRatio ?: 0f) * 100f))
        if (runnerUp != null) {
            Log.i(TAG, "PostDetector: RunnerUp='${runnerUp.username}' (TargetScore=%.1f, Dominance=%.0f%%)"
                .format(runnerUp.targetConfidenceScore, runnerUp.dominanceRatio * 100f))
        }
        if (isAmbiguous) {
            Log.w(TAG, "PostDetector: AMBIGUOUS_TARGET detected: $ambiguityReason")
        }

        return TargetResolutionResult(
            intendedPost = if (isAmbiguous) null else finalizedBest,
            activePost = activePost,
            allCandidates = evaluatedCandidates,
            scoreMargin = margin,
            isAmbiguous = isAmbiguous,
            ambiguityReason = ambiguityReason
        )
    }

    private fun evaluateTargetConfidence(
        candidate: PostContainer,
        triggerBounds: Rect?,
        triggerPoint: PointF?,
        recentInteractionNode: AccessibilityNodeInfo?,
        usableViewport: Rect
    ): PostContainer {
        var targetScore = 0f
        val signals = mutableListOf<Pair<String, Int>>()

        // 1. Floating Trigger Button Spatial Association (up to 100 pts)
        if (triggerBounds != null || triggerPoint != null) {
            val tx = triggerPoint?.x ?: triggerBounds?.centerX()?.toFloat() ?: 0f
            val ty = triggerPoint?.y ?: triggerBounds?.centerY()?.toFloat() ?: 0f

            // Direct vertical containment within post bounds
            val isVerticallyContained = ty >= candidate.bounds.top && ty <= candidate.bounds.bottom
            if (isVerticallyContained) {
                targetScore += 60f
                signals.add("Trigger inside post bounds" to 60)
            } else {
                val distY = if (ty < candidate.bounds.top) candidate.bounds.top - ty else ty - candidate.bounds.bottom
                if (distY > 200) {
                    targetScore -= 60f
                    signals.add("Trigger far outside post (${distY.toInt()}px)" to -60)
                } else {
                    val edgePenalty = ((distY / 200f) * 40f).toInt()
                    targetScore -= edgePenalty
                    signals.add("Trigger near post boundary (${distY.toInt()}px)" to -edgePenalty)
                }
            }

            // Proximity to the post's action row / Share button
            val arBounds = candidate.actionRowBounds ?: candidate.shareNodeBounds
            if (arBounds != null) {
                val arDistY = Math.abs(ty - arBounds.centerY())
                if (arDistY <= 180) {
                    targetScore += 40f
                    signals.add("Trigger adjacent to action row (${arDistY.toInt()}px)" to 40)
                } else if (arDistY <= 400) {
                    targetScore += 20f
                    signals.add("Trigger near action row (${arDistY.toInt()}px)" to 20)
                } else if (arDistY <= 650) {
                    targetScore += 10f
                    signals.add("Trigger moderate distance to action row (${arDistY.toInt()}px)" to 10)
                }
            }

            // Normalized center distance to trigger
            val normDist = Math.abs(ty - candidate.bounds.centerY()) / usableViewport.height().toFloat()
            val centerBonus = ((1f - normDist.coerceIn(0f, 1f)) * 20f).toInt()
            targetScore += centerBonus
            signals.add("Trigger-to-post center proximity" to centerBonus)
        } else {
            // Fallback if no trigger coordinates: center of viewport
            val normCenterDist = candidate.distanceFromCenter / usableViewport.height().toFloat()
            val focalBonus = ((1f - normCenterDist.coerceIn(0f, 1f)) * 30f).toInt()
            targetScore += focalBonus
            signals.add("Viewport center proximity fallback" to focalBonus)
        }

        // 2. Action-Row & Share-Button Completeness (up to 50 pts)
        if (candidate.hasShare) {
            val shareInViewport = candidate.shareNodeBounds?.let { usableViewport.contains(it.centerX(), it.centerY()) } ?: true
            if (shareInViewport) {
                targetScore += 40f
                signals.add("Share button visible in viewport" to 40)
            } else {
                targetScore -= 20f
                signals.add("Share button clipped or offscreen" to -20)
            }
        } else {
            targetScore -= 40f
            signals.add("No Share button in post" to -40)
        }

        if (candidate.hasLike && candidate.hasComment) {
            targetScore += 10f
            signals.add("Complete action row (Like & Comment)" to 10)
        }

        // 3. Accessibility Focus & Interaction State (up to 40 pts)
        if (candidate.rootNode.isFocused || candidate.rootNode.isAccessibilityFocused) {
            targetScore += 40f
            signals.add("Post container has focus" to 40)
        } else if (recentInteractionNode != null) {
            val nodeBounds = Rect()
            recentInteractionNode.getBoundsInScreen(nodeBounds)
            if (candidate.bounds.contains(nodeBounds.centerX(), nodeBounds.centerY())) {
                targetScore += 25f
                signals.add("Recent touch inside post" to 25)
            }
        }

        // 4. Viewport Dominance (Demoted fallback, up to 10 pts max)
        val domPoints = (candidate.dominanceRatio * 10f).toInt()
        targetScore += domPoints
        signals.add("Viewport dominance fallback (%.0f%%)".format(candidate.dominanceRatio * 100f) to domPoints)

        Log.i(TAG, "PostCandidate: user='${candidate.username}' bounds=${candidate.bounds} score=$targetScore signals=$signals")

        return candidate.copy(
            targetConfidenceScore = targetScore,
            targetSignals = signals
        )
    }

    private fun inspectPostContainer(
        containerNode: AccessibilityNodeInfo,
        containerBounds: Rect,
        usableViewport: Rect,
        viewportArea: Float
    ): PostContainer? {
        val intersection = Rect()
        val isVisibleInViewport = intersection.setIntersect(containerBounds, usableViewport)
        if (!isVisibleInViewport) return null

        val visibleArea = intersection.width() * intersection.height().toFloat()
        val dominanceRatio = (visibleArea / viewportArea).coerceIn(0f, 1f)
        val distanceFromCenter = Math.abs(intersection.centerY() - usableViewport.centerY()).toFloat()

        var hasMedia = false
        var hasUsername = false
        var hasCaption = false
        var hasLike = false
        var hasComment = false
        var hasShare = false
        var hasSave = false
        var username = ""
        var captionSnippet = ""
        var visibleMediaBounds: Rect? = null
        var actionRowBounds: Rect? = null
        var shareNodeBounds: Rect? = null
        var detectedSlideIndex: Int? = null
        var detectedTotalSlides: Int? = null
        val slideRegex = Regex("""\b(?:photo|video|slide|item)?\s*(\d+)\s*(?:of|\/)\s*(\d+)\b""", RegexOption.IGNORE_CASE)

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(containerNode)
        var inspectedCount = 0

        while (!queue.isEmpty() && inspectedCount < 250) {
            val child = queue.poll() ?: break
            inspectedCount++

            val resId = (child.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val desc = (child.contentDescription ?: "").toString().lowercase(Locale.ROOT)
            val text = (child.text ?: "").toString()
            val childBounds = Rect()
            child.getBoundsInScreen(childBounds)
            val childCls = (child.className ?: "").toString()

            // Carousel slide signals: Strictly within this container's descendants
            if (detectedSlideIndex == null) {
                val rawDesc = (child.contentDescription ?: "").toString()
                val rawText = (child.text ?: "").toString()
                for (target in listOf(rawDesc, rawText)) {
                    if (target.isNotBlank()) {
                        val m = slideRegex.find(target)
                        if (m != null) {
                            val cur = m.groupValues[1].toIntOrNull()
                            val total = m.groupValues[2].toIntOrNull()
                            if (cur != null && total != null && cur in 1..total && total > 1) {
                                detectedSlideIndex = cur - 1
                                detectedTotalSlides = total
                                break
                            }
                        }
                    }
                }
            }

            // Media signals: Images, Videos, Textures, Carousels
            if (resId.contains("media_group") || resId.contains("carousel") || resId.contains("image_view") ||
                resId.contains("video_view") || resId.contains("texture") || resId.contains("surface") ||
                childCls.contains("TextureView") || childCls.contains("SurfaceView") || childCls.contains("VideoView") ||
                (childCls.contains("ImageView") && childBounds.width() > 450 && childBounds.height() > 300) ||
                desc.contains("photo") || desc.contains("video") || desc.contains("reel") || desc.contains("carousel")) {
                hasMedia = true
                if (visibleMediaBounds == null || (childBounds.width() * childBounds.height()) > (visibleMediaBounds.width() * visibleMediaBounds.height())) {
                    visibleMediaBounds = Rect(childBounds)
                }
            }

            // Username signals
            if (username.isBlank()) {
                if (resId.contains("profile_header") || resId.contains("user_name") || resId.contains("author")) {
                    if (text.isNotBlank() && !text.contains(" ") && text.length in 3..30) {
                        username = text
                        hasUsername = true
                    }
                } else if (desc.contains("'s profile picture")) {
                    val candidate = desc.split("'s profile picture")[0].trim()
                    if (candidate.length in 2..35) {
                        username = candidate
                        hasUsername = true
                    }
                } else if (desc.contains("profile picture of ")) {
                    val candidate = desc.split("profile picture of ")[1].trim()
                    if (candidate.length in 2..35) {
                        username = candidate
                        hasUsername = true
                    }
                } else if (desc.contains("posted a photo") || desc.contains("posted a carousel") || desc.contains("posted a video")) {
                    val words = desc.split(" ")
                    if (words.isNotEmpty() && words[0].length in 2..30) {
                        username = words[0]
                        hasUsername = true
                    }
                } else if (text.isNotBlank() && !text.contains(" ") && !text.contains("•") && !text.contains(":") && text.length in 3..30 &&
                    childBounds.top in containerBounds.top..(containerBounds.top + 280) &&
                    childBounds.height() in 20..100 &&
                    !text.equals("follow", ignoreCase = true) && !text.equals("sponsored", ignoreCase = true) && !text.equals("ad", ignoreCase = true)) {
                    username = text
                    hasUsername = true
                } else if (desc.contains(" by ") && !desc.contains("audio") && !desc.contains("music") && !desc.contains("song") && !desc.contains("track")) {
                    val parts = desc.split(" by ")
                    if (parts.size > 1) {
                        val potentialUser = parts[1].split(",")[0].trim()
                        if (potentialUser.length in 2..35 && !potentialUser.contains(" ") && !potentialUser.contains("audio")) {
                            username = potentialUser
                            hasUsername = true
                        }
                    }
                }
            }

            // Caption signals
            if (captionSnippet.isBlank() && text.length > 10 && !text.contains("hours ago") && !text.contains("likes") && !text.contains("comments")) {
                captionSnippet = text.take(60)
                hasCaption = true
            }

            // Action signals & Action Row Bounds Detection
            if (desc.contains("like") || resId.contains("button_like")) {
                hasLike = true
                if (actionRowBounds == null) actionRowBounds = Rect(childBounds) else actionRowBounds.union(childBounds)
            }
            if (desc.contains("comment") || resId.contains("button_comment")) {
                hasComment = true
                if (actionRowBounds == null) actionRowBounds = Rect(childBounds) else actionRowBounds.union(childBounds)
            }
            if (desc.contains("share") || desc.contains("send post") || desc.contains("send reel") || desc.contains("share post") ||
                resId.contains("button_share") || resId.contains("share_button") || resId.contains("row_feed_button_share")) {
                hasShare = true
                shareNodeBounds = Rect(childBounds)
                if (actionRowBounds == null) actionRowBounds = Rect(childBounds) else actionRowBounds.union(childBounds)
            }
            if (desc.contains("save") || desc.contains("bookmark") || resId.contains("button_save")) {
                hasSave = true
                if (actionRowBounds == null) actionRowBounds = Rect(childBounds) else actionRowBounds.union(childBounds)
            }

            for (i in 0 until child.childCount) {
                child.getChild(i)?.let { queue.add(it) }
            }
        }

        // Sibling inspection for Instagram RecyclerView architecture where Header, Media, and Buttons are siblings
        if (!hasUsername || !hasShare) {
            val parent = containerNode.parent
            if (parent != null) {
                for (siblingIdx in 0 until parent.childCount) {
                    val sibling = parent.getChild(siblingIdx) ?: continue
                    if (sibling == containerNode) continue
                    val sibBounds = Rect()
                    sibling.getBoundsInScreen(sibBounds)

                    // Only consider siblings close to this container (within 600px above or below)
                    val isCloseAbove = sibBounds.bottom <= containerBounds.top && (containerBounds.top - sibBounds.bottom) < 400
                    val isCloseBelow = sibBounds.top >= containerBounds.bottom && (sibBounds.top - containerBounds.bottom) < 600
                    if (!isCloseAbove && !isCloseBelow) continue

                    val sibRes = (sibling.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                    val sibDesc = (sibling.contentDescription ?: "").toString().lowercase(Locale.ROOT)

                    if (!hasUsername && isCloseAbove) {
                        if (sibRes.contains("profile_header") || sibDesc.contains("posted a") || sibDesc.contains("profile picture")) {
                            hasUsername = true
                            if (username.isBlank()) {
                                if (sibling.text?.isNotBlank() == true && !sibling.text.contains(" ")) {
                                    username = sibling.text.toString()
                                } else {
                                    for (c in 0 until sibling.childCount) {
                                        val sc = sibling.getChild(c) ?: continue
                                        val scText = sc.text?.toString() ?: ""
                                        val scDesc = sc.contentDescription?.toString() ?: ""
                                        if (scText.isNotBlank() && !scText.contains(" ") && scText.length in 2..30) {
                                            username = scText
                                            break
                                        } else if (scDesc.contains("'s profile picture")) {
                                            username = scDesc.split("'s profile picture")[0].trim()
                                            break
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!hasShare && isCloseBelow) {
                        if (sibRes.contains("buttons") || sibRes.contains("share") || sibDesc.contains("send post") || sibDesc.contains("share")) {
                            hasShare = true
                            hasLike = true
                            shareNodeBounds = Rect(sibBounds)
                        }
                    }
                }
            }
        }
        Log.i(TAG, "inspectPostContainer: inspected $inspectedCount nodes for bounds=$containerBounds -> user='$username', hasShare=$hasShare, hasLike=$hasLike, hasMedia=$hasMedia, actionRow=$actionRowBounds")

        // Must have at least 1 strong media or post signature element
        var featureScore = 0f
        if (hasMedia) featureScore += 35f
        if (hasUsername) featureScore += 25f
        if (hasShare) featureScore += 25f
        if (hasLike) featureScore += 10f
        if (hasComment) featureScore += 10f
        if (hasCaption) featureScore += 10f

        if (featureScore < 30f) return null

        // Legacy score formula: visual dominance
        val centerProximityScore = (1f - (distanceFromCenter / usableViewport.height().toFloat()).coerceIn(0f, 1f)) * 40f
        val compositeScore = (dominanceRatio * 50f) + centerProximityScore + featureScore

        return PostContainer(
            rootNode = containerNode,
            bounds = containerBounds,
            visibleMediaBounds = visibleMediaBounds,
            username = username,
            captionSnippet = captionSnippet,
            hasMedia = hasMedia,
            hasUsername = hasUsername,
            hasCaption = hasCaption,
            hasLike = hasLike,
            hasComment = hasComment,
            hasShare = hasShare,
            hasSave = hasSave,
            dominanceRatio = dominanceRatio,
            distanceFromCenter = distanceFromCenter,
            score = compositeScore,
            actionRowBounds = actionRowBounds,
            shareNodeBounds = shareNodeBounds,
            carouselSlideIndex = detectedSlideIndex,
            carouselTotalSlides = detectedTotalSlides
        )
    }
}
