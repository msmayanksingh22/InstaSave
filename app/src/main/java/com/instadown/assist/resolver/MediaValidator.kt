package com.instadown.assist.resolver

import android.util.Log
import com.instadown.assist.resolver.model.CdnValidationResult
import com.instadown.assist.resolver.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

object MediaValidator {

    private const val TAG = "MediaValidator"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val TRUSTED_CDN_HOST_SUFFIXES = listOf(
        "cdninstagram.com",
        "fbcdn.net",
        "instagram.com",
        "facebook.com",
        "fastdl.app"
    )

    /**
     * Performs lightweight CDN validation on a candidate media URL using HTTP Range bytes=0-1024.
     * Validates that the stream is live, returns expected status (200/206), and matches expected media type.
     */
    suspend fun validate(url: String, expectedMediaType: MediaType): CdnValidationResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            return@withContext CdnValidationResult(
                checked = true,
                isAccessible = false,
                notes = "Empty media URL"
            )
        }

        try {
            val uri = URI(url)
            val host = uri.host?.lowercase() ?: ""
            val isTrustedHost = TRUSTED_CDN_HOST_SUFFIXES.any { host.endsWith(it) }
            if (!isTrustedHost) {
                Log.w(TAG, "Untrusted CDN host: $host")
                return@withContext CdnValidationResult(
                    checked = true,
                    isAccessible = false,
                    notes = "Security: CDN host '$host' is not a recognized Meta/Instagram domain"
                )
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .addHeader("Range", "bytes=0-1024")
                .addHeader("Accept", "*/*")
                .build()

            client.newCall(request).execute().use { response ->
                val statusCode = response.code
                val isSuccessStatus = statusCode == 200 || statusCode == 206
                val rawContentType = response.header("Content-Type")?.lowercase()
                val contentLength = response.header("Content-Length")?.toLongOrNull()
                    ?: response.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()

                // Verify content type consistency
                val isTypeConsistent = when (expectedMediaType) {
                    MediaType.IMAGE -> rawContentType?.startsWith("image/") == true || rawContentType?.contains("webp") == true || rawContentType?.contains("jpeg") == true
                    MediaType.VIDEO -> rawContentType?.startsWith("video/") == true || rawContentType?.contains("mp4") == true || rawContentType?.contains("octet-stream") == true
                }

                val accessible = isSuccessStatus && (isTypeConsistent || rawContentType == null)

                val notes = buildString {
                    if (!isSuccessStatus) append("HTTP $statusCode; ")
                    if (!isTypeConsistent) append("Type mismatch: expected $expectedMediaType, got $rawContentType; ")
                    if (contentLength == null) append("No Content-Length (chunked/streamed); ")
                }.trim()

                return@withContext CdnValidationResult(
                    checked = true,
                    httpStatusCode = statusCode,
                    contentType = rawContentType,
                    contentLength = contentLength,
                    isAccessible = accessible,
                    notes = notes.ifBlank { "OK (HTTP $statusCode, $rawContentType)" }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Validation error for URL", e)
            return@withContext CdnValidationResult(
                checked = true,
                isAccessible = false,
                notes = "Network exception: ${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }
}
