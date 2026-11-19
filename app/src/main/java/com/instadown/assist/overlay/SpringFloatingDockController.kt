package com.instadown.assist.overlay

import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.roundToInt

class SpringFloatingDockController(
    private val windowManager: WindowManager,
    private val overlayView: View,
    private val layoutParams: WindowManager.LayoutParams
) {
    private var springX: SpringAnimation? = null
    private var springY: SpringAnimation? = null

    private val windowXProperty = object : FloatPropertyCompat<WindowManager.LayoutParams>("windowX") {
        override fun getValue(params: WindowManager.LayoutParams): Float = params.x.toFloat()
        override fun setValue(params: WindowManager.LayoutParams, value: Float) {
            params.x = value.roundToInt()
            if (overlayView.isAttachedToWindow) {
                try {
                    windowManager.updateViewLayout(overlayView, params)
                } catch (ignored: Exception) {}
            }
        }
    }

    private val windowYProperty = object : FloatPropertyCompat<WindowManager.LayoutParams>("windowY") {
        override fun getValue(params: WindowManager.LayoutParams): Float = params.y.toFloat()
        override fun setValue(params: WindowManager.LayoutParams, value: Float) {
            params.y = value.roundToInt()
            if (overlayView.isAttachedToWindow) {
                try {
                    windowManager.updateViewLayout(overlayView, params)
                } catch (ignored: Exception) {}
            }
        }
    }

    fun cancelActiveSprings() {
        springX?.cancel()
        springY?.cancel()
    }

    /**
     * Docks bubble to nearest horizontal edge using velocity-aware spring physics,
     * clamping Y inside safe inset bounds (status bar + navigation bar).
     */
    fun snapToEdgeWithSpring(
        screenWidthPx: Int,
        safeTopPx: Int,
        safeBottomPx: Int,
        edgeMarginPx: Int,
        velocityTracker: VelocityTracker?,
        onSnapSettled: ((finalX: Int, finalY: Int) -> Unit)? = null
    ) {
        cancelActiveSprings()
        velocityTracker?.computeCurrentVelocity(1000)
        val vx = velocityTracker?.xVelocity ?: 0f
        val vy = velocityTracker?.yVelocity ?: 0f

        val bubbleWidth = overlayView.width.coerceAtLeast(1)
        val bubbleHeight = overlayView.height.coerceAtLeast(1)

        // Predict landing X using a short momentum projection (120 ms) so quick flicks cross the screen
        val projectedCenterX = layoutParams.x + (bubbleWidth / 2f) + (vx * 0.12f)
        val targetX = if (projectedCenterX < screenWidthPx / 2f) {
            edgeMarginPx.toFloat()
        } else {
            (screenWidthPx - bubbleWidth - edgeMarginPx).toFloat()
        }

        val targetY = (layoutParams.y + (vy * 0.08f))
            .coerceIn(safeTopPx.toFloat(), (safeBottomPx - bubbleHeight).coerceAtLeast(safeTopPx).toFloat())

        springX = SpringAnimation(layoutParams, windowXProperty, targetX).apply {
            setStartVelocity(vx)
            spring = SpringForce(targetX).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
            addEndListener { _, _, _, _ ->
                onSnapSettled?.invoke(layoutParams.x, layoutParams.y)
            }
            start()
        }

        springY = SpringAnimation(layoutParams, windowYProperty, targetY).apply {
            setStartVelocity(vy)
            spring = SpringForce(targetY).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
            start()
        }
    }
}
