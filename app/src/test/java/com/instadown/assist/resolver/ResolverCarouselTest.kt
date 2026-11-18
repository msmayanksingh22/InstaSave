package com.instadown.assist.resolver

import android.content.Context
import com.instadown.assist.resolver.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResolverCarouselTest {

    private fun createMockClientWithHtml(html: String): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(html.toResponseBody("text/html; charset=utf-8".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun testEmbedFallbackRejectsCarouselWithEdgeSidecar() = runBlocking {
        val embedHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <script>
                    window.__additionalDataLoaded('/p/C_test123/', {
                        "graphql": {
                            "shortcode_media": {
                                "edge_sidecar_to_children": {
                                    "edges": [{"node": {"id": "1"}}, {"node": {"id": "2"}}]
                                },
                                "display_url": "https://cdn.instagram.com/slide1.jpg"
                            }
                        }
                    });
                </script>
            </head>
            <body><img src="https://cdn.instagram.com/slide1.jpg" /></body>
            </html>
        """.trimIndent()

        val resolver = EmbedFallbackResolver(client = createMockClientWithHtml(embedHtml))
        val result = resolver.resolve("https://www.instagram.com/p/C_test123/", validateCdn = false)

        // Must NOT return a 1-item PHOTO success result!
        assertFalse("Carousel embed must NOT return success with 1 item", result.success)
        assertEquals(PostType.CAROUSEL, result.postType)
        assertEquals(ResolveErrorCode.PARTIAL_MEDIA, result.error?.code)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun testEmbedFallbackRejectsCarouselWithIsCarouselIndicator() = runBlocking {
        val embedHtml = """
            <div class="EmbedSidecar" data-media-type="GraphSidecar">
                "is_carousel":true,
                "display_url": "https://cdn.instagram.com/carousel_cover.jpg"
            </div>
        """.trimIndent()

        val resolver = EmbedFallbackResolver(client = createMockClientWithHtml(embedHtml))
        val result = resolver.resolve("https://www.instagram.com/p/C_test456/", validateCdn = false)

        assertFalse("Embed with is_carousel:true indicator must reject 1-item fallback", result.success)
        assertEquals(PostType.CAROUSEL, result.postType)
        assertEquals(ResolveErrorCode.PARTIAL_MEDIA, result.error?.code)
    }

    @Test
    fun testEmbedFallbackAcceptsSinglePhotoWithoutCarouselIndicators() = runBlocking {
        val embedHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <script>
                    var data = {
                        "display_url": "https://cdn.instagram.com/single_highres.jpg"
                    };
                </script>
            </body>
            </html>
        """.trimIndent()

        val resolver = EmbedFallbackResolver(client = createMockClientWithHtml(embedHtml))
        val result = resolver.resolve("https://www.instagram.com/p/C_single/", validateCdn = false)

        assertTrue("Single photo embed without carousel indicators should succeed", result.success)
        assertEquals(PostType.PHOTO, result.postType)
        assertEquals(1, result.items.size)
        assertEquals("https://cdn.instagram.com/single_highres.jpg", result.items[0].mediaUrl)
    }

    @Test
    fun testHeadlessWebBridgeParses15CarouselItemsWithPreviewThumbnails() {
        // Construct FastDL-style JSON with 15 items
        val jsonBuilder = StringBuilder("[")
        for (i in 0 until 15) {
            if (i > 0) jsonBuilder.append(",")
            val isVideo = (i == 4) // Item 5 is a video
            if (isVideo) {
                jsonBuilder.append("""{"type":"video","downloadableVideoUrl":"https://cdn.instagram.com/video_$i.mp4","previewImageUrl":"https://cdn.instagram.com/preview_thumb_$i.jpg"}""")
            } else {
                jsonBuilder.append("""{"type":"photo","downloadableImageUrl":"https://cdn.instagram.com/huge_5mb_photo_$i.jpg","previewImageUrl":"https://cdn.instagram.com/small_preview_$i.jpg"}""")
            }
        }
        jsonBuilder.append("]")
        val jsonStr = jsonBuilder.toString()

        val dummyContext = android.app.Application()
        val bridgeResolver = HeadlessWebBridgeResolver(dummyContext)
        val items = bridgeResolver.parseMediaItems(jsonStr, "C_test15")

        assertEquals("Must extract ALL 15 items without 6-item truncation", 15, items.size)

        for (i in 0 until 15) {
            val item = items[i]
            assertEquals(i, item.index)
            if (i == 4) {
                assertEquals(MediaType.VIDEO, item.mediaType)
                assertEquals("https://cdn.instagram.com/video_4.mp4", item.mediaUrl)
                assertEquals("https://cdn.instagram.com/preview_thumb_4.jpg", item.thumbnailUrl)
            } else {
                assertEquals(MediaType.IMAGE, item.mediaType)
                assertEquals("https://cdn.instagram.com/huge_5mb_photo_$i.jpg", item.mediaUrl)
                // Critical requirement: thumbUrl must be the small preview image, NOT the multi-megabyte downloadUrl
                assertEquals("https://cdn.instagram.com/small_preview_$i.jpg", item.thumbnailUrl)
                assertNotEquals("Thumbnail must NOT be the 5MB photo", item.mediaUrl, item.thumbnailUrl)
            }
        }
    }

    @Test
    fun testHeadlessWebBridgeParsesFastDlApiConvertWrappedObject() {
        val jsonBuilder = StringBuilder("""{"status":"ok","data":[""")
        for (i in 0 until 16) {
            if (i > 0) jsonBuilder.append(",")
            jsonBuilder.append("""{"downloadUrl":"https://media.fastdl.app/get/item_$i.jpg","thumbUrl":"https://media.fastdl.app/thumb/preview_$i.jpg","isVideo":false}""")
        }
        jsonBuilder.append("]}")
        val jsonStr = jsonBuilder.toString()

        val dummyContext = android.app.Application()
        val bridgeResolver = HeadlessWebBridgeResolver(dummyContext)
        val items = bridgeResolver.parseMediaItems(jsonStr, "C_16items")

        assertEquals(16, items.size)
        assertEquals("https://media.fastdl.app/thumb/preview_0.jpg", items[0].thumbnailUrl)
        assertEquals("https://media.fastdl.app/get/item_0.jpg", items[0].mediaUrl)
    }

    @Test
    fun testCompositeResolverFallsThroughToBridgeOnCarousel() = runBlocking {
        val targetUrl = "https://www.instagram.com/p/C_carousel15/"

        // Primary fails with AUTH_REQUIRED
        val mockPrimary = object : InstagramResolver {
            override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult {
                return ResolveResult(
                    success = false,
                    originalInputUrl = url,
                    canonicalUrl = url,
                    shortcode = "C_carousel15",
                    sourcePathType = "p",
                    postType = null,
                    items = emptyList(),
                    methodUsed = "WebPageSjsResolver",
                    totalResolutionTimeMs = 50L,
                    error = ResolveError(
                        code = ResolveErrorCode.AUTH_REQUIRED,
                        message = "Redirect to login"
                    )
                )
            }
        }

        // Fallback rejects carousel
        val mockFallback = object : InstagramResolver {
            override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult {
                return ResolveResult(
                    success = false,
                    isPartial = true,
                    originalInputUrl = url,
                    canonicalUrl = url,
                    shortcode = "C_carousel15",
                    sourcePathType = "p",
                    postType = PostType.CAROUSEL,
                    items = emptyList(),
                    methodUsed = "EmbedFallbackResolver",
                    totalResolutionTimeMs = 40L,
                    error = ResolveError(
                        code = ResolveErrorCode.PARTIAL_MEDIA,
                        message = "Embed indicates carousel"
                    )
                )
            }
        }

        // Bridge returns full 15 items
        val bridgeItems = (0 until 15).map { idx ->
            ResolvedMedia(
                index = idx,
                mediaType = MediaType.IMAGE,
                mediaUrl = "https://cdn.instagram.com/photo_$idx.jpg",
                thumbnailUrl = "https://cdn.instagram.com/thumb_$idx.jpg",
                width = 1080,
                height = 1080,
                durationMs = null,
                bitRate = null,
                qualityClassification = "High-resolution photo",
                selectionReason = "Bridge",
                candidates = emptyList()
            )
        }

        val mockBridge = object : InstagramResolver {
            override suspend fun resolve(url: String, validateCdn: Boolean): ResolveResult {
                return ResolveResult(
                    success = true,
                    originalInputUrl = url,
                    canonicalUrl = url,
                    shortcode = "C_carousel15",
                    sourcePathType = "p",
                    postType = PostType.CAROUSEL,
                    items = bridgeItems,
                    methodUsed = "HeadlessWebBridgeResolver",
                    totalResolutionTimeMs = 200L
                )
            }
        }

        val composite = CompositeInstagramResolver(
            context = null,
            primaryResolver = mockPrimary,
            fallbackResolver = mockFallback,
            webBridgeResolver = mockBridge
        )

        val result = composite.resolve(targetUrl, validateCdn = false)

        assertTrue("Composite resolver must succeed via web bridge", result.success)
        assertEquals(PostType.CAROUSEL, result.postType)
        assertEquals(15, result.items.size)
        assertEquals("HeadlessWebBridgeResolver", result.methodUsed)
    }
}
