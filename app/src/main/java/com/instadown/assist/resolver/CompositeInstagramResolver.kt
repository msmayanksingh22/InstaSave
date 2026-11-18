package com.instadown.assist.resolver

import android.content.Context
import android.util.Log
import com.instadown.assist.resolver.model.ResolveErrorCode
import com.instadown.assist.resolver.model.ResolveResult
import kotlinx.coroutines.withTimeoutOrNull

class CompositeInstagramResolver(
    private val context: Context? = null,
    private val primaryResolver: InstagramResolver = WebPageSjsResolver(),
    private val fallbackResolver: InstagramResolver = EmbedFallbackResolver(),
    private val webBridgeResolver: InstagramResolver? = context?.let { HeadlessWebBridgeResolver(it) }
) : InstagramResolver {

    companion object {
        private const val TAG = "CompositeResolver"
        private const val FALLBACK_TIMEOUT_MS = 2500L
    }

    init {
        // Pre-warm the headless bridge background WebView immediately on initialization
        preWarm()
    }

    fun preWarm() {
        if (context != null) {
            HeadlessWebBridgeResolver.preWarm(context)
        }
    }

    override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult {
        val startTime = System.currentTimeMillis()

        // Ensure web bridge background WebView is pre-warmed
        preWarm()

        // 1. TIER 1 (PRIMARY): Ultra-fast Headless Web Bridge (Instant Pre-warmed ~1s execution)
        if (webBridgeResolver != null) {
            val bridgeResult = webBridgeResolver.resolve(url, validateCdn)
            if (bridgeResult.success && bridgeResult.items.isNotEmpty()) {
                Log.i(TAG, "Tier 1 (Headless Web Bridge) succeeded in ${bridgeResult.totalResolutionTimeMs}ms with ${bridgeResult.items.size} items!")
                return bridgeResult
            }
            Log.w(TAG, "Tier 1 Web Bridge did not succeed: ${bridgeResult.error?.message}. Attempting fast fallbacks...")
        }

        // 2. TIER 2: Fast Embed Fallback
        val fallbackResult = try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                fallbackResolver.resolve(url, validateCdn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback resolver exception", e)
            null
        }

        if (fallbackResult != null && fallbackResult.success && fallbackResult.items.isNotEmpty()) {
            Log.i(TAG, "Tier 2 (Embed Fallback) succeeded in ${System.currentTimeMillis() - startTime}ms")
            return fallbackResult
        }

        // 3. TIER 3: WebPage SJS Resolver
        val sjsResult = try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                primaryResolver.resolve(url, validateCdn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "SJS resolver exception", e)
            null
        }

        if (sjsResult != null && sjsResult.success && sjsResult.items.isNotEmpty()) {
            Log.i(TAG, "Tier 3 (SJS Resolver) succeeded in ${System.currentTimeMillis() - startTime}ms")
            return sjsResult
        }

        // If all tiers failed, return the primary error
        return fallbackResult ?: sjsResult ?: ResolveResult(
            success = false,
            originalInputUrl = url,
            canonicalUrl = null,
            shortcode = null,
            sourcePathType = null,
            postType = null,
            items = emptyList(),
            methodUsed = "CompositeInstagramResolver",
            totalResolutionTimeMs = System.currentTimeMillis() - startTime,
            error = com.instadown.assist.resolver.model.ResolveError(
                code = ResolveErrorCode.NETWORK_ERROR,
                message = "Failed to resolve Instagram post media"
            )
        )
    }
}
