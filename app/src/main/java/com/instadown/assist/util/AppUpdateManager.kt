package com.instadown.assist.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val fallbackApkUrl: String? = null,
    val fileSizeBytes: Long = 0L,
    val sha256: String? = null,
    val isAvailable: Boolean = false
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"

    // Default release metadata endpoint
    const val DEFAULT_UPDATE_URL = "https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/release-manifest.json"
    const val DEFAULT_APK_URL = "https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/app-release.apk"

    // Persistent state keys to survive Android 11+ process-kill when unknown sources toggle changes
    private const val PREFS_UPDATE_NAME = "instasave_ota_update_state"
    private const val KEY_PENDING_APK_PATH = "pending_apk_path"
    private const val KEY_PENDING_VERSION_CODE = "pending_version_code"
    private const val KEY_PENDING_SHA256 = "pending_sha256"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Retrieves the installed application versionCode as a 64-bit Long safely across API 26-36.
     * Prevents NoSuchMethodError on Android 8.0-8.1 (minSdk=26) and avoids 32-bit integer truncation.
     */
    fun getCurrentVersionCode(context: Context): Long {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(pInfo)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to read installed package version: ${e.message}")
            1L
        }
    }

    /**
     * Checks if the app has permission to install unknown APKs.
     * On Android 8.0+ (API 26+), checks PackageInstaller capability.
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (e: Exception) {
                AppLogger.w(TAG, "canRequestPackageInstalls check failed: ${e.message}")
                false
            }
        } else {
            true
        }
    }

    /**
     * Checks whether an update APK has already been downloaded and is ready to install in filesDir.
     * Stored in private filesDir/updates/ to protect against cache eviction during storage pressure.
     */
    fun getDownloadedUpdateApk(context: Context, versionName: String): File? {
        val updateDir = File(context.filesDir, "updates")
        val apkFile = File(updateDir, "InstaSave-v${versionName}.apk")
        return if (apkFile.exists() && apkFile.length() > 0) apkFile else null
    }

    /**
     * Persists pending installation state across Android 11+ process death.
     */
    fun savePendingInstall(context: Context, apkFile: File, versionCode: Long, sha256: String?) {
        context.getSharedPreferences(PREFS_UPDATE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_APK_PATH, apkFile.absolutePath)
            .putLong(KEY_PENDING_VERSION_CODE, versionCode)
            .putString(KEY_PENDING_SHA256, sha256)
            .apply()
    }

    /**
     * Clears pending installation state from disk.
     */
    fun clearPendingInstall(context: Context) {
        context.getSharedPreferences(PREFS_UPDATE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /**
     * Retrieves saved pending installation state if the APK file still exists on disk.
     */
    fun getPendingInstall(context: Context): Triple<File, Long, String?>? {
        val prefs = context.getSharedPreferences(PREFS_UPDATE_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_PENDING_APK_PATH, null) ?: return null
        val versionCode = prefs.getLong(KEY_PENDING_VERSION_CODE, -1L)
        val sha256 = prefs.getString(KEY_PENDING_SHA256, null)
        val file = File(path)
        return if (file.exists() && file.length() > 0) Triple(file, versionCode, sha256) else null
    }

    /**
     * Checks if the device has sufficient storage space to download and unpack the update.
     * Requires at least 2.5x the APK size (minimum 15 MB) for safe ART compilation.
     */
    fun hasEnoughDiskSpace(context: Context, expectedBytes: Long): Boolean {
        val updateDir = File(context.filesDir, "updates")
        if (!updateDir.exists()) updateDir.mkdirs()
        val requiredBytes = (expectedBytes * 2.5).toLong().coerceAtLeast(15L * 1024L * 1024L)
        val freeSpace = updateDir.usableSpace
        AppLogger.i(TAG, "Storage pre-flight check: Available=${freeSpace / (1024 * 1024)}MB, Required=${requiredBytes / (1024 * 1024)}MB")
        return freeSpace >= requiredBytes
    }

    /**
     * Computes the SHA-256 hash of a file as a lowercase hex string.
     */
    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Validates that the downloaded APK is non-empty, matches expected SHA-256 (if given),
     * and is a valid package archive belonging to this application.
     */
    fun verifyDownloadedApk(
        context: Context,
        apkFile: File,
        expectedVersionCode: Long = -1L,
        expectedSha256: String? = null
    ): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            AppLogger.e(TAG, "Integrity check failed: APK file does not exist or is empty")
            return false
        }

        // 1. Verify SHA-256 checksum if provided in manifest
        if (!expectedSha256.isNullOrBlank()) {
            val actualSha256 = try {
                computeSha256(apkFile)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed computing SHA-256: ${e.message}")
                return false
            }
            if (!actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                AppLogger.e(TAG, "SHA-256 mismatch! Expected: $expectedSha256, actual: $actualSha256")
                return false
            }
            AppLogger.i(TAG, "SHA-256 checksum verified successfully: $actualSha256")
        }

        // 2. Verify APK package archive metadata using PackageManager
        val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        if (archiveInfo == null) {
            AppLogger.e(TAG, "Integrity check failed: getPackageArchiveInfo returned null (corrupted or invalid APK)")
            return false
        }

        if (archiveInfo.packageName != context.packageName) {
            AppLogger.e(TAG, "Package name mismatch! Expected: ${context.packageName}, actual: ${archiveInfo.packageName}")
            return false
        }

        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        if (expectedVersionCode > 0 && archiveVersionCode != expectedVersionCode) {
            AppLogger.e(TAG, "Version code mismatch! Expected: $expectedVersionCode, actual: $archiveVersionCode")
            return false
        }

        AppLogger.i(TAG, "APK archive integrity verified: package=${archiveInfo.packageName}, versionCode=$archiveVersionCode")
        return true
    }

    /**
     * Automatically resumes installation if an APK was downloaded and the user
     * just granted the "Install unknown apps" permission from Settings.
     * Survives Android 11+ process force-stop by reading persisted disk metadata.
     */
    fun resumePendingInstallIfPermitted(context: Context): Boolean {
        if (!canInstallPackages(context)) return false
        val pending = getPendingInstall(context) ?: return false
        val (apkFile, expectedVersionCode, expectedSha256) = pending

        // CRITICAL: Clear pending state before firing installer so if user taps "Cancel",
        // they are not trapped in a recurring popup loop on each subsequent onResume().
        clearPendingInstall(context)

        if (!apkFile.exists() || apkFile.length() == 0L) {
            AppLogger.w(TAG, "Pending APK missing on resume: ${apkFile.absolutePath}")
            return false
        }

        if (!verifyDownloadedApk(context, apkFile, expectedVersionCode, expectedSha256)) {
            AppLogger.e(TAG, "Pending APK failed integrity check on resume")
            apkFile.delete()
            return false
        }

        AppLogger.i(TAG, "Resuming pending installation after permission grant: ${apkFile.name}")
        return promptInstall(context, apkFile, null, expectedVersionCode, expectedSha256)
    }

    /**
     * Checks if a newer version of InstaSave is available, with CDN cache busting.
     */
    suspend fun checkForUpdates(context: Context, customUrl: String? = null): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val targetUrl = customUrl?.ifBlank { null } ?: DEFAULT_UPDATE_URL
        try {
            AppLogger.i(TAG, "Checking for updates from $targetUrl...")
            val currentVersionCode = getCurrentVersionCode(context)

            // Bust GitHub Raw and CDN caches by appending timestamp query and no-cache headers
            val separator = if (targetUrl.contains("?")) "&" else "?"
            val cacheBustedUrl = "$targetUrl${separator}_cb=${System.currentTimeMillis()}"

            val request = Request.Builder()
                .url(cacheBustedUrl)
                .header("User-Agent", "InstaSave-Updater/1.0")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                AppLogger.w(TAG, "Update check failed: HTTP ${response.code}")
                return@withContext null
            }

            val jsonStr = response.body?.string() ?: return@withContext null
            val updateInfo = parseManifest(jsonStr, currentVersionCode)
            if (updateInfo != null) {
                AppLogger.i(TAG, "Update check: Current=$currentVersionCode, Remote=${updateInfo.versionCode}, Available=${updateInfo.isAvailable}")
            }
            updateInfo
        } catch (e: Exception) {
            AppLogger.w(TAG, "Check updates error: ${e.message}")
            null
        }
    }

    /**
     * Parses the update manifest JSON and calculates update availability against 64-bit currentVersionCode.
     */
    fun parseManifest(jsonStr: String, currentVersionCode: Long): AppUpdateInfo? {
        return try {
            val json = JSONObject(jsonStr)
            val remoteVersionCode = json.optLong("versionCode", 0L)
            val remoteVersionName = json.optString("versionName", "1.0")
            val releaseNotes = json.optString("releaseNotes", "New performance and feature updates.")
            val apkUrl = json.optString("apkUrl", "")
            val fallbackApkUrl = json.optString("fallbackApkUrl", "").takeIf { it.isNotBlank() }
                ?: "https://github.com/msmayanksingh22/InstaSave/releases/download/v$remoteVersionName/app-release.apk"
            val fileSizeBytes = json.optLong("fileSizeBytes", 0L)
            val sha256 = json.optString("sha256", "").takeIf { it.isNotBlank() }

            val isAvailable = remoteVersionCode > currentVersionCode && apkUrl.isNotBlank()

            AppUpdateInfo(
                versionCode = remoteVersionCode,
                versionName = remoteVersionName,
                releaseNotes = releaseNotes,
                apkUrl = apkUrl,
                fallbackApkUrl = fallbackApkUrl,
                fileSizeBytes = fileSizeBytes,
                sha256 = sha256,
                isAvailable = isAvailable
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads the update APK to the app private files directory with progress reporting.
     * Includes pre-flight storage check and automatic fallback.
     */
    suspend fun downloadUpdate(
        context: Context,
        updateInfo: AppUpdateInfo,
        onProgress: (percent: Int, bytesRead: Long, totalBytes: Long) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        // Pre-flight disk space check before wasting cellular data
        if (!hasEnoughDiskSpace(context, updateInfo.fileSizeBytes)) {
            AppLogger.e(TAG, "Insufficient disk space to download update package")
            return@withContext null
        }

        val urlsToTry = mutableListOf<String>()
        if (updateInfo.apkUrl.isNotBlank()) urlsToTry.add(updateInfo.apkUrl)
        if (!updateInfo.fallbackApkUrl.isNullOrBlank() && updateInfo.fallbackApkUrl != updateInfo.apkUrl) {
            urlsToTry.add(updateInfo.fallbackApkUrl)
        }

        for (url in urlsToTry) {
            AppLogger.i(TAG, "Attempting update download from: $url")
            val file = downloadSingleUrl(context, url, updateInfo, onProgress)
            if (file != null && file.exists() && file.length() > 0) {
                // Post-download validation of archive structure & checksum
                if (verifyDownloadedApk(context, file, updateInfo.versionCode, updateInfo.sha256)) {
                    return@withContext file
                } else {
                    AppLogger.w(TAG, "Downloaded APK failed verification, deleting and retrying...")
                    file.delete()
                }
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
            val updateDir = File(context.filesDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val apkFile = File(updateDir, "InstaSave-v${updateInfo.versionName}.apk")
            if (apkFile.exists()) apkFile.delete()

            val tempFile = File(updateDir, "InstaSave-download.tmp")
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "InstaSave-Updater/1.0")
                .header("Cache-Control", "no-cache")
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
            } else {
                try {
                    tempFile.copyTo(apkFile, overwrite = true)
                    tempFile.delete()
                    AppLogger.i(TAG, "Update APK copied to destination: ${apkFile.length()} bytes -> ${apkFile.absolutePath}")
                    apkFile
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed renaming tmp file to destination: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Download APK exception from $targetUrl: ${e.message}", e)
            return null
        }
    }

    /**
     * Prompts the native Android package installer to install the downloaded APK.
     * If the user has not yet permitted installs from this app, saves the state to SharedPreferences
     * (surviving Android 11+ process-kill) and directs the user to the Unknown App Sources settings page.
     */
    fun promptInstall(
        context: Context,
        apkFile: File,
        fallbackApkUrl: String? = null,
        expectedVersionCode: Long = -1L,
        expectedSha256: String? = null
    ): Boolean {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            AppLogger.e(TAG, "Cannot install: APK file does not exist or is empty")
            return false
        }

        // Verify APK integrity before attempting package installer launch
        if (!verifyDownloadedApk(context, apkFile, expectedVersionCode, expectedSha256)) {
            AppLogger.e(TAG, "Pre-installation integrity check failed")
            apkFile.delete()
            try {
                android.widget.Toast.makeText(context, "Update package corrupted. Please re-download.", android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            return false
        }

        // On Android 8.0+ (API 26+), verify and request unknown app sources permission if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!canInstallPackages(context)) {
                // Persist state to disk so it survives Android 11+ process force-kill
                savePendingInstall(context, apkFile, expectedVersionCode, expectedSha256)
                AppLogger.i(TAG, "Install permission not yet granted. Saved state to disk & directing user to Settings...")

                try {
                    android.widget.Toast.makeText(
                        context,
                        "Please allow 'Install unknown apps' for InstaSave to complete update",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {}

                val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(manageIntent)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to open unknown sources settings: ${e.message}", e)
                    try {
                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                }
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

            // Explicitly grant URI read permission to all matching activities (now found via <queries>)
            val resInfoList = context.packageManager.queryIntentActivities(
                installIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resInfoList) {
                val targetPackage = resolveInfo.activityInfo.packageName
                context.grantUriPermission(targetPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            clearPendingInstall(context)
            context.startActivity(installIntent)
            AppLogger.i(TAG, "Successfully launched Android PackageInstaller for $contentUri")
            return true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch package installer: ${e.message}", e)
            if (!fallbackApkUrl.isNullOrBlank()) {
                try {
                    android.widget.Toast.makeText(context, "Opening direct download...", android.widget.Toast.LENGTH_SHORT).show()
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackApkUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}
            }
            return false
        }
    }

    /**
     * Self-cleaning routine: scans filesDir/updates/ and removes obsolete update packages
     * whose version is less than or equal to the currently running version.
     */
    fun cleanupOldUpdateApks(context: Context) {
        try {
            val currentVersion = getCurrentVersionCode(context)
            val updateDir = File(context.filesDir, "updates")
            if (updateDir.exists() && updateDir.isDirectory) {
                updateDir.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".tmp")) {
                        file.delete()
                    } else if (file.name.endsWith(".apk")) {
                        val archiveInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                        if (archiveInfo != null) {
                            val apkVersion = PackageInfoCompat.getLongVersionCode(archiveInfo)
                            if (apkVersion <= currentVersion) {
                                AppLogger.i(TAG, "Cleaned up obsolete update package: ${file.name} (v$apkVersion <= current v$currentVersion)")
                                file.delete()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Cleanup old updates error: ${e.message}")
        }
    }
}
