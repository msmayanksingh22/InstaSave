package com.instadown.assist.resolver

import android.util.Log
import com.instadown.assist.resolver.model.*
import com.instadown.assist.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class EmbedFallbackResolver(
    private val client: OkHttpClient = defaultFastClient
) : InstagramResolver {

    companion object {
        private const val TAG = "EmbedFallbackResolver"

        val defaultFastClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .readTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .build()
        }

        private fun unescapeUrl(raw: String): String {
            var s = raw
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("\\u0025", "%")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
            s = s.replace(Regex("""\\+/"""), "/")
            s = s.replace(Regex("""\\+""""), "\"")
            return s.trim()
        }

        private val EMBED_VIDEO_PATTERNS = listOf(
            Pattern.compile("""\\*\"video_url\\*\"\s*:\s*\\*\"([^\"\\]*(?:\\.[^\"\\]*)*)\\*\""""),
            Pattern.compile("""video_url[\\\"'\s]*:[\\\"'\s]*(https?:[^\s\"']+)"""),
            Pattern.compile("""\\*\"playable_url(?:_quality_hd)?\\*\"\s*:\s*\\*\"([^\"\\]*(?:\\.[^\"\\]*)*)\\*\""""),
            Pattern.compile("""(https?:[\\/]+[^\"'\s<>]+\.mp4[^\"'\s<>]*)"""),
            Pattern.compile("""<video[^>]+src=[\"']([^\"']+)[\"']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""<source[^>]+src=[\"']([^\"']+)[\"']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""\\*\"video_versions\\*\"\s*:\s*\[\s*\{\s*\\*\"url\\*\"\s*:\s*\\*\"([^\"\\]+?)\\*\"""")
        )

        private val EMBED_IMAGE_PATTERNS = listOf(
            Pattern.compile("""<img[^>]+class=[\"'][^\"']*EmbeddedMediaImage[^\"']*[\"'][^>]+src=[\"']([^\"']+)[\"']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""\\*\"(?:display_url|thumbnail_src)\\*\"\s*:\s*\\*\"([^\"\\]*(?:\\.[^\"\\]*)*)\\*\""""),
            Pattern.compile("""(?:display_url|thumbnail_src)[\\\"'\s]*:[\\\"'\s]*(https?:[^\s\"']+)"""),
            Pattern.compile("""(https?:[\\/]+[^\"'\s<>]*(?:cdninstagram\.com|fbcdn\.net)[^\"'\s<>]+\.jpg[^\"'\s<>]*)"""),
            Pattern.compile("""<img[^>]+src=[\"'](https?://[^\"'\s<>]*(?:cdninstagram\.com|fbcdn\.net)[^\"'\s<>]*)[\"']""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""\\*\"image_versions2\\*\"\s*:\s*\{\s*\\*\"candidates\\*\"\s*:\s*\[\s*\{\s*\\*\"url\\*\"\s*:\s*\\*\"([^\"\\]+?)\\*\"""")
        )

        private val EMBED_AUTHOR_PATTERNS = listOf(
            Pattern.compile("""\\*\"username\\*\"\s*:\s*\\*\"([a-zA-Z0-9._-]+)\\*\""""),
            Pattern.compile("""username[\\\"'\s]*:[\\\"'\s]*\\*\"([a-zA-Z0-9._-]+)\\*\"""")
        )

        private val EMBED_CAPTION_PATTERNS = listOf(
            Pattern.compile("""\\*\"edge_media_to_caption\\*\"\s*:\s*\{\\*"edges\\*"\s*:\s*\[\{\\*"node\\*"\s*:\s*\{\\*"text\\*"\s*:\s*\\*"([^"\\]*(?:\\.[^"\\]*)*)\\*""""),
            Pattern.compile("""class=[\"'][^\"']*Caption[^\"']*[\"'][^>]*>([^<]+)""")
        )

        private fun containsValidMedia(html: String): Boolean {
            if (html.isBlank() ||
                html.contains("<title>Page Not Found") ||
                html.contains("Page Not Found &bull; Instagram") ||
                html.contains("Content Not Found")
            ) return false

            for (pattern in EMBED_VIDEO_PATTERNS) {
                val matcher = pattern.matcher(html)
                if (matcher.find()) {
                    val raw = matcher.group(1) ?: continue
                    val candidate = unescapeUrl(raw)
                    if (candidate.startsWith("http") && (candidate.contains(".mp4") || candidate.contains("fbcdn.net") || candidate.contains("cdninstagram.com"))) {
                        return true
                    }
                }
            }
            for (pattern in EMBED_IMAGE_PATTERNS) {
                val matcher = pattern.matcher(html)
                if (matcher.find()) {
                    val raw = matcher.group(1) ?: continue
                    val candidate = unescapeUrl(raw)
                    if (candidate.startsWith("http")) {
                        return true
                    }
                }
            }
            return false
        }
    }

    private data class ProbeResult(
        val pathType: String,
        val html: String?,
        val httpCode: Int,
        val hasMedia: Boolean
    )

    override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val normalized = UrlNormalizer.normalize(url)
            ?: return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = null,
                shortcode = null,
                sourcePathType = null,
                postType = null,
                items = emptyList(),
                methodUsed = "EmbedFallbackResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                error = ResolveError(
                    code = ResolveErrorCode.UNSUPPORTED_URL,
                    message = "Could not parse Instagram shortcode from URL: '$url'"
                )
            )

        val pathTypesToTry = if (normalized.sourcePathType in listOf("reel", "reels", "r")) {
            listOf("reel", "p")
        } else {
            listOf("p", "reel")
        }

        var initialHost: String? = null
        var finalHost: String? = null
        var html: String? = null
        var lastHttpCode = 0
        var resolvedPathType: String? = null

        // Parallel non-blocking dual-probe race for instant response (<300ms)
        val (winningHtml, winningPathType, httpCode) = run {
            val winner = CompletableDeferred<ProbeResult>()
            val calls = ConcurrentHashMap<String, okhttp3.Call>()
            val completedCount = AtomicInteger(0)
            val fallbackRef = java.util.concurrent.atomic.AtomicReference<ProbeResult?>(null)

            val jobs = pathTypesToTry.map { pathType ->
                CoroutineScope(Dispatchers.IO).launch {
                    val embedUrl = "https://www.instagram.com/$pathType/${normalized.shortcode}/embed/captioned/"
                    var candidateBody: String? = null
                    var code = 0
                    val reqStartTime = System.currentTimeMillis()
                    try {
                        val request = Request.Builder()
                            .url(embedUrl)
                            .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .build()

                        val call = client.newCall(request)
                        calls[pathType] = call
                        call.execute().use { response ->
                            code = response.code
                            if (response.isSuccessful) {
                                candidateBody = response.body?.string()
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.d(TAG, "Embed probe failed for $pathType in ${System.currentTimeMillis() - reqStartTime}ms: ${e.message}")
                    }

                    val hasMedia = candidateBody != null && containsValidMedia(candidateBody!!)
                    val probeResult = ProbeResult(pathType, candidateBody, code, hasMedia)

                    if (hasMedia) {
                        if (winner.complete(probeResult)) {
                            // Cancel all other competing probe requests immediately to eliminate bandwidth & latency waste
                            calls.forEach { (type, call) ->
                                if (type != pathType) {
                                    try { call.cancel() } catch (_: Exception) {}
                                }
                            }
                        }
                    } else {
                        fallbackRef.updateAndGet { current ->
                            if (current == null || (code > 0 && current.httpCode == 0)) {
                                probeResult
                            } else {
                                current
                            }
                        }
                    }

                    if (completedCount.incrementAndGet() >= pathTypesToTry.size) {
                        // All probes finished and no valid media found
                        winner.complete(fallbackRef.get() ?: probeResult)
                    }
                }
            }

            val finalResult = try {
                winner.await()
            } finally {
                jobs.forEach { it.cancel() }
                calls.forEach { (_, call) -> try { call.cancel() } catch (_: Exception) {} }
            }
            Triple(finalResult.html, finalResult.pathType, finalResult.httpCode)
        }

        html = winningHtml
        resolvedPathType = winningPathType
        lastHttpCode = httpCode
        if (!html.isNullOrBlank()) {
            initialHost = "www.instagram.com"
            finalHost = "www.instagram.com"
        }

        if (html.isNullOrBlank()) {
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "EmbedFallbackResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = if (lastHttpCode == 404) ResolveErrorCode.CONTENT_NOT_FOUND else ResolveErrorCode.NETWORK_ERROR,
                    message = if (lastHttpCode > 0) "Embed page returned HTTP $lastHttpCode" else "Failed to load embed page"
                )
            )
        }

        // Check if embed indicates error or 404 page or disabled rich embed
        if (html!!.contains("<title>Page Not Found") ||
            html!!.contains("Page Not Found &bull; Instagram") ||
            html!!.contains("Content Not Found") ||
            (html!!.contains("\"isRichEmbed\":false") && html!!.contains("\"contextJSON\":null"))
        ) {
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "EmbedFallbackResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.CONTENT_NOT_FOUND,
                    message = "Embed page unavailable or rich embed disabled for '${normalized.shortcode}'"
                )
            )
        }

        // Search for video URL first across multiple pattern variants
        var videoUrl: String? = null
        for (pattern in EMBED_VIDEO_PATTERNS) {
            val matcher = pattern.matcher(html!!)
            if (matcher.find()) {
                val raw = matcher.group(1) ?: continue
                val candidate = unescapeUrl(raw)
                if (candidate.startsWith("http") && (candidate.contains(".mp4") || candidate.contains("fbcdn.net") || candidate.contains("cdninstagram.com"))) {
                    videoUrl = candidate
                    break
                }
            }
        }

        // Search for image URL across multiple pattern variants
        var imageUrl: String? = null
        for (pattern in EMBED_IMAGE_PATTERNS) {
            val matcher = pattern.matcher(html!!)
            if (matcher.find()) {
                val raw = matcher.group(1) ?: continue
                val candidate = unescapeUrl(raw)
                if (candidate.startsWith("http")) {
                    imageUrl = candidate
                    break
                }
            }
        }

        // Extract author username and caption
        var authorUsername: String? = null
        for (pattern in EMBED_AUTHOR_PATTERNS) {
            val matcher = pattern.matcher(html!!)
            if (matcher.find()) {
                val raw = matcher.group(1)?.trim()
                if (!raw.isNullOrBlank()) {
                    authorUsername = raw
                    break
                }
            }
        }

        var caption: String? = null
        for (pattern in EMBED_CAPTION_PATTERNS) {
            val matcher = pattern.matcher(html!!)
            if (matcher.find()) {
                val raw = matcher.group(1)?.trim()
                if (!raw.isNullOrBlank()) {
                    caption = unescapeUrl(raw).take(500)
                    break
                }
            }
        }

        val isReelSource = normalized.sourcePathType.contains("reel") || normalized.sourcePathType.contains("reels")

        // Carousel Detection: Check for edge_sidecar_to_children or carousel indicators in embed payload
        val isCarousel = html!!.contains("edge_sidecar_to_children") ||
                html!!.contains("sidecar_to_children") ||
                html!!.contains("\"is_carousel\":true") ||
                html!!.contains("\"is_carousel\": true") ||
                html!!.contains("carousel_media") ||
                html!!.contains("class=\"EmbeddedMediaCarousel") ||
                html!!.contains("class=\"Carousel")

        if (isCarousel) {
            com.instadown.assist.util.AppLogger.i(TAG, "Embed payload represents carousel. Deferring to HeadlessWebBridgeResolver for full extraction.")
            return@withContext ResolveResult(
                success = false,
                isPartial = true,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = PostType.CAROUSEL,
                items = emptyList(),
                methodUsed = "EmbedFallbackResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.PARTIAL_MEDIA,
                    message = "Embed payload represents a multi-item carousel. Deferring to HeadlessWebBridgeResolver for complete 15+ item extraction."
                )
            )
        }

        // If Reel but only partial image found, return PARTIAL_MEDIA
        if (videoUrl == null && isReelSource && imageUrl != null) {
            return@withContext ResolveResult(
                success = false,
                isPartial = true,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = PostType.REEL,
                items = listOf(
                    ResolvedMedia(
                        index = 0,
                        mediaType = MediaType.IMAGE,
                        mediaUrl = imageUrl,
                        thumbnailUrl = imageUrl,
                        width = null,
                        height = null,
                        durationMs = null,
                        bitRate = null,
                        qualityClassification = "Embed thumbnail fallback",
                        selectionReason = "Only thumbnail recovered from embed for Reel",
                        candidates = emptyList()
                    )
                ),
                methodUsed = "EmbedFallbackResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.PARTIAL_MEDIA,
                    message = "Embed page only provided a thumbnail image for this Reel, no playable video stream."
                )
            )
        }

        if (videoUrl != null) {
            val candidate = MediaCandidate(
                url = videoUrl,
                width = null,
                height = null,
                mimeType = "video/mp4",
                isSelected = true,
                selectionReason = "Single video stream from embed"
            )
            val item = ResolvedMedia(
                index = 0,
                mediaType = MediaType.VIDEO,
                mediaUrl = videoUrl,
                thumbnailUrl = imageUrl,
                width = null,
                height = null,
                durationMs = null,
                bitRate = null,
                qualityClassification = "Embed video stream",
                selectionReason = "Extracted from captioned embed payload",
                candidates = listOf(candidate)
            )

            var finalItems = listOf(item)
            var stageB: StageBResult? = null
            if (validateCdn) {
                val cdn = MediaValidator.validate(videoUrl, MediaType.VIDEO)
                finalItems = listOf(item.copy(cdnValidation = cdn))
                stageB = StageBResult(
                    success = cdn.isAccessible,
                    validationTimeMs = 0L,
                    allAccessible = cdn.isAccessible,
                    itemsValidated = 1,
                    error = cdn.notes
                )
            }

            return@withContext ResolveResult(
                success = true,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = PostType.REEL,
                items = finalItems,
                methodUsed = "EmbedFallbackResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                stageA = StageAResult(
                    success = true,
                    postType = PostType.REEL,
                    itemsCount = 1,
                    shortcode = normalized.shortcode,
                    identityVerification = "EMBED_PATH_MATCH",
                    extractionTimeMs = System.currentTimeMillis() - startTime
                ),
                stageB = stageB,
                initialHost = initialHost,
                finalHost = finalHost,
                authorUsername = authorUsername,
                caption = caption
            )
        } else if (imageUrl != null) {
            val candidate = MediaCandidate(
                url = imageUrl,
                width = null,
                height = null,
                mimeType = "image/jpeg",
                isSelected = true,
                selectionReason = "Single display_url from embed"
            )
            val item = ResolvedMedia(
                index = 0,
                mediaType = MediaType.IMAGE,
                mediaUrl = imageUrl,
                thumbnailUrl = imageUrl,
                width = null,
                height = null,
                durationMs = null,
                bitRate = null,
                qualityClassification = "Embed display image",
                selectionReason = "Extracted from captioned embed payload",
                candidates = listOf(candidate)
            )

            var finalItems = listOf(item)
            var stageB: StageBResult? = null
            if (validateCdn) {
                val cdn = MediaValidator.validate(imageUrl, MediaType.IMAGE)
                finalItems = listOf(item.copy(cdnValidation = cdn))
                stageB = StageBResult(
                    success = cdn.isAccessible,
                    validationTimeMs = 0L,
                    allAccessible = cdn.isAccessible,
                    itemsValidated = 1,
                    error = cdn.notes
                )
            }

            return@withContext ResolveResult(
                success = true,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = PostType.PHOTO,
                items = finalItems,
                methodUsed = "EmbedFallbackResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                stageA = StageAResult(
                    success = true,
                    postType = PostType.PHOTO,
                    itemsCount = 1,
                    shortcode = normalized.shortcode,
                    identityVerification = "EMBED_PATH_MATCH",
                    extractionTimeMs = System.currentTimeMillis() - startTime
                ),
                stageB = stageB,
                initialHost = initialHost,
                finalHost = finalHost,
                authorUsername = authorUsername,
                caption = caption
            )
        }

        return@withContext ResolveResult(
            success = false,
            originalInputUrl = url,
            canonicalUrl = normalized.canonicalUrl,
            shortcode = normalized.shortcode,
            sourcePathType = normalized.sourcePathType,
            postType = null,
            items = emptyList(),
            methodUsed = "EmbedFallbackResolver",
            totalResolutionTimeMs = System.currentTimeMillis() - startTime,
            initialHost = initialHost,
            finalHost = finalHost,
            error = ResolveError(
                code = ResolveErrorCode.UNSUPPORTED_MEDIA,
                message = "Neither video_url nor display_url found in embed HTML"
            )
        )
    }
}
