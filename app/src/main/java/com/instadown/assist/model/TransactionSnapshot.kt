package com.instadown.assist.model

import android.graphics.Rect

data class TransactionSnapshot(
    val startTimeMs: Long,
    val foregroundPackage: String,
    val foregroundActivity: String,
    val postFingerprint: PostFingerprint?,
    val postBounds: Rect?,
    val baselineClipboardText: String,
    val baselineClipboardTimestamp: Long
) {
    fun summary(): String {
        val fpStr = postFingerprint?.summary() ?: "no-fingerprint"
        return "Snapshot(pkg=$foregroundPackage, act=$foregroundActivity, post=$fpStr, clipBaseline='${baselineClipboardText.take(20)}')"
    }
}
