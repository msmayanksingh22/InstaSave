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
        val trace = mutableListOf<com.instadown.assist.resolver.model.StepTrace>()
        var stepIndex = 1

        // Step 1: URL Normalization & Canonical Redirect Probe
        val step1Start = System.currentTimeMillis()
        val canonicalNormalized = UrlNormalizer.resolveCanonical(url)
        val targetUrl = canonicalNormalized?.canonicalUrl ?: url
        val step1Dur = System.currentTimeMillis() - step1Start
        val normSuccess = canonicalNormalized != null && canonicalNormalized.shortcode.isNotBlank()
        trace.add(
            com.instadown.assist.resolver.model.StepTrace(
                stepIndex = stepIndex++,
                stepName = "URL Normalizer",
                durationMs = step1Dur,
                success = normSuccess,
                details = if (normSuccess) "Shortcode=${canonicalNormalized?.shortcode}, Type=${canonicalNormalized?.sourcePathType}, Canonical=$targetUrl" else "Could not extract valid shortcode"
            )
        )
        AppLogger.i(TAG, "Step 1: ${trace.last()}")

        if (!normSuccess) {
            val errResult = ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = null,
                shortcode = null,
                sourcePathType = null,
                postType = null,
                items = emptyList(),
                methodUsed = "UrlNormalizer",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                error = com.instadown.assist.resolver.model.ResolveError(
                    code = ResolveErrorCode.UNSUPPORTED_URL,
                    message = "Could not parse a valid Instagram shortcode from URL: '$url'"
                ),
                diagnosticTrace = trace
            )
            AppLogger.e(TAG, errResult.formattedTrace)
            return errResult
        }

        // Step 2: High-Speed Fast Embed Resolver (~250-400ms for single photos & reels)
        val step2Start = System.currentTimeMillis()
        val embedResult = try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                fallbackResolver.resolve(targetUrl, validateCdn)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Fast Embed fallback exception: ${e.message}")
            null
        }
        val step2Dur = System.currentTimeMillis() - step2Start
        val embedSuccess = embedResult != null && embedResult.success && embedResult.items.isNotEmpty()
        val isEmbedCarousel = embedResult?.isPartial == true && embedResult.postType == PostType.CAROUSEL

        trace.add(
            com.instadown.assist.resolver.model.StepTrace(
                stepIndex = stepIndex++,
                stepName = "Fast Embed Resolver",
                durationMs = step2Dur,
                success = embedSuccess,
                details = when {
                    embedSuccess -> "Extracted ${embedResult?.items?.size} items (${embedResult?.postType}) directly from Meta CDN"
                    isEmbedCarousel -> "Detected multi-item carousel (deferring to Headless Web Bridge)"
                    embedResult != null -> "Failed: ${embedResult.error?.message}"
                    else -> "Timed out after ${FALLBACK_TIMEOUT_MS}ms"
                }
            )
        )
        AppLogger.i(TAG, "Step 2: ${trace.last()}")

        // Instant return if Fast Embed resolved single photo or reel
        if (embedSuccess && embedResult != null) {
            val finalRes = embedResult.copy(
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                diagnosticTrace = trace
            )
            AppLogger.i(TAG, "Resolution completed successfully via Fast Embed in ${finalRes.totalResolutionTimeMs}ms:\n${finalRes.formattedTrace}")
            return finalRes
        }

        // Step 3: Tier 0 Direct OkHttp GraphQL (Zero-WebView fallback if not tripped)
        if (!isEmbedCarousel && !TierCircuitBreaker.isTripped("Tier0_GraphQL")) {
            val step3Start = System.currentTimeMillis()
            val tier0Result = try {
                withTimeoutOrNull(1200L) {
                    directResolver.resolve(targetUrl, validateCdn)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Tier 0 GraphQL exception: ${e.message}")
                null
            }
            val step3Dur = System.currentTimeMillis() - step3Start
            val tier0Success = tier0Result != null && tier0Result.success && tier0Result.items.isNotEmpty()

            trace.add(
                com.instadown.assist.resolver.model.StepTrace(
                    stepIndex = stepIndex++,
                    stepName = "Direct OkHttp GraphQL",
                    durationMs = step3Dur,
                    success = tier0Success,
                    details = when {
                        tier0Success -> "GraphQL success with ${tier0Result?.items?.size} items"
                        tier0Result?.error?.code == ResolveErrorCode.AUTH_REQUIRED || tier0Result?.error?.code == ResolveErrorCode.RATE_LIMITED -> {
                            TierCircuitBreaker.trip("Tier0_GraphQL")
                            "Unauthenticated auth/rate challenge (HTTP ${tier0Result.error?.rawDetails}). Tripped circuit breaker."
                        }
                        tier0Result != null -> "GraphQL error: ${tier0Result.error?.message}"
                        else -> "GraphQL timed out after 1200ms"
                    }
                )
            )
            AppLogger.i(TAG, "Step 3: ${trace.last()}")

            if (tier0Success && tier0Result != null) {
                TierCircuitBreaker.reset("Tier0_GraphQL")
                val finalRes = tier0Result.copy(
                    totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                    diagnosticTrace = trace
                )
                AppLogger.i(TAG, "Resolution completed successfully via GraphQL in ${finalRes.totalResolutionTimeMs}ms:\n${finalRes.formattedTrace}")
                return finalRes
            }
        } else if (!isEmbedCarousel) {
            trace.add(
                com.instadown.assist.resolver.model.StepTrace(
                    stepIndex = stepIndex++,
                    stepName = "Direct OkHttp GraphQL",
                    durationMs = 0L,
                    success = false,
                    details = "Bypassed (Circuit breaker cooldown active for 10 minutes)"
                )
            )
            AppLogger.d(TAG, "Step 3: ${trace.last()}")
        }

        // Step 4: Headless Web Bridge (Executed for full 15+ carousels or if embed/GraphQL failed)
        preWarm()
        var bridgeResult: ResolveResult? = null
        if (webBridgeResolver != null) {
            val step4Start = System.currentTimeMillis()
            AppLogger.i(TAG, "Attempting Headless Web Bridge for complete extraction...")
            bridgeResult = try {
                webBridgeResolver.resolve(targetUrl, validateCdn)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Headless Web Bridge exception: ${e.message}")
                null
            }
            val step4Dur = System.currentTimeMillis() - step4Start
            val bridgeSuccess = bridgeResult != null && bridgeResult.success && bridgeResult.items.isNotEmpty()

            trace.add(
                com.instadown.assist.resolver.model.StepTrace(
                    stepIndex = stepIndex++,
                    stepName = "Headless Web Bridge",
                    durationMs = step4Dur,
                    success = bridgeSuccess,
                    details = if (bridgeSuccess) "Extracted ${bridgeResult?.items?.size} items via ${bridgeResult?.methodUsed}" else "Failed: ${bridgeResult?.error?.message}"
                )
            )
            AppLogger.i(TAG, "Step 4: ${trace.last()}")

            if (bridgeSuccess && bridgeResult != null) {
                val finalRes = bridgeResult.copy(
                    authorUsername = bridgeResult.authorUsername ?: embedResult?.authorUsername,
                    caption = bridgeResult.caption ?: embedResult?.caption,
                    totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                    diagnosticTrace = trace
                )
                AppLogger.i(TAG, "Resolution completed successfully via Web Bridge in ${finalRes.totalResolutionTimeMs}ms:\n${finalRes.formattedTrace}")
                return finalRes
            }
        }

        // Step 5: WebPage SJS Resolver (Final fallback)
        val step5Start = System.currentTimeMillis()
        val sjsResult = try {
            withTimeoutOrNull(FALLBACK_TIMEOUT_MS) {
                primaryResolver.resolve(targetUrl, validateCdn)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Tier 3 SJS resolver exception: ${e.message}")
            null
        }
        val step5Dur = System.currentTimeMillis() - step5Start
        val sjsSuccess = sjsResult != null && sjsResult.success && sjsResult.items.isNotEmpty()

        trace.add(
            com.instadown.assist.resolver.model.StepTrace(
                stepIndex = stepIndex++,
                stepName = "WebPage SJS Resolver",
                durationMs = step5Dur,
                success = sjsSuccess,
                details = if (sjsSuccess) "Extracted ${sjsResult?.items?.size} items" else "Failed: ${sjsResult?.error?.message ?: "Timed out"}"
            )
        )
        AppLogger.i(TAG, "Step 5: ${trace.last()}")

        if (sjsSuccess && sjsResult != null) {
            val finalRes = sjsResult.copy(
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                diagnosticTrace = trace
            )
            AppLogger.i(TAG, "Resolution completed successfully via SJS in ${finalRes.totalResolutionTimeMs}ms:\n${finalRes.formattedTrace}")
            return finalRes
        }

        // All tiers failed: compile comprehensive diagnostic trace
        val finalResult = ResolveResult(
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
                message = "All resolution tiers failed to extract media streams",
                rawDetails = trace.joinToString(" -> ") { "${it.stepName}: ${if (it.success) "OK" else "FAIL"}" }
            ),
            diagnosticTrace = trace
        )
        AppLogger.e(TAG, "All resolution tiers failed for $url:\n${finalResult.formattedTrace}")
        return finalResult
    }
}
