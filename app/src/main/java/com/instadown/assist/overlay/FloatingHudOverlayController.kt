package com.instadown.assist.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.instadown.assist.ui.MainActivity

/**
 * Manages the top Dynamic Island-Style Floating Progress HUD Pill overlay.
 * Uses zero-focus-theft WindowManager flags so Instagram or other apps
 * remain completely interactive while progress is seamlessly rendered.
 * Driven by HudWatchdogDriver for mathematically bounded lifetime.
 */
class FloatingHudOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "FloatingHudController"
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = InstaSavePreferences(context)

    private var pillView: FloatingHudPillView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isViewAttached = false
    private var watchdogDriver: HudWatchdogDriver? = null

    fun start() {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot start FloatingHudOverlay: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        if (!prefs.isFloatingHudEnabled) {
            Log.i(TAG, "Floating HUD Pill is disabled in preferences")
            return
        }

        ensureViewCreated()

        watchdogDriver?.stop()
        watchdogDriver = HudWatchdogDriver(
            onRenderVisible = { state ->
                ensureViewCreated()
                pillView?.bindResilientState(state)
            },
            onAnimateOutAndDetach = {
                pillView?.bindResilientState(ResilientHudState.Hidden())
            },
            onForceDetachImmediately = {
                detachViewImmediately()
            }
        ).also { it.start() }
    }

    private fun ensureViewCreated() {
        if (pillView != null && isViewAttached) return

        val view = FloatingHudPillView(context).apply {
            setOnClickListener {
                val currentState = ResilientHudBus.state.value
                if (currentState is ResilientHudState.Success) {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(MainActivity.EXTRA_TARGET_TAB, com.instadown.assist.R.id.nav_gallery)
                    }
                    context.startActivity(intent)
                }
                // Tapping the pill at any time dismisses it immediately
                ResilientHudBus.dismiss(force = true)
                FloatingHudBus.reset()
            }
        }
        pillView = view

        val statusBarOffset = calculateCutoutOrStatusBarOffset()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarOffset

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            // Android 12+ (API 31+) Glassmorphic background blur
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    if (windowManager.isCrossWindowBlurEnabled) {
                        flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        blurBehindRadius = 24
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Cross window blur not supported: ${e.message}")
                }
            }
        }
        windowParams = params

        try {
            windowManager.addView(view, params)
            isViewAttached = true
            Log.i(TAG, "FloatingHudPillView attached to WindowManager at y=$statusBarOffset")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach FloatingHudPillView: ${e.message}", e)
        }
    }

    private fun calculateCutoutOrStatusBarOffset(): Int {
        val density = context.resources.displayMetrics.density
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resId > 0) {
            context.resources.getDimensionPixelSize(resId)
        } else {
            (28 * density).toInt()
        }
        // Offset 8dp below status bar / camera notch
        return statusBarHeight + (8 * density).toInt()
    }

    private fun detachViewImmediately() {
        pillView?.let { view ->
            if (isViewAttached) {
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    Log.w(TAG, "Error removing FloatingHudPillView: ${e.message}")
                }
                isViewAttached = false
            }
        }
        pillView = null
    }

    fun stop() {
        watchdogDriver?.stop()
        watchdogDriver = null
        detachViewImmediately()
    }
}
