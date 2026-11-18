package com.instadown.assist.resolver

import java.util.regex.Pattern

data class NormalizedUrl(
    val originalUrl: String,
    val canonicalUrl: String,
    val shortcode: String,
    val sourcePathType: String,
    val queryParams: Map<String, String>
)

object UrlNormalizer {

    // Matches:
    // /p/{code}, /reel/{code}, /reels/{code}, /tv/{code}
    // /share/p/{code}, /share/reel/{code}, /share/{code}, /s/{code}
    // Supports domains: instagram.com, instagr.am, with optional http(s)
    private val INSTA_URL_PATTERN = Pattern.compile(
        """(?:https?://(?:www\.)?(?:instagram\.com|instagr\.am))?/(?:share/)?(p|reel|reels|tv|share|s)/([a-zA-Z0-9_-]+)""",
        Pattern.CASE_INSENSITIVE
    )

    private val EMBEDDED_URL_PATTERN = Pattern.compile(
        """https?://(?:www\.)?(?:instagram\.com|instagr\.am)/[^\s"'<>]+""",
        Pattern.CASE_INSENSITIVE
    )

    private val PURE_SHORTCODE_PATTERN = Pattern.compile(
        """^[a-zA-Z0-9_-]{9,16}$"""
    )

    /**
     * Extracts an Instagram link from an arbitrary string (e.g. caption shared via Android share sheet).
     */
    fun extractUrlFromText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val matcher = EMBEDDED_URL_PATTERN.matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(0)
        }
        return if (trimmed.contains("instagram.com") || trimmed.contains("instagr.am")) trimmed else null
    }

    fun normalize(input: String): NormalizedUrl? {
        val extracted = extractUrlFromText(input) ?: input.trim()
        val trimmed = extracted.trim()
        if (trimmed.isEmpty()) return null

        // Check if input is a direct shortcode (e.g. "DfUi8HNDX94")
        if (PURE_SHORTCODE_PATTERN.matcher(trimmed).matches()) {
            val canonical = "https://www.instagram.com/p/$trimmed/"
            return NormalizedUrl(
                originalUrl = canonical,
                canonicalUrl = canonical,
                shortcode = trimmed,
                sourcePathType = "p",
                queryParams = emptyMap()
            )
        }

        val matcher = INSTA_URL_PATTERN.matcher(trimmed)
        if (!matcher.find()) {
            // Check if URL is an Instagram CDN filename containing shortcode (e.g. Instagram_DfUi8HNDX94.jpg)
            val fileCodeMatcher = Pattern.compile("""Instagram_([a-zA-Z0-9_-]{9,16})""", Pattern.CASE_INSENSITIVE).matcher(trimmed)
            if (fileCodeMatcher.find()) {
                val sc = fileCodeMatcher.group(1)!!
                val canonical = "https://www.instagram.com/p/$sc/"
                return NormalizedUrl(
                    originalUrl = canonical,
                    canonicalUrl = canonical,
                    shortcode = sc,
                    sourcePathType = "p",
                    queryParams = emptyMap()
                )
            }
            return null
        }

        val rawPathType = matcher.group(1)?.lowercase() ?: return null
        val shortcode = matcher.group(2) ?: return null

        // Map share aliases to standard canonical types
        val canonicalPathType = when (rawPathType) {
            "reel", "reels" -> "reel"
            "tv" -> "tv"
            "p", "share", "s" -> "p"
            else -> "p"
        }

        // Parse query parameters from original input for diagnostics
        val queryParams = mutableMapOf<String, String>()
        val questionMarkIndex = trimmed.indexOf('?')
        if (questionMarkIndex != -1 && questionMarkIndex < trimmed.length - 1) {
            val queryString = trimmed.substring(questionMarkIndex + 1).split("#")[0]
            val pairs = queryString.split("&")
            for (pair in pairs) {
                val parts = pair.split("=", limit = 2)
                if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                    queryParams[parts[0]] = if (parts.size > 1) parts[1] else ""
                }
            }
        }

        val canonical = "https://www.instagram.com/$canonicalPathType/$shortcode/"

        return NormalizedUrl(
            originalUrl = trimmed,
            canonicalUrl = canonical,
            shortcode = shortcode,
            sourcePathType = rawPathType,
            queryParams = queryParams
        )
    }

    /**
     * Converts an Instagram shortcode to a numeric media_id (pk) if needed.
     * Uses Instagram's base64 encoding scheme.
     */
    fun shortcodeToMediaId(shortcode: String): Long? {
        val base64Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var cleanCode = shortcode
        if (cleanCode.length > 28) {
            cleanCode = cleanCode.substring(0, cleanCode.length - 28)
        }
        var pk = 0L
        for (char in cleanCode) {
            val index = base64Alphabet.indexOf(char)
            if (index == -1) return null
            pk = pk * 64 + index
        }
        return if (pk > 0) pk else null
    }
}
