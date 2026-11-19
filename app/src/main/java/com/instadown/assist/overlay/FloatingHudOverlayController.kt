package com.instadown.assist.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.instadown.assist.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages the top Dynamic Island-Style Floating Progress HUD Pill overlay.
 * Uses zero-focus-theft WindowManager flags so Instagram or other apps
 * remain completely interactive while progress is seamlessly rendered.
 */
class FloatingHudOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "FloatingHudController"
    }

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = InstaSavePreferences(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var pillView: FloatingHudPillView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isViewAttached = false
    private var dismissRunnable: Runnable? = null
    private var observeJob: Job? = null

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

        observeJob?.cancel()
        observeJob = scope.launch {
            FloatingHudBus.state.collect { state ->
                handleState(state)
            }
        }
    }

    private fun ensureViewCreated() {
        if (pillView != null && isViewAttached) return

        val view = FloatingHudPillView(context).apply {
            setOnClickListener {
                val currentState = FloatingHudBus.state.value
                if (currentState is FloatingHudState.Success) {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    context.startActivity(intent)
                }
                // Tapping the pill at any time dismisses it immediately
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

    private fun handleState(state: FloatingHudState) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }

        if (state is FloatingHudState.Idle) {
            pillView?.bindState(state)
            return
        }

        // Make sure view is attached before updating
        ensureViewCreated()
        pillView?.bindState(state)

        when (state) {
            is FloatingHudState.LinkDetected -> {
                // Pulse state: auto-dismiss after 3.5s if resolving does not start
                dismissRunnable = Runnable { FloatingHudBus.reset() }
                mainHandler.postDelayed(dismissRunnable!!, 3500)
            }
            is FloatingHudState.Resolving -> {
                // Safeguard: auto-timeout resolution state after 14s
                dismissRunnable = Runnable {
                    FloatingHudBus.emit(FloatingHudState.Error("", "Resolution timed out"))
                }
                mainHandler.postDelayed(dismissRunnable!!, 14000)
            }
            is FloatingHudState.Downloading -> {
                // Safeguard: auto-reset if download hangs longer than 45s
                dismissRunnable = Runnable { FloatingHudBus.reset() }
                mainHandler.postDelayed(dismissRunnable!!, 45000)
            }
            is FloatingHudState.Success -> {
                dismissRunnable = Runnable { FloatingHudBus.reset() }
                mainHandler.postDelayed(dismissRunnable!!, 2500)
            }
            is FloatingHudState.Error -> {
                dismissRunnable = Runnable { FloatingHudBus.reset() }
                mainHandler.postDelayed(dismissRunnable!!, 3200)
            }
            is FloatingHudState.Idle -> {
                // Already handled above
            }
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

    fun stop() {
        observeJob?.cancel()
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
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
        scope.cancel()
    }
}
