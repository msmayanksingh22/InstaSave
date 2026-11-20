package com.instadown.assist.resolver

import com.instadown.assist.resolver.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class ResolvedMediaItem(
    val url: String,
    val isVideo: Boolean,
    val thumbnailUrl: String?,
    val width: Int,
    val height: Int
)

sealed interface FastResolveOutcome {
    data class Success(val shortcode: String, val items: List<ResolvedMediaItem>) : FastResolveOutcome
    data class FallbackNeeded(val reason: String, val httpCode: Int = 0) : FastResolveOutcome
}

/**
 * Tier 0 Zero-WebView Resolver: Queries Instagram's public web GraphQL endpoint directly over HTTP/2.
 * Fails fast on 302/401/429 so CompositeInstagramResolver drops immediately into HeadlessWebBridgeResolver.
 */
class DirectOkHttpInstagramResolver(
    private val client: OkHttpClient = SharedHttpEngine.client
) : InstagramResolver {

    companion object {
        private const val IG_WEB_APP_ID = "936619743392459"
        private const val IG_ASBD_ID = "129477"
        // Public web PolarisPostActionLoadPostQueryQuery doc_id
        private const val GRAPHQL_DOC_ID = "8845758582119845"
    }

    override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult {
        val startTime = System.currentTimeMillis()
        val normalized = UrlNormalizer.normalize(url)
        val shortcode = normalized?.shortcode

        if (shortcode.isNullOrBlank()) {
            return ResolveResult(
                success = false,
                originalInputUrl = url,
                canonicalUrl = null,
                shortcode = null,
                sourcePathType = null,
                postType = null,
                items = emptyList(),
                methodUsed = "DirectOkHttpInstagramResolver",
                totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                error = ResolveError(
                    code = ResolveErrorCode.UNSUPPORTED_URL,
                    message = "Could not parse Instagram shortcode from URL"
                )
            )
        }

        return when (val outcome = resolveByShortcode(shortcode)) {
            is FastResolveOutcome.Success -> {
                val resolvedMediaList = outcome.items.mapIndexed { index, item ->
                    val mediaType = if (item.isVideo) MediaType.VIDEO else MediaType.IMAGE
                    val candidate = MediaCandidate(
                        url = item.url,
                        width = item.width.takeIf { it > 0 },
                        height = item.height.takeIf { it > 0 },
                        mimeType = if (item.isVideo) "video/mp4" else "image/jpeg",
                        isSelected = true,
                        selectionReason = "DirectOkHttpGraphQL"
                    )
                    ResolvedMedia(
                        index = index,
                        mediaType = mediaType,
                        mediaUrl = item.url,
                        thumbnailUrl = item.thumbnailUrl,
                        width = item.width.takeIf { it > 0 },
                        height = item.height.takeIf { it > 0 },
                        durationMs = null,
                        bitRate = null,
                        qualityClassification = "ORIGINAL_CDN",
                        selectionReason = "DirectOkHttpGraphQL",
                        candidates = listOf(candidate),
                        cdnValidation = null
                    )
                }

                val postType = when {
                    resolvedMediaList.size > 1 -> PostType.CAROUSEL
                    resolvedMediaList.firstOrNull()?.mediaType == MediaType.VIDEO -> PostType.REEL
                    else -> PostType.PHOTO
                }

                ResolveResult(
                    success = true,
                    originalInputUrl = url,
                    canonicalUrl = "https://www.instagram.com/p/$shortcode/",
                    shortcode = shortcode,
                    sourcePathType = normalized.sourcePathType,
                    postType = postType,
                    items = resolvedMediaList,
                    methodUsed = "DirectOkHttpInstagramResolver",
                    totalResolutionTimeMs = System.currentTimeMillis() - startTime
                )
            }
            is FastResolveOutcome.FallbackNeeded -> {
                ResolveResult(
                    success = false,
                    originalInputUrl = url,
                    canonicalUrl = normalized.canonicalUrl,
                    shortcode = shortcode,
                    sourcePathType = normalized.sourcePathType,
                    postType = null,
                    items = emptyList(),
                    methodUsed = "DirectOkHttpInstagramResolver",
                    totalResolutionTimeMs = System.currentTimeMillis() - startTime,
                    error = ResolveError(
                        code = if (outcome.httpCode == 429) ResolveErrorCode.RATE_LIMITED else ResolveErrorCode.AUTH_REQUIRED,
                        message = outcome.reason,
                        rawDetails = "HTTP ${outcome.httpCode}"
                    )
                )
            }
        }
    }

    suspend fun resolveByShortcode(
        shortcode: String,
        docId: String = GRAPHQL_DOC_ID
    ): FastResolveOutcome = withContext(Dispatchers.IO) {
        val variables = JSONObject().apply {
            put("shortcode", shortcode)
            put("fetch_tagged_user_count", JSONObject.NULL)
            put("hoisted_comment_id", JSONObject.NULL)
            put("hoisted_reply_id", JSONObject.NULL)
        }.toString()

        val formBody = FormBody.Builder()
            .add("av", "0")
            .add("__d", "www")
            .add("__user", "0")
            .add("__a", "1")
            .add("lsd", "AVqbxe3J_YA")
            .add("fb_api_caller_class", "RelayModern")
            .add("fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery")
            .add("variables", variables)
            .add("doc_id", docId)
            .build()

        val request = Request.Builder()
            .url("https://www.instagram.com/graphql/query")
            .post(formBody)
            .header("User-Agent", NetworkPrewarmer.DESKTOP_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")
            .header("X-IG-App-ID", IG_WEB_APP_ID)
            .header("X-ASBD-ID", IG_ASBD_ID)
            .header("X-FB-LSD", "AVqbxe3J_YA")
            .header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .build()

        try {
            client.newCall(request).apply {
                timeout().timeout(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.execute().use { response ->
                if (response.code in 300..399 || response.code == 401 || response.code == 429) {
                    com.instadown.assist.util.AppLogger.d("DirectOkHttpResolver", "GraphQL unauthenticated challenge: HTTP ${response.code}")
                    return@withContext FastResolveOutcome.FallbackNeeded(
                        reason = "Auth/rate challenge",
                        httpCode = response.code
                    )
                }
                if (!response.isSuccessful) {
                    return@withContext FastResolveOutcome.FallbackNeeded("HTTP ${response.code}", response.code)
                }

                val rawBody = response.body?.string().orEmpty()
                if (rawBody.isBlank() || !rawBody.trimStart().startsWith("{")) {
                    return@withContext FastResolveOutcome.FallbackNeeded("Non-JSON response", response.code)
                }

                val items = parseGraphQlMedia(JSONObject(rawBody))
                if (items.isEmpty()) {
                    FastResolveOutcome.FallbackNeeded("Empty media node", response.code)
                } else {
                    FastResolveOutcome.Success(shortcode, items)
                }
            }
        } catch (e: IOException) {
            FastResolveOutcome.FallbackNeeded("Network I/O: ${e.message}")
        }
    }

    internal fun parseGraphQlMedia(root: JSONObject): List<ResolvedMediaItem> {
        val media = root.optJSONObject("data")?.optJSONObject("xdt_shortcode_media") ?: return emptyList()
        val sidecar = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        if (sidecar != null && sidecar.length() > 0) {
            return buildList(sidecar.length()) {
                for (i in 0 until sidecar.length()) {
                    extractSingleNode(sidecar.getJSONObject(i).getJSONObject("node"))?.let(::add)
                }
            }
        }
        return listOfNotNull(extractSingleNode(media))
    }

    private fun extractSingleNode(node: JSONObject): ResolvedMediaItem? {
        val isVideo = node.optBoolean("is_video", false)
        val displayUrl = node.optString("display_url").takeIf { it.isNotBlank() }
        val lowResThumb = extractLowResThumbnail(node) ?: displayUrl
        val mediaUrl = if (isVideo) {
            node.optString("video_url").takeIf { it.isNotBlank() } ?: return null
        } else {
            val resources = node.optJSONArray("display_resources")
            resources?.optJSONObject(resources.length() - 1)?.optString("src")?.takeIf { it.isNotBlank() }
                ?: displayUrl
                ?: return null
        }
        val dims = node.optJSONObject("dimensions")
        return ResolvedMediaItem(
            url = mediaUrl,
            isVideo = isVideo,
            thumbnailUrl = lowResThumb,
            width = dims?.optInt("width", 0) ?: 0,
            height = dims?.optInt("height", 0) ?: 0
        )
    }

    private fun extractLowResThumbnail(node: JSONObject): String? {
        val thumbResources = node.optJSONArray("thumbnail_resources")
        if (thumbResources != null && thumbResources.length() > 0) {
            var bestSrc: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until thumbResources.length()) {
                val res = thumbResources.optJSONObject(i) ?: continue
                val width = res.optInt("config_width", 0)
                val src = res.optString("src").takeIf { it.isNotBlank() } ?: continue
                val diff = kotlin.math.abs(width - 320)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestSrc = src
                }
            }
            if (bestSrc != null) return bestSrc
        }

        val dispResources = node.optJSONArray("display_resources")
        if (dispResources != null && dispResources.length() > 0) {
            var bestSrc: String? = null
            var bestDiff = Int.MAX_VALUE
            for (i in 0 until dispResources.length()) {
                val res = dispResources.optJSONObject(i) ?: continue
                val width = res.optInt("config_width", 0)
                if (width > 0 && width <= 640) {
                    val src = res.optString("src").takeIf { it.isNotBlank() } ?: continue
                    val diff = kotlin.math.abs(width - 320)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestSrc = src
                    }
                }
            }
            if (bestSrc != null) return bestSrc
        }

        return null
    }
}
