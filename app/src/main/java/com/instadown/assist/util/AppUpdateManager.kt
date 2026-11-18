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
    val fallbackApkUrl: String? = null,
    val fileSizeBytes: Long = 0L,
    val isAvailable: Boolean = false
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // Default release metadata endpoint (e.g. GitHub Releases latest or raw JSON)
    const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/release-manifest.json"
    const val DEFAULT_APK_URL = "https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/app-release.apk"

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
            val fallbackApkUrl = json.optString("fallbackApkUrl", "").takeIf { it.isNotBlank() }
                ?: "https://github.com/msmayanksingh22/InstaSave/releases/download/v$remoteVersionName/app-release.apk"
            val fileSizeBytes = json.optLong("fileSizeBytes", 0L)

            val isAvailable = remoteVersionCode > currentVersionCode && apkUrl.isNotBlank()
            AppLogger.i(TAG, "Update check: Current=$currentVersionCode, Remote=$remoteVersionCode, Available=$isAvailable")

            AppUpdateInfo(
                versionCode = remoteVersionCode,
                versionName = remoteVersionName,
                releaseNotes = releaseNotes,
                apkUrl = apkUrl,
                fallbackApkUrl = fallbackApkUrl,
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
     * Supports automatic fallback if the primary URL fails.
     */
    suspend fun downloadUpdate(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (percent: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val urlsToTry = mutableListOf<String>()
        if (updateInfo.apkUrl.isNotBlank()) urlsToTry.add(updateInfo.apkUrl)
        if (!updateInfo.fallbackApkUrl.isNullOrBlank() && updateInfo.fallbackApkUrl != updateInfo.apkUrl) {
            urlsToTry.add(updateInfo.fallbackApkUrl)
        }

        for (url in urlsToTry) {
            AppLogger.i(TAG, "Attempting update download from: $url")
            val file = downloadSingleUrl(context, url, updateInfo, onProgress)
            if (file != null && file.exists() && file.length() > 0) {
                return@withContext file
            }
            AppLogger.w(TAG, "Failed downloading from $url, trying next candidate if available...")
        }
        null
    }

    private fun downloadSingleUrl(
        context: Context,
        targetUrl: String,
        updateInfo: AppUpdateInfo,
        onProgress: (percent: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): File? {
        try {
            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val apkFile = File(updateDir, "InstaSave-v${updateInfo.versionName}.apk")
            if (apkFile.exists()) apkFile.delete()

            val tempFile = File(updateDir, "InstaSave-download.tmp")
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "InstaSave-Updater/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.e(TAG, "Download APK failed: HTTP ${response.code} from $targetUrl")
                return null
            }

            val body = response.body ?: return null
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
                } else {
                    onProgress(-1, totalRead, 0L)
                }
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            return if (tempFile.renameTo(apkFile)) {
                AppLogger.i(TAG, "Update APK downloaded successfully: ${apkFile.length()} bytes -> ${apkFile.absolutePath}")
                apkFile
            } else if (tempFile.exists() && tempFile.length() > 0) {
                AppLogger.i(TAG, "Update APK saved as temp: ${tempFile.length()} bytes -> ${tempFile.absolutePath}")
                tempFile
            } else {
                null
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download APK exception from $targetUrl: ${e.message}", e)
            return null
        }
    }

    /**
     * Prompts the native Android package installer to install the downloaded APK.
     * Falls back to browser download if REQUEST_INSTALL_PACKAGES permission is unavailable
     * (omitted from manifest for Play Protect compliance).
     */
    fun promptInstall(context: Context, apkFile: File, fallbackApkUrl: String? = null): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            AppLogger.e(TAG, "Cannot install: APK file does not exist or is empty")
            return false
        }

        // On Android 8.0+, guard canRequestPackageInstalls() — without REQUEST_INSTALL_PACKAGES
        // in the manifest this throws SecurityException. Catch it and fall back to browser.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = try {
                context.packageManager.canRequestPackageInstalls()
            } catch (se: SecurityException) {
                AppLogger.w(TAG, "REQUEST_INSTALL_PACKAGES not declared — routing to browser download")
                // Fall back: open APK URL directly in browser for the user to download & install
                val browserUrl = fallbackApkUrl?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_APK_URL
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}
                return false
            }
            if (!canInstall) {
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
