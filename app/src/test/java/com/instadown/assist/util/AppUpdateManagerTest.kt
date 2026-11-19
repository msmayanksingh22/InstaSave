package com.instadown.assist.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppUpdateManagerTest {

    private val sampleManifestJson = """
        {
          "versionCode": 10,
          "versionName": "1.1.7",
          "releaseNotes": "• In-app seamless OTA update fix\n• Fixed update notification loop",
          "apkUrl": "https://raw.githubusercontent.com/msmayanksingh22/InstaSave/main/app-release.apk",
          "fallbackApkUrl": "https://github.com/msmayanksingh22/InstaSave/releases/download/v1.1.7/app-release.apk",
          "fileSizeBytes": 3851081,
          "sha256": "2f57fef68312c9b3ac7ba05ebad320bfc84042e14cbe391dbc017dd5033cc65b"
        }
    """.trimIndent()

    @Test
    fun testParseManifest_newerVersion_isAvailableTrue() {
        // App is on versionCode 9 (or older), remote is 10
        val updateInfo = AppUpdateManager.parseManifest(sampleManifestJson, currentVersionCode = 9L)
        assertNotNull(updateInfo)
        assertEquals(10L, updateInfo!!.versionCode)
        assertEquals("1.1.7", updateInfo.versionName)
        assertEquals(3851081L, updateInfo.fileSizeBytes)
        assertEquals("2f57fef68312c9b3ac7ba05ebad320bfc84042e14cbe391dbc017dd5033cc65b", updateInfo.sha256)
        assertTrue("Update must be marked available when remote versionCode is greater", updateInfo.isAvailable)
    }

    @Test
    fun testParseManifest_sameVersion_isAvailableFalse() {
        // App is already updated to versionCode 10, remote is 10
        // CRITICAL: Must be false to terminate the update loop!
        val updateInfo = AppUpdateManager.parseManifest(sampleManifestJson, currentVersionCode = 10L)
        assertNotNull(updateInfo)
        assertEquals(10L, updateInfo!!.versionCode)
        assertFalse("Update must NOT be available when current version equals remote version", updateInfo!!.isAvailable)
    }

    @Test
    fun testParseManifest_devBuildAhead_isAvailableFalse() {
        // App is a dev build with versionCode 11, remote is 10
        val updateInfo = AppUpdateManager.parseManifest(sampleManifestJson, currentVersionCode = 11L)
        assertNotNull(updateInfo)
        assertFalse("Update must NOT be available when local versionCode is ahead of remote", updateInfo!!.isAvailable)
    }

    @Test
    fun testParseManifest_64BitVersionCodes_noTruncation() {
        val largeCodeJson = """
            {
              "versionCode": 202611190100,
              "versionName": "2.0.0",
              "apkUrl": "https://example.com/app.apk"
            }
        """.trimIndent()
        val updateInfo = AppUpdateManager.parseManifest(largeCodeJson, currentVersionCode = 202611190000L)
        assertNotNull(updateInfo)
        assertEquals(202611190100L, updateInfo!!.versionCode)
        assertTrue(updateInfo.isAvailable)
    }

    @Test
    fun testParseManifest_blankApkUrl_isAvailableFalse() {
        val blankUrlJson = """
            {
              "versionCode": 12,
              "versionName": "1.2.0",
              "apkUrl": ""
            }
        """.trimIndent()
        val updateInfo = AppUpdateManager.parseManifest(blankUrlJson, currentVersionCode = 5L)
        assertNotNull(updateInfo)
        assertFalse("Update must not be available if APK URL is empty", updateInfo!!.isAvailable)
    }

    @Test
    fun testParseManifest_autoGeneratesFallbackUrl() {
        val noFallbackJson = """
            {
              "versionCode": 10,
              "versionName": "1.1.7",
              "apkUrl": "https://example.com/app.apk"
            }
        """.trimIndent()
        val updateInfo = AppUpdateManager.parseManifest(noFallbackJson, currentVersionCode = 5L)
        assertNotNull(updateInfo)
        assertEquals("https://github.com/msmayanksingh22/InstaSave/releases/download/v1.1.7/app-release.apk", updateInfo!!.fallbackApkUrl)
    }

    @Test
    fun testParseManifest_invalidJson_returnsNull() {
        val badJson = "not a valid json string"
        val updateInfo = AppUpdateManager.parseManifest(badJson, currentVersionCode = 1L)
        assertNull(updateInfo)
    }

    @Test
    fun testComputeSha256_matchesKnownHash() {
        val tempFile = File.createTempFile("sha_test", ".txt")
        try {
            tempFile.writeText("InstaSave SHA-256 Test String")
            val hash = AppUpdateManager.computeSha256(tempFile)
            assertNotNull(hash)
            assertEquals(64, hash.length)
        } finally {
            tempFile.delete()
        }
    }
}
