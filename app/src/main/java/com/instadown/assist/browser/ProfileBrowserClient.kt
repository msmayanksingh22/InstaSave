package com.instadown.assist.browser

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.instadown.assist.util.AppLogger

/**
 * Sandboxed SPA-aware WebViewClient for the native-feel Profile Explorer.
 * Intercepts Single-Page Application (SPA) navigation via doUpdateVisitedHistory,
 * hides web app banners, and enforces strict host security.
 */
class ProfileBrowserClient(
    private val onRouteChanged: (DetectedRoute) -> Unit,
    private val onPageLoadingChanged: (Boolean) -> Unit,
    private val onErrorReceived: (String) -> Unit
) : WebViewClient() {

    companion object {
        private const val TAG = "ProfileBrowserClient"
    }

    @Volatile
    private var isLoadingMainFrame: Boolean = false

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return true

        // Block app store schemes, intent:// redirects, and external links
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            AppLogger.d(TAG, "Blocked non-http scheme: $url")
            return true
        }

        if (!InstagramRouteParser.isAllowedHost(url)) {
            AppLogger.d(TAG, "Blocked external domain navigation: $url")
            return true
        }

        if (view != null && ProfileSessionManager.applyOptimalUserAgent(view, url)) {
            AppLogger.d(TAG, "Switching WebView User-Agent for route: $url")
            view.post { view.loadUrl(url) }
            return true
        }

        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        isLoadingMainFrame = true
        onPageLoadingChanged(true)
        // DO NOT mutate DOM during onPageStarted: modifying DOM before or during initial render
        // triggers React 18 SSR hydration error #418, which halts the timeline grid mount.
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        isLoadingMainFrame = false
        onPageLoadingChanged(false)
        if (view != null) {
            // Re-run cleanly after React hydration completes (350ms + 1000ms safety pass)
            view.postDelayed({
                if (view.isAttachedToWindow && !isLoadingMainFrame) ProfileSessionManager.injectNativeStyling(view)
            }, 350)
            view.postDelayed({
                if (view.isAttachedToWindow && !isLoadingMainFrame) ProfileSessionManager.injectNativeStyling(view)
            }, 1000)
        }
        if (url != null) {
            val route = InstagramRouteParser.parse(url)
            onRouteChanged(route)
        }
    }

    /**
     * CRITICAL for React/Relay SPAs:
     * When the user taps a post or reel in the profile grid, Instagram changes the route
     * via window.history.pushState without a full page reload.
     * doUpdateVisitedHistory cleanly catches this transition with 0 ms latency!
     */
    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (view != null && !isLoadingMainFrame) {
            view.postDelayed({
                if (view.isAttachedToWindow && !isLoadingMainFrame) {
                    ProfileSessionManager.injectNativeStyling(view)
                }
            }, 250)
        }
        if (url != null) {
            val route = InstagramRouteParser.parse(url)
            onRouteChanged(route)
        }
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val desc = error?.description?.toString() ?: "Connection failed"
            AppLogger.w(TAG, "Main frame error: $desc on url: ${request.url}")
            onErrorReceived(desc)
        }
    }
}
