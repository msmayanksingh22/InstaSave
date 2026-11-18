package com.instadown.assist.ui.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.instadown.assist.R

/**
 * High-performance Canvas-rendered floating trigger button.
 * Enforces strictly invariant center coordinates (0px WindowManager drift)
 * and renders real progress rings (bytesWritten / totalBytes for single items;
 * (completed + itemFraction) / total for carousels), distinct success checkmarks,
 * partial success, duplicate, and error states.
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
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val progressRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val successBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00C853") // Vibrant Green
    }
    private val partialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF9100") // Amber
    }
    private val duplicateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#7928CA") // Purple
    }
    private val errorBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF1744") // Red
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val checkmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    // Geometries
    private val progressRect = RectF()
    private val checkmarkPath = Path()
    private val errorPath = Path()
    private var indeterminateRotation = 0f
    private var scaleMultiplier = 1.0f

    // Animators
    private var rotationAnimator: ValueAnimator? = null
    private var checkmarkProgress = 0f
    private var checkmarkAnimator: ValueAnimator? = null

    // Bitmaps
    private var downloadIconBitmap: Bitmap? = null

    init {
        val density = resources.displayMetrics.density
        borderPaint.strokeWidth = 2.5f * density
        progressRingPaint.strokeWidth = 3.5f * density
        checkmarkPaint.strokeWidth = 4.0f * density
        textPaint.textSize = 12f * density

        loadIcons()
    }

    private fun loadIcons() {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_download_arrow)
        if (drawable != null) {
            val size = (24 * resources.displayMetrics.density).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.setTint(Color.WHITE)
            drawable.draw(canvas)
            downloadIconBitmap = bmp
        }
    }

    fun setState(state: VisualState, progress: Int = 0, carouselLabel: String = "") {
        currentState = state
        progressPercent = progress.coerceIn(0, 100)
        carouselStatusText = carouselLabel

        when (state) {
            VisualState.RESOLVING -> {
                startIndeterminateAnimation()
            }
            VisualState.DOWNLOADING -> {
                stopIndeterminateAnimation()
            }
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
            duration = 1000
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
            duration = 350
            interpolator = OvershootInterpolator(1.5f)
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
            val shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(),
                intArrayOf(
                    Color.parseColor("#833AB4"),
                    Color.parseColor("#E1306C"),
                    Color.parseColor("#FD1D1D")
                ),
                null,
                Shader.TileMode.CLAMP
            )
            gradientPaint.shader = shader

            val padding = progressRingPaint.strokeWidth / 2f + 2f
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

        // 1. Base Background Circle
        when (currentState) {
            VisualState.SUCCESS -> canvas.drawCircle(cx, cy, radius, successBgPaint)
            VisualState.PARTIAL_SUCCESS -> canvas.drawCircle(cx, cy, radius, partialBgPaint)
            VisualState.DUPLICATE -> canvas.drawCircle(cx, cy, radius, duplicateBgPaint)
            VisualState.ERROR -> canvas.drawCircle(cx, cy, radius, errorBgPaint)
            else -> canvas.drawCircle(cx, cy, radius, gradientPaint)
        }

        // 2. White inner border
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // 3. Progress Ring Overlay
        when (currentState) {
            VisualState.RESOLVING -> {
                canvas.save()
                canvas.rotate(indeterminateRotation, cx, cy)
                canvas.drawArc(progressRect, 0f, 90f, false, progressRingPaint)
                canvas.restore()
            }
            VisualState.DOWNLOADING -> {
                val sweep = (progressPercent / 100f) * 360f
                canvas.drawArc(progressRect, -90f, sweep, false, progressRingPaint)
            }
            else -> {
                // No outer progress ring in other states
            }
        }

        // 4. Center State Representation
        when (currentState) {
            VisualState.IDLE -> {
                downloadIconBitmap?.let { bmp ->
                    val left = cx - (bmp.width / 2f)
                    val top = cy - (bmp.height / 2f)
                    canvas.drawBitmap(bmp, left, top, null)
                }
            }
            VisualState.RESOLVING -> {
                downloadIconBitmap?.let { bmp ->
                    val left = cx - (bmp.width / 2f)
                    val top = cy - (bmp.height / 2f)
                    canvas.drawBitmap(bmp, left, top, null)
                }
            }
            VisualState.DOWNLOADING -> {
                if (carouselStatusText.isNotBlank()) {
                    // Show item progress text like "2/5" or "48%"
                    val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText(carouselStatusText, cx, textY, textPaint)
                } else {
                    // Show percentage
                    val label = "$progressPercent%"
                    val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText(label, cx, textY, textPaint)
                }
            }
            VisualState.SUCCESS -> {
                drawCheckmark(canvas, cx, cy, radius * 0.5f, checkmarkProgress)
            }
            VisualState.PARTIAL_SUCCESS -> {
                // Distinct partial state: label or dash
                val label = if (carouselStatusText.isNotBlank()) carouselStatusText else "PARTIAL"
                val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(label, cx, textY, textPaint)
            }
            VisualState.DUPLICATE -> {
                // Distinct duplicate indicator: "SAVED"
                val label = "SAVED"
                val textY = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                canvas.drawText(label, cx, textY, textPaint)
            }
            VisualState.ERROR -> {
                drawErrorCross(canvas, cx, cy, radius * 0.4f)
            }
        }

        canvas.restore()
    }

    private fun drawCheckmark(canvas: Canvas, cx: Float, cy: Float, size: Float, animProgress: Float) {
        if (animProgress <= 0f) return
        checkmarkPath.reset()

        // Checkmark points relative to center
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
