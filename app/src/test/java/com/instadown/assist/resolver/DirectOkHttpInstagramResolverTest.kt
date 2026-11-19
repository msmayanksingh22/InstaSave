package com.instadown.assist.resolver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectOkHttpInstagramResolverTest {

    private val resolver = DirectOkHttpInstagramResolver()

    @Test
    fun testParseSinglePhoto() {
        val json = JSONObject(
            """
            {
              "data": {
                "xdt_shortcode_media": {
                  "is_video": false,
                  "display_url": "https://scontent.cdninstagram.com/photo_thumb.jpg",
                  "display_resources": [
                    { "src": "https://scontent.cdninstagram.com/photo_640.jpg" },
                    { "src": "https://scontent.cdninstagram.com/photo_1080.jpg" }
                  ],
                  "dimensions": { "width": 1080, "height": 1350 }
                }
              }
            }
            """.trimIndent()
        )

        val items = resolver.parseGraphQlMedia(json)
        assertEquals(1, items.size)
        val item = items[0]
        assertFalse(item.isVideo)
        assertEquals("https://scontent.cdninstagram.com/photo_1080.jpg", item.url)
        assertEquals("https://scontent.cdninstagram.com/photo_thumb.jpg", item.thumbnailUrl)
        assertEquals(1080, item.width)
        assertEquals(1350, item.height)
    }

    @Test
    fun testParseSingleVideoReel() {
        val json = JSONObject(
            """
            {
              "data": {
                "xdt_shortcode_media": {
                  "is_video": true,
                  "display_url": "https://scontent.cdninstagram.com/reel_thumb.jpg",
                  "video_url": "https://scontent.cdninstagram.com/reel_video.mp4",
                  "dimensions": { "width": 1080, "height": 1920 }
                }
              }
            }
            """.trimIndent()
        )

        val items = resolver.parseGraphQlMedia(json)
        assertEquals(1, items.size)
        val item = items[0]
        assertTrue(item.isVideo)
        assertEquals("https://scontent.cdninstagram.com/reel_video.mp4", item.url)
        assertEquals("https://scontent.cdninstagram.com/reel_thumb.jpg", item.thumbnailUrl)
        assertEquals(1080, item.width)
        assertEquals(1920, item.height)
    }

    @Test
    fun testParseMultiItemCarousel() {
        val json = JSONObject(
            """
            {
              "data": {
                "xdt_shortcode_media": {
                  "is_video": false,
                  "display_url": "https://scontent.cdninstagram.com/carousel_cover.jpg",
                  "edge_sidecar_to_children": {
                    "edges": [
                      {
                        "node": {
                          "is_video": false,
                          "display_url": "https://scontent.cdninstagram.com/slide1_thumb.jpg",
                          "display_resources": [
                            { "src": "https://scontent.cdninstagram.com/slide1_1080.jpg" }
                          ],
                          "dimensions": { "width": 1080, "height": 1080 }
                        }
                      },
                      {
                        "node": {
                          "is_video": true,
                          "display_url": "https://scontent.cdninstagram.com/slide2_thumb.jpg",
                          "video_url": "https://scontent.cdninstagram.com/slide2_video.mp4",
                          "dimensions": { "width": 1080, "height": 1080 }
                        }
                      }
                    ]
                  }
                }
              }
            }
            """.trimIndent()
        )

        val items = resolver.parseGraphQlMedia(json)
        assertEquals(2, items.size)

        assertFalse(items[0].isVideo)
        assertEquals("https://scontent.cdninstagram.com/slide1_1080.jpg", items[0].url)
        assertEquals("https://scontent.cdninstagram.com/slide1_thumb.jpg", items[0].thumbnailUrl)

        assertTrue(items[1].isVideo)
        assertEquals("https://scontent.cdninstagram.com/slide2_video.mp4", items[1].url)
        assertEquals("https://scontent.cdninstagram.com/slide2_thumb.jpg", items[1].thumbnailUrl)
    }

    @Test
    fun testParseEmptyResponse() {
        val json = JSONObject("{}")
        val items = resolver.parseGraphQlMedia(json)
        assertTrue(items.isEmpty())
    }

    @Test
    fun testParseThumbnailResourcesForFastGrid() {
        val json = JSONObject(
            """
            {
              "data": {
                "xdt_shortcode_media": {
                  "is_video": false,
                  "display_url": "https://scontent.cdninstagram.com/huge_3mb_photo.jpg",
                  "thumbnail_resources": [
                    { "config_width": 150, "src": "https://scontent.cdninstagram.com/thumb_150.jpg" },
                    { "config_width": 320, "src": "https://scontent.cdninstagram.com/thumb_320_fast.jpg" },
                    { "config_width": 640, "src": "https://scontent.cdninstagram.com/thumb_640.jpg" }
                  ],
                  "display_resources": [
                    { "config_width": 1080, "src": "https://scontent.cdninstagram.com/photo_1080.jpg" }
                  ],
                  "dimensions": { "width": 1080, "height": 1350 }
                }
              }
            }
            """.trimIndent()
        )

        val items = resolver.parseGraphQlMedia(json)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("https://scontent.cdninstagram.com/photo_1080.jpg", item.url)
        // Must select the 320px resource (~25 KB) instead of the 3 MB display_url
        assertEquals("https://scontent.cdninstagram.com/thumb_320_fast.jpg", item.thumbnailUrl)
    }
}
