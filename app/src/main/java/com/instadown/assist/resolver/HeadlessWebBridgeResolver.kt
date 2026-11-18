package com.instadown.assist.resolver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.instadown.assist.resolver.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.net.URI
import kotlin.coroutines.resume

class HeadlessWebBridgeResolver(
    private val context: Context
) : InstagramResolver {

    companion object {
        private const val TAG = "HeadlessWebBridge"
        private const val EXTRACTOR_URL = "https://fastdl.app/en"
        private const val TIMEOUT_MS = 22000L
    }

    override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult {
        val startTime = System.currentTimeMillis()
        val normalized = UrlNormalizer.normalize(url)
            ?: return ResolveResult(
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

        return try {
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
            var webView: WebView? = null
            var isFinished = false

            fun cleanup() {
                try {
                    webView?.stopLoading()
                    webView?.removeJavascriptInterface("AndroidBridge")
                    webView?.destroy()
                    webView = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error cleaning up WebView", e)
                }
            }

            continuation.invokeOnCancellation {
                cleanup()
            }

            class BridgeInterface {
                @JavascriptInterface
                fun onMediaFound(jsonStr: String) {
                    if (isFinished) return
                    isFinished = true
                    Log.i(TAG, "onMediaFound received: ${jsonStr.take(200)}...")

                    Handler(Looper.getMainLooper()).post {
                        try {
                            val items = parseMediaItems(jsonStr, normalized.shortcode)
                            cleanup()

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
                                        methodUsed = "HeadlessWebBridgeResolver",
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
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse bridge JSON", e)
                            cleanup()
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
                                        message = "JSON parse error: ${e.message}"
                                    )
                                )
                            )
                        }
                    }
                }

                @JavascriptInterface
                fun onError(errorMsg: String) {
                    if (isFinished) return
                    isFinished = true
                    Log.w(TAG, "Bridge reported error: $errorMsg")

                    Handler(Looper.getMainLooper()).post {
                        cleanup()
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
                }
            }

            try {
                val newWebView = WebView(context.applicationContext)
                webView = newWebView

                val settings = newWebView.settings
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadsImagesAutomatically = false // optimize memory & bandwidth
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                newWebView.addJavascriptInterface(BridgeInterface(), "AndroidBridge")

                var hasInjected = false
                newWebView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        if (!hasInjected && pageUrl?.contains("fastdl.app") == true) {
                            hasInjected = true
                            Log.i(TAG, "Page loaded. Injecting automation script for: $targetUrl")
                            val js = buildInjectionScript(targetUrl)
                            view?.evaluateJavascript(js, null)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "Main frame error: ${error?.description}")
                        }
                    }
                }

                Log.i(TAG, "Loading extractor URL: $EXTRACTOR_URL")
                newWebView.loadUrl(EXTRACTOR_URL)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to instantiate WebView", e)
                cleanup()
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
                            code = ResolveErrorCode.NETWORK_ERROR,
                            message = "Could not initialize background WebView: ${e.message}"
                        )
                    )
                )
            }
        }
    }

    private fun parseMediaItems(jsonStr: String, shortcode: String): List<ResolvedMedia> {
        val array = JSONArray(jsonStr)
        val items = mutableListOf<ResolvedMedia>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val downloadUrl = obj.optString("downloadUrl")
            if (downloadUrl.isBlank()) continue

            val isVideo = obj.optBoolean("isVideo", false) ||
                    downloadUrl.contains(".mp4") ||
                    downloadUrl.contains("/t16/")

            val thumbUrl = obj.optString("thumbUrl").takeIf { it.isNotBlank() } ?: downloadUrl
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

    private fun buildInjectionScript(targetUrl: String): String {
        val safeUrl = targetUrl.replace("'", "\\'")
        return """
            (function() {
                console.log('InstaSave bridge injection started');
                
                function tryExtractFromVue() {
                    try {
                        var targets = document.querySelectorAll('.output-list, .output-list__list, #app, div[class*="output"]');
                        for (var i = 0; i < targets.length; i++) {
                            var comp = targets[i].__vueParentComponent;
                            while (comp) {
                                var state = comp.ctx || comp.setupState || comp.data;
                                if (state && state.data && Array.isArray(state.data) && state.data.length > 0) {
                                    var vueItems = [];
                                    for (var j = 0; j < state.data.length; j++) {
                                        var d = state.data[j];
                                        var isVid = !!(d.downloadableVideoUrl || (d.video_versions && d.video_versions.length > 0) || d.type === 'video');
                                        var vUrl = d.downloadableVideoUrl || (d.video_versions && d.video_versions[0] && d.video_versions[0].url);
                                        var iUrl = d.downloadableImageUrl || d.previewImageUrl || (d.url && d.url[0] && d.url[0].url);
                                        var dlUrl = (isVid && vUrl) ? vUrl : (iUrl || vUrl);
                                        if (dlUrl) {
                                            vueItems.push({
                                                downloadUrl: dlUrl,
                                                thumbUrl: d.previewImageUrl || iUrl || '',
                                                isVideo: isVid
                                            });
                                        }
                                    }
                                    if (vueItems.length > 0) {
                                        return vueItems;
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
                        var lists = document.querySelectorAll('.output-list__list, .output-list');
                        for (var i = 0; i < lists.length; i++) {
                            var el = lists[i];
                            if (el && el.__vueParentComponent && el.__vueParentComponent.ctx) {
                                el.__vueParentComponent.ctx.visibleDataCount = 999;
                            }
                        }
                    } catch (_) {}
                    window.scrollTo(0, document.body.scrollHeight || 99999);
                }
                
                function tryExtractDom() {
                    var results = [];
                    var items = document.querySelectorAll('.output-list__item, .media-content, .search-result__item, .download-items');
                    if (items.length > 0) {
                        items.forEach(function(item) {
                            var btn = item.querySelector('a.button--download, a[download], a[href*="media.fastdl.app"], a[href*="fbcdn"], a[href*="cdninstagram"]');
                            var img = item.querySelector('img');
                            if (btn && btn.href && !btn.href.startsWith('javascript:')) {
                                var isVideo = btn.href.indexOf('.mp4') !== -1 || 
                                              btn.href.indexOf('/t16/') !== -1 || 
                                              (btn.innerText && btn.innerText.toLowerCase().indexOf('video') !== -1) ||
                                              item.querySelector('.icon-play, svg, video') !== null;
                                results.push({
                                    downloadUrl: btn.href,
                                    thumbUrl: img ? img.src : '',
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

                // Input URL into search field and trigger search immediately
                var input = document.querySelector('.search-form__input, input[name="url"], input[type="text"]');
                var btn = document.querySelector('.search-form__button, button[type="submit"]');
                if (input && btn) {
                    input.value = '$safeUrl';
                    input.dispatchEvent(new Event('input', { bubbles: true }));
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                    setTimeout(function() {
                        btn.click();
                        console.log('Search button clicked');
                    }, 50);
                } else {
                    console.log('Search input or button not found');
                }

                var attempts = 0;
                var lastCount = 0;
                var stableTicks = 0;
                var pollInterval = setInterval(function() {
                    attempts++;
                    expandDomItems();
                    
                    // 1. Check direct Vue state first (contains full carousel immediately!)
                    var vueMedia = tryExtractFromVue();
                    if (vueMedia && vueMedia.length > 0) {
                        clearInterval(pollInterval);
                        console.log('Media extracted via Vue state: ' + vueMedia.length + ' items');
                        AndroidBridge.onMediaFound(JSON.stringify(vueMedia));
                        return;
                    }
                    
                    // 2. Check DOM extraction
                    var media = tryExtractDom();
                    if (media.length > 0) {
                        if (media.length === lastCount) {
                            stableTicks++;
                        } else {
                            stableTicks = 0;
                            lastCount = media.length;
                        }
                        
                        // Single items return at once; multi-item carousels wait until stabilized
                        if (media.length === 1 || stableTicks >= 3) {
                            clearInterval(pollInterval);
                            console.log('Media extracted via DOM: ' + media.length + ' items');
                            AndroidBridge.onMediaFound(JSON.stringify(media));
                            return;
                        }
                    }
                    
                    var err = checkError();
                    if (err) {
                        clearInterval(pollInterval);
                        console.log('Error found: ' + err);
                        AndroidBridge.onError(err);
                        return;
                    }
                    
                    if (attempts >= 60) { // ~12 seconds
                        clearInterval(pollInterval);
                        if (media.length > 0) {
                            AndroidBridge.onMediaFound(JSON.stringify(media));
                        } else {
                            AndroidBridge.onError("Extraction timed out on web bridge");
                        }
                    }
                }, 200);
            })();
        """.trimIndent()
    }
}
