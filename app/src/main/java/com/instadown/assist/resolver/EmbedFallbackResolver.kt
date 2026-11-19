package com.instadown.assist.resolver

import android.util.Log
import com.instadown.assist.resolver.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.regex.Pattern

class EmbedFallbackResolver(
    private val client: OkHttpClient = WebPageSjsResolver.defaultClient
) : InstagramResolver {

    companion object {
        private const val TAG = "EmbedFallbackResolver"

        private val EMBED_IMAGE_PATTERN = Pattern.compile(
            """(?:\"display_url\"|\"thumbnail_src\")\s*:\s*\"([^\"]+)\""""
        )
        private val EMBED_VIDEO_PATTERN = Pattern.compile(
            """\"video_url\"\s*:\s*\"([^\"]+)\""""
        )
    }

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

        val embedUrl = "https://www.instagram.com/p/${normalized.shortcode}/embed/captioned/"
        var initialHost: String? = null
        var finalHost: String? = null
        var html: String? = null

        try {
            initialHost = URI(embedUrl).host
            val request = Request.Builder()
                .url(embedUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                finalHost = URI(finalUrl).host
                if (!response.isSuccessful) {
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
                            code = if (response.code == 404) ResolveErrorCode.CONTENT_NOT_FOUND else ResolveErrorCode.NETWORK_ERROR,
                            message = "Embed page returned HTTP ${response.code}"
                        )
                    )
                }
                html = response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Embed fetch failed", e)
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
                    code = ResolveErrorCode.NETWORK_ERROR,
                    message = "Embed request exception: ${e.message}"
                )
            )
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
                    code = ResolveErrorCode.PARSE_ERROR,
                    message = "Empty embed response"
                )
            )
        }

        // Check if embed indicates error or 404 page
        if (html!!.contains("httpErrorPage") || html!!.contains("Page Not Found")) {
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
                    message = "Embed page displayed error/not found for '${normalized.shortcode}'"
                )
            )
        }

        // Search for video URL first
        val vidMatcher = EMBED_VIDEO_PATTERN.matcher(html!!)
        var videoUrl: String? = null
        if (vidMatcher.find()) {
            videoUrl = vidMatcher.group(1)?.replace("\\u0026", "&")?.replace("&amp;", "&")
        }

        // Search for image URL
        val imgMatcher = EMBED_IMAGE_PATTERN.matcher(html!!)
        var imageUrl: String? = null
        if (imgMatcher.find()) {
            imageUrl = imgMatcher.group(1)?.replace("\\u0026", "&")?.replace("&amp;", "&")
        }

        val isReelSource = normalized.sourcePathType.contains("reel") || normalized.sourcePathType.contains("reels")

        // Carousel Detection: Check for edge_sidecar_to_children or carousel indicators in embed payload
        val isCarousel = html!!.contains("edge_sidecar_to_children") ||
                html!!.contains("GraphSidecar") ||
                html!!.contains("\"is_carousel\":true") ||
                html!!.contains("\"is_carousel\": true") ||
                html!!.contains("carousel_media") ||
                html!!.contains("CarouselIndicator") ||
                html!!.contains("CarouselLoaded") ||
                html!!.contains("EmbeddedMediaCarousel") ||
                html!!.contains("EmbedSidecar") ||
                html!!.contains("class=\"EmbedSidecar") ||
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
                finalHost = finalHost
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
                finalHost = finalHost
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
