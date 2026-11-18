package com.instadown.assist.download

import com.instadown.assist.resolver.model.MediaType
import com.instadown.assist.resolver.model.PostType
import com.instadown.assist.resolver.model.ResolveResult
import com.instadown.assist.resolver.model.ResolvedMedia
import org.junit.Assert.*
import org.junit.Test

class DownloadPlannerTest {

    private fun makeResolvedMedia(
        index: Int,
        mediaType: MediaType,
        url: String,
        width: Int = 1080,
        height: Int = 1080
    ) = ResolvedMedia(
        index = index,
        mediaType = mediaType,
        mediaUrl = url,
        thumbnailUrl = url,
        width = width,
        height = height,
        durationMs = null,
        bitRate = null,
        qualityClassification = "BEST",
        selectionReason = "test",
        candidates = emptyList()
    )

    private fun makeResolveResult(
        shortcode: String,
        postType: PostType,
        items: List<ResolvedMedia>
    ) = ResolveResult(
        success = true,
        isPartial = false,
        originalInputUrl = "https://instagram.com/p/$shortcode/",
        canonicalUrl = "https://instagram.com/p/$shortcode/",
        shortcode = shortcode,
        sourcePathType = "POST",
        postType = postType,
        identityVerified = true,
        methodUsed = "TEST",
        items = items,
        totalResolutionTimeMs = 10L
    )

    @Test
    fun testSinglePhotoPlanCreation() {
        val resolveResult = makeResolveResult(
            shortcode = "TEST_PHOTO_1",
            postType = PostType.PHOTO,
            items = listOf(
                makeResolvedMedia(0, MediaType.IMAGE, "https://cdn.instagram.com/photo.jpg")
            )
        )

        val plan = DownloadPlanner.createPlan(
            expectedShortcode = "TEST_PHOTO_1",
            resolveResult = resolveResult
        )

        assertTrue(plan.success)
        assertEquals(1, plan.items.size)
        assertEquals("Instagram_TEST_PHOTO_1.jpg", plan.items[0].fileName)
        assertEquals(0, plan.items[0].index)
    }

    @Test
    fun testCarouselWithAllSelected() {
        val items = (0..3).map { idx ->
            makeResolvedMedia(idx, MediaType.IMAGE, "https://cdn.instagram.com/photo_$idx.jpg")
        }

        val resolveResult = makeResolveResult(
            shortcode = "TEST_CAROUSEL",
            postType = PostType.CAROUSEL,
            items = items
        )

        val plan = DownloadPlanner.createPlan(
            expectedShortcode = "TEST_CAROUSEL",
            resolveResult = resolveResult,
            selectedIndices = null
        )

        assertTrue(plan.success)
        assertEquals(4, plan.items.size)
        assertEquals("Instagram_TEST_CAROUSEL_01.jpg", plan.items[0].fileName)
        assertEquals("Instagram_TEST_CAROUSEL_04.jpg", plan.items[3].fileName)
    }

    @Test
    fun testCarouselWithSelectedSubsetPreservesOriginalIndices() {
        val items = (0..4).map { idx ->
            val type = if (idx == 1) MediaType.VIDEO else MediaType.IMAGE
            val ext = if (idx == 1) "mp4" else "jpg"
            makeResolvedMedia(idx, type, "https://cdn.instagram.com/item_$idx.$ext")
        }

        val resolveResult = makeResolveResult(
            shortcode = "CAROUSEL_SUBSET",
            postType = PostType.CAROUSEL,
            items = items
        )

        // Select only indices 1 and 3
        val plan = DownloadPlanner.createPlan(
            expectedShortcode = "CAROUSEL_SUBSET",
            resolveResult = resolveResult,
            selectedIndices = setOf(1, 3)
        )

        assertTrue(plan.success)
        assertEquals(2, plan.items.size)

        // First item in plan corresponds to original slide 1 (video)
        assertEquals(1, plan.items[0].index)
        assertEquals(MediaType.VIDEO, plan.items[0].mediaType)
        assertEquals("Instagram_CAROUSEL_SUBSET_02.mp4", plan.items[0].fileName)

        // Second item in plan corresponds to original slide 3 (photo)
        assertEquals(3, plan.items[1].index)
        assertEquals(MediaType.IMAGE, plan.items[1].mediaType)
        assertEquals("Instagram_CAROUSEL_SUBSET_04.jpg", plan.items[1].fileName)
    }

    @Test
    fun testShortcodeMismatchFailsPlan() {
        val resolveResult = makeResolveResult(
            shortcode = "RESOLVED_CODE",
            postType = PostType.PHOTO,
            items = listOf(
                makeResolvedMedia(0, MediaType.IMAGE, "https://cdn.instagram.com/photo.jpg")
            )
        )

        val plan = DownloadPlanner.createPlan(
            expectedShortcode = "DIFFERENT_CODE",
            resolveResult = resolveResult
        )

        assertFalse(plan.success)
        assertEquals(com.instadown.assist.download.model.DownloadErrorCode.IDENTITY_VERIFICATION_FAILED, plan.errorCode)
    }
}
