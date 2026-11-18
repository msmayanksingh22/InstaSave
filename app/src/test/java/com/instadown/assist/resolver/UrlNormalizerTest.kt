package com.instadown.assist.resolver

import org.junit.Assert.*
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun testStandardPostUrl() {
        val url = "https://www.instagram.com/p/Dcyf-FPo5kl/"
        val normalized = UrlNormalizer.normalize(url)
        assertNotNull(normalized)
        assertEquals("Dcyf-FPo5kl", normalized!!.shortcode)
        assertEquals("https://www.instagram.com/p/Dcyf-FPo5kl/", normalized.canonicalUrl)
    }

    @Test
    fun testReelUrl() {
        val url = "https://www.instagram.com/reel/DFK8j2sN123/?igsh=MWQ1eA=="
        val normalized = UrlNormalizer.normalize(url)
        assertNotNull(normalized)
        assertEquals("DFK8j2sN123", normalized!!.shortcode)
        assertEquals("reel", normalized.sourcePathType)
        assertEquals("https://www.instagram.com/reel/DFK8j2sN123/", normalized.canonicalUrl)
        assertTrue(normalized.queryParams.containsKey("igsh"))
    }

    @Test
    fun testSharePostUrl() {
        val url = "https://www.instagram.com/share/p/C9xyzABC123/?stkn=abc12345"
        val normalized = UrlNormalizer.normalize(url)
        assertNotNull(normalized)
        assertEquals("C9xyzABC123", normalized!!.shortcode)
        assertEquals("https://www.instagram.com/p/C9xyzABC123/", normalized.canonicalUrl)
    }

    @Test
    fun testShareReelUrl() {
        val url = "https://www.instagram.com/share/reel/C890123xyz/"
        val normalized = UrlNormalizer.normalize(url)
        assertNotNull(normalized)
        assertEquals("C890123xyz", normalized!!.shortcode)
        assertEquals("https://www.instagram.com/reel/C890123xyz/", normalized.canonicalUrl)
    }

    @Test
    fun testDirectShareUrl() {
        val url = "https://www.instagram.com/share/Dcyf-FPo5kl/"
        val normalized = UrlNormalizer.normalize(url)
        assertNotNull(normalized)
        assertEquals("Dcyf-FPo5kl", normalized!!.shortcode)
        assertEquals("https://www.instagram.com/p/Dcyf-FPo5kl/", normalized.canonicalUrl)
    }

    @Test
    fun testTextWithEmbeddedInstagramUrl() {
        val sharedText = "Look at this amazing recipe: https://www.instagram.com/reel/DFK8j2sN123/?utm_source=ig_web from Chef"
        val normalized = UrlNormalizer.normalize(sharedText)
        assertNotNull(normalized)
        assertEquals("DFK8j2sN123", normalized!!.shortcode)
        assertEquals("https://www.instagram.com/reel/DFK8j2sN123/", normalized.canonicalUrl)
    }

    @Test
    fun testShortcodeToMediaId() {
        val shortcode = "Dcyf-FPo5kl"
        val pk = UrlNormalizer.shortcodeToMediaId(shortcode)
        assertNotNull(pk)
        assertTrue(pk!! > 0L)
    }
}
