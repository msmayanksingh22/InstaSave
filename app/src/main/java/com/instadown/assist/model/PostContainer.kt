package com.instadown.assist.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class PostFingerprint(
    val username: String,
    val captionSnippet: String,
    val mediaAspectRatio: Float,
    val containerHash: Int,
    val bounds: Rect
) {
    /**
     * Checks if two fingerprints represent the exact same post.
     */
    fun matches(other: PostFingerprint?): Boolean {
        if (other == null) return false
        // If both have usernames and they match, that is a definitive signal
        if (username.isNotBlank() && other.username.isNotBlank()) {
            if (username.equals(other.username, ignoreCase = true)) return true
        }
        // If both have caption snippets that match
        if (captionSnippet.isNotBlank() && other.captionSnippet.isNotBlank()) {
            if (captionSnippet == other.captionSnippet) return true
        }
        // Fallback to structural container hash and aspect ratio tolerance
        val aspectDiff = Math.abs(mediaAspectRatio - other.mediaAspectRatio)
        return containerHash == other.containerHash || aspectDiff < 0.05f
    }

    fun summary(): String {
        val userStr = if (username.isNotBlank()) "user=@$username" else "user=unknown"
        val capStr = if (captionSnippet.isNotBlank()) "cap='${captionSnippet.take(20)}...'" else ""
        return "[$userStr, $capStr, aspect=%.2f]".format(mediaAspectRatio)
    }
}

data class PostContainer(
    val rootNode: AccessibilityNodeInfo,
    val bounds: Rect,
    val visibleMediaBounds: Rect?,
    val username: String,
    val captionSnippet: String,
    val hasMedia: Boolean,
    val hasUsername: Boolean,
    val hasCaption: Boolean,
    val hasLike: Boolean,
    val hasComment: Boolean,
    val hasShare: Boolean,
    val hasSave: Boolean,
    val dominanceRatio: Float,
    val distanceFromCenter: Float,
    val score: Float,
    val actionRowBounds: Rect? = null,
    val shareNodeBounds: Rect? = null,
    val targetConfidenceScore: Float = 0f,
    val targetSignals: List<Pair<String, Int>> = emptyList(),
    val isTargetAmbiguous: Boolean = false,
    val targetMargin: Float = 0f,
    val carouselSlideIndex: Int? = null,
    val carouselTotalSlides: Int? = null
) {
    fun createFingerprint(): PostFingerprint {
        val aspect = if (visibleMediaBounds != null && visibleMediaBounds.height() > 0) {
            visibleMediaBounds.width().toFloat() / visibleMediaBounds.height().toFloat()
        } else if (bounds.height() > 0) {
            bounds.width().toFloat() / bounds.height().toFloat()
        } else {
            1.0f
        }

        val structuralHash = (username.hashCode() * 31) +
                (captionSnippet.hashCode() * 17) +
                (if (hasMedia) 1 else 0) * 7 +
                (if (hasShare) 1 else 0) * 3

        return PostFingerprint(
            username = username,
            captionSnippet = captionSnippet,
            mediaAspectRatio = aspect,
            containerHash = structuralHash,
            bounds = Rect(bounds)
        )
    }

    fun summary(): String {
        val slideInfo = if (carouselSlideIndex != null && carouselTotalSlides != null) " slide=${carouselSlideIndex + 1}/$carouselTotalSlides" else ""
        return "PostContainer(user='$username'$slideInfo, targetScore=%.1f, margin=%.1f, dominance=%.0f%%, bounds=$bounds)"
            .format(targetConfidenceScore, targetMargin, dominanceRatio * 100f)
    }
}
