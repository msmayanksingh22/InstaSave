package com.instadown.assist.resolver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.instadown.assist.resolver.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Ultra-fast headless web bridge for zero-login Instagram resolution.
 * Features persistent background WebView pre-warming, Desktop User-Agent anti-ad spoofing,
 * automatic banner dismissal, and instant DOM/Vue/API interceptors.
 */
class HeadlessWebBridgeResolver(
    private val context: Context
) : InstagramResolver {

    companion object {
        private const val TAG = "HeadlessWebBridge"
        private const val EXTRACTOR_URL = "https://fastdl.app/"
        private const val TIMEOUT_MS = 9000L
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        @Volatile
        private var prewarmedWebView: WebView? = null
        @Volatile
        private var isPrewarmReady: Boolean = false
        @Volatile
        private var isPrewarmBusy: Boolean = false

        private val bridgeMutex = Mutex()
        private val activeCallbacks = ConcurrentHashMap<String, (String?, String?) -> Unit>()
        @Volatile
        private var currentActiveRequestId: String? = null

        private fun configureWebView(webView: WebView) {
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = false // Prevent heavy ad/image downloads
            settings.userAgentString = DESKTOP_UA // Desktop UA prevents "Download our Android app" banners!
            try {
                webView.resumeTimers()
                webView.onResume()
            } catch (_: Exception) {}
        }

        fun preWarm(context: Context) {
            Handler(Looper.getMainLooper()).post {
                if (prewarmedWebView != null && isPrewarmReady) {
                    return@post
                }
                try {
                    Log.i(TAG, "Pre-warming background headless WebView with $EXTRACTOR_URL...")
                    val wv = WebView(context.applicationContext)
                    prewarmedWebView = wv
                    isPrewarmReady = false
                    isPrewarmBusy = false
                    configureWebView(wv)

                    class StaticBridge {
                        @JavascriptInterface
                        fun onMediaFound(jsonStr: String) {
                            Handler(Looper.getMainLooper()).post {
                                val reqId = currentActiveRequestId
                                if (reqId != null) {
                                    activeCallbacks.remove(reqId)?.invoke(jsonStr, null)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onMediaFoundWithId(requestId: String, jsonStr: String) {
                            Handler(Looper.getMainLooper()).post {
                                activeCallbacks.remove(requestId)?.invoke(jsonStr, null)
                            }
                        }

                        @JavascriptInterface
                        fun onError(errorMsg: String) {
                            Handler(Looper.getMainLooper()).post {
                                val reqId = currentActiveRequestId
                                if (reqId != null) {
                                    activeCallbacks.remove(reqId)?.invoke(null, errorMsg)
                                }
                            }
                        }

                        @JavascriptInterface
                        fun onErrorWithId(requestId: String, errorMsg: String) {
                            Handler(Looper.getMainLooper()).post {
                                activeCallbacks.remove(requestId)?.invoke(null, errorMsg)
                            }
                        }
                    }

                    wv.addJavascriptInterface(StaticBridge(), "AndroidBridge")
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            if (msg.startsWith("INSTASAVE_FOUND:")) {
                                val raw = msg.removePrefix("INSTASAVE_FOUND:")
                                val (reqId, json) = if (raw.contains("::")) {
                                    val parts = raw.split("::", limit = 2)
                                    parts[0] to parts[1]
                                } else {
                                    (currentActiveRequestId ?: "") to raw
                                }
                                Handler(Looper.getMainLooper()).post {
                                    activeCallbacks.remove(reqId)?.invoke(json, null)
                                }
                                return true
                            }
                            if (msg.startsWith("INSTASAVE_ERROR:")) {
                                val raw = msg.removePrefix("INSTASAVE_ERROR:")
                                val (reqId, err) = if (raw.contains("::")) {
                                    val parts = raw.split("::", limit = 2)
                                    parts[0] to parts[1]
                                } else {
                                    (currentActiveRequestId ?: "") to raw
                                }
                                Handler(Looper.getMainLooper()).post {
                                    activeCallbacks.remove(reqId)?.invoke(null, err)
                                }
                                return true
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url?.contains("fastdl.app") == true) {
                                isPrewarmReady = true
                                Log.i(TAG, "Pre-warmed headless WebView is ready in memory!")
                            }
                        }
                    }

                    wv.loadUrl(EXTRACTOR_URL)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to pre-warm WebView: ${e.message}")
                    prewarmedWebView = null
                    isPrewarmReady = false
                }
            }
        }
    }

    fun preWarm() {
        preWarm(context)
    }

    override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult = bridgeMutex.withLock {
        val startTime = System.currentTimeMillis()
        val normalized = UrlNormalizer.normalize(url)
            ?: return@withLock ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = null,
                shortcode = null,
                sourcePathType = null,
                postType = null,
                items = emptyList(),
                methodUsed = "HeadlessWebBridgeResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                error = ResolveError(
                    code = ResolveErrorCode.UNSUPPORTED_URL,
                    message = "Could not parse Instagram shortcode from URL: '$url'"
                )
            )

        val targetUrl = normalized.originalUrl

        return@withLock try {
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                resolveInternal(normalized, targetUrl, startTime)
            }

            if (result != null) {
                result
            } else {
                ResolveResult(
                    success = false,
                    originalInputUrl = url,
                    canonicalUrl = normalized.canonicalUrl,
                    shortcode = normalized.shortcode,
                    sourcePathType = normalized.sourcePathType,
                    postType = null,
                    items = emptyList(),
                    methodUsed = "HeadlessWebBridgeResolver",
                    totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                    error = ResolveError(
                        code = ResolveErrorCode.NETWORK_ERROR,
                        message = "Headless web bridge timed out waiting for media extraction"
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Headless web bridge exception", e)
            ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "HeadlessWebBridgeResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                error = ResolveError(
                    code = ResolveErrorCode.PARSE_ERROR,
                    message = "Bridge error: ${e.message}"
                )
            )
        }
    }

    private suspend fun resolveInternal(
        normalized: NormalizedUrl,
        targetUrl: String,
        startTime: Long
    ): ResolveResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val requestId = "req_${System.currentTimeMillis()}_${(1000..9999).random()}"
            currentActiveRequestId = requestId
            var onDemandWebView: WebView? = null
            var isFinished = false
            val usingPrewarmed = prewarmedWebView != null && isPrewarmReady && !isPrewarmBusy

            fun finishWithSuccess(items: List<ResolvedMedia>) {
                if (isFinished) return
                isFinished = true
                activeCallbacks.remove(requestId)
                if (currentActiveRequestId == requestId) {
                    currentActiveRequestId = null
                }
                com.instadown.assist.util.AppLogger.i(TAG, "[$requestId] Headless web bridge finished with success: ${items.size} items")

                if (usingPrewarmed) {
                    isPrewarmBusy = false
                    // Trigger a light reload in background to keep pre-warm ready for the next request
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            prewarmedWebView?.loadUrl(EXTRACTOR_URL)
                        } catch (_: Exception) {}
                    }, 500)
                } else {
                    try {
                        onDemandWebView?.stopLoading()
                        onDemandWebView?.destroy()
                        onDemandWebView = null
                    } catch (_: Exception) {}
                }

                // If pre-warmed instance was consumed or wasn't ready, prime a new one
                preWarm(context)

                if (items.isNotEmpty()) {
                    val postType = when {
                        items.size > 1 -> PostType.CAROUSEL
                        items.first().mediaType == MediaType.VIDEO -> PostType.REEL
                        else -> PostType.PHOTO
                    }

                    continuation.resume(
                        ResolveResult(
                            success = true,
                            originalInputUrl = normalized.originalUrl,
                            canonicalUrl = normalized.canonicalUrl,
                            shortcode = normalized.shortcode,
                            sourcePathType = normalized.sourcePathType,
                            postType = postType,
                            identityVerified = true,
                            identityVerificationMethod = "HEADLESS_WEB_BRIDGE",
                            items = items,
                            methodUsed = if (usingPrewarmed) "HeadlessWebBridgeResolver (Instant Prewarmed)" else "HeadlessWebBridgeResolver (On-Demand)",
                            totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                            stageA = StageAResult(
                                success = true,
                                postType = postType,
                                itemsCount = items.size,
                                shortcode = normalized.shortcode,
                                identityVerification = "HEADLESS_WEB_BRIDGE",
                                extractionTimeMs = System.currentTimeMillis() - startTime
                            ),
                            initialHost = "fastdl.app",
                            finalHost = "fastdl.app"
                        )
                    )
                } else {
                    continuation.resume(
                        ResolveResult(
                            success = false,
                            originalInputUrl = normalized.originalUrl,
                            canonicalUrl = normalized.canonicalUrl,
                            shortcode = normalized.shortcode,
                            sourcePathType = normalized.sourcePathType,
                            postType = null,
                            items = emptyList(),
                            methodUsed = "HeadlessWebBridgeResolver",
                            totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                            error = ResolveError(
                                code = ResolveErrorCode.PARSE_ERROR,
                                message = "No media links extracted from web bridge"
                            )
                        )
                    )
                }
            }

            fun finishWithError(errorMsg: String) {
                if (isFinished) return
                isFinished = true
                activeCallbacks.remove(requestId)
                if (currentActiveRequestId == requestId) {
                    currentActiveRequestId = null
                }
                com.instadown.assist.util.AppLogger.w(TAG, "[$requestId] Headless web bridge finished with error: $errorMsg")

                if (usingPrewarmed) {
                    isPrewarmBusy = false
                    prewarmedWebView?.loadUrl(EXTRACTOR_URL)
                } else {
                    try {
                        onDemandWebView?.stopLoading()
                        onDemandWebView?.destroy()
                        onDemandWebView = null
                    } catch (_: Exception) {}
                }
                preWarm(context)

                continuation.resume(
                    ResolveResult(
                        success = false,
                        originalInputUrl = normalized.originalUrl,
                        canonicalUrl = normalized.canonicalUrl,
                        shortcode = normalized.shortcode,
                        sourcePathType = normalized.sourcePathType,
                        postType = null,
                        items = emptyList(),
                        methodUsed = "HeadlessWebBridgeResolver",
                        totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                        error = ResolveError(
                            code = ResolveErrorCode.CONTENT_NOT_FOUND,
                            message = errorMsg
                        )
                    )
                )
            }

            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post {
                    activeCallbacks.remove(requestId)
                    if (currentActiveRequestId == requestId) {
                        currentActiveRequestId = null
                    }
                    if (usingPrewarmed) {
                        isPrewarmBusy = false
                    } else {
                        try {
                            onDemandWebView?.stopLoading()
                            onDemandWebView?.destroy()
                            onDemandWebView = null
                        } catch (_: Exception) {}
                    }
                }
            }

            // Bind the active callback for this request ID
            activeCallbacks[requestId] = { jsonStr, errStr ->
                if (errStr != null) {
                    finishWithError(errStr)
                } else if (jsonStr != null) {
                    try {
                        val items = parseMediaItems(jsonStr, normalized.shortcode)
                        finishWithSuccess(items)
                    } catch (e: Exception) {
                        finishWithError("JSON parse error: ${e.message}")
                    }
                }
            }

            val injectionJs = buildInjectionScript(targetUrl, requestId)

            if (usingPrewarmed && prewarmedWebView != null) {
                isPrewarmBusy = true
                Log.i(TAG, "Executing INSTANT resolution using pre-warmed WebView for [$requestId]: $targetUrl")
                try {
                    prewarmedWebView?.resumeTimers()
                    prewarmedWebView?.onResume()
                } catch (_: Exception) {}
                prewarmedWebView?.evaluateJavascript(injectionJs, null)
            } else {
                Log.i(TAG, "Pre-warmed WebView not ready. Launching on-demand WebView for [$requestId]: $targetUrl")
                try {
                    val wv = WebView(context.applicationContext)
                    onDemandWebView = wv
                    configureWebView(wv)

                    class OnDemandBridge {
                        @JavascriptInterface
                        fun onMediaFound(jsonStr: String) {
                            Handler(Looper.getMainLooper()).post {
                                activeCallbacks.remove(requestId)?.invoke(jsonStr, null)
                            }
                        }

                        @JavascriptInterface
                        fun onMediaFoundWithId(reqId: String, jsonStr: String) {
                            Handler(Looper.getMainLooper()).post {
                                activeCallbacks.remove(reqId)?.invoke(jsonStr, null)
                            }
                        }

                        @JavascriptInterface
                        fun onError(errorMsg: String) {
                            Handler(Looper.getMainLooper()).post {
                                activeCallbacks.remove(requestId)?.invoke(null, errorMsg)
                            }
                        }

                        @JavascriptInterface
                        fun onErrorWithId(reqId: String, errorMsg: String) {
                            Handler(Looper.getMainLooper()).post {
                                activeCallbacks.remove(reqId)?.invoke(null, errorMsg)
                            }
                        }
                    }

                    wv.addJavascriptInterface(OnDemandBridge(), "AndroidBridge")
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            val msg = consoleMessage?.message() ?: return false
                            if (msg.startsWith("INSTASAVE_FOUND:")) {
                                val raw = msg.removePrefix("INSTASAVE_FOUND:")
                                val (reqId, json) = if (raw.contains("::")) {
                                    val parts = raw.split("::", limit = 2)
                                    parts[0] to parts[1]
                                } else {
                                    requestId to raw
                                }
                                Handler(Looper.getMainLooper()).post {
                                    activeCallbacks.remove(reqId)?.invoke(json, null)
                                }
                                return true
                            }
                            if (msg.startsWith("INSTASAVE_ERROR:")) {
                                val raw = msg.removePrefix("INSTASAVE_ERROR:")
                                val (reqId, err) = if (raw.contains("::")) {
                                    val parts = raw.split("::", limit = 2)
                                    parts[0] to parts[1]
                                } else {
                                    requestId to raw
                                }
                                Handler(Looper.getMainLooper()).post {
                                    activeCallbacks.remove(reqId)?.invoke(null, err)
                                }
                                return true
                            }
                            return super.onConsoleMessage(consoleMessage)
                        }
                    }

                    var hasInjected = false
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            if (!hasInjected && pageUrl?.contains("fastdl.app") == true) {
                                hasInjected = true
                                Log.i(TAG, "On-demand page loaded. Evaluating automation script...")
                                view?.evaluateJavascript(injectionJs, null)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request?.isForMainFrame == true) {
                                Log.w(TAG, "On-demand frame error: ${error?.description}")
                            }
                        }
                    }

                    wv.loadUrl(EXTRACTOR_URL)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to instantiate on-demand WebView", e)
                    finishWithError("Failed to initialize WebView: ${e.message}")
                }
            }
        }
    }

    internal fun parseMediaItems(jsonStr: String, shortcode: String): List<ResolvedMedia> {
        val trimmed = jsonStr.trim()
        val array: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            when {
                root.has("data") && root.optJSONArray("data") != null -> root.getJSONArray("data")
                root.has("items") && root.optJSONArray("items") != null -> root.getJSONArray("items")
                root.has("result") && root.optJSONArray("result") != null -> root.getJSONArray("result")
                root.has("media") && root.optJSONArray("media") != null -> root.getJSONArray("media")
                root.has("url") && root.optJSONArray("url") != null -> root.getJSONArray("url")
                else -> JSONArray().put(root)
            }
        } else {
            return emptyList()
        }

        val items = mutableListOf<ResolvedMedia>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue

            var downloadUrl = obj.optString("downloadUrl")
            if (downloadUrl.isBlank()) {
                downloadUrl = obj.optString("downloadableVideoUrl")
            }
            if (downloadUrl.isBlank()) {
                val videoVersions = obj.optJSONArray("video_versions")
                if (videoVersions != null && videoVersions.length() > 0) {
                    downloadUrl = videoVersions.getJSONObject(0).optString("url")
                }
            }
            if (downloadUrl.isBlank()) {
                downloadUrl = obj.optString("downloadableImageUrl")
            }
            if (downloadUrl.isBlank()) {
                val urlArr = obj.optJSONArray("url")
                if (urlArr != null && urlArr.length() > 0) {
                    downloadUrl = urlArr.getJSONObject(0).optString("url")
                }
            }
            if (downloadUrl.isBlank()) {
                downloadUrl = obj.optString("url")
            }
            if (downloadUrl.isBlank()) continue

            val isVideo = obj.optBoolean("isVideo", false) ||
                    obj.optString("type").equals("video", ignoreCase = true) ||
                    obj.optInt("media_type", 0) == 2 ||
                    obj.has("downloadableVideoUrl") ||
                    (obj.optJSONArray("video_versions")?.length() ?: 0) > 0 ||
                    downloadUrl.contains(".mp4") ||
                    downloadUrl.contains("/t16/")

            // Prioritize small preview image (~20KB) instead of multi-megabyte downloadUrl
            var thumbUrl = obj.optString("thumbUrl").takeIf { it.isNotBlank() }
            if (thumbUrl == null) {
                thumbUrl = obj.optString("previewImageUrl").takeIf { it.isNotBlank() }
            }
            if (thumbUrl == null) {
                thumbUrl = obj.optString("thumbnail_url").takeIf { it.isNotBlank() }
            }
            if (thumbUrl == null) {
                thumbUrl = obj.optString("previewUrl").takeIf { it.isNotBlank() }
            }
            if (thumbUrl == null) {
                thumbUrl = obj.optString("thumbnail_src").takeIf { it.isNotBlank() }
            }
            if (thumbUrl == null) {
                thumbUrl = obj.optString("display_url").takeIf { it.isNotBlank() }
            }
            if (thumbUrl == null) {
                val urlArr = obj.optJSONArray("url")
                if (urlArr != null && urlArr.length() > 0) {
                    val first = urlArr.getJSONObject(0)
                    thumbUrl = first.optString("previewImageUrl").takeIf { it.isNotBlank() }
                        ?: first.optString("thumbnail_url").takeIf { it.isNotBlank() }
                }
            }

            // Fallback for photo if no thumbnail found: downloadUrl is photo anyway
            if (thumbUrl == null && !isVideo) {
                thumbUrl = downloadUrl
            }

            val mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE

            val candidate = MediaCandidate(
                url = downloadUrl,
                width = null,
                height = null,
                mimeType = if (isVideo) "video/mp4" else "image/jpeg",
                isSelected = true,
                selectionReason = "Extracted via Headless Web Bridge"
            )

            items.add(
                ResolvedMedia(
                    index = i,
                    mediaType = mediaType,
                    mediaUrl = downloadUrl,
                    thumbnailUrl = thumbUrl,
                    width = null,
                    height = null,
                    durationMs = null,
                    bitRate = null,
                    qualityClassification = if (isVideo) "High-definition video" else "High-resolution photo",
                    selectionReason = "Resolved via Headless Web Bridge",
                    candidates = listOf(candidate)
                )
            )
        }

        return items
    }

    private fun buildInjectionScript(targetUrl: String, requestId: String): String {
        val safeUrl = targetUrl.replace("'", "\\'")
        return """
            (function() {
                var currentReqId = '$requestId';
                console.log('InstaSave bridge execution starting for: ' + currentReqId + ' -> $safeUrl');

                function dismissPopups() {
                    try {
                        var selectors = [
                            '.modal', '[class*="modal"]', '[class*="popup"]', '[class*="banner"]',
                            '[class*="overlay"]', '[class*="app-promo"]', '[class*="download-app"]',
                            '#ad-banner', '.app-install-modal'
                        ];
                        for (var s = 0; s < selectors.length; s++) {
                            var els = document.querySelectorAll(selectors[s]);
                            for (var e = 0; e < els.length; e++) {
                                els[e].remove();
                            }
                        }
                    } catch (_) {}
                }
                dismissPopups();

                function sendResult(items) {
                    if (!items || items.length === 0) return;
                    var json = JSON.stringify(items);
                    console.log('INSTASAVE_FOUND:' + currentReqId + '::' + json);
                    try {
                        if (window.AndroidBridge && typeof window.AndroidBridge.onMediaFoundWithId === 'function') {
                            window.AndroidBridge.onMediaFoundWithId(currentReqId, json);
                        } else if (window.AndroidBridge && typeof window.AndroidBridge.onMediaFound === 'function') {
                            window.AndroidBridge.onMediaFound(json);
                        }
                    } catch (_) {}
                }

                function sendError(msg) {
                    console.log('INSTASAVE_ERROR:' + currentReqId + '::' + msg);
                    try {
                        if (window.AndroidBridge && typeof window.AndroidBridge.onErrorWithId === 'function') {
                            window.AndroidBridge.onErrorWithId(currentReqId, msg);
                        } else if (window.AndroidBridge && typeof window.AndroidBridge.onError === 'function') {
                            window.AndroidBridge.onError(msg);
                        }
                    } catch (_) {}
                }

                window._fastDlSendResult = sendResult;
                window._fastDlSendError = sendError;

                // Monkey-patch window.fetch and XMLHttpRequest to catch raw API payload
                if (!window._fastDlPatched) {
                    window._fastDlPatched = true;
                    window._capturedFastDlApiData = null;

                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function() {
                            var args = arguments;
                            var url = (typeof args[0] === 'string') ? args[0] : (args[0] && args[0].url ? args[0].url : '');
                            return origFetch.apply(this, args).then(function(response) {
                                try {
                                    if (url && (url.indexOf('/api/convert') !== -1 || url.indexOf('convert') !== -1)) {
                                        response.clone().json().then(function(json) {
                                            console.log('Intercepted /api/convert via fetch');
                                            window._capturedFastDlApiData = json;
                                            var items = extractFromCapturedApiData();
                                            if (items && items.length > 0) {
                                                if (window._fastDlSendResult) {
                                                    window._fastDlSendResult(items);
                                                } else {
                                                    sendResult(items);
                                                }
                                            }
                                        }).catch(function(_) {});
                                    }
                                } catch (_) {}
                                return response;
                            });
                        };
                    }

                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this._reqUrl = url;
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        this.addEventListener('load', function() {
                            try {
                                if (this._reqUrl && (this._reqUrl.indexOf('/api/convert') !== -1 || this._reqUrl.indexOf('convert') !== -1)) {
                                    var json = JSON.parse(this.responseText);
                                    console.log('Intercepted /api/convert via XHR');
                                    window._capturedFastDlApiData = json;
                                    var items = extractFromCapturedApiData();
                                    if (items && items.length > 0) {
                                        if (window._fastDlSendResult) {
                                            window._fastDlSendResult(items);
                                        } else {
                                            sendResult(items);
                                        }
                                    }
                                }
                            } catch (_) {}
                        });
                        return origSend.apply(this, arguments);
                    };
                }

                function extractFromDataArray(arr) {
                    if (!Array.isArray(arr) || arr.length === 0) return null;
                    var items = [];
                    for (var j = 0; j < arr.length; j++) {
                        var d = arr[j];
                        if (!d) continue;

                        var isVid = !!(
                            d.downloadableVideoUrl ||
                            (d.video_versions && d.video_versions.length > 0) ||
                            d.type === 'video' ||
                            d.media_type === 2 ||
                            (typeof d.url === 'string' && d.url.indexOf('.mp4') !== -1)
                        );

                        var vUrl = d.downloadableVideoUrl || 
                                   (d.video_versions && d.video_versions[0] && d.video_versions[0].url) ||
                                   (isVid && typeof d.url === 'string' ? d.url : null) ||
                                   (isVid && Array.isArray(d.url) && d.url[0] && d.url[0].url);

                        var previewImg = d.previewImageUrl || 
                                         d.thumbnail_url || 
                                         d.thumbUrl || 
                                         d.thumbnail || 
                                         d.previewUrl || 
                                         d.thumbnail_src || 
                                         d.display_url || 
                                         (d.url && Array.isArray(d.url) && d.url[0] && (d.url[0].previewImageUrl || d.url[0].thumbnail_url || d.url[0].url)) || 
                                         '';

                        var iUrl = d.downloadableImageUrl || 
                                   (typeof d.url === 'string' ? d.url : null) || 
                                   (Array.isArray(d.url) && d.url[0] && d.url[0].url) || 
                                   previewImg;

                        var dlUrl = (isVid && vUrl) ? vUrl : (iUrl || vUrl);
                        var thumb = previewImg || (isVid ? '' : dlUrl);

                        if (dlUrl) {
                            items.push({
                                downloadUrl: dlUrl,
                                thumbUrl: thumb,
                                isVideo: isVid
                            });
                        }
                    }
                    return items.length > 0 ? items : null;
                }

                function extractFromCapturedApiData() {
                    if (!window._capturedFastDlApiData) return null;
                    var d = window._capturedFastDlApiData;
                    var rawList = null;
                    if (Array.isArray(d)) {
                        rawList = d;
                    } else if (d && typeof d === 'object') {
                        if (Array.isArray(d.data)) rawList = d.data;
                        else if (Array.isArray(d.items)) rawList = d.items;
                        else if (Array.isArray(d.result)) rawList = d.result;
                        else if (Array.isArray(d.media)) rawList = d.media;
                        else if (Array.isArray(d.url)) rawList = d.url;
                        else if (d.url && typeof d.url === 'string') rawList = [d];
                    }
                    if (rawList) {
                        return extractFromDataArray(rawList);
                    }
                    return null;
                }

                function tryExtractFromVue() {
                    try {
                        var targets = document.querySelectorAll('.output-list, .output-list__list, #app, div[class*="output"], div[class*="download"], div[class*="result"], div[class*="search-result"]');
                        for (var i = 0; i < targets.length; i++) {
                            var comp = targets[i].__vueParentComponent;
                            while (comp) {
                                var candidateArrays = [];
                                if (comp.props && comp.props.data) candidateArrays.push(comp.props.data);
                                if (comp.proxy && comp.proxy.data) candidateArrays.push(comp.proxy.data);
                                if (comp.ctx && comp.ctx.data) candidateArrays.push(comp.ctx.data);
                                if (comp.setupState && comp.setupState.data) candidateArrays.push(comp.setupState.data);
                                if (comp.data && comp.data.data) candidateArrays.push(comp.data.data);

                                var state = comp.ctx || comp.setupState || comp.data || comp.proxy;
                                if (state) {
                                    if (state.data) candidateArrays.push(state.data);
                                    if (state.items) candidateArrays.push(state.items);
                                    if (state.media) candidateArrays.push(state.media);
                                    if (state.result) candidateArrays.push(state.result);
                                }
                                if (comp.props && comp.props.items) candidateArrays.push(comp.props.items);
                                if (comp.proxy && comp.proxy.items) candidateArrays.push(comp.proxy.items);

                                for (var k = 0; k < candidateArrays.length; k++) {
                                    var candidate = candidateArrays[k];
                                    if (candidate && Array.isArray(candidate) && candidate.length > 0) {
                                        var vueItems = extractFromDataArray(candidate);
                                        if (vueItems && vueItems.length > 0) {
                                            console.log('Extracted ' + vueItems.length + ' items from Vue component state/props');
                                            return vueItems;
                                        }
                                    }
                                }
                                comp = comp.parent;
                            }
                        }
                    } catch (e) {
                        console.warn('Vue extraction warning:', e);
                    }
                    return null;
                }

                function expandDomItems() {
                    try {
                        var targets = document.querySelectorAll('.output-list, .output-list__list, #app, div[class*="output"], div[class*="download"]');
                        for (var i = 0; i < targets.length; i++) {
                            var comp = targets[i].__vueParentComponent;
                            while (comp) {
                                try {
                                    if (comp.proxy) {
                                        comp.proxy.visibleDataCount = 999;
                                        if (typeof comp.proxy.incrementVisibleDataCount === 'function') {
                                            comp.proxy.incrementVisibleDataCount();
                                        }
                                    }
                                    if (comp.ctx) {
                                        comp.ctx.visibleDataCount = 999;
                                        if (typeof comp.ctx.incrementVisibleDataCount === 'function') {
                                            comp.ctx.incrementVisibleDataCount();
                                        }
                                    }
                                } catch (_) {}
                                comp = comp.parent;
                            }
                        }
                    } catch (_) {}

                    try {
                        var moreButtons = document.querySelectorAll(
                            '.load-more-trigger, button.load-more, .output-list__more, button.output-list__more, ' +
                            'a.output-list__more, .load-more, button[class*="load-more"], button[class*="more"]'
                        );
                        for (var m = 0; m < moreButtons.length; m++) {
                            try {
                                moreButtons[m].click();
                            } catch (_) {}
                        }
                    } catch (_) {}

                    try {
                        window.scrollTo(0, document.body.scrollHeight || 99999);
                    } catch (_) {}
                }

                function tryExtractDom() {
                    var results = [];
                    var items = document.querySelectorAll('.output-list__item, .media-content, .search-result__item, .download-items, div[class*="output-list__item"]');
                    if (items.length > 0) {
                        items.forEach(function(item) {
                            var btn = item.querySelector('a.button--download, a[download], a[href*="media.fastdl.app"], a[href*="fbcdn"], a[href*="cdninstagram"]');
                            var img = item.querySelector('img');
                            if (btn && btn.href && !btn.href.startsWith('javascript:')) {
                                var isVideo = btn.href.indexOf('.mp4') !== -1 || 
                                              btn.href.indexOf('/t16/') !== -1 || 
                                              (btn.innerText && btn.innerText.toLowerCase().indexOf('video') !== -1) ||
                                              item.querySelector('.icon-play, svg, video') !== null;

                                var thumb = '';
                                if (img) {
                                    thumb = img.getAttribute('data-src') || 
                                            img.getAttribute('data-lazy-src') || 
                                            img.currentSrc || 
                                            img.src || '';
                                    if (thumb.startsWith('data:image')) {
                                        thumb = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || '';
                                    }
                                }

                                results.push({
                                    downloadUrl: btn.href,
                                    thumbUrl: thumb,
                                    isVideo: isVideo
                                });
                            }
                        });
                    }

                    if (results.length === 0) {
                        var btns = document.querySelectorAll('a.button--download, a[href*="media.fastdl.app/get"]');
                        btns.forEach(function(btn) {
                            if (btn && btn.href && !btn.href.startsWith('javascript:')) {
                                var isVideo = btn.href.indexOf('.mp4') !== -1 || 
                                              btn.href.indexOf('/t16/') !== -1 || 
                                              (btn.innerText && btn.innerText.toLowerCase().indexOf('video') !== -1);
                                results.push({
                                    downloadUrl: btn.href,
                                    thumbUrl: '',
                                    isVideo: isVideo
                                });
                            }
                        });
                    }
                    return results;
                }

                function checkError() {
                    var el = document.querySelector('.search-error, .alert-danger, .error-message');
                    if (el && el.innerText && el.innerText.trim().length > 0) {
                        return el.innerText.trim();
                    }
                    return null;
                }

                function doTriggerSearch() {
                    dismissPopups();
                    var input = document.getElementById('search-form-input') || document.querySelector('.search-form__input, input[name="url"], input[type="text"]');
                    var btn = document.getElementById('searchFormButton') || document.querySelector('.search-form__button, button[type="submit"]');

                    if (input) {
                        try {
                            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                            if (setter) {
                                setter.call(input, '$safeUrl');
                            } else {
                                input.value = '$safeUrl';
                            }
                        } catch (_) {
                            input.value = '$safeUrl';
                        }
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));

                        try {
                            var el = input;
                            while (el) {
                                if (el.__vueParentComponent && el.__vueParentComponent.proxy) {
                                    var p = el.__vueParentComponent.proxy;
                                    if ('query' in p) p.query = '$safeUrl';
                                    if ('disabled' in p) p.disabled = false;
                                    if (typeof p.handleSearchButtonClick === 'function') {
                                        console.log('Invoked proxy.handleSearchButtonClick()');
                                        p.handleSearchButtonClick();
                                        return;
                                    }
                                }
                                el = el.parentElement;
                            }
                        } catch (_) {}
                    }

                    if (btn) {
                        btn.removeAttribute('disabled');
                        btn.disabled = false;
                        btn.click();
                        console.log('Clicked search button');
                    }
                }

                doTriggerSearch();
                setTimeout(doTriggerSearch, 100);
                setTimeout(doTriggerSearch, 250);

                var attempts = 0;
                var lastCount = 0;
                var stableTicks = 0;
                var pollInterval = setInterval(function() {
                    attempts++;
                    dismissPopups();

                    // 0. Intercepted API data
                    var apiMedia = extractFromCapturedApiData();
                    if (apiMedia && apiMedia.length > 0) {
                        clearInterval(pollInterval);
                        console.log('Returning ' + apiMedia.length + ' items via intercepted API');
                        sendResult(apiMedia);
                        return;
                    }

                    expandDomItems();

                    // 1. Vue component state
                    var vueMedia = tryExtractFromVue();
                    if (vueMedia && vueMedia.length > 0) {
                        clearInterval(pollInterval);
                        console.log('Returning ' + vueMedia.length + ' items via Vue state');
                        sendResult(vueMedia);
                        return;
                    }

                    // 2. DOM elements
                    var media = tryExtractDom();
                    if (media.length > 0) {
                        if (media.length === lastCount) {
                            stableTicks++;
                        } else {
                            stableTicks = 0;
                            lastCount = media.length;
                        }

                        if (media.length === 1 || stableTicks >= 4) {
                            clearInterval(pollInterval);
                            console.log('Returning ' + media.length + ' items via DOM');
                            sendResult(media);
                            return;
                        }
                    }

                    var err = checkError();
                    if (err) {
                        clearInterval(pollInterval);
                        sendError(err);
                        return;
                    }

                    if (attempts >= 45) { // ~9 seconds
                        clearInterval(pollInterval);
                        if (media.length > 0) {
                            sendResult(media);
                        } else {
                            sendError("Extraction timed out on web bridge");
                        }
                    }
                }, 200);
            })();
        """.trimIndent()
    }
}
