package com.instadown.assist.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager

class FloatingPositionManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "FloatingPosMgr"
        private const val PREFS_NAME = "instasave_floating_position"
        private const val KEY_NORMALIZED_X = "norm_x"
        private const val KEY_NORMALIZED_Y = "norm_y"

        // Default position: mid-right edge, comfortable thumb reach
        const val DEFAULT_X_RATIO = 0.92f
        const val DEFAULT_Y_RATIO = 0.40f
    }

    var normalizedX: Float
        get() = prefs.getFloat(KEY_NORMALIZED_X, DEFAULT_X_RATIO).coerceIn(0f, 1f)
        private set(value) = prefs.edit().putFloat(KEY_NORMALIZED_X, value.coerceIn(0f, 1f)).apply()

    var normalizedY: Float
        get() = prefs.getFloat(KEY_NORMALIZED_Y, DEFAULT_Y_RATIO).coerceIn(0f, 1f)
        private set(value) = prefs.edit().putFloat(KEY_NORMALIZED_Y, value.coerceIn(0f, 1f)).apply()

    data class UsableArea(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val width: Int,
        val height: Int
    )

    fun getUsableScreenArea(windowManager: WindowManager): UsableArea {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )

            val left = bounds.left + insets.left
            val top = bounds.top + insets.top
            val right = bounds.right - insets.right
            val bottom = bounds.bottom - insets.bottom
            val width = (right - left).coerceAtLeast(1)
            val height = (bottom - top).coerceAtLeast(1)

            return UsableArea(left, top, right, bottom, width, height)
        } else {
            val displayMetrics = context.resources.displayMetrics
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            return UsableArea(0, 0, width, height, width, height)
        }
    }

    /**
     * Converts the stored normalized coordinates into exact clamped pixel coordinates
     * for the given button dimensions, guaranteeing 100% visibility on screen.
     */
    fun calculatePixelPosition(
        windowManager: WindowManager,
        buttonWidth: Int,
        buttonHeight: Int
    ): Point {
        val area = getUsableScreenArea(windowManager)
        val maxAvailableX = (area.width - buttonWidth).coerceAtLeast(0)
        val maxAvailableY = (area.height - buttonHeight).coerceAtLeast(0)

        val rawX = area.left + (normalizedX * maxAvailableX).toInt()
        val rawY = area.top + (normalizedY * maxAvailableY).toInt()

        val clampedX = rawX.coerceIn(area.left, area.left + maxAvailableX)
        val clampedY = rawY.coerceIn(area.top, area.top + maxAvailableY)

        Log.d(TAG, "calculatePixelPosition: norm=($normalizedX, $normalizedY) -> pixel=($clampedX, $clampedY) area=$area")
        return Point(clampedX, clampedY)
    }

    /**
     * Commits the final position on drag completion (ACTION_UP).
     * Normalizes against current usable screen boundaries and clamps before saving.
     */
    fun savePosition(
        windowManager: WindowManager,
        droppedX: Int,
        droppedY: Int,
        buttonWidth: Int,
        buttonHeight: Int
    ): Point {
        val area = getUsableScreenArea(windowManager)
        val maxAvailableX = (area.width - buttonWidth).coerceAtLeast(0)
        val maxAvailableY = (area.height - buttonHeight).coerceAtLeast(0)

        val clampedX = droppedX.coerceIn(area.left, area.left + maxAvailableX)
        val clampedY = droppedY.coerceIn(area.top, area.top + maxAvailableY)

        val newNormX = if (maxAvailableX > 0) {
            ((clampedX - area.left).toFloat() / maxAvailableX).coerceIn(0f, 1f)
        } else DEFAULT_X_RATIO

        val newNormY = if (maxAvailableY > 0) {
            ((clampedY - area.top).toFloat() / maxAvailableY).coerceIn(0f, 1f)
        } else DEFAULT_Y_RATIO

        normalizedX = newNormX
        normalizedY = newNormY

        Log.i(TAG, "savePosition: Persisted normalized=($newNormX, $newNormY) from pixel=($clampedX, $clampedY)")
        return Point(clampedX, clampedY)
    }

    fun resetPosition(
        windowManager: WindowManager,
        buttonWidth: Int,
        buttonHeight: Int
    ): Point {
        normalizedX = DEFAULT_X_RATIO
        normalizedY = DEFAULT_Y_RATIO
        Log.i(TAG, "resetPosition: Reset to default ($DEFAULT_X_RATIO, $DEFAULT_Y_RATIO)")
        return calculatePixelPosition(windowManager, buttonWidth, buttonHeight)
    }
}
