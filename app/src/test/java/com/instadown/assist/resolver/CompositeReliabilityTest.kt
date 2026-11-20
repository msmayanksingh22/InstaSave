package com.instadown.assist.resolver

import com.instadown.assist.resolver.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompositeReliabilityTest {
    private val url = "https://www.instagram.com/p/C_test123/"
    private fun result(success: Boolean = false, partial: Boolean = false) = ResolveResult(
        success = success, isPartial = partial, originalInputUrl = url,
        canonicalUrl = url, shortcode = "C_test123", sourcePathType = "p",
        postType = PostType.CAROUSEL, items = if (success) listOf(
            ResolvedMedia(0, MediaType.IMAGE, "https://example.com/photo.jpg", null,
                null, null, null, null, "original", "test", emptyList())
        ) else emptyList(), methodUsed = "test", totalResolutionTimeMs = 0
    )
    private fun resolver(block: suspend () -> ResolveResult) = object : InstagramResolver {
        override suspend fun resolve(url: String, validateCdn: Boolean) = block()
    }

    @Test fun carouselTriesNativeBeforeBridge() = runBlocking {
        TierCircuitBreaker.resetAll()
        var nativeCalls = 0
        var bridgeCalls = 0
        val composite = CompositeInstagramResolver(
            fallbackResolver = resolver { result(partial = true) },
            directResolver = resolver { nativeCalls++; result(success = true) },
            webBridgeResolver = resolver { bridgeCalls++; result() },
            primaryResolver = resolver { result() }
        )
        assertTrue(composite.resolve(url, false).success)
        assertEquals(1, nativeCalls)
        assertEquals(0, bridgeCalls)
    }

    @Test fun cancellationDoesNotStartAnotherTier() = runBlocking {
        var downstreamCalls = 0
        val downstream = resolver { downstreamCalls++; result() }
        val composite = CompositeInstagramResolver(
            fallbackResolver = resolver { throw CancellationException("caller stopped") },
            directResolver = downstream, primaryResolver = downstream,
            webBridgeResolver = downstream
        )
        try {
            composite.resolve(url, false)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0, downstreamCalls)
        }
    }
}
