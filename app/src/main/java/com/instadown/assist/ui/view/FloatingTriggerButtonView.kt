package com.instadown.assist.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.instadown.assist.R

/**
 * Ultra-Modern Luxury Canvas-rendered floating trigger button (Dynamic Island Dock).
 * Renders obsidian glass background, neon dual-gradient jewel ring, real progress sweeps,
 * animated spring checkmark, and mathematically symmetric vector arrow.
 */
class FloatingTriggerButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class VisualState {
        IDLE,
        RESOLVING,
        DOWNLOADING,
        SUCCESS,
        PARTIAL_SUCCESS,
        DUPLICATE,
        ERROR
    }

    private var currentState: VisualState = VisualState.IDLE
    private var progressPercent: Int = 0
    private var carouselStatusText: String = ""
    private var isPressedState: Boolean = false

    // Paints
    private val obsidianBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#131622")
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#50000000")
    }
    private val neonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val progressRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val innerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#26FFFFFF")
    }
    private val successBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981") // Luxury Emerald
    }
    private val partialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F59E0B") // Amber
    }
    private val duplicateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8A67FF") // Violet
    }
    private val errorBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EF4444") // Coral Red
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val vectorArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val checkmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    // Geometries
    private val progressRect = RectF()
    private val arrowPath = Path()
    private val checkmarkPath = Path()
    private val errorPath = Path()
    private var indeterminateRotation = 0f
    private var scaleMultiplier = 1.0f

    // Animators
    private var rotationAnimator: ValueAnimator? = null
    private var checkmarkProgress = 0f
    private var checkmarkAnimator: ValueAnimator? = null

    init {
        val density = resources.displayMetrics.density
        neonBorderPaint.strokeWidth = 2.0f * density
        progressRingPaint.strokeWidth = 3.2f * density
        innerBorderPaint.strokeWidth = 1.0f * density
        vectorArrowPaint.strokeWidth = 2.2f * density
        checkmarkPaint.strokeWidth = 3.5f * density
        textPaint.textSize = 11.5f * density
    }

    fun setState(state: VisualState, progress: Int = 0, carouselLabel: String = "") {
        currentState = state
        progressPercent = progress.coerceIn(0, 100)
        carouselStatusText = carouselLabel

        when (state) {
            VisualState.RESOLVING -> startIndeterminateAnimation()
            VisualState.DOWNLOADING -> stopIndeterminateAnimation()
            VisualState.SUCCESS -> {
                stopIndeterminateAnimation()
                startCheckmarkAnimation()
            }
            VisualState.PARTIAL_SUCCESS, VisualState.DUPLICATE, VisualState.ERROR -> {
                stopIndeterminateAnimation()
            }
            VisualState.IDLE -> {
                stopIndeterminateAnimation()
                checkmarkProgress = 0f
            }
        }
        invalidate()
    }

    fun updateProgress(overallPercent: Int, label: String = "") {
        if (currentState != VisualState.DOWNLOADING) {
            currentState = VisualState.DOWNLOADING
            stopIndeterminateAnimation()
        }
        progressPercent = overallPercent.coerceIn(0, 100)
        carouselStatusText = label
        invalidate()
    }

    fun setPressedScale(isPressed: Boolean) {
        if (isPressedState == isPressed) return
        isPressedState = isPressed

        val targetScale = if (isPressed) 0.90f else 1.0f
        val animator = ValueAnimator.ofFloat(scaleMultiplier, targetScale).apply {
            duration = 150
            interpolator = if (isPressed) AccelerateDecelerateInterpolator() else OvershootInterpolator(2.0f)
            addUpdateListener {
                scaleMultiplier = it.animatedValue as Float
                invalidate()
            }
        }
        animator.start()
    }

    private fun startIndeterminateAnimation() {
        if (rotationAnimator?.isRunning == true) return
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                indeterminateRotation = it.animatedValue as Float
                invalidate()
            }
        }
        rotationAnimator?.start()
    }

    private fun stopIndeterminateAnimation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        indeterminateRotation = 0f
    }

    private fun startCheckmarkAnimation() {
        checkmarkAnimator?.cancel()
        checkmarkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener {
                checkmarkProgress = it.animatedValue as Float
                invalidate()
            }
        }
        checkmarkAnimator?.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val neonShader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    Color.parseColor("#8A67FF"), // Radiant Violet
                    Color.parseColor("#FF3377"), // Electric Coral
                    Color.parseColor("#FFA238")  // Amber Glow
                ),
                null,
                Shader.TileMode.CLAMP
            )
            neonBorderPaint.shader = neonShader

            val progressShader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    Color.parseColor("#38BDF8"), // Cyan
                    Color.parseColor("#8A67FF")  // Violet
                ),
                null,
                Shader.TileMode.CLAMP
            )
            progressRingPaint.shader = progressShader

            val padding = progressRingPaint.strokeWidth / 2f + 3f
            progressRect.set(padding, padding, w - padding, h - padding)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (Math.min(width, height) / 2f) - progressRingPaint.strokeWidth - 2f
        if (radius <= 0) return

        canvas.save()
        canvas.scale(scaleMultiplier, scaleMultiplier, cx, cy)

        // 1. Subtle Outer Drop Shadow
        canvas.drawCircle(cx, cy + 2f, radius + 1f, shadowPaint)

        // 2. Base Background Fill
        when (currentState) {
            VisualState.SUCCESS -> canvas.drawCircle(cx, cy, radius, successBgPaint)
            VisualState.PARTIAL_SUCCESS -> canvas.drawCircle(cx, cy, radius, partialBgPaint)
            VisualState.DUPLICATE -> canvas.drawCircle(cx, cy, radius, duplicateBgPaint)
            VisualState.ERROR -> canvas.drawCircle(cx, cy, radius, errorBgPaint)
            else -> canvas.drawCircle(cx, cy, radius, obsidianBgPaint)
        }

        // 3. Neon Jewel Border
        if (currentState == VisualState.IDLE) {
            canvas.drawCircle(cx, cy, radius, neonBorderPaint)
        }
        canvas.drawCircle(cx, cy, radius - 2f, innerBorderPaint)

        // 4. Progress Ring Overlay
        when (currentState) {
            VisualState.RESOLVING -> {
                canvas.save()
                canvas.rotate(indeterminateRotation, cx, cy)
                canvas.drawArc(progressRect, 0f, 100f, false, progressRingPaint)
                canvas.restore()
            }
            VisualState.DOWNLOADING -> {
                val sweep = (progressPercent / 100f) * 360f
                canvas.drawArc(progressRect, -90f, sweep, false, progressRingPaint)
            }
            else -> {}
        }

        // 5. Center State Iconography
        when (currentState) {
            VisualState.IDLE, VisualState.RESOLVING -> {
                drawSymmetricArrow(canvas, cx, cy, radius * 0.42f)
            }
            VisualState.DOWNLOADING -> {
                val label = if (carouselStatusText.isNotBlank()) carouselStatusText else "$progressPercent%"
                val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(label, cx, textY, textPaint)
            }
            VisualState.SUCCESS -> {
                drawCheckmark(canvas, cx, cy, radius * 0.52f, checkmarkProgress)
            }
            VisualState.PARTIAL_SUCCESS -> {
                val label = if (carouselStatusText.isNotBlank()) carouselStatusText else "PARTIAL"
                val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(label, cx, textY, textPaint)
            }
            VisualState.DUPLICATE -> {
                val label = "SAVED"
                val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(label, cx, textY, textPaint)
            }
            VisualState.ERROR -> {
                drawErrorCross(canvas, cx, cy, radius * 0.38f)
            }
        }

        canvas.restore()
    }

    private fun drawSymmetricArrow(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        arrowPath.reset()
        // Arrow stem
        arrowPath.moveTo(cx, cy - size * 0.75f)
        arrowPath.lineTo(cx, cy + size * 0.45f)
        // Arrow head
        arrowPath.moveTo(cx - size * 0.55f, cy - size * 0.05f)
        arrowPath.lineTo(cx, cy + size * 0.5f)
        arrowPath.lineTo(cx + size * 0.55f, cy - size * 0.05f)
        // Base landing tray
        arrowPath.moveTo(cx - size * 0.7f, cy + size * 0.85f)
        arrowPath.lineTo(cx + size * 0.7f, cy + size * 0.85f)

        canvas.drawPath(arrowPath, vectorArrowPaint)
    }

    private fun drawCheckmark(canvas: Canvas, cx: Float, cy: Float, size: Float, animProgress: Float) {
        if (animProgress <= 0f) return
        checkmarkPath.reset()

        val startX = cx - (size * 0.55f)
        val startY = cy
        val midX = cx - (size * 0.15f)
        val midY = cy + (size * 0.45f)
        val endX = cx + (size * 0.65f)
        val endY = cy - (size * 0.45f)

        checkmarkPath.moveTo(startX, startY)
        if (animProgress <= 0.4f) {
            val frac = animProgress / 0.4f
            checkmarkPath.lineTo(startX + (midX - startX) * frac, startY + (midY - startY) * frac)
        } else {
            checkmarkPath.lineTo(midX, midY)
            val frac = (animProgress - 0.4f) / 0.6f
            checkmarkPath.lineTo(midX + (endX - midX) * frac, midY + (endY - midY) * frac)
        }

        canvas.drawPath(checkmarkPath, checkmarkPaint)
    }

    private fun drawErrorCross(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        errorPath.reset()
        errorPath.moveTo(cx - size, cy - size)
        errorPath.lineTo(cx + size, cy + size)
        errorPath.moveTo(cx + size, cy - size)
        errorPath.lineTo(cx - size, cy + size)
        canvas.drawPath(errorPath, checkmarkPaint)
    }
}
