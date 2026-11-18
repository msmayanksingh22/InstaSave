package com.instadown.assist.resolver

import android.content.Context
import android.util.Log
import com.instadown.assist.resolver.model.ResolveErrorCode
import com.instadown.assist.resolver.model.ResolveResult

class CompositeInstagramResolver(
    private val context: Context? = null,
    private val primaryResolver: InstagramResolver = WebPageSjsResolver(),
    private val fallbackResolver: InstagramResolver = EmbedFallbackResolver(),
    private val webBridgeResolver: InstagramResolver? = context?.let { HeadlessWebBridgeResolver(it) }
) : InstagramResolver {

    companion object {
        private const val TAG = "CompositeResolver"
    }

    override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult {
        // 1. Always execute primary SSR resolver first (fastest on-device)
        val primaryResult = primaryResolver.resolve(url, validateCdn)
        if (primaryResult.success) {
            return primaryResult
        }

        // Do not attempt fallback if URL itself was malformed or unsupported
        if (primaryResult.error?.code == ResolveErrorCode.UNSUPPORTED_URL) {
            return primaryResult
        }

        Log.w(TAG, "Primary resolver failed with ${primaryResult.error?.code}: ${primaryResult.error?.message}. Trying embed fallback...")

        // 2. Attempt embed fallback resolver
        val fallbackResult = fallbackResolver.resolve(url, validateCdn)
        if (fallbackResult.success) {
            return fallbackResult
        }

        // 3. If primary & embed failed (e.g. age-gated, login-restricted), attempt Headless Web Bridge
        if (webBridgeResolver != null) {
            Log.i(TAG, "Embed fallback failed (${fallbackResult.error?.message}). Attempting Headless Web Bridge (login-free)...")
            val bridgeResult = webBridgeResolver.resolve(url, validateCdn)
            if (bridgeResult.success) {
                return bridgeResult
            }
            Log.w(TAG, "Headless Web Bridge failed: ${bridgeResult.error?.message}")
        }

        // If all tiers failed, return the error with comprehensive diagnosis
        val combinedError = primaryResult.error?.copy(
            rawDetails = "Primary: ${primaryResult.error.message} | Embed: ${fallbackResult.error?.message}" +
                    if (webBridgeResolver != null) " | WebBridge: Check network/extractor" else ""
        )
        return primaryResult.copy(error = combinedError)
    }
}

