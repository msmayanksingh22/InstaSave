package com.instadown.assist.util

import android.content.Context
import android.text.Html
import android.util.Log
import com.instadown.assist.data.db.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class InstagramMetadata(
    val shortcode: String,
    val authorUsername: String? = null,
    val authorDisplayName: String? = null,
    val caption: String? = null,
    val thumbnailPath: String? = null
)

object InstagramMetadataFetcher {

    private const val TAG = "InstaMetadataFetcher"
    private const val USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val twitterTitlePattern = Pattern.compile("<meta[^>]+name=[\"']twitter:title[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val ogTitlePattern = Pattern.compile("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val ogImagePattern = Pattern.compile("<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    private val descPattern = Pattern.compile("<meta[^>]+name=[\"']description[\"'][^>]+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)

    /**
     * Fetches public metadata (creator @username, title/caption, and high-res thumbnail)
     * anonymously and updates the persistent database.
     */
    suspend fun fetchAndStoreMetadata(context: Context, shortcode: String): InstagramMetadata? = withContext(Dispatchers.IO) {
        if (shortcode.isBlank()) return@withContext null

        val targetUrl = "https://www.instagram.com/p/$shortcode/"
        try {
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "fetchMetadata: HTTP ${response.code} for $shortcode")
                return@withContext null
            }

            val html = response.body?.string() ?: return@withContext null

            // 1. Extract and unescape Twitter Title
            var authorUsername: String? = null
            var authorDisplayName: String? = null

            val twitterMatcher = twitterTitlePattern.matcher(html)
            if (twitterMatcher.find()) {
                val rawTitle = twitterMatcher.group(1)?.let { unescapeHtml(it) } ?: ""
                // e.g. "Ridhima Pandit (@ridhimapandit) • Instagram photos" or "Instagram (@instagram) • Instagram reel"
                val usernameMatch = Regex("""\(@?([a-zA-Z0-9._]+)\)""").find(rawTitle)
                authorUsername = usernameMatch?.groupValues?.get(1)

                val displayNameMatch = Regex("""^(.*?)\s*\(@""").find(rawTitle)
                authorDisplayName = displayNameMatch?.groupValues?.get(1)?.trim()
            }

            // Fallback 1: Extract username from description
            // e.g. "218K likes, 5,121 comments - instagram on November 16, 2026: ..."
            // or "100 likes, 2 comments - @username on Date: ..."
            if (authorUsername.isNullOrBlank()) {
                val descMatcher = descPattern.matcher(html)
                if (descMatcher.find()) {
                    val rawDesc = descMatcher.group(1)?.let { unescapeHtml(it) } ?: ""
                    val uMatch1 = Regex("""-\s*@?([a-zA-Z0-9._]+)\s+on\s+[A-Za-z]+""", RegexOption.IGNORE_CASE).find(rawDesc)
                    if (uMatch1 != null) {
                        authorUsername = uMatch1.groupValues[1]
                    } else {
                        val uMatch2 = Regex("""@([a-zA-Z0-9._]+)""").find(rawDesc)
                        authorUsername = uMatch2?.groupValues?.get(1)
                    }
                }
            }

            // Fallback 2: Extract username from og:title
            // e.g. "Instagram on Instagram: ..." or "@username on Instagram: ..."
            if (authorUsername.isNullOrBlank()) {
                val ogTitleMatcher = ogTitlePattern.matcher(html)
                if (ogTitleMatcher.find()) {
                    val rawOgTitle = ogTitleMatcher.group(1)?.let { unescapeHtml(it) } ?: ""
                    val uMatch = Regex("""^@?([a-zA-Z0-9._]+)\s+on\s+Instagram""", RegexOption.IGNORE_CASE).find(rawOgTitle)
                    if (uMatch != null) {
                        authorUsername = uMatch.groupValues[1]
                    }
                }
            }

            // Fallback 3: JSON script inspection for "username":"..."
            if (authorUsername.isNullOrBlank()) {
                val jsonUserMatch = Regex(""""username"\s*:\s*"([a-zA-Z0-9._]+)"""").find(html)
                authorUsername = jsonUserMatch?.groupValues?.get(1)
            }

            // 2. Extract Caption
            var caption: String? = null
            val descM = descPattern.matcher(html)
            if (descM.find()) {
                caption = descM.group(1)?.let { unescapeHtml(it) }
            }

            // 3. Extract and Download Thumbnail
            var thumbnailPath: String? = null
            val imgMatcher = ogImagePattern.matcher(html)
            if (imgMatcher.find()) {
                val rawImgUrl = imgMatcher.group(1)?.let { unescapeHtml(it) }
                if (!rawImgUrl.isNullOrBlank() && (rawImgUrl.startsWith("http://") || rawImgUrl.startsWith("https://"))) {
                    thumbnailPath = downloadAndSaveThumbnail(context, shortcode, rawImgUrl)
                }
            }

            val result = InstagramMetadata(
                shortcode = shortcode,
                authorUsername = authorUsername,
                authorDisplayName = authorDisplayName,
                caption = caption,
                thumbnailPath = thumbnailPath
            )

            // 4. Update SQLite database
            val repo = DownloadRepository.getInstance(context)
            repo.updateMetadata(
                shortcode = shortcode,
                authorUsername = authorUsername,
                caption = caption,
                thumbnailPath = thumbnailPath
            )

            Log.i(TAG, "fetchMetadata: Saved for $shortcode: @$authorUsername ($authorDisplayName), thumb=$thumbnailPath")
            result
        } catch (e: Exception) {
            Log.e(TAG, "fetchMetadata error for $shortcode: ${e.message}")
            null
        }
    }

    private fun downloadAndSaveThumbnail(context: Context, shortcode: String, imageUrl: String): String? {
        return try {
            val thumbDir = File(context.filesDir, "thumbnails")
            if (!thumbDir.exists()) thumbDir.mkdirs()

            val targetFile = File(thumbDir, "${shortcode}.jpg")
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            // Check if older cache directory had it
            val legacyFile = File(File(context.cacheDir, "thumbnails"), "${shortcode}.jpg")
            if (legacyFile.exists() && legacyFile.length() > 0) {
                legacyFile.copyTo(targetFile, overwrite = true)
                return targetFile.absolutePath
            }

            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val bytes = response.body?.bytes() ?: return null
            if (bytes.isEmpty()) return null

            val tempFile = File(thumbDir, "${shortcode}.tmp")
            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            if (tempFile.renameTo(targetFile)) {
                targetFile.absolutePath
            } else {
                tempFile.absolutePath
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to download thumbnail for $shortcode: ${e.message}")
            null
        }
    }

    private fun unescapeHtml(text: String): String {
        val sanitized = text
            .replace("&#064;", "@")
            .replace("&#64;", "@")
            .replace("&amp;#064;", "@")
            .replace("&amp;#64;", "@")
            .replace("&#x2022;", "•")
            .replace("&bull;", "•")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        return try {
            Html.fromHtml(sanitized, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } catch (_: Exception) {
            sanitized.trim()
        }
    }
}
