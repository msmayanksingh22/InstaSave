package com.instadown.assist.browser

import java.net.URI

sealed interface DetectedRoute {
    data class MediaPost(
        val canonicalUrl: String,
        val shortcode: String,
        val isReel: Boolean,
        val authorHint: String? = null
    ) : DetectedRoute

    data class Profile(
        val username: String,
        val canonicalUrl: String
    ) : DetectedRoute

    data class Feed(
        val canonicalUrl: String,
        val isReelsTab: Boolean = false
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
        "m.instagram.com",
        "i.instagram.com",
        "accountscenter.instagram.com"
    )

    private val AUTH_PATH_PREFIXES = listOf(
        "/accounts/",
        "/challenge/",
        "/two_factor",
        "/consent/",
        "/oauth/"
    )

    // Matches /share/r/{code} or /share/p/{code}
    private val SHARE_MEDIA_PATH_REGEX = Regex(
        pattern = "^/(share/(?:r|p))/([A-Za-z0-9_-]+)(?:/.*)?$",
        option = RegexOption.IGNORE_CASE
    )

    // Matches /p/{code}, /reel/{code}, /reels/{code}, /tv/{code}, /reels/videos/{code},
    // as well as logged-in creator-prefixed /{username}/p/{code} & /{username}/reel/{code}
    private val MEDIA_PATH_REGEX = Regex(
        pattern = "^/(?:([A-Za-z0-9._]+)/)?(p|reel|reels/videos|reels|tv)/([A-Za-z0-9_-]+)(?:/.*)?$",
        option = RegexOption.IGNORE_CASE
    )

    // Reserved subpaths under /reels/ that are pages, not shortcodes
    private val RESERVED_MEDIA_SHORTCODES = setOf("audio", "videos", "effects", "camera")

    // Reserved path prefixes that are NOT usernames
    val RESERVED_PATHS = setOf(
        "p", "reel", "reels", "tv", "share", "explore", "accounts",
        "direct", "stories", "about", "legal", "developer", "terms",
        "privacy", "static", "api"
    )

    private val PROFILE_PATH_REGEX = Regex(
        pattern = "^/([A-Za-z0-9._]+)/?$"
    )

    fun isAuthOrSafetyUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val path = try {
            URI(url.trim()).path?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return AUTH_PATH_PREFIXES.any { path.contains(it) }
    }

    fun isMediaPostInput(input: String): Boolean {
        val resolved = resolveProfileUrl(input)
        return parse(resolved) is DetectedRoute.MediaPost
    }

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
        val lowerPath = path.lowercase()

        // 1. Check /share/r/{token} and /share/p/{token}
        val shareMatch = SHARE_MEDIA_PATH_REGEX.find(path)
        if (shareMatch != null) {
            val token = shareMatch.groupValues[2]
            val isReel = lowerPath.startsWith("/share/r/")
            return DetectedRoute.MediaPost(
                canonicalUrl = "https://www.instagram.com${path.trimEnd('/')}/",
                shortcode = token,
                isReel = isReel,
                authorHint = null
            )
        }

        // 2. Check /p/{code}, /reel/{code}, /reels/{code}, /{user}/p/{code}, /{user}/reel/{code}
        val mediaMatch = MEDIA_PATH_REGEX.find(path)
        if (mediaMatch != null) {
            val possibleUser = mediaMatch.groupValues[1].trim().lowercase()
            val kind = mediaMatch.groupValues[2].lowercase()
            val shortcode = mediaMatch.groupValues[3]
            val authorHint = possibleUser.takeIf { it.isNotBlank() && it !in RESERVED_PATHS }
            if ((possibleUser !in RESERVED_PATHS || possibleUser == "") &&
                shortcode.lowercase() !in RESERVED_MEDIA_SHORTCODES
            ) {
                val isReel = kind.startsWith("reel")
                val canonicalUrl = if (isReel) {
                    "https://www.instagram.com/reel/$shortcode/"
                } else {
                    "https://www.instagram.com/p/$shortcode/"
                }
                return DetectedRoute.MediaPost(
                    canonicalUrl = canonicalUrl,
                    shortcode = shortcode,
                    isReel = isReel,
                    authorHint = authorHint
                )
            }
        }

        // 3. Check Home Feed / Reels tab / Explore multi-post feeds
        if (lowerPath == "/" || lowerPath.isBlank()) {
            return DetectedRoute.Feed("https://www.instagram.com/", isReelsTab = false)
        }
        if (lowerPath == "/reels" || lowerPath == "/reels/" || lowerPath.startsWith("/explore")) {
            return DetectedRoute.Feed("https://www.instagram.com${path.trimEnd('/')}/", isReelsTab = lowerPath.startsWith("/reels"))
        }

        // 4. Check for profile route
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
                val code = if (segments[0].lowercase() == "reels" && segments[1].lowercase() == "videos" && segments.size >= 3) {
                    segments[2]
                } else {
                    segments[1]
                }
                return "https://www.instagram.com/$targetPrefix/$code/"
            }

            // Preserve logged-in creator-prefixed /{username}/p/{code} or /{username}/reel/{code}
            if (segments.size >= 3 && segments[1].lowercase() in setOf("p", "reel", "reels", "tv")) {
                val targetPrefix = if (segments[1].lowercase() in setOf("reel", "reels")) "reel" else "p"
                return "https://www.instagram.com/$targetPrefix/${segments[2]}/"
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
