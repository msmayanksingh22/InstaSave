package com.instadown.assist.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import kotlin.coroutines.coroutineContext

/**
 * Bounded pool of off-heap DirectByteBuffers (64 KB each).
 * Eliminates JVM heap byte[] churn and GC pauses during concurrent downloads.
 */
object DirectBufferPool {
    private const val BUFFER_SIZE_BYTES = 64 * 1024
    private const val MAX_POOLED_BUFFERS = 8
    private val pool = ArrayBlockingQueue<ByteBuffer>(MAX_POOLED_BUFFERS)

    fun borrow(): ByteBuffer =
        (pool.poll() ?: ByteBuffer.allocateDirect(BUFFER_SIZE_BYTES)).apply { clear() }

    fun release(buffer: ByteBuffer) {
        buffer.clear()
        pool.offer(buffer)
    }
}

data class MediaWriteSpec(
    val url: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean
)

class ZeroAllocMediaPipeline(
    private val context: Context,
    private val okHttpClient: OkHttpClient = MediaDownloadManager.defaultClient
) {
    /**
     * Streams directly from OkHttp TLS socket -> Off-Heap DirectByteBuffer -> MediaStore FileChannel.
     * Throttles progress callbacks to 100 ms intervals to avoid saturating NotificationManager.
     */
    suspend fun streamToMediaStore(
        spec: MediaWriteSpec,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val pendingUri = createPendingMediaEntry(resolver, spec)
            ?: throw IOException("Failed to create MediaStore entry for ${spec.displayName}")

        val directBuffer = DirectBufferPool.borrow()
        var committed = false

        try {
            val request = Request.Builder()
                .url(spec.url)
                .header("Accept-Encoding", "identity") // Media is already compressed; preserve Content-Length
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("CDN HTTP ${response.code} for ${spec.displayName}")
                }
                val body = response.body ?: throw IOException("Empty CDN response body")
                val totalBytes = body.contentLength()
                val okioSource = body.source()

                val pfd = resolver.openFileDescriptor(pendingUri, "w")
                    ?: throw IOException("Cannot open FileDescriptor for $pendingUri")

                pfd.use { descriptor ->
                    FileOutputStream(descriptor.fileDescriptor).channel.use { outChannel ->
                        var totalWritten = 0L
                        var lastProgressEmitMs = 0L

                        while (true) {
                            coroutineContext.ensureActive()
                            directBuffer.clear()
                            val bytesRead = okioSource.read(directBuffer)
                            if (bytesRead == -1) break

                            directBuffer.flip()
                            while (directBuffer.hasRemaining()) {
                                totalWritten += outChannel.write(directBuffer)
                            }

                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgressEmitMs >= 100L) {
                                lastProgressEmitMs = now
                                onProgress(totalWritten, totalBytes)
                            }
                        }

                        outChannel.force(false) // Flush dirty file pages without stalling on metadata fsync
                        onProgress(totalWritten, if (totalBytes > 0) totalBytes else totalWritten)
                    }
                }
            }

            publishPendingEntry(resolver, pendingUri)
            committed = true
            pendingUri
        } catch (t: Throwable) {
            if (!committed) {
                runCatching { resolver.delete(pendingUri, null, null) }
            }
            throw t
        } finally {
            DirectBufferPool.release(directBuffer)
        }
    }

    private fun createPendingMediaEntry(resolver: ContentResolver, spec: MediaWriteSpec): Uri? {
        val collection = if (spec.isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        val relativeDir = if (spec.isVideo) {
            "${Environment.DIRECTORY_MOVIES}/InstaSave"
        } else {
            "${Environment.DIRECTORY_PICTURES}/InstaSave"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, spec.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, spec.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        return resolver.insert(collection, values)
    }

    private fun publishPendingEntry(resolver: ContentResolver, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
    }
}
