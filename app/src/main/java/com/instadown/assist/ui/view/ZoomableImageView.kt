package com.instadown.assist.ui.view

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * A touch-interactive ImageView supporting pinch-to-zoom, double-tap zoom, and smooth panning.
 * Integrates cleanly with ViewPager2 by coordinating touch event interception.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var scaleDetector: ScaleGestureDetector
    private var gestureDetector: GestureDetector

    private var minScale = 1.0f
    private var maxScale = 4.0f
    private var isZoomed = false
    private val lastTouch = PointF()

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val currentScale = getScale()
                val targetScale = currentScale * scaleFactor

                if (targetScale in minScale..maxScale) {
                    suppMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    checkAndDisplayMatrix()
                }
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val currentScale = getScale()
                if (currentScale > 1.2f) {
                    // Reset to 1.0x
                    suppMatrix.reset()
                    checkAndDisplayMatrix()
                } else {
                    // Zoom to 2.5x
                    val zoomFactor = 2.5f / currentScale
                    suppMatrix.postScale(zoomFactor, zoomFactor, e.x, e.y)
                    checkAndDisplayMatrix()
                }
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val currentScale = getScale()
        isZoomed = currentScale > 1.05f

        // Allow ViewPager2 horizontal swipe when not zoomed; disallow when user is panning zoomed image
        parent?.requestDisallowInterceptTouchEvent(isZoomed)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch.set(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isZoomed && !scaleDetector.isInProgress) {
                    val dx = event.x - lastTouch.x
                    val dy = event.y - lastTouch.y
                    suppMatrix.postTranslate(dx, dy)
                    checkAndDisplayMatrix()
                }
                lastTouch.set(event.x, event.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (getScale() < minScale) {
                    suppMatrix.reset()
                    checkAndDisplayMatrix()
                }
            }
        }
        return true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) {
            resetBaseMatrix()
        }
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        resetBaseMatrix()
    }

    fun resetZoom() {
        suppMatrix.reset()
        checkAndDisplayMatrix()
    }

    private fun resetBaseMatrix() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return

        val dWidth = d.intrinsicWidth.toFloat()
        val dHeight = d.intrinsicHeight.toFloat()
        if (dWidth <= 0 || dHeight <= 0) return

        baseMatrix.reset()
        val scale = Math.min(viewWidth / dWidth, viewHeight / dHeight)
        val dx = (viewWidth - dWidth * scale) / 2f
        val dy = (viewHeight - dHeight * scale) / 2f

        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)

        suppMatrix.reset()
        checkAndDisplayMatrix()
    }

    private fun checkAndDisplayMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
        imageMatrix = drawMatrix
    }

    private fun getScale(): Float {
        suppMatrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }
}
