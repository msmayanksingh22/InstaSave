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
        private const val PRIMARY_RESOLVER_TIMEOUT_MS = 3500L
        private const val FALLBACK_RESOLVER_TIMEOUT_MS = 3000L
    }

    init {
        // Pre-warm the headless bridge background WebView immediately on initialization
        preWarm()
    }

    fun preWarm() {
        (webBridgeResolver as? HeadlessWebBridgeResolver)?.preWarm()
    }

    override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult {
        val startTime = System.currentTimeMillis()

        // Ensure web bridge background WebView is pre-warmed
        preWarm()

        // 1. Execute primary SSR resolver first (streamlined with timeout so slow network stalls don't block)
        val primaryResult = try {
            withTimeoutOrNull(PRIMARY_RESOLVER_TIMEOUT_MS) {
                primaryResolver.resolve(url, validateCdn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary resolver exception", e)
            null
        }

        if (primaryResult != null && primaryResult.success) {
            return primaryResult
        }

        // Do not attempt fallback if URL itself was malformed or unsupported
        if (primaryResult?.error?.code == ResolveErrorCode.UNSUPPORTED_URL) {
            return primaryResult
        }

        val primaryMsg = primaryResult?.error?.message ?: "Timed out after ${PRIMARY_RESOLVER_TIMEOUT_MS}ms"
        Log.w(TAG, "Primary resolver did not succeed ($primaryMsg). Falling through immediately to embed fallback...")

        // 2. Attempt embed fallback resolver (streamlined with timeout)
        val fallbackResult = try {
            withTimeoutOrNull(FALLBACK_RESOLVER_TIMEOUT_MS) {
                fallbackResolver.resolve(url, validateCdn)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallback resolver exception", e)
            null
        }

        if (fallbackResult != null && fallbackResult.success) {
            return fallbackResult
        }

        val fallbackMsg = fallbackResult?.error?.message ?: "Timed out after ${FALLBACK_RESOLVER_TIMEOUT_MS}ms"
        Log.i(TAG, "Embed fallback did not succeed ($fallbackMsg). Falling through immediately to Headless Web Bridge...")

        // 3. Fall through immediately to Headless Web Bridge (login-free extractor)
        if (webBridgeResolver != null) {
            val bridgeResult = webBridgeResolver.resolve(url, validateCdn)
            if (bridgeResult.success) {
                return bridgeResult
            }
            Log.w(TAG, "Headless Web Bridge failed: ${bridgeResult.error?.message}")
            return bridgeResult
        }

        // If all tiers failed, return the error with comprehensive diagnosis
        val combinedError = primaryResult?.error?.copy(
            rawDetails = "Primary: $primaryMsg | Embed: $fallbackMsg | WebBridge: No Context"
        )
        return primaryResult?.copy(
            totalResolutionTimeMs = System.currentTimeMillis() - startTime,
            error = combinedError
        ) ?: ResolveResult(
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
                message = "All resolvers failed or timed out: Primary: $primaryMsg | Embed: $fallbackMsg"
            )
        )
    }
}
