package com.instadown.assist.resolver

import android.util.Base64
import android.util.Log
import com.instadown.assist.resolver.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class WebPageSjsResolver(
    private val client: OkHttpClient = defaultClient
) : InstagramResolver {

    companion object {
        private const val TAG = "WebPageSjsResolver"

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        private val SJS_PATTERN = Pattern.compile(
            """<script\b[^>]+\bdata-sjs>(\{.+?\})</script>""",
            Pattern.DOTALL
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
                methodUsed = "WebPageSjsResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                error = ResolveError(
                    code = ResolveErrorCode.UNSUPPORTED_URL,
                    message = "Could not parse a valid Instagram shortcode from URL: '$url'"
                )
            )

        val targetSegment = if (normalized.sourcePathType.lowercase() in setOf("reel", "reels", "r")) "reel" else "p"
        val targetUrl = "https://www.instagram.com/$targetSegment/${normalized.shortcode}/"
        val expectedMediaId = UrlNormalizer.shortcodeToMediaId(normalized.shortcode)

        var initialHost: String? = null
        var finalHost: String? = null
        var htmlContent: String? = null

        try {
            val initialUri = URI(targetUrl)
            initialHost = initialUri.host

            val request = Request.Builder()
                .url(targetUrl)
                .addHeader("User-Agent", NetworkPrewarmer.DESKTOP_UA)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-Site", "none")
                .addHeader("Upgrade-Insecure-Requests", "1")
                .build()

            client.newCall(request).apply {
                // Coroutine timeouts alone cannot interrupt blocking socket reads.
                timeout().timeout(2500, TimeUnit.MILLISECONDS)
            }.execute().use { response ->
                val finalUrl = response.request.url.toString()
                finalHost = URI(finalUrl).host

                // Check for redirect to login or check-point
                if (finalUrl.contains("/accounts/login/") || finalUrl.contains("/challenge/")) {
                    return@withContext ResolveResult(
                        success = false,
                        originalInputUrl = url,
                        canonicalUrl = normalized.canonicalUrl,
                        shortcode = normalized.shortcode,
                        sourcePathType = normalized.sourcePathType,
                        postType = null,
                        items = emptyList(),
                        methodUsed = "WebPageSjsResolver",
                        totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                        initialHost = initialHost,
                        finalHost = finalHost,
                        error = ResolveError(
                            code = ResolveErrorCode.AUTH_REQUIRED,
                            message = "Webpage redirected to login page. Anonymous access is restricted or post is private.",
                            rawDetails = "Redirected to: $finalUrl"
                        )
                    )
                }

                if (!response.isSuccessful) {
                    val code = if (response.code == 404) ResolveErrorCode.CONTENT_NOT_FOUND else ResolveErrorCode.NETWORK_ERROR
                    return@withContext ResolveResult(
                        success = false,
                        originalInputUrl = url,
                        canonicalUrl = normalized.canonicalUrl,
                        shortcode = normalized.shortcode,
                        sourcePathType = normalized.sourcePathType,
                        postType = null,
                        items = emptyList(),
                        methodUsed = "WebPageSjsResolver",
                        totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                        initialHost = initialHost,
                        finalHost = finalHost,
                        error = ResolveError(
                            code = code,
                            message = "HTTP ${response.code} received from Instagram for post '${normalized.shortcode}'"
                        )
                    )
                }

                htmlContent = response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network failure fetching $targetUrl", e)
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "WebPageSjsResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.NETWORK_ERROR,
                    message = "Network error: ${e.javaClass.simpleName}: ${e.message}"
                )
            )
        }

        if (htmlContent.isNullOrBlank()) {
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "WebPageSjsResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.PARSE_ERROR,
                    message = "Empty HTML response received from Instagram."
                )
            )
        }

        if (htmlContent!!.contains("httpErrorPage") || 
            htmlContent!!.contains("PolarisErrorRoute") || 
            htmlContent!!.contains("show_lox_redesigned_404_page") ||
            htmlContent!!.contains("Page Not Found")) {
            Log.w(TAG, "Instagram page is 404 / httpErrorPage for shortcode ${normalized.shortcode}")
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "WebPageSjsResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.CONTENT_NOT_FOUND,
                    message = "Post '${normalized.shortcode}' returned 404 or restricted on Instagram."
                )
            )
        }

        // Layered Discovery: Find all <script data-sjs> blocks
        val matcher = SJS_PATTERN.matcher(htmlContent!!)
        val sjsScripts = mutableListOf<String>()
        while (matcher.find()) {
            matcher.group(1)?.let { sjsScripts.add(it) }
        }

        if (sjsScripts.isEmpty()) {
            val ogResult = tryExtractFromOpenGraphOrJsonLd(htmlContent!!, normalized, startTime, initialHost, finalHost)
            if (ogResult != null && ogResult.success) {
                return@withContext ogResult
            }
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "WebPageSjsResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.PARSE_ERROR,
                    message = "No <script data-sjs> blocks found in Instagram HTML. Format may have changed."
                )
            )
        }

        // Search recursively across all data-sjs scripts for verified media candidates
        var matchedMediaNode: JSONObject? = null
        var identityVerificationMethod: String? = null

        for (script in sjsScripts) {
            // Must contain media indicators to be worth parsing
            if (!script.contains("image_versions2") && !script.contains("video_versions") && !script.contains("carousel_media")) {
                continue
            }

            val jsonObject = try {
                JSONObject(script)
            } catch (e: Exception) {
                // One malformed script must not terminate the entire resolver
                continue
            }

            val result = findMediaNodeWithIdentity(
                obj = jsonObject,
                requestedShortcode = normalized.shortcode,
                expectedMediaId = expectedMediaId,
                depth = 0
            )

            if (result != null) {
                matchedMediaNode = result.node
                identityVerificationMethod = result.verificationMethod
                break
            }
        }

        if (matchedMediaNode == null) {
            val ogResult = tryExtractFromOpenGraphOrJsonLd(htmlContent!!, normalized, startTime, initialHost, finalHost)
            if (ogResult != null && ogResult.success) {
                return@withContext ogResult
            }
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "WebPageSjsResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.UNVERIFIED_IDENTITY,
                    message = "Could not find a media object matching requested shortcode '${normalized.shortcode}' in ${sjsScripts.size} data-sjs blocks."
                )
            )
        }

        // Parse media items from verified media node
        val parsedItems = parseMediaItems(matchedMediaNode)
        if (parsedItems.isEmpty()) {
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = null,
                items = emptyList(),
                methodUsed = "WebPageSjsResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.UNSUPPORTED_MEDIA,
                    message = "Media object found but contained no valid image or video streams."
                )
            )
        }

        // Determine post type and video requirement
        val isVideoPost = matchedMediaNode.optBoolean("is_video", false) ||
                matchedMediaNode.optInt("media_type", 0) == 2 ||
                normalized.sourcePathType.contains("reel") ||
                normalized.sourcePathType.contains("reels")

        // Critical Safety Gate: If this post represents a Reel/Video, but parsedItems contains NO video streams
        // (common when unauthenticated SJS only delivers static preview images), do NOT falsely claim success!
        // Failing allows CompositeInstagramResolver to fall back to HeadlessWebBridgeResolver to fetch the MP4.
        if (isVideoPost && parsedItems.none { it.mediaType == MediaType.VIDEO }) {
            Log.w(TAG, "Media node for ${normalized.shortcode} indicates Video/Reel, but parsedItems contained no MP4 streams. Rejecting partial image so fallback resolvers can obtain MP4.")
            val ogResult = tryExtractFromOpenGraphOrJsonLd(htmlContent!!, normalized, startTime, initialHost, finalHost)
            if (ogResult != null && ogResult.success && ogResult.items.any { it.mediaType == MediaType.VIDEO }) {
                return@withContext ogResult
            }
            return@withContext ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = PostType.REEL,
                items = emptyList(),
                methodUsed = "WebPageSjsResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                error = ResolveError(
                    code = ResolveErrorCode.UNSUPPORTED_MEDIA,
                    message = "Reel video stream not present in web SJS payload. Fallback required."
                )
            )
        }

        // Determine post type
        val postType = when {
            matchedMediaNode.has("carousel_media") -> PostType.CAROUSEL
            parsedItems.any { it.mediaType == MediaType.VIDEO } || isVideoPost -> PostType.REEL
            else -> PostType.PHOTO
        }

        val extractionTimeMs = System.currentTimeMillis() - startTime
        val stageA = StageAResult(
            success = true,
            postType = postType,
            itemsCount = parsedItems.size,
            shortcode = normalized.shortcode,
            identityVerification = identityVerificationMethod ?: "VERIFIED",
            extractionTimeMs = extractionTimeMs
        )

        // Stage B: Lightweight CDN validation if requested
        var finalItems = parsedItems
        var stageB: StageBResult? = null

        if (validateCdn) {
            val stageBStart = System.currentTimeMillis()
            val validatedList = mutableListOf<ResolvedMedia>()
            var allAccessible = true

            for (item in parsedItems) {
                val cdnResult = MediaValidator.validate(item.mediaUrl, item.mediaType)
                if (!cdnResult.isAccessible) {
                    allAccessible = false
                }
                validatedList.add(item.copy(cdnValidation = cdnResult))
            }

            finalItems = validatedList
            stageB = StageBResult(
                success = allAccessible,
                validationTimeMs = System.currentTimeMillis() - stageBStart,
                allAccessible = allAccessible,
                itemsValidated = finalItems.size,
                error = if (!allAccessible) "One or more CDN media streams were not accessible" else null
            )
        }

        val totalTime = System.currentTimeMillis() - startTime

        return@withContext ResolveResult(
            success = true,
            originalInputUrl = url,
            canonicalUrl = normalized.canonicalUrl,
            shortcode = normalized.shortcode,
            sourcePathType = normalized.sourcePathType,
            postType = postType,
            identityVerified = true,
            identityVerificationMethod = identityVerificationMethod,
            items = finalItems,
            methodUsed = "WebPageSjsResolver",
            totalResolutionTimeMs = totalTime,
            stageA = stageA,
            stageB = stageB,
            initialHost = initialHost,
            finalHost = finalHost,
            error = null
        )
    }

    private data class IdentityMatch(
        val node: JSONObject,
        val verificationMethod: String
    )

    /**
     * Recursively searches for a media node that affirmatively establishes identity with the requested post.
     */
    private fun findMediaNodeWithIdentity(
        obj: Any?,
        requestedShortcode: String,
        expectedMediaId: Long?,
        depth: Int
    ): IdentityMatch? {
        if (depth > 25 || obj == null) return null

        when (obj) {
            is JSONObject -> {
                // Check if this object itself has media payloads
                val hasMediaPayload = obj.has("carousel_media") || obj.has("image_versions2") || obj.has("video_versions")

                if (hasMediaPayload) {
                    val verification = checkIdentity(obj, requestedShortcode, expectedMediaId)
                    if (verification != null) {
                        return IdentityMatch(obj, verification)
                    }
                }

                // If this is an 'if_not_gated_logged_out' or 'xig_polaris_media' container, check its children
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = obj.opt(key)
                    val match = findMediaNodeWithIdentity(child, requestedShortcode, expectedMediaId, depth + 1)
                    if (match != null) return match
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    val match = findMediaNodeWithIdentity(obj.opt(i), requestedShortcode, expectedMediaId, depth + 1)
                    if (match != null) return match
                }
            }
        }
        return null
    }

    /**
     * Layered identity check:
     * 1. Explicit shortcode / code field
     * 2. Canonical / permalink field
     * 3. Media ID / PK match
     */
    private fun checkIdentity(node: JSONObject, requestedShortcode: String, expectedMediaId: Long?): String? {
        // 1. Explicit code / shortcode
        val code = node.optString("code", "").ifEmpty { node.optString("shortcode", "") }
        if (code.equals(requestedShortcode, ignoreCase = true)) {
            return "EXPLICIT_CODE_FIELD"
        }

        // 2. Permalink / canonical URL inspection
        val permalink = node.optString("permalink", "").ifEmpty { node.optString("canonical_url", "") }
        if (permalink.isNotBlank() && permalink.contains("/$requestedShortcode")) {
            return "PERMALINK_MATCH"
        }

        // 3. Numeric media ID / PK
        if (expectedMediaId != null) {
            val id = node.optString("id", "")
            val pk = node.optString("pk", "")
            val expectedStr = expectedMediaId.toString()
            val cleanId = when {
                pk.isNotBlank() -> pk
                id.startsWith("POLARIS_") -> id.removePrefix("POLARIS_")
                id.contains("_") -> id.substringBefore("_")
                else -> id
            }
            if (cleanId == expectedStr || id.contains(expectedStr) || pk == expectedStr) {
                return "MEDIA_ID_MATCH"
            }
        }

        return null
    }

    /**
     * Parses all media items from the verified node (Photo, Carousel, or Reel/Video).
     */
    private fun parseMediaItems(mediaNode: JSONObject): List<ResolvedMedia> {
        val items = mutableListOf<ResolvedMedia>()

        // 1. Check for Carousel
        val carouselArray = mediaNode.optJSONArray("carousel_media")
        if (carouselArray != null && carouselArray.length() > 0) {
            for (i in 0 until carouselArray.length()) {
                val slideObj = carouselArray.optJSONObject(i) ?: continue
                val resolvedSlide = parseSingleMediaItem(slideObj, index = i)
                if (resolvedSlide != null) {
                    items.add(resolvedSlide)
                }
            }
            return items
        }

        // 2. Single item (Photo or Video/Reel)
        val singleItem = parseSingleMediaItem(mediaNode, index = 0)
        if (singleItem != null) {
            items.add(singleItem)
        }
        return items
    }

    /**
     * Parses a single slide or top-level post into ResolvedMedia with deterministic quality ranking.
     */
    private fun parseSingleMediaItem(node: JSONObject, index: Int): ResolvedMedia? {
        val mediaTypeInt = node.optInt("media_type", -1)
        val hasVideos = node.has("video_versions") && (node.optJSONArray("video_versions")?.length() ?: 0) > 0
        val isVideo = mediaTypeInt == 2 || hasVideos

        val origW = node.optInt("original_width", 0).takeIf { it > 0 }
        val origH = node.optInt("original_height", 0).takeIf { it > 0 }
        val durationMs = (node.optDouble("video_duration", 0.0) * 1000).toLong().takeIf { it > 0 }

        if (isVideo) {
            val videoArray = node.optJSONArray("video_versions") ?: JSONArray()
            val videoCandidates = mutableListOf<MediaCandidate>()

            for (i in 0 until videoArray.length()) {
                val vObj = videoArray.optJSONObject(i) ?: continue
                val vUrl = vObj.optString("url", "")
                if (vUrl.isBlank()) continue

                val width = vObj.optInt("width", 0).takeIf { it > 0 } ?: extractResolutionFromUrl(vUrl) ?: origW
                val height = vObj.optInt("height", 0).takeIf { it > 0 } ?: origH
                val type = vObj.optInt("type", 0)
                val bitRate = vObj.optLong("bandwidth", 0L).takeIf { it > 0 }

                val pixelCount = if (width != null && height != null) width.toLong() * height.toLong() else 0L
                val score = pixelCount * 1000 + (bitRate ?: 0L)

                videoCandidates.add(
                    MediaCandidate(
                        url = vUrl,
                        width = width,
                        height = height,
                        mimeType = "video/mp4",
                        bitRate = bitRate,
                        tag = "type=$type",
                        rankingScore = score
                    )
                )
            }

            if (videoCandidates.isEmpty()) return null

            // Deterministic quality ranking:
            // 1. Highest pixel count (width * height)
            // 2. Tie-break: highest bitrate
            // 3. Tie-break: first candidate
            val sorted = videoCandidates.sortedWith(
                compareByDescending<MediaCandidate> { (it.width ?: 0) * (it.height ?: 0) }
                    .thenByDescending { it.bitRate ?: 0L }
            )

            val bestCandidate = sorted.first()
            val candidateList = videoCandidates.map {
                if (it.url == bestCandidate.url) {
                    it.copy(
                        isSelected = true,
                        selectionReason = "Highest resolution stream (${bestCandidate.width}x${bestCandidate.height})"
                    )
                } else {
                    it
                }
            }

            // Thumbnail extraction
            val thumbnail = extractBestImageCandidate(node)?.url

            return ResolvedMedia(
                index = index,
                mediaType = MediaType.VIDEO,
                mediaUrl = bestCandidate.url,
                thumbnailUrl = thumbnail,
                width = bestCandidate.width,
                height = bestCandidate.height,
                durationMs = durationMs,
                bitRate = bestCandidate.bitRate,
                qualityClassification = "Highest metadata stream resolution (${bestCandidate.width}x${bestCandidate.height})",
                selectionReason = "Ranked #1 stream based on resolution and stream bandwidth",
                candidates = candidateList
            )
        } else {
            // Image parsing
            val imageCandidates = parseImageCandidates(node, origW, origH)
            if (imageCandidates.isEmpty()) return null

            // Deterministic quality ranking for images:
            // 1. Highest pixel count (width * height)
            // 2. Reject impossible dimensions (<= 0)
            // 3. Reject obvious thumbnails (< 150px) if larger available
            val sorted = imageCandidates.sortedWith(
                compareByDescending<MediaCandidate> { (it.width ?: 0) * (it.height ?: 0) }
            )

            val bestCandidate = sorted.first()
            val candidateList = imageCandidates.map {
                if (it.url == bestCandidate.url) {
                    it.copy(
                        isSelected = true,
                        selectionReason = "Highest resolution image candidate (${bestCandidate.width}x${bestCandidate.height})"
                    )
                } else {
                    it
                }
            }

            val smallestCandidate = sorted.lastOrNull()
            return ResolvedMedia(
                index = index,
                mediaType = MediaType.IMAGE,
                mediaUrl = bestCandidate.url,
                thumbnailUrl = smallestCandidate?.url ?: imageCandidates.lastOrNull()?.url,
                width = bestCandidate.width,
                height = bestCandidate.height,
                durationMs = null,
                bitRate = null,
                qualityClassification = "Highest metadata image resolution (${bestCandidate.width}x${bestCandidate.height})",
                selectionReason = "Highest available pixel dimensions returned in candidate list",
                candidates = candidateList
            )
        }
    }

    private fun parseImageCandidates(node: JSONObject, origW: Int?, origH: Int?): List<MediaCandidate> {
        val candidatesList = mutableListOf<MediaCandidate>()
        val imgVersions = node.optJSONObject("image_versions2") ?: return emptyList()
        val candsArray = imgVersions.optJSONArray("candidates") ?: return emptyList()

        for (i in 0 until candsArray.length()) {
            val cObj = candsArray.optJSONObject(i) ?: continue
            val cUrl = cObj.optString("url", "")
            if (cUrl.isBlank()) continue

            var width = cObj.optInt("width", 0).takeIf { it > 0 }
            var height = cObj.optInt("height", 0).takeIf { it > 0 }

            // If width/height omitted from candidate dict (e.g. carousels), derive from efg or master
            if (width == null || height == null) {
                val tagRes = extractResolutionFromUrl(cUrl)
                if (tagRes != null && origW != null && origH != null && origW > 0) {
                    width = tagRes
                    height = (tagRes.toDouble() / origW * origH).toInt()
                } else if (i == 0 && origW != null && origH != null) {
                    width = origW
                    height = origH
                }
            }

            val score = if (width != null && height != null) width.toLong() * height.toLong() else 0L
            val tag = extractTagFromUrl(cUrl)

            candidatesList.add(
                MediaCandidate(
                    url = cUrl,
                    width = width,
                    height = height,
                    mimeType = "image/jpeg",
                    bitRate = null,
                    tag = tag,
                    rankingScore = score
                )
            )
        }

        return candidatesList
    }

    private fun extractBestImageCandidate(node: JSONObject): MediaCandidate? {
        val candidates = parseImageCandidates(node, null, null)
        return candidates.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
    }

    private fun extractResolutionFromUrl(url: String): Int? {
        // Check for .1440., .1080., .720., .640., etc. in base64 efg or url
        val efgMatch = Pattern.compile("""efg=([a-zA-Z0-9_-]+)""").matcher(url)
        if (efgMatch.find()) {
            val b64 = efgMatch.group(1) ?: ""
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
            try {
                val decoded = String(Base64.decode(padded, Base64.DEFAULT))
                val numMatcher = Pattern.compile("""\.(\d{3,4})\.""").matcher(decoded)
                if (numMatcher.find()) {
                    return numMatcher.group(1)?.toIntOrNull()
                }
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun extractTagFromUrl(url: String): String? {
        val efgMatch = Pattern.compile("""efg=([a-zA-Z0-9_-]+)""").matcher(url)
        if (efgMatch.find()) {
            val b64 = efgMatch.group(1) ?: ""
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
            try {
                val decoded = String(Base64.decode(padded, Base64.DEFAULT))
                val json = JSONObject(decoded)
                val tag = json.optString("vencode_tag", "")
                return if (tag.isNotEmpty()) tag else null
            } catch (ignored: Exception) {}
        }
        return null
    }

    private fun tryExtractFromOpenGraphOrJsonLd(
        html: String,
        normalized: NormalizedUrl,
        startTime: Long,
        initialHost: String?,
        finalHost: String?
    ): ResolveResult? {
        try {
            // 1. Check OpenGraph video
            val videoMatcher = Pattern.compile("""<meta\s+property=["']og:video(?::secure_url)?["']\s+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(html)
            val videoUrl = if (videoMatcher.find()) decodeHtml(videoMatcher.group(1)) else null

            // 2. Check OpenGraph image
            val imageMatcher = Pattern.compile("""<meta\s+property=["']og:image["']\s+content=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE).matcher(html)
            val imageUrl = if (imageMatcher.find()) decodeHtml(imageMatcher.group(1)) else null

            // 3. Check JSON-LD
            val jsonLdMatcher = Pattern.compile("""<script\s+type=["']application/ld\+json["']>(\{.+?\})</script>""", Pattern.DOTALL).matcher(html)
            var jsonLdVideoUrl: String? = null
            var jsonLdImageUrl: String? = null
            if (jsonLdMatcher.find()) {
                val jsonStr = jsonLdMatcher.group(1)
                if (!jsonStr.isNullOrBlank()) {
                    try {
                        val obj = JSONObject(jsonStr)
                        if (obj.has("contentUrl")) jsonLdVideoUrl = obj.getString("contentUrl")
                        if (obj.has("thumbnailUrl")) jsonLdImageUrl = obj.getString("thumbnailUrl")
                    } catch (_: Exception) {}
                }
            }

            val finalVideoUrl = videoUrl ?: jsonLdVideoUrl
            val finalImageUrl = imageUrl ?: jsonLdImageUrl

            if (finalVideoUrl.isNullOrBlank() && finalImageUrl.isNullOrBlank()) {
                return null
            }

            val isVideo = !finalVideoUrl.isNullOrBlank()

            // If the post is an Instagram Reel or Video (either by URL, OG metadata, or JSON-LD),
            // but no video stream was found in OG/JSON-LD, refuse to succeed with only a static image!
            val isVideoHint = normalized.sourcePathType.contains("reel") ||
                    normalized.sourcePathType.contains("reels") ||
                    html.contains("property=\"og:type\" content=\"video") ||
                    html.contains("property='og:type' content='video") ||
                    html.contains("\"@type\":\"VideoObject\"") ||
                    html.contains("\"@type\": \"VideoObject\"")

            if (isVideoHint && !isVideo) {
                Log.w(TAG, "OpenGraph/JSON-LD only contained static image for requested Reel/Video. Refusing partial static thumbnail.")
                return null
            }

            val primaryMediaUrl = if (isVideo) finalVideoUrl!! else finalImageUrl!!
            val mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE
            val postType = if (isVideo || isVideoHint) PostType.REEL else PostType.PHOTO

            val candidate = MediaCandidate(
                url = primaryMediaUrl,
                width = null,
                height = null,
                mimeType = if (isVideo) "video/mp4" else "image/jpeg",
                isSelected = true,
                selectionReason = "Extracted via OpenGraph / JSON-LD metadata"
            )

            val resolvedMedia = ResolvedMedia(
                index = 0,
                mediaType = mediaType,
                mediaUrl = primaryMediaUrl,
                thumbnailUrl = finalImageUrl,
                width = null,
                height = null,
                durationMs = null,
                bitRate = null,
                qualityClassification = "STANDARD",
                selectionReason = "OpenGraph / JSON-LD direct fallback",
                candidates = listOf(candidate)
            )

            return ResolveResult(
                success = true,
                originalInputUrl = normalized.originalUrl,
                canonicalUrl = normalized.canonicalUrl,
                shortcode = normalized.shortcode,
                sourcePathType = normalized.sourcePathType,
                postType = postType,
                identityVerified = true,
                identityVerificationMethod = "OPENGRAPH_JSONLD",
                items = listOf(resolvedMedia),
                methodUsed = "WebPageSjsResolver (OG/JSON-LD)",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                initialHost = initialHost,
                finalHost = finalHost,
                stageA = StageAResult(
                    success = true,
                    postType = postType,
                    itemsCount = 1,
                    shortcode = normalized.shortcode,
                    identityVerification = "OPENGRAPH_JSONLD",
                    extractionTimeMs = System.currentTimeMillis() - startTime
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "OpenGraph/JSON-LD fallback extraction error: ${e.message}")
            return null
        }
    }

    private fun decodeHtml(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }

}
