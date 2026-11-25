package com.instadown.assist.browser

import android.content.Context
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.resolver.EmbedFallbackResolver
import com.instadown.assist.resolver.UrlNormalizer
import com.instadown.assist.util.AppLogger
import com.instadown.assist.util.InstagramMetadataFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ResolvedExplorerTarget(
    val loadUrl: String,
    val creatorUsername: String? = null,
    val sourcePostUrl: String? = null,
    val sourceShortcode: String? = null,
    val resolvedFromPost: Boolean = false
)

/**
 * Resolves any Instagram input (@username, profile URL, post URL, reel URL, or /share/r/ token)
 * into a Creator Profile URL whenever [preferCreatorProfile] is requested.
 * Runs 100% anonymously with zero access to WebView login cookies.
 */
object InstagramCreatorResolver {

    private const val TAG = "CreatorResolver"

    suspend fun resolveExplorerTarget(
        context: Context,
        input: String,
        preferCreatorProfile: Boolean
    ): ResolvedExplorerTarget = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        val directUsername = InstagramRouteParser.extractUsername(trimmed)
        if (directUsername.isNotBlank()) {
            return@withContext ResolvedExplorerTarget(
                loadUrl = "https://www.instagram.com/$directUsername/",
                creatorUsername = directUsername,
                sourcePostUrl = null,
                resolvedFromPost = false
            )
        }

        val initialUrl = InstagramRouteParser.resolveProfileUrl(trimmed)
        if (!preferCreatorProfile) {
            return@withContext ResolvedExplorerTarget(
                loadUrl = initialUrl,
                creatorUsername = null,
                sourcePostUrl = if (InstagramRouteParser.isMediaPostInput(trimmed)) initialUrl else null,
                resolvedFromPost = false
            )
        }

        // Canonicalize share tokens (/share/r/, /share/p/) before looking up creator
        val canonical = UrlNormalizer.resolveCanonical(trimmed) ?: UrlNormalizer.normalize(trimmed)
        val postUrl = canonical?.canonicalUrl ?: initialUrl
        val shortcode = canonical?.shortcode.orEmpty()

        if (shortcode.isNotBlank()) {
            // 1. Instant local cache hit (0 ms)
            val cachedAuthor = DownloadRepository.getInstance(context).findAuthorByShortcode(shortcode)
            if (!cachedAuthor.isNullOrBlank()) {
                AppLogger.i(TAG, "Resolved creator @$cachedAuthor from local DB cache for $shortcode")
                return@withContext ResolvedExplorerTarget(
                    loadUrl = "https://www.instagram.com/$cachedAuthor/",
                    creatorUsername = cachedAuthor,
                    sourcePostUrl = postUrl,
                    sourceShortcode = shortcode,
                    resolvedFromPost = true
                )
            }

            // 2. Fast anonymous embed / metadata probe (does NOT use WebView cookies)
            val author = withTimeoutOrNull(3000L) {
                fetchCreatorUsernameAnonymously(context, postUrl, shortcode)
            }
            if (!author.isNullOrBlank()) {
                val cleanHandle = author.removePrefix("@").trim()
                if (cleanHandle.isNotBlank()) {
                    AppLogger.i(TAG, "Resolved creator @$cleanHandle for post $shortcode")
                    return@withContext ResolvedExplorerTarget(
                        loadUrl = "https://www.instagram.com/$cleanHandle/",
                        creatorUsername = cleanHandle,
                        sourcePostUrl = postUrl,
                        sourceShortcode = shortcode,
                        resolvedFromPost = true
                    )
                }
            }
        }

        AppLogger.w(TAG, "Could not resolve creator upfront for '$trimmed'; loading post page with in-DOM author fallback")
        ResolvedExplorerTarget(
            loadUrl = postUrl,
            creatorUsername = null,
            sourcePostUrl = postUrl,
            sourceShortcode = shortcode.ifBlank { null },
            resolvedFromPost = false
        )
    }

    suspend fun resolveAuthorOnly(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        val direct = InstagramRouteParser.extractUsername(url)
        if (direct.isNotBlank()) return@withContext direct
        val canonical = UrlNormalizer.resolveCanonical(url) ?: UrlNormalizer.normalize(url)
        val shortcode = canonical?.shortcode ?: return@withContext null
        val cached = DownloadRepository.getInstance(context).findAuthorByShortcode(shortcode)
        if (!cached.isNullOrBlank()) return@withContext cached
        return@withContext withTimeoutOrNull(2500L) {
            fetchCreatorUsernameAnonymously(context, canonical.canonicalUrl, shortcode)
        }?.removePrefix("@")?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun fetchCreatorUsernameAnonymously(
        context: Context,
        postUrl: String,
        shortcode: String
    ): String? {
        try {
            val embedResult = EmbedFallbackResolver().resolve(postUrl, validateCdn = false)
            val fromEmbed = embedResult.authorUsername?.removePrefix("@")?.trim()
            if (!fromEmbed.isNullOrBlank()) {
                return fromEmbed
            }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Embed author probe notice: ${e.message}")
        }

        return try {
            val meta = InstagramMetadataFetcher.fetchAndStoreMetadata(context, shortcode)
            meta?.authorUsername?.removePrefix("@")?.trim()?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            AppLogger.d(TAG, "Metadata author probe notice: ${e.message}")
            null
        }
    }
}
