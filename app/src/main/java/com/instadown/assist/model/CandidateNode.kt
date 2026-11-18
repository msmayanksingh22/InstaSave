package com.instadown.assist.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class CandidateNode(
    val node: AccessibilityNodeInfo,
    val text: String,
    val desc: String,
    val resId: String,
    val className: String,
    val bounds: Rect,
    val visibleToUser: Boolean,
    val isClickable: Boolean,
    val hasClickableAncestor: Boolean,
    val clickableAncestor: AccessibilityNodeInfo?,
    val windowId: Int,
    val packageName: String,
    val actions: List<Int>,
    val parentClassName: String,
    val neighborCount: Int,
    val nearestText: String,
    val positiveSignals: List<Pair<String, Int>>,
    val negativePenalties: List<Pair<String, Int>>,
    val totalScore: Int,
    val postContainer: PostContainer? = null
) {
    fun summary(): String {
        val label = if (desc.isNotBlank()) "desc='$desc'" else if (text.isNotBlank()) "text='$text'" else "id='$resId'"
        return "$label (Score: $totalScore, bounds=$bounds)"
    }

    fun details(): String {
        val posStr = positiveSignals.joinToString("; ") { "+${it.second} ${it.first}" }
        val negStr = if (negativePenalties.isNotEmpty()) {
            " | Penalties: " + negativePenalties.joinToString("; ") { "-${it.second} ${it.first}" }
        } else ""
        return "pkg=$packageName, class=$className, id=$resId, desc='$desc', text='$text', bounds=$bounds, clickable=$isClickable | Signals: [$posStr$negStr]"
    }
}
