package com.instadown.assist.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.instadown.assist.resolver.UrlNormalizer
import java.util.regex.Pattern

object ClipboardReader {
    private const val TAG = "ClipboardReader"

    // Legacy fallback regex matching https://www.instagram.com/p/... or /reel/...
    val INSTAGRAM_URL_REGEX: Pattern = Pattern.compile(
        """https?://(?:www\.)?(?:instagram\.com|instagr\.am)/(?:p|reel|reels|share|tv)/([a-zA-Z0-9_-]+)/?.*""",
        Pattern.CASE_INSENSITIVE
    )

    data class ClipResult(
        val text: String,
        val mimeTypes: List<String>,
        val isValidInstagramUrl: Boolean,
        val cleanUrl: String,
        val timestamp: Long = 0L
    )

    fun readCurrentClip(context: Context): ClipResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = try {
            clipboard.primaryClip
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException reading primaryClip: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception reading primaryClip: ${e.message}")
            null
        }

        if (clip == null || clip.itemCount == 0) {
            return ClipResult(
                text = "",
                mimeTypes = emptyList(),
                isValidInstagramUrl = false,
                cleanUrl = "",
                timestamp = 0L
            )
        }

        val description: ClipDescription? = clip.description
        val mimes = mutableListOf<String>()
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                mimes.add(description.getMimeType(i))
            }
        }

        val rawText = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim() ?: ""
        val (isValid, cleanUrl) = validateInstagramUrl(rawText)
        val clipTime = description?.timestamp ?: 0L

        AppLogger.d(TAG, "readCurrentClip: rawText='${rawText.take(60)}', isValid=$isValid, cleanUrl='$cleanUrl', clipTime=$clipTime")

        return ClipResult(
            text = rawText,
            mimeTypes = mimes,
            isValidInstagramUrl = isValid,
            cleanUrl = cleanUrl,
            timestamp = clipTime
        )
    }

    /**
     * Validates and extracts an Instagram post/reel/carousel link from raw text.
     * Uses UrlNormalizer for comprehensive 2026 support (/share/r/, /share/p/, /share/, /s/, /tv/, captions).
     */
    fun validateInstagramUrl(text: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to ""
        
        // 1. Primary: Use 2026 UrlNormalizer (handles share tokens, embedded text, shortcodes)
        val normalized = UrlNormalizer.normalize(text)
        if (normalized != null && normalized.shortcode.isNotBlank()) {
            return true to normalized.originalUrl
        }

        // 2. Secondary fallback: Regex match
        val matcher = INSTAGRAM_URL_REGEX.matcher(text)
        return if (matcher.find()) {
            val url = matcher.group()
            true to url
        } else {
            false to ""
        }
    }
}
