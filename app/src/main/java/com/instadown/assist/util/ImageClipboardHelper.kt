package com.instadown.assist.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Enterprise-grade utility for copying rich images directly into the Android system clipboard.
 * Employs androidx FileProvider, memory-efficient bitmap compression, and automatic TTL purge
 * so images can be pasted immediately into WhatsApp, Telegram, Slack, Notes, Gboard, etc.
 * without saving to device gallery or polluting disk storage.
 */
object ImageClipboardHelper {
    private const val TAG = "ImageClipboardHelper"
    private const val CLIP_DIR = "copied_images"
    private const val MAX_FILE_AGE_MS = 5 * 60 * 1000L // 5 minutes TTL

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(10, 2, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Copies an image to the Android system clipboard as a rich image/jpeg ClipData.
     *
     * @param context Application or Activity context
     * @param bitmap In-memory bitmap to copy, if already decoded
     * @param localUri Local ContentResolver or file URI
     * @param remoteUrl Direct HTTP/HTTPS image URL to download
     * @param label Optional text label for ClipData
     * @param showToast Whether to display standard confirmation toast
     * @return true if successfully copied, false otherwise
     */
    suspend fun copyImageToClipboard(
        context: Context,
        bitmap: Bitmap? = null,
        localUri: Uri? = null,
        remoteUrl: String? = null,
        label: String? = null,
        showToast: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext

            // 1. Resolve or decode target bitmap
            val resolvedBitmap: Bitmap? = when {
                bitmap != null -> bitmap
                localUri != null -> {
                    appContext.contentResolver.openInputStream(localUri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    }
                }
                !remoteUrl.isNullOrBlank() -> {
                    fetchRemoteBitmap(remoteUrl)
                }
                else -> null
            }

            if (resolvedBitmap == null) {
                AppLogger.e(TAG, "copyImageToClipboard: Failed to decode/fetch image source (remoteUrl=$remoteUrl, localUri=$localUri)")
                if (showToast) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Unable to load image for clipboard", Toast.LENGTH_SHORT).show()
                    }
                }
                return@withContext false
            }

            // 2. Prepare cache directory and write high-quality JPEG
            val cacheDir = File(appContext.cacheDir, CLIP_DIR).apply {
                if (!exists()) mkdirs()
            }
            purgeExpiredFiles(cacheDir)

            val timestamp = System.currentTimeMillis()
            val imageFile = File(cacheDir, "clip_${timestamp}.jpg")
            FileOutputStream(imageFile).use { fos ->
                resolvedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                fos.flush()
            }

            // 3. Obtain secure FileProvider URI
            val authority = "${appContext.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(appContext, authority, imageFile)

            // 4. Create ClipData with URI and image MIME type
            val clipTitle = label ?: "Instagram Image"
            val clipData = ClipData.newUri(
                appContext.contentResolver,
                clipTitle,
                contentUri
            )

            // 5. Place in system ClipboardManager on Main thread
            withContext(Dispatchers.Main) {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clipData)

                    // Subtle haptic confirmation
                    performHapticPulse(appContext)

                    if (showToast) {
                        Toast.makeText(appContext, "✓ Image copied to clipboard! Ready to paste", Toast.LENGTH_SHORT).show()
                    }
                    AppLogger.i(TAG, "copyImageToClipboard: Successfully placed image clip in clipboard ($contentUri)")
                }
            }

            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "copyImageToClipboard error: ${e.message}", e)
            if (showToast) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context.applicationContext, "Failed to copy image to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            false
        }
    }

    private fun fetchRemoteBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLogger.w(TAG, "fetchRemoteBitmap: HTTP ${response.code} for $url")
                    return null
                }
                val body = response.body ?: return null
                body.byteStream().use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "fetchRemoteBitmap exception for $url: ${e.message}")
            null
        }
    }

    private fun purgeExpiredFiles(cacheDir: File) {
        try {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && now - file.lastModified() > MAX_FILE_AGE_MS) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun performHapticPulse(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            }
        } catch (_: Exception) {}
    }
}
