package com.instadown.assist.ui

import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.min

/**
 * Reusable Predictive Back binder for MediaSelectionActivity & GalleryActivity.
 * Animates live edge-swipe progress on Android 14-16 while staying 100% compatible with API 26+.
 */
fun AppCompatActivity.registerPredictiveBackMotion(
    rootContainer: View,
    shouldInterceptBack: () -> Boolean,
    onInterceptedCommit: () -> Unit
): OnBackPressedCallback {
    val callback = object : OnBackPressedCallback(true) {
        override fun handleOnBackStarted(backEvent: BackEventCompat) {
            rootContainer.pivotX = if (backEvent.swipeEdge == BackEventCompat.EDGE_LEFT) {
                rootContainer.width.toFloat()
            } else {
                0f
            }
            rootContainer.pivotY = rootContainer.height * 0.5f
        }

        override fun handleOnBackProgressed(backEvent: BackEventCompat) {
            val progress = min(backEvent.progress, 1f)
            val scale = 1f - (0.08f * progress)
            val shiftPx = (if (backEvent.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f) *
                (rootContainer.width * 0.04f * progress)

            rootContainer.scaleX = scale
            rootContainer.scaleY = scale
            rootContainer.translationX = shiftPx
            rootContainer.alpha = 1f - (0.15f * progress)
        }

        override fun handleOnBackCancelled() {
            rootContainer.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationX(0f)
                .alpha(1f)
                .setDuration(180L)
                .start()
        }

        override fun handleOnBackPressed() {
            resetTransform(rootContainer)
            if (shouldInterceptBack()) {
                onInterceptedCommit()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }
    onBackPressedDispatcher.addCallback(this, callback)
    return callback
}

private fun resetTransform(view: View) {
    view.scaleX = 1f
    view.scaleY = 1f
    view.translationX = 0f
    view.alpha = 1f
}
