package com.instadown.assist.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val fileSizeBytes: Long = 0L,
    val isAvailable: Boolean = false
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // Default release metadata endpoint (e.g. GitHub Releases latest or raw JSON)
    const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/release-manifest.json"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Checks if a newer version of InstaSave is available.
     */
    suspend fun checkForUpdates(context: Context, customUrl: String? = null): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val targetUrl = customUrl?.ifBlank { null } ?: DEFAULT_UPDATE_URL
        try {
            AppLogger.i(TAG, "Checking for updates from $targetUrl...")
            val currentVersionCode = try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (e: Exception) {
                1
            }

            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "InstaSave-Updater/1.0")
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.w(TAG, "Update check failed: HTTP ${response.code}")
                return@withContext null
            }

            val jsonStr = response.body?.string() ?: return@withContext null
            val json = JSONObject(jsonStr)

            val remoteVersionCode = json.optInt("versionCode", 0)
            val remoteVersionName = json.optString("versionName", "1.0")
            val releaseNotes = json.optString("releaseNotes", "New performance and feature updates.")
            val apkUrl = json.optString("apkUrl", "")
            val fileSizeBytes = json.optLong("fileSizeBytes", 0L)

            val isAvailable = remoteVersionCode > currentVersionCode && apkUrl.isNotBlank()
            AppLogger.i(TAG, "Update check: Current=$currentVersionCode, Remote=$remoteVersionCode, Available=$isAvailable")

            AppUpdateInfo(
                versionCode = remoteVersionCode,
                versionName = remoteVersionName,
                releaseNotes = releaseNotes,
                apkUrl = apkUrl,
                fileSizeBytes = fileSizeBytes,
                isAvailable = isAvailable
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Check updates error: ${e.message}")
            null
        }
    }

    /**
     * Downloads the update APK to the app cache directory with progress reporting.
     */
    suspend fun downloadUpdate(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (percent: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val apkFile = File(updateDir, "InstaSave-v${updateInfo.versionName}.apk")
            if (apkFile.exists()) apkFile.delete()

            val tempFile = File(updateDir, "InstaSave-download.tmp")
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder()
                .url(updateInfo.apkUrl)
                .header("User-Agent", "InstaSave-Updater/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "Download APK failed: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: updateInfo.fileSizeBytes
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(64 * 1024)
            var read: Int
            var totalRead = 0L
            var lastPercent = -1

            while (inputStream.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                totalRead += read
                if (totalBytes > 0) {
                    val percent = ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent, totalRead, totalBytes)
                    }
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            if (tempFile.renameTo(apkFile)) {
                AppLogger.i(TAG, "Update APK downloaded successfully: ${apkFile.length()} bytes -> ${apkFile.absolutePath}")
                apkFile
            } else {
                tempFile
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download APK exception: ${e.message}", e)
            null
        }
    }

    /**
     * Prompts the native Android package installer to install the downloaded APK.
     */
    fun promptInstall(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            AppLogger.e(TAG, "Cannot install: APK file does not exist or is empty")
            return false
        }

        // On Android 8.0+, check if app is permitted to request package installs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                AppLogger.i(TAG, "Requesting unknown app sources permission...")
                val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(manageIntent)
                return false
            }
        }

        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            AppLogger.i(TAG, "Launched PackageInstaller for $contentUri")
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch package installer: ${e.message}", e)
            return false
        }
    }
}
