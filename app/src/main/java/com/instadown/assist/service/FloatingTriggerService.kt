package com.instadown.assist.service

import android.animation.ValueAnimator
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import com.instadown.assist.R
import com.instadown.assist.download.InstagramDownloadCoordinator
import com.instadown.assist.download.model.DownloadErrorCode
import com.instadown.assist.download.model.DownloadResult
import com.instadown.assist.overlay.ButtonSize
import com.instadown.assist.overlay.FloatingPositionManager
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.overlay.ResilientHudBus
import com.instadown.assist.overlay.ResilientHudState
import com.instadown.assist.overlay.SpringFloatingDockController
import com.instadown.assist.util.DownloadNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingTriggerService : Service() {

    private var windowManager: WindowManager? = null
    private var containerView: FrameLayout? = null
    private var floatingButtonView: com.instadown.assist.ui.view.FloatingTriggerButtonView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var springDockController: SpringFloatingDockController? = null
    private var velocityTracker: VelocityTracker? = null
    private var floatingHudController: com.instadown.assist.overlay.FloatingHudOverlayController? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var positionManager: FloatingPositionManager
    private lateinit var preferences: InstaSavePreferences

    private var lastResult: DownloadResult? = null
    private var isTriggerActive = false
    private var idleResetRunnable: Runnable? = null

    private val clipboardCoordinator by lazy { com.instadown.assist.download.ClipboardDownloadCoordinator(this) }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isRunning) return@OnPrimaryClipChangedListener
        if (preferences.downloadMode != com.instadown.assist.overlay.DownloadMode.CLIPBOARD ||
            !preferences.isClipboardAutoStartEnabled
        ) return@OnPrimaryClipChangedListener

        // Temporarily acquire focus to legally read clipboard on Android 10-14+
        acquireFocusForClipboard()
        mainHandler.postDelayed({
            try {
                val clip = com.instadown.assist.util.ClipboardReader.readCurrentClip(this)
                if (clip.isValidInstagramUrl) {
                    val decision = clipboardCoordinator.evaluateProcessDecision(
                        cleanUrl = clip.cleanUrl,
                        clipTimestamp = clip.timestamp,
                        prefs = preferences
                    )
                    if (decision == com.instadown.assist.download.ClipboardDownloadCoordinator.ProcessDecision.ALLOW) {
                        clipboardCoordinator.startProcessing(clip.cleanUrl)
                        triggerHaptic()
                        Toast.makeText(this, "Link detected! Saving in background...", Toast.LENGTH_SHORT).show()
                        com.instadown.assist.InstaSaveApp.applicationScope.launch(Dispatchers.IO) {
                            val dlResult = coordinator.executeDirectDownload(
                                url = clip.cleanUrl,
                                recordInDb = true
                            )
                            if (dlResult.success || dlResult.completedCount > 0) {
                                clipboardCoordinator.onProcessingSuccess(clip.cleanUrl, System.currentTimeMillis(), preferences)
                            } else {
                                clipboardCoordinator.onProcessingFailed(clip.cleanUrl)
                            }
                        }
                    }
                }
            } finally {
                releaseFocusForClipboard()
            }
        }, 100)
    }

    companion object {
        private const val TAG = "InstaSaveOverlay"
        var isRunning = false
            private set

        const val ACTION_STOP_SERVICE = "com.instadown.assist.ACTION_STOP_SERVICE"
        const val ACTION_RESET_POSITION = "com.instadown.assist.RESET_POSITION"
        const val ACTION_UPDATE_PREFS = "com.instadown.assist.UPDATE_PREFS"

        fun start(context: Context) {
            val intent = Intent(context, FloatingTriggerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingTriggerService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Fallback if foreground start not allowed from background
                context.stopService(intent)
            }
        }

        fun resetPosition(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, FloatingTriggerService::class.java).apply {
                action = ACTION_RESET_POSITION
            }
            context.startService(intent)
        }

        fun updatePreferences(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, FloatingTriggerService::class.java).apply {
                action = ACTION_UPDATE_PREFS
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        preferences = InstaSavePreferences(this)
        preferences.isServiceRunningDesired = true
        isRunning = true
        positionManager = FloatingPositionManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.addPrimaryClipChangedListener(clipListener)
            Log.i("OPPO_CLIP_TEST", "clipListener registered in FloatingTriggerService")
        } catch (e: Exception) {
            Log.w("OPPO_CLIP_TEST", "Failed to register clipListener: ${e.message}")
        }

        startForegroundNotification()
        createFloatingControl()

        if (preferences.isFloatingHudEnabled) {
            floatingHudController = com.instadown.assist.overlay.FloatingHudOverlayController(this).apply {
                start()
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: Task dismissed from recents on OPPO K13 Turbo 5G (ColorOS V16.0.0). Keeping FloatingTriggerService alive.")
        floatingHudController?.stop()
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && !preferences.isServiceRunningDesired) {
            Log.i(TAG, "onStartCommand: START_STICKY restart but serviceRunningDesired is false, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }

        // Ensure persistent notification is active
        startForegroundNotification()

        intent?.let {
            when (it.action) {
                ACTION_STOP_SERVICE -> {
                    handleStopService()
                    return START_NOT_STICKY
                }
                ACTION_RESET_POSITION -> handleResetPosition()
                ACTION_UPDATE_PREFS -> handleUpdatePreferences()
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = DownloadNotificationManager.createServiceRunningNotification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    DownloadNotificationManager.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(DownloadNotificationManager.SERVICE_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service notification: ${e.message}", e)
            stopSelf()
        }
    }

    private fun handleStopService() {
        Log.i(TAG, "handleStopService: stopping FloatingTriggerService")
        preferences.isButtonEnabled = false
        preferences.isServiceRunningDesired = false
        isRunning = false
        idleResetRunnable?.let { mainHandler.removeCallbacks(it) }
        springDockController?.cancelActiveSprings()
        springDockController = null
        velocityTracker?.recycle()
        velocityTracker = null
        floatingHudController?.stop()
        floatingHudController = null

        containerView?.let {
            try { windowManager?.removeView(it) } catch (ignored: Exception) {}
            containerView = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    private fun getButtonSizePx(): Int {
        val dp = preferences.buttonSize.dpValue
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun createFloatingControl() {
        if (preferences.downloadMode == com.instadown.assist.overlay.DownloadMode.CLIPBOARD) {
            Log.i(TAG, "createFloatingControl: CLIPBOARD mode active, suppressing floating overlay button")
            return
        }
        val wm = windowManager ?: return
        val sizePx = getButtonSizePx()
        val initialPoint = positionManager.calculatePixelPosition(wm, sizePx, sizePx)

        // Strict fixed width and height (NO WRAP_CONTENT) to guarantee 0px coordinate drift
        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialPoint.x
            y = initialPoint.y
        }
        windowParams = params

        val root = FrameLayout(this).apply {
            alpha = preferences.buttonOpacity
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        }

        // Custom Canvas-rendered floating trigger button (strictly invariant geometry, real progress ring)
        val buttonView = com.instadown.assist.ui.view.FloatingTriggerButtonView(this).apply {
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            elevation = 8f * resources.displayMetrics.density
        }
        root.addView(buttonView)

        floatingButtonView = buttonView
        containerView = root
        springDockController = SpringFloatingDockController(wm, root, params)

        setupTouchListener(root, params)

        try {
            wm.addView(root, params)
            Log.i(TAG, "createFloatingControl: Added circular floating button at (${params.x}, ${params.y}) size=${sizePx}px")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view to WindowManager", e)
        }
    }

    private fun setupTouchListener(root: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var isDrag = false

        root.setOnTouchListener { _, event ->
            velocityTracker?.addMovement(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    springDockController?.cancelActiveSprings()
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    floatingButtonView?.setPressedScale(true)
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (Math.abs(dx) > 15 || Math.abs(dy) > 15) {
                        isDrag = true
                        floatingButtonView?.setPressedScale(false)
                    }
                    if (isDrag) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager?.updateViewLayout(root, params)
                        } catch (ignored: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    floatingButtonView?.setPressedScale(false)
                    if (!isDrag) {
                        handleButtonTap(event.rawX, event.rawY)
                        velocityTracker?.recycle()
                        velocityTracker = null
                    } else {
                        handleDragCompleted(params, velocityTracker)
                        // Note: velocityTracker is recycled after computing velocity in handleDragCompleted
                        velocityTracker = null
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    floatingButtonView?.setPressedScale(false)
                    velocityTracker?.recycle()
                    velocityTracker = null
                    true
                }
                else -> false
            }
        }
    }

    private fun handleButtonTap(tapX: Float, tapY: Float) {
        if (isTriggerActive) {
            Toast.makeText(this, "Download already in progress...", Toast.LENGTH_SHORT).show()
            return
        }
        triggerHaptic()
        initiateDownloadFlow(tapX, tapY)
    }

    private fun handleDragCompleted(params: WindowManager.LayoutParams, tracker: VelocityTracker?) {
        val wm = windowManager ?: return
        val root = containerView ?: return
        val sizePx = getButtonSizePx()

        if (preferences.isEdgeDockingEnabled) {
            val usable = positionManager.getUsableScreenArea(wm)
            val edgeMarginPx = (16 * resources.displayMetrics.density).toInt().coerceAtLeast(16)
            springDockController?.snapToEdgeWithSpring(
                screenWidthPx = usable.left + usable.width,
                safeTopPx = usable.top,
                safeBottomPx = usable.bottom,
                edgeMarginPx = edgeMarginPx,
                velocityTracker = tracker
            ) { finalX, finalY ->
                positionManager.savePosition(wm, finalX, finalY, sizePx, sizePx)
            }
            tracker?.recycle()
        } else {
            tracker?.recycle()
            val savedPoint = positionManager.savePosition(wm, params.x, params.y, sizePx, sizePx)
            params.x = savedPoint.x
            params.y = savedPoint.y
            try {
                wm.updateViewLayout(root, params)
            } catch (ignored: Exception) {}
        }
    }

    private val coordinator by lazy {
        InstagramDownloadCoordinator(this)
    }

    private fun initiateDownloadFlow(tapX: Float, tapY: Float) {
        val a11y = InstaAccessibilityService.instance
        if (a11y == null) {
            // High-resilience fallback: Check if user already has an Instagram URL in clipboard
            acquireFocusForClipboard()
            val clip = com.instadown.assist.util.ClipboardReader.readCurrentClip(this)
            releaseFocusForClipboard()
            if (clip.isValidInstagramUrl && clip.cleanUrl.isNotBlank()) {
                triggerHaptic()
                isTriggerActive = true
                floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.RESOLVING)
                val opId = ResilientHudBus.startOperation()
                ResilientHudBus.emit(ResilientHudState.LinkDetected(opId, "Instagram Link"))
                com.instadown.assist.InstaSaveApp.applicationScope.launch {
                    val result = coordinator.executeDirectDownload(url = clip.cleanUrl, operationId = opId)
                    withContext(Dispatchers.Main) {
                        isTriggerActive = false
                        if (result.success) {
                            updateStateVisual(StateVisual.SUCCESS)
                        } else {
                            updateStateVisual(StateVisual.ERROR)
                            Toast.makeText(this@FloatingTriggerService, "Download failed: ${result.error}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                return
            }

            Toast.makeText(
                this,
                "Please enable InstaSave Accessibility Service or copy an Instagram link",
                Toast.LENGTH_LONG
            ).show()
            updateStateVisual(StateVisual.ERROR)
            return
        }

        if (a11y.isEngineRunning) {
            Toast.makeText(this, "Automation active...", Toast.LENGTH_SHORT).show()
            return
        }

        isTriggerActive = true
        floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.RESOLVING)

        val loc = IntArray(2)
        containerView?.getLocationOnScreen(loc)
        val sizePx = getButtonSizePx()
        val buttonBounds = Rect(loc[0], loc[1], loc[0] + sizePx, loc[1] + sizePx)

        coordinator.startOneTapDownload(
            postTypeCategory = "Feed post",
            triggerBounds = buttonBounds,
            triggerPoint = PointF(tapX, tapY),
            onRequestFocus = { acquire ->
                mainHandler.post {
                    if (acquire) acquireFocusForClipboard() else releaseFocusForClipboard()
                }
            },
            onStatusUpdate = {
                mainHandler.post {
                    if (isTriggerActive) {
                        floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.RESOLVING)
                    }
                }
            },
            onProgress = { progress ->
                mainHandler.post {
                    if (isTriggerActive) {
                        val label = if (progress.totalItems > 1) {
                            "${progress.currentItemIndex + 1}/${progress.totalItems}"
                        } else {
                            "${progress.overallPercent}%"
                        }
                        floatingButtonView?.updateProgress(progress.overallPercent, label)
                    }
                }
            },
            onComplete = { result ->
                mainHandler.post {
                    isTriggerActive = false
                    lastResult = result
                    releaseFocusForClipboard()
                    idleResetRunnable?.let { mainHandler.removeCallbacks(it) }

                    if (result.transactionId == "ASK_PROMPT_POSTED") {
                        floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.IDLE)
                        return@post
                    }

                    if (result.success) {
                        triggerHaptic()
                        floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.SUCCESS)
                        recordTransactionInDb(result)

                        DownloadNotificationManager.showCompletionNotification(
                            applicationContext,
                            "InstaSave",
                            if (result.items.size > 1) "${result.completedCount} items saved to gallery" else "Media saved to gallery"
                        )
                    } else if (result.isPartial || (result.completedCount > 0 && result.failedCount > 0)) {
                        triggerHaptic()
                        floatingButtonView?.setState(
                            com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.PARTIAL_SUCCESS,
                            carouselLabel = "${result.completedCount}/${result.items.size}"
                        )
                        recordTransactionInDb(result)
                        Toast.makeText(this, "Saved ${result.completedCount} of ${result.items.size} items", Toast.LENGTH_SHORT).show()
                    } else if (result.skippedCount > 0 && result.completedCount == 0) {
                        floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.DUPLICATE)
                        recordTransactionInDb(result)
                        Toast.makeText(this, "Already saved to gallery", Toast.LENGTH_SHORT).show()
                    } else {
                        floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.ERROR)
                        recordTransactionInDb(result)
                        val failMsg = when (result.errorCode) {
                            DownloadErrorCode.TARGET_SELECTION_FAILED,
                            DownloadErrorCode.IDENTITY_VERIFICATION_FAILED -> "Couldn't identify post"
                            DownloadErrorCode.RESOLUTION_FAILED,
                            DownloadErrorCode.MEDIA_URL_EXPIRED -> "Couldn't resolve media"
                            else -> "Couldn't download media"
                        }
                        com.instadown.assist.overlay.FloatingHudBus.emit(
                            com.instadown.assist.overlay.FloatingHudState.Error(
                                shortcode = result.shortcode ?: "",
                                message = failMsg
                            )
                        )
                        Toast.makeText(this, failMsg, Toast.LENGTH_SHORT).show()
                    }

                    idleResetRunnable = Runnable {
                        floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.IDLE)
                    }
                    mainHandler.postDelayed(idleResetRunnable!!, 2200)
                }
            }
        )
    }

    private fun recordTransactionInDb(result: DownloadResult) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = com.instadown.assist.data.db.DownloadRepository.getInstance(applicationContext)
                repo.recordDownloadResult(result)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record transaction in database: ${e.message}")
            }
        }
    }

    private enum class StateVisual { IDLE, WORKING, SUCCESS, DUPLICATE, ERROR }

    private fun updateStateVisual(state: StateVisual) {
        idleResetRunnable?.let { mainHandler.removeCallbacks(it) }
        when (state) {
            StateVisual.IDLE -> floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.IDLE)
            StateVisual.WORKING -> floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.RESOLVING)
            StateVisual.SUCCESS -> {
                floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.SUCCESS)
                idleResetRunnable = Runnable { updateStateVisual(StateVisual.IDLE) }
                mainHandler.postDelayed(idleResetRunnable!!, 2000)
            }
            StateVisual.DUPLICATE -> {
                floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.DUPLICATE)
                idleResetRunnable = Runnable { updateStateVisual(StateVisual.IDLE) }
                mainHandler.postDelayed(idleResetRunnable!!, 2000)
            }
            StateVisual.ERROR -> {
                floatingButtonView?.setState(com.instadown.assist.ui.view.FloatingTriggerButtonView.VisualState.ERROR)
                idleResetRunnable = Runnable { updateStateVisual(StateVisual.IDLE) }
                mainHandler.postDelayed(idleResetRunnable!!, 2000)
            }
        }
    }

    private fun handleResetPosition() {
        val wm = windowManager ?: return
        val root = containerView ?: return
        val params = windowParams ?: return
        val sizePx = getButtonSizePx()

        val resetPoint = positionManager.resetPosition(wm, sizePx, sizePx)
        params.x = resetPoint.x
        params.y = resetPoint.y

        try {
            wm.updateViewLayout(root, params)
            Toast.makeText(this, "Button position reset", Toast.LENGTH_SHORT).show()
        } catch (ignored: Exception) {}
    }

    private fun handleUpdatePreferences() {
        if (preferences.downloadMode == com.instadown.assist.overlay.DownloadMode.CLIPBOARD) {
            containerView?.let {
                try { windowManager?.removeView(it) } catch (ignored: Exception) {}
                containerView = null
            }
            return
        }

        if (containerView == null) {
            createFloatingControl()
            return
        }

        val root = containerView ?: return
        val params = windowParams ?: return
        val wm = windowManager ?: return

        root.alpha = preferences.buttonOpacity
        val newSizePx = getButtonSizePx()

        // Update layout parameters for the new size
        params.width = newSizePx
        params.height = newSizePx
        root.layoutParams = FrameLayout.LayoutParams(newSizePx, newSizePx)
        floatingButtonView?.layoutParams = FrameLayout.LayoutParams(newSizePx, newSizePx)

        val updatedPoint = positionManager.calculatePixelPosition(wm, newSizePx, newSizePx)
        params.x = updatedPoint.x
        params.y = updatedPoint.y

        try {
            wm.updateViewLayout(root, params)
        } catch (ignored: Exception) {}

        // Reconcile Floating HUD Pill state
        floatingHudController?.stop()
        if (preferences.isFloatingHudEnabled) {
            floatingHudController = com.instadown.assist.overlay.FloatingHudOverlayController(this).apply {
                start()
            }
        }
    }

    private fun triggerHaptic() {
        if (!preferences.isHapticFeedbackEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(25)
            }
        } catch (ignored: Exception) {}
    }

    private fun acquireFocusForClipboard() {
        val params = windowParams ?: return
        val view = containerView ?: return
        try {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            windowManager?.updateViewLayout(view, params)
            view.requestFocus()
        } catch (ignored: Exception) {}
    }

    private fun releaseFocusForClipboard() {
        val params = windowParams ?: return
        val view = containerView ?: return
        try {
            view.clearFocus()
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(view, params)
        } catch (ignored: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        idleResetRunnable?.let { mainHandler.removeCallbacks(it) }
        floatingHudController?.stop()
        floatingHudController = null
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.removePrimaryClipChangedListener(clipListener)
        } catch (ignored: Exception) {}
        containerView?.let {
            try { windowManager?.removeView(it) } catch (ignored: Exception) {}
            containerView = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
