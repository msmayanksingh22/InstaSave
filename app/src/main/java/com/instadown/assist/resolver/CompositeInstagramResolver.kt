package com.instadown.assist.resolver

import android.content.Context
import android.util.Log
import com.instadown.assist.resolver.model.ResolveErrorCode
import com.instadown.assist.resolver.model.ResolveResult
import com.instadown.assist.util.AppLogger
import kotlinx.coroutines.withTimeoutOrNull

class CompositeInstagramResolver(
    private val context: Context? = null,
    private val directResolver: InstagramResolver = DirectOkHttpInstagramResolver(),
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
        AppLogger.i(TAG, "Starting multi-tier resolution for: $url")

        // 0. TIER 0: Ultra-fast Direct OkHttp GraphQL (Zero-WebView 180ms - 350ms)
        val tier0Result = try {
            withTimeoutOrNull(1500L) {
                directResolver.resolve(url, validateCdn)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Tier 0 Direct OkHttp GraphQL exception: ${e.message}")
            null
        }

        if (tier0Result != null && tier0Result.success && tier0Result.items.isNotEmpty()) {
            AppLogger.i(TAG, "Tier 0 (GraphQL) succeeded in ${System.currentTimeMillis() - startTime}ms with ${tier0Result.items.size} items!")
            return tier0Result
        }

        // Ensure web bridge background WebView is pre-warmed
        preWarm()

        // 1. TIER 1 (PRIMARY): Ultra-fast Headless Web Bridge (Instant Pre-warmed ~1s execution)
        if (webBridgeResolver != null) {
            val bridgeResult = webBridgeResolver.resolve(url, validateCdn)
            if (bridgeResult.success && bridgeResult.items.isNotEmpty()) {
                AppLogger.i(TAG, "Tier 1 (Headless Web Bridge) succeeded in ${bridgeResult.totalResolutionTimeMs}ms with ${bridgeResult.items.size} items!")
                return bridgeResult
            }
            AppLogger.w(TAG, "Tier 1 Web Bridge did not succeed: ${bridgeResult.error?.message}. Attempting fast fallbacks...")
        }

        // 2. TIER 2: Fast Embed Fallback
        val fallbackResult = try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                fallbackResolver.resolve(url, validateCdn)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Tier 2 Embed fallback exception: ${e.message}")
            null
        }

        if (fallbackResult != null && fallbackResult.success && fallbackResult.items.isNotEmpty()) {
            AppLogger.i(TAG, "Tier 2 (Embed Fallback) succeeded in ${System.currentTimeMillis() - startTime}ms with ${fallbackResult.items.size} items!")
            return fallbackResult
        }

        // 3. TIER 3: WebPage SJS Resolver
        val sjsResult = try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                primaryResolver.resolve(url, validateCdn)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Tier 3 SJS resolver exception: ${e.message}")
            null
        }

        if (sjsResult != null && sjsResult.success && sjsResult.items.isNotEmpty()) {
            AppLogger.i(TAG, "Tier 3 (SJS Resolver) succeeded in ${System.currentTimeMillis() - startTime}ms with ${sjsResult.items.size} items!")
            return sjsResult
        }

        val finalResult = fallbackResult ?: sjsResult ?: ResolveResult(
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
        AppLogger.e(TAG, "All resolution tiers failed for $url: ${finalResult.error?.message}")
        return finalResult
    }
}
