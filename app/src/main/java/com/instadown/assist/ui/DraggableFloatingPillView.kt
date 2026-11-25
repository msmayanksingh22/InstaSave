package com.instadown.assist.ui

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.card.MaterialCardView
import kotlin.math.hypot

/**
 * Floating translucent capsule that supports normal child button clicks while
 * allowing smooth, edge-clamped vertical dragging (with spring-back horizontal
 * centering) anywhere over the WebView viewport.
 */
class DraggableFloatingPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startTranslationX = 0f
    private var startTranslationY = 0f
    private var isDragging = false

    var preferredTranslationY: Float = 0f
        private set

    var verticalBoundsProvider: (() -> Pair<Float, Float>)? = null
    var onDragSettled: ((Float) -> Unit)? = null

    fun setPreferredTranslationY(offsetY: Float, applyImmediately: Boolean = true) {
        preferredTranslationY = offsetY
        if (applyImmediately) {
            translationY = clampY(offsetY)
        }
    }

    fun clampCurrentPosition() {
        preferredTranslationY = clampY(preferredTranslationY)
        if (!isDragging) {
            translationY = preferredTranslationY
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!isDragging && visibility == VISIBLE) {
            val clamped = clampY(preferredTranslationY)
            if (clamped != translationY) {
                translationY = clamped
            }
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                startTranslationX = translationX
                startTranslationY = translationY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    beginDrag()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startTranslationX = translationX
                startTranslationY = translationY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                    beginDrag()
                }
                if (isDragging) {
                    val density = resources.displayMetrics.density
                    val maxXDrift = 14f * density
                    translationX = (startTranslationX + dx * 0.45f).coerceIn(-maxXDrift, maxXDrift)
                    translationY = clampY(startTranslationY + dy)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    finishDrag()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginDrag() {
        isDragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        animate().scaleX(1.025f).scaleY(1.025f).setDuration(90).start()
    }

    private fun finishDrag() {
        isDragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        animate().scaleX(1f).scaleY(1f).setDuration(120).start()

        val clampedY = clampY(translationY)
        preferredTranslationY = clampedY
        translationY = clampedY

        SpringAnimation(this, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            start()
        }

        onDragSettled?.invoke(clampedY)
    }

    private fun clampY(candidateY: Float): Float {
        val bounds = verticalBoundsProvider?.invoke() ?: return candidateY
        val minY = bounds.first
        val maxY = bounds.second
        return if (minY <= maxY) candidateY.coerceIn(minY, maxY) else candidateY
    }
}
