package com.instadown.assist.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Commercial-grade, two-tier (Memory + Disk LRU) cached thumbnail loader.
 * Employs connection-pooled HTTP/2 streaming, aggressive downsampling, and
 * fast local media fallbacks to ensure instantaneous UI tile rendering.
 */
object MediaThumbnailLoader {
    private const val TAG = "MediaThumbnailLoader"

    // 1/8th of available memory for memory cache
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun loadThumbnail(
        context: Context,
        uriString: String?,
        isVideo: Boolean = false,
        targetWidth: Int = 400,
        targetHeight: Int = 400
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (uriString.isNullOrBlank()) return@withContext null

        val cacheKey = "${uriString}_${targetWidth}x${targetHeight}"
        synchronized(memoryCache) {
            val cached = memoryCache.get(cacheKey)
            if (cached != null && !cached.isRecycled) {
                return@withContext cached
            }
        }

        var bitmap: Bitmap? = null

        // Strategy 1: Local file path on disk (e.g. cached thumbnails or internal storage)
        if (uriString.startsWith("/") || uriString.startsWith("file://")) {
            val filePath = if (uriString.startsWith("file://")) uriString.removePrefix("file://") else uriString
            val file = File(filePath)
            if (file.exists() && file.canRead()) {
                bitmap = decodeSampledBitmapFromFile(file.absolutePath, targetWidth, targetHeight)
                if (bitmap != null) {
                    synchronized(memoryCache) { memoryCache.put(cacheKey, bitmap) }
                    return@withContext bitmap
                }
            }
        }

        // Strategy 2: Remote HTTP/HTTPS URL
        if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
            // Guard: If URL is a direct .mp4 video stream without image cover, do not attempt image download
            val isDirectVideoUrl = uriString.contains(".mp4", ignoreCase = true) || uriString.contains("/video", ignoreCase = true)
            if (isDirectVideoUrl && isVideo) {
                return@withContext null
            }

            try {
                // Check disk cache first
                val diskFile = getDiskCacheFile(context, uriString)
                if (diskFile.exists() && diskFile.length() > 0) {
                    bitmap = decodeSampledBitmapFromFile(diskFile.absolutePath, targetWidth, targetHeight)
                } else {
                    val request = Request.Builder()
                        .url(uriString)
                        .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X)")
                        .build()

                    sharedHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body
                            if (body != null) {
                                val tempFile = File(diskFile.parentFile, "${diskFile.name}.tmp")
                                FileOutputStream(tempFile).use { fos ->
                                    body.byteStream().copyTo(fos)
                                }
                                tempFile.renameTo(diskFile)
                                bitmap = decodeSampledBitmapFromFile(diskFile.absolutePath, targetWidth, targetHeight)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "HTTP thumbnail fetch error for $uriString: ${e.message}")
            }

            if (bitmap != null) {
                synchronized(memoryCache) { memoryCache.put(cacheKey, bitmap) }
                return@withContext bitmap
            }
        }

        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            return@withContext null
        }

        // Strategy 3: ContentResolver.loadThumbnail (Android Q+ hardware acceleration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !uriString.startsWith("http")) {
            try {
                bitmap = context.contentResolver.loadThumbnail(uri, Size(targetWidth, targetHeight), null)
            } catch (_: Exception) {}
        }

        // Strategy 4: Video frame extraction via MediaMetadataRetriever
        if (bitmap == null && isVideo && !uriString.startsWith("http")) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                bitmap = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
                retriever.release()
            } catch (_: Exception) {}
        }

        // Strategy 5: ContentResolver openInputStream fallback for local photos
        if (bitmap == null && !isVideo && !uriString.startsWith("http")) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)

                    var sample = 1
                    if (bounds.outHeight > targetHeight || bounds.outWidth > targetWidth) {
                        val halfH = bounds.outHeight / 2
                        val halfW = bounds.outWidth / 2
                        while ((halfH / sample) >= targetHeight && (halfW / sample) >= targetWidth) {
                            sample *= 2
                        }
                    }

                    context.contentResolver.openInputStream(uri)?.use { decStream ->
                        val opts = BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        bitmap = BitmapFactory.decodeStream(decStream, null, opts)
                    }
                }
            } catch (_: Exception) {}
        }

        if (bitmap != null) {
            synchronized(memoryCache) {
                memoryCache.put(cacheKey, bitmap)
            }
        }

        bitmap
    }

    private fun decodeSampledBitmapFromFile(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            var sample = 1
            if (bounds.outHeight > reqHeight || bounds.outWidth > reqWidth) {
                val halfH = bounds.outHeight / 2
                val halfW = bounds.outWidth / 2
                while ((halfH / sample) >= reqHeight && (halfW / sample) >= reqWidth) {
                    sample *= 2
                }
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(filePath, opts)
        } catch (e: Exception) {
            Log.d(TAG, "decodeSampledBitmapFromFile error: ${e.message}")
            null
        }
    }

    private fun getDiskCacheFile(context: Context, url: String): File {
        val cacheDir = File(context.cacheDir, "img_cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$hash.img")
    }

    fun loadInto(
        context: Context,
        uriString: String?,
        imageView: ImageView,
        isVideo: Boolean = false,
        placeholderResId: Int = android.R.drawable.ic_menu_gallery,
        targetWidth: Int = 300,
        targetHeight: Int = 300,
        scope: CoroutineScope
    ) {
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        if (uriString.isNullOrBlank()) {
            imageView.setImageResource(placeholderResId)
            imageView.tag = null
            return
        }

        val cacheKey = "${uriString}_${targetWidth}x${targetHeight}"
        synchronized(memoryCache) {
            val cached = memoryCache.get(cacheKey)
            if (cached != null && !cached.isRecycled) {
                imageView.setImageBitmap(cached)
                imageView.tag = uriString
                return
            }
        }

        imageView.tag = uriString
        imageView.setImageResource(placeholderResId)

        scope.launch {
            val bmp = loadThumbnail(context, uriString, isVideo, targetWidth, targetHeight)
            if (imageView.tag == uriString && bmp != null) {
                imageView.setImageBitmap(bmp)
            }
        }
    }

    fun evict(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        val cacheKey = "${uriString}_400x400"
        synchronized(memoryCache) {
            memoryCache.remove(cacheKey)
        }
    }

    fun clearAll() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
    }
}
