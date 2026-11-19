package com.instadown.assist.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.instadown.assist.engine.AutomationEngine
import com.instadown.assist.model.AutomationResult

class InstaAccessibilityService : AccessibilityService() {

    data class ObservedSlideInfo(
        val currentSlideIndex: Int, // 0-indexed
        val totalSlides: Int,
        val timestamp: Long,
        val sourceBounds: android.graphics.Rect? = null
    )

    companion object {
        const val TAG = "InstaDownloaderPOC"
        var instance: InstaAccessibilityService? = null
            private set

        var lastObservedSlide: ObservedSlideInfo? = null

        val isRunning: Boolean
            get() = instance != null

        fun isServiceEnabled(context: android.content.Context): Boolean {
            if (instance != null) return true
            val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val expectedPackage = context.packageName
            val expectedClass = InstaAccessibilityService::class.java.name
            return enabledServices.any {
                val serviceInfo = it.resolveInfo?.serviceInfo
                serviceInfo != null &&
                serviceInfo.packageName == expectedPackage &&
                serviceInfo.name == expectedClass
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var isInstagramForeground: Boolean = false
        private set

    private var automationEngine: AutomationEngine? = null

    val isEngineRunning: Boolean
        get() = automationEngine?.isRunning() == true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityBinderWatchdog.onBinderConnected(this)
        automationEngine = AutomationEngine(
            service = this,
            getRootsProvider = { getCandidateRootNodes() },
            gestureDispatcher = { gesture, callback, handler ->
                dispatchGesture(gesture, callback, handler)
            },
            globalActionBackDispatcher = {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        )
        Log.i(TAG, "InstaAccessibilityService connected. AutomationEngine initialized.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityBinderWatchdog.onBinderDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        AccessibilityBinderWatchdog.onBinderDisconnected(this)
        if (instance === this) {
            instance = null
        }
        automationEngine = null
        Log.i(TAG, "InstaAccessibilityService destroyed.")
    }

    var lastInteractionNode: AccessibilityNodeInfo? = null
        private set
    var lastInteractionTimestamp: Long = 0L
        private set

    private val slideRegex = Regex("""\b(?:photo|video|slide|item)?\s*(\d+)\s*(?:of|\/)\s*(\d+)\b""", RegexOption.IGNORE_CASE)

    fun extractSlideInfoFromNode(node: AccessibilityNodeInfo?): ObservedSlideInfo? {
        if (node == null) return null
        try {
            // Walk up to find the enclosing post container
            var curr: AccessibilityNodeInfo? = node
            var container: AccessibilityNodeInfo? = null
            var steps = 0
            while (curr != null && steps < 7) {
                val b = android.graphics.Rect()
                curr.getBoundsInScreen(b)
                if (b.width() > 600 && b.height() > 400) {
                    container = curr
                    break
                }
                curr = curr.parent
                steps++
            }

            val targetRoot = container ?: node
            val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
            queue.add(targetRoot)
            var count = 0
            while (!queue.isEmpty() && count < 80) {
                val child: AccessibilityNodeInfo = queue.poll() ?: break
                count++
                val desc = (child.contentDescription ?: "").toString()
                val text = (child.text ?: "").toString()
                for (str in listOf(desc, text)) {
                    if (str.isNotBlank()) {
                        val m = slideRegex.find(str)
                        if (m != null) {
                            val cur = m.groupValues[1].toIntOrNull()
                            val total = m.groupValues[2].toIntOrNull()
                            if (cur != null && total != null && cur in 1..total && total > 1) {
                                val bounds = android.graphics.Rect()
                                child.getBoundsInScreen(bounds)
                                return ObservedSlideInfo(
                                    currentSlideIndex = cur - 1,
                                    totalSlides = total,
                                    timestamp = System.currentTimeMillis(),
                                    sourceBounds = bounds
                                )
                            }
                        }
                    }
                }
                val childCount = child.childCount
                for (i in 0 until childCount) {
                    child.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed extracting slide info from node: ${e.message}")
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == "com.instagram.android") {
            isInstagramForeground = true
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_SELECTED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    try {
                        event.source?.let {
                            lastInteractionNode = it
                            lastInteractionTimestamp = System.currentTimeMillis()
                            extractSlideInfoFromNode(it)?.let { slide ->
                                lastObservedSlide = slide
                                Log.i(TAG, "Observed carousel slide from event: ${slide.currentSlideIndex + 1}/${slide.totalSlides}")
                            }
                        }
                    } catch (ignored: Exception) {}
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "InstaAccessibilityService interrupted.")
    }

    /**
     * Executes the automated Instagram Copy Link sequence using the adaptive AutomationEngine.
     */
    fun startCopyLinkAutomation(
        postType: String,
        triggerBounds: android.graphics.Rect? = null,
        triggerPoint: android.graphics.PointF? = null,
        onRequestFocus: ((Boolean) -> Unit)? = null,
        onStatusUpdate: ((String) -> Unit)? = null,
        onComplete: ((AutomationResult) -> Unit)? = null
    ) {
        val engine = automationEngine ?: AutomationEngine(
            service = this,
            getRootsProvider = { getCandidateRootNodes() },
            gestureDispatcher = { gesture, callback, handler ->
                dispatchGesture(gesture, callback, handler)
            },
            globalActionBackDispatcher = {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        ).also { automationEngine = it }

        val recentNode = if (System.currentTimeMillis() - lastInteractionTimestamp < 5000) lastInteractionNode else null

        engine.startAutomation(
            postType = postType,
            triggerBounds = triggerBounds,
            triggerPoint = triggerPoint,
            recentInteractionNode = recentNode,
            onRequestFocus = onRequestFocus,
            onStatusUpdate = onStatusUpdate,
            onComplete = onComplete
        )
    }

    /**
     * Retrieves all candidate root nodes across all interactive windows.
     * Prioritizes the Instagram window if multiple windows exist.
     */
    fun getCandidateRootNodes(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()

        // 1. Primary: rootInActiveWindow
        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting rootInActiveWindow: ${e.message}")
        }

        // 2. All interactive windows
        try {
            val allWindows = windows
            for (window in allWindows) {
                try {
                    val windowRoot = window.root
                    if (windowRoot != null && !roots.any { it == windowRoot }) {
                        val pkg = windowRoot.packageName?.toString() ?: ""
                        if (pkg == "com.instagram.android") {
                            roots.add(0, windowRoot) // Priority to Instagram
                        } else {
                            roots.add(windowRoot)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error accessing window root: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying windows: ${e.message}")
        }
        return roots
    }
}
