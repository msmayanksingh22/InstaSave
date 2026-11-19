package com.instadown.assist.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OtaReleaseInfo(
    val versionCode: Long?,          // Exact Android versionCode from release-manifest.json (null if API-only)
    val versionName: String,         // Normalized SemVer, e.g., "1.2.5"
    val tagName: String,             // e.g., "v1.2.5"
    val apkUrl: String,
    val sha256: String?,
    val sizeBytes: Long,
    val releaseNotes: String,
    val source: String
)

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        private val SEMVER_REGEX = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""")

        fun parseOrNull(raw: String?): SemVer? {
            if (raw.isNullOrBlank()) return null
            val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            val match = SEMVER_REGEX.find(cleaned) ?: return null
            val maj = match.groupValues[1].toIntOrNull() ?: return null
            val min = match.groupValues[2].toIntOrNull() ?: return null
            val pat = match.groupValues[3].toIntOrNull() ?: 0
            return SemVer(maj, min, pat)
        }
    }
}

class DualSourceUpdateResolver(
    context: Context,
    private val baseClient: OkHttpClient = OkHttpClient()
) {
    companion object {
        private const val TAG = "DualSourceUpdater"
        private const val REPO_OWNER = "msmayanksingh22"
        private const val REPO_NAME = "InstaSave"
        private const val RELEASES_LATEST_WEB = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"
        private const val RELEASES_LATEST_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        private const val RAW_MANIFEST_FALLBACK =
            "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/release-manifest.json"
        private const val PREF_NAME = "instasave_ota_etag_cache"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val noRedirectClient: OkHttpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val freshClient: OkHttpClient = baseClient.newBuilder()
        .followRedirects(true)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Resolves the newest release with 0-second CDN lag.
     * Priority:
     *   1) Web 302 redirect tag probe -> immutable release asset manifest (0s lag, no API quota)
     *   2) GitHub REST Releases API with ETag caching (0s lag, 304 saves quota)
     *   3) Raw GitHub main manifest (last-resort offline/CDN fallback)
     */
    suspend fun fetchLatestRelease(): OtaReleaseInfo? = withContext(Dispatchers.IO) {
        // Tier 1: Zero-quota instant tag probe (302 Location header)
        val latestTag = probeLatestTagViaRedirect()
        if (!latestTag.isNullOrBlank()) {
            AppLogger.i(TAG, "Probed latest tag via 302 redirect: $latestTag")
            val assetManifestUrl =
                "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$latestTag/release-manifest.json"
            fetchManifestJson(assetManifestUrl, source = "release_asset_manifest")?.let {
                AppLogger.i(TAG, "Found release manifest at release asset URL: v${it.versionName}")
                return@withContext it
            }

            val taggedRawUrl =
                "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$latestTag/release-manifest.json"
            fetchManifestJson(taggedRawUrl, source = "tagged_raw_manifest")?.let {
                AppLogger.i(TAG, "Found release manifest at tagged raw URL: v${it.versionName}")
                return@withContext it
            }
        }

        // Tier 2: GitHub REST API (/releases/latest) with ETag support
        fetchFromGitHubReleasesApi()?.let {
            AppLogger.i(TAG, "Resolved release from GitHub Releases API: ${it.tagName}")
            return@withContext it
        }

        // Tier 3: Last-resort raw main branch manifest
        fetchManifestJson(RAW_MANIFEST_FALLBACK, source = "raw_main_fallback")
    }

    /**
     * Safe hybrid comparator:
     *  - Uses exact 64-bit versionCode when available in release-manifest.json.
     *  - Falls back to strict numeric SemVer comparison if only API tag_name was available.
     */
    fun isUpdateNewer(
        remote: OtaReleaseInfo,
        installedVersionCode: Long,
        installedVersionName: String
    ): Boolean {
        val remoteCode = remote.versionCode
        if (remoteCode != null && remoteCode > 0L) {
            return remoteCode > installedVersionCode
        }
        val remoteSemVer = SemVer.parseOrNull(remote.versionName) ?: SemVer.parseOrNull(remote.tagName)
        val localSemVer = SemVer.parseOrNull(installedVersionName)
        return if (remoteSemVer != null && localSemVer != null) {
            remoteSemVer > localSemVer
        } else {
            false
        }
    }

    private fun probeLatestTagViaRedirect(): String? = runCatching {
        val req = Request.Builder()
            .url(RELEASES_LATEST_WEB)
            .head()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Pragma", "no-cache")
            .header("X-Request-Nonce", UUID.randomUUID().toString())
            .build()

        noRedirectClient.newCall(req).execute().use { resp ->
            if (resp.code in 301..308) {
                val location = resp.header("Location").orEmpty()
                val marker = "/releases/tag/"
                val idx = location.indexOf(marker)
                if (idx >= 0) {
                    location.substring(idx + marker.length).substringBefore('?').trim()
                        .takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } else {
                null
            }
        }
    }.getOrNull()

    private fun fetchManifestJson(url: String, source: String): OtaReleaseInfo? = runCatching {
        val req = Request.Builder()
            .url(url)
            .get()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Cache-Control", "no-cache, no-store, must-revalidate")
            .header("Pragma", "no-cache")
            .header("X-Request-Nonce", UUID.randomUUID().toString())
            .build()

        freshClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            val apkUrl = json.optString("apkUrl").takeIf { it.startsWith("https://") } ?: return null
            val versionName = json.optString("versionName").ifBlank {
                json.optString("tag").removePrefix("v")
            }
            OtaReleaseInfo(
                versionCode = json.optLong("versionCode", -1L).takeIf { it > 0L },
                versionName = versionName,
                tagName = json.optString("tag").ifBlank { "v$versionName" },
                apkUrl = apkUrl,
                sha256 = json.optString("sha256").takeIf { it.length == 64 },
                sizeBytes = json.optLong("fileSizeBytes", 0L).let { if (it > 0L) it else json.optLong("sizeBytes", 0L) },
                releaseNotes = json.optString("releaseNotes", ""),
                source = source
            )
        }
    }.getOrNull()

    private fun fetchFromGitHubReleasesApi(): OtaReleaseInfo? = runCatching {
        val savedEtag = prefs.getString("latest_etag", null)
        val reqBuilder = Request.Builder()
            .url(RELEASES_LATEST_API)
            .get()
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "InstaSave-Android-OTA")

        if (!savedEtag.isNullOrBlank()) {
            reqBuilder.header("If-None-Match", savedEtag)
        }

        freshClient.newCall(reqBuilder.build()).execute().use { resp ->
            val rawJson = when (resp.code) {
                304 -> prefs.getString("latest_api_json", null)
                in 200..299 -> {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        prefs.edit()
                            .putString("latest_etag", resp.header("ETag"))
                            .putString("latest_api_json", body)
                            .apply()
                    }
                    body
                }
                else -> null
            } ?: return null

            val root = JSONObject(rawJson)
            val tagName = root.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val assets = root.optJSONArray("assets") ?: return null

            var apkUrl: String? = null
            var apkSize = 0L
            var manifestAssetUrl: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val downloadUrl = asset.optString("browser_download_url")
                when {
                    name.equals("release-manifest.json", ignoreCase = true) -> manifestAssetUrl = downloadUrl
                    name.endsWith(".apk", ignoreCase = true) && apkUrl == null -> {
                        apkUrl = downloadUrl
                        apkSize = asset.optLong("size", 0L)
                    }
                }
            }

            // If release-manifest.json was attached to the release, read it for exact versionCode + SHA-256
            if (!manifestAssetUrl.isNullOrBlank()) {
                fetchManifestJson(manifestAssetUrl, source = "github_api_asset_manifest")?.let { return it }
            }

            val finalApkUrl = apkUrl ?: return null
            OtaReleaseInfo(
                versionCode = null, // SemVer fallback will compare tag_name safely
                versionName = tagName.removePrefix("v").removePrefix("V"),
                tagName = tagName,
                apkUrl = finalApkUrl,
                sha256 = null,
                sizeBytes = apkSize,
                releaseNotes = root.optString("body", ""),
                source = "github_releases_api"
            )
        }
    }.getOrNull()
}
