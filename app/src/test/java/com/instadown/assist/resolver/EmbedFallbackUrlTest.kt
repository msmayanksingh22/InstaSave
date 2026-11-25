package com.instadown.assist.resolver

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedFallbackUrlTest {

    @Test
    fun `extracts clean MP4 URL from double-escaped contextJSON without trailing braces or backslash-percent`() = runBlocking {
        val rawHtml = """
            <html><body><script>
            ["PolarisEmbedPostController", {"contextJSON": "{\"media\":{\"video_url\":\"https:\\\/\\\/instagram.flko5-1.fna.fbcdn.net\\\/o1\\\/v\\\/t2\\\/f2\\\/m86\\\/sample.mp4?efg=eyJ0YWciOiJ2aWRlbyJ9\\u00253D\\u00253D&oh=00_ABC123&oe=6B08B804\"}}}"}]
            </script></body></html>
        """.trimIndent()

        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(rawHtml.toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val resolver = EmbedFallbackResolver(client)
        val result = resolver.resolve("https://www.instagram.com/reel/DfrZdrKS04k/", validateCdn = false)

        assertTrue("Expected success", result.success)
        assertEquals(1, result.items.size)
        val url = result.items[0].mediaUrl
        assertEquals(
            "https://instagram.flko5-1.fna.fbcdn.net/o1/v/t2/f2/m86/sample.mp4?efg=eyJ0YWciOiJ2aWRlbyJ9%3D%3D&oh=00_ABC123&oe=6B08B804",
            url
        )
        assertFalse(url.contains("\\"))
        assertFalse(url.endsWith("}"))
        assertFalse(url.contains("\""))
    }
}
