package com.instadown.assist.resolver

import android.content.Context
import android.util.Log
import com.instadown.assist.resolver.model.PostType
import com.instadown.assist.resolver.model.ResolveErrorCode
import com.instadown.assist.resolver.model.ResolveResult
import com.instadown.assist.util.AppLogger
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory circuit breaker preventing battery drain and repeated network timeouts
 * on unauthenticated endpoints that return 302 Login Redirects or 429 Rate Limits.
 */
object TierCircuitBreaker {
    private const val COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes
    private val trippedMap = ConcurrentHashMap<String, Long>()

    fun trip(tier: String) {
        trippedMap[tier] = System.currentTimeMillis()
    }

    fun isTripped(tier: String): Boolean {
        val tripTime = trippedMap[tier] ?: return false
        if (System.currentTimeMillis() - tripTime > COOLDOWN_MS) {
            trippedMap.remove(tier)
            return false
        }
        return true
    }

    fun reset(tier: String) {
        trippedMap.remove(tier)
    }

    fun resetAll() {
        trippedMap.clear()
    }
}

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
        val canonicalNormalized = UrlNormalizer.resolveCanonical(url)
        val targetUrl = canonicalNormalized?.canonicalUrl ?: url
        AppLogger.i(TAG, "Starting multi-tier resolution for: $url (canonical: $targetUrl)")

        // 0. TIER 0: Direct OkHttp GraphQL (Zero-WebView ~200ms) with Circuit Breaker
        if (!TierCircuitBreaker.isTripped("Tier0_GraphQL")) {
            val tier0Result = try {
                withTimeoutOrNull(1500L) {
                    directResolver.resolve(targetUrl, validateCdn)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Tier 0 Direct OkHttp GraphQL exception: ${e.message}")
                null
            }

            if (tier0Result != null && tier0Result.success && tier0Result.items.isNotEmpty()) {
                TierCircuitBreaker.reset("Tier0_GraphQL")
                AppLogger.i(TAG, "Tier 0 (GraphQL) succeeded in ${System.currentTimeMillis() - startTime}ms with ${tier0Result.items.size} items!")
                return tier0Result
            } else if (tier0Result?.error?.code == ResolveErrorCode.AUTH_REQUIRED || tier0Result?.error?.code == ResolveErrorCode.RATE_LIMITED) {
                AppLogger.w(TAG, "Tier 0 received unauthenticated login/rate challenge. Tripping circuit breaker for 10 minutes.")
                TierCircuitBreaker.trip("Tier0_GraphQL")
            }
        } else {
            AppLogger.d(TAG, "Tier 0 GraphQL circuit breaker is active (cooldown). Bypassing to fast fallbacks.")
        }

        // 1. TIER 1: Fast Embed Fallback (~250ms execution for single photos & reels)
        val fallbackResult = try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                fallbackResolver.resolve(targetUrl, validateCdn)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Fast Embed fallback exception: ${e.message}")
            null
        }

        if (fallbackResult != null && fallbackResult.success && fallbackResult.items.isNotEmpty()) {
            AppLogger.i(TAG, "Fast Embed succeeded in ${System.currentTimeMillis() - startTime}ms with ${fallbackResult.items.size} items!")
            return fallbackResult
        }

        // 2. TIER 2: Headless Web Bridge (Executed for full 15+ carousels or if embed fails)
        preWarm()
        if (webBridgeResolver != null) {
            AppLogger.i(TAG, "Attempting Headless Web Bridge for complete extraction...")
            val bridgeResult = webBridgeResolver.resolve(targetUrl, validateCdn)
            if (bridgeResult.success && bridgeResult.items.isNotEmpty()) {
                AppLogger.i(TAG, "Headless Web Bridge succeeded in ${bridgeResult.totalResolutionTimeMs}ms with ${bridgeResult.items.size} items!")
                return bridgeResult
            }
            AppLogger.w(TAG, "Headless Web Bridge did not succeed: ${bridgeResult.error?.message}. Attempting webpage SJS fallback...")
        }

        // 3. TIER 3: WebPage SJS Resolver
        val sjsResult = try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                primaryResolver.resolve(targetUrl, validateCdn)
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
            canonicalUrl = targetUrl,
            shortcode = canonicalNormalized?.shortcode,
            sourcePathType = canonicalNormalized?.sourcePathType,
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
