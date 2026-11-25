package com.instadown.assist.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramRouteParserTest {

    @Test
    fun parseDetectsPostsReelsShareLinksAndProfiles() {
        val post = InstagramRouteParser.parse("https://www.instagram.com/p/DfhVhc5ESRv/")
        assertTrue(post is DetectedRoute.MediaPost)
        post as DetectedRoute.MediaPost
        assertEquals("DfhVhc5ESRv", post.shortcode)
        assertFalse(post.isReel)
        assertEquals("https://www.instagram.com/p/DfhVhc5ESRv/", post.canonicalUrl)

        val reel = InstagramRouteParser.parse("https://www.instagram.com/reel/DFK8j2sN123/")
        assertTrue(reel is DetectedRoute.MediaPost)
        reel as DetectedRoute.MediaPost
        assertEquals("DFK8j2sN123", reel.shortcode)
        assertTrue(reel.isReel)
        assertEquals("https://www.instagram.com/reel/DFK8j2sN123/", reel.canonicalUrl)

        val shareReel = InstagramRouteParser.parse("https://www.instagram.com/share/r/ABC123xyz/")
        assertTrue(shareReel is DetectedRoute.MediaPost)
        shareReel as DetectedRoute.MediaPost
        assertTrue(shareReel.isReel)
        assertEquals("https://www.instagram.com/share/r/ABC123xyz/", shareReel.canonicalUrl)

        val profile = InstagramRouteParser.parse("https://www.instagram.com/anjaliandsnehal/")
        assertTrue(profile is DetectedRoute.Profile)
        assertEquals("anjaliandsnehal", (profile as DetectedRoute.Profile).username)

        val login = InstagramRouteParser.parse("https://www.instagram.com/accounts/login/")
        assertEquals(DetectedRoute.Other, login)
    }

    @Test
    fun resolveProfileUrlPreservesShortcodesAndShareTokens() {
        assertEquals(
            "https://www.instagram.com/anjaliandsnehal/",
            InstagramRouteParser.resolveProfileUrl("@anjaliandsnehal")
        )
        assertEquals(
            "https://www.instagram.com/p/DfhVhc5ESRv/",
            InstagramRouteParser.resolveProfileUrl("https://www.instagram.com/p/DfhVhc5ESRv/?igsh=abc")
        )
        assertEquals(
            "https://www.instagram.com/reel/DFK8j2sN123/",
            InstagramRouteParser.resolveProfileUrl("https://www.instagram.com/reel/DFK8j2sN123/")
        )
        assertEquals(
            "https://www.instagram.com/share/r/OpaqueToken1/",
            InstagramRouteParser.resolveProfileUrl("https://www.instagram.com/share/r/OpaqueToken1/")
        )
    }

    @Test
    fun isValidProfileInputRejectsRandomClipboardTextAndExternalUrls() {
        assertTrue(InstagramRouteParser.isValidProfileInput("@anjaliandsnehal"))
        assertTrue(InstagramRouteParser.isValidProfileInput("natgeo"))
        assertTrue(InstagramRouteParser.isValidProfileInput("https://www.instagram.com/anjaliandsnehal/"))
        assertTrue(InstagramRouteParser.isValidProfileInput("https://www.instagram.com/p/DfhVhc5ESRv/"))

        assertFalse(InstagramRouteParser.isValidProfileInput(""))
        assertFalse(InstagramRouteParser.isValidProfileInput("Buy milk today"))
        assertFalse(InstagramRouteParser.isValidProfileInput("https://anonyig.com/en2/"))
        assertFalse(InstagramRouteParser.isValidProfileInput("https://google.com/search"))
    }
}
