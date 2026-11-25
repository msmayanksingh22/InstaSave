package com.instadown.assist.browser

import java.net.URI

sealed interface DetectedRoute {
    data class MediaPost(
        val canonicalUrl: String,
        val shortcode: String,
        val isReel: Boolean
    ) : DetectedRoute

    data class Profile(
        val username: String,
        val canonicalUrl: String
    ) : DetectedRoute

    data object Other : DetectedRoute
}

/**
 * High-precision route parser for Instagram SPA navigation.
 * Centralizes URL matching for posts, reels, carousels, and profiles.
 */
object InstagramRouteParser {

    private val ALLOWED_HOSTS = setOf(
        "instagram.com",
        "www.instagram.com",
        "m.instagram.com"
    )

    // Matches /p/{code}, /reel/{code}, /reels/{code}, /share/r/{code}, /share/p/{code}
    private val MEDIA_PATH_REGEX = Regex(
        pattern = "^/(?:p|reel|reels|share/r|share/p)/([A-Za-z0-9_-]+)(?:/.*)?$",
        option = RegexOption.IGNORE_CASE
    )

    // Reserved path prefixes that are NOT usernames
    val RESERVED_PATHS = setOf(
        "p", "reel", "reels", "tv", "share", "explore", "accounts",
        "direct", "stories", "about", "legal", "developer", "terms",
        "privacy", "static", "api"
    )

    private val PROFILE_PATH_REGEX = Regex(
        pattern = "^/([A-Za-z0-9._]+)/?$"
    )

    fun isAllowedHost(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val host = try {
            URI(url.trim()).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return host in ALLOWED_HOSTS || ALLOWED_HOSTS.any { host.endsWith(".$it") }
    }

    fun parse(url: String?): DetectedRoute {
        if (url.isNullOrBlank()) return DetectedRoute.Other
        if (!isAllowedHost(url)) return DetectedRoute.Other

        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return DetectedRoute.Other
        }

        val path = uri.path?.trim() ?: return DetectedRoute.Other

        // 1. Check for media post/reel route
        val mediaMatch = MEDIA_PATH_REGEX.find(path)
        if (mediaMatch != null) {
            val shortcode = mediaMatch.groupValues[1]
            val lowerPath = path.lowercase()
            val isReel = lowerPath.contains("/reel") || lowerPath.startsWith("/share/r/")
            val canonicalUrl = when {
                lowerPath.startsWith("/share/") -> "https://www.instagram.com${path.trimEnd('/')}/"
                isReel -> "https://www.instagram.com/reel/$shortcode/"
                else -> "https://www.instagram.com/p/$shortcode/"
            }
            return DetectedRoute.MediaPost(
                canonicalUrl = canonicalUrl,
                shortcode = shortcode,
                isReel = isReel
            )
        }

        // 2. Check for profile route
        val profileMatch = PROFILE_PATH_REGEX.find(path)
        if (profileMatch != null) {
            val username = profileMatch.groupValues[1].lowercase()
            if (username !in RESERVED_PATHS) {
                return DetectedRoute.Profile(
                    username = username,
                    canonicalUrl = "https://www.instagram.com/$username/"
                )
            }
        }

        return DetectedRoute.Other
    }

    private val USERNAME_REGEX = Regex("^[A-Za-z0-9._]{1,30}$")

    /**
     * Returns true if [input] is either a supported Instagram URL or a valid creator handle/@username.
     */
    fun isValidProfileInput(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return isAllowedHost(trimmed)
        }
        val clean = trimmed.removePrefix("@").trim()
        return USERNAME_REGEX.matches(clean) && clean.lowercase() !in RESERVED_PATHS
    }

    /**
     * Resolves an arbitrary input string (e.g. "@username", "username", post URL, or profile URL)
     * into a clean Instagram URL without truncating post shortcodes or /share/ tokens.
     */
    fun resolveProfileUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return "https://www.instagram.com/"

        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val uri = try { URI(trimmed) } catch (_: Exception) { null }
            val path = uri?.path?.trim('/') ?: ""
            val segments = path.split('/').filter { it.isNotBlank() }

            // Preserve /share/ opaque links so UrlNormalizer can probe their redirect when saving
            if (segments.size >= 2 && segments[0].lowercase() == "share") {
                return "https://www.instagram.com/${segments.joinToString("/")}/"
            }

            // Preserve /reel/{code} vs /p/{code}
            if (segments.size >= 2 && segments[0].lowercase() in setOf("p", "reel", "reels", "tv")) {
                val targetPrefix = if (segments[0].lowercase() in setOf("reel", "reels")) "reel" else "p"
                return "https://www.instagram.com/$targetPrefix/${segments[1]}/"
            }

            // If it's a profile URL (/{username}/)
            val firstClean = segments.firstOrNull()?.removePrefix("@") ?: ""
            if (firstClean.isNotBlank() && firstClean.lowercase() !in RESERVED_PATHS) {
                return "https://www.instagram.com/$firstClean/"
            }
            return trimmed
        }

        val cleanUsername = trimmed.removePrefix("@").trim()
        return if (USERNAME_REGEX.matches(cleanUsername)) {
            "https://www.instagram.com/$cleanUsername/"
        } else {
            "https://www.instagram.com/"
        }
    }

    /**
     * Extracts username from a profile URL or input string.
     */
    fun extractUsername(input: String): String {
        val trimmed = input.trim()
        if (!trimmed.contains("/") && !trimmed.contains("http", ignoreCase = true)) {
            val clean = trimmed.removePrefix("@").trim()
            return if (USERNAME_REGEX.matches(clean) && clean.lowercase() !in RESERVED_PATHS) clean else ""
        }
        val uri = try { URI(trimmed) } catch (_: Exception) { null }
        val path = uri?.path?.trim('/') ?: trimmed.substringAfterLast('/')
        val segments = path.split('/').filter { it.isNotBlank() }
        val first = segments.firstOrNull()?.removePrefix("@") ?: ""
        if (first.isNotBlank() && first.lowercase() !in RESERVED_PATHS) {
            return first
        }
        return ""
    }
}
