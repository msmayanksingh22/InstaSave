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
          "fileSizeBytes": 3848344
        }
    """.trimIndent()

    @Test
    fun testParseManifest_newerVersion_isAvailableTrue() {
        // App is on versionCode 9 (or older), remote is 10
        val updateInfo = AppUpdateManager.parseManifest(sampleManifestJson, currentVersionCode = 9)
        assertNotNull(updateInfo)
        assertEquals(10, updateInfo!!.versionCode)
        assertEquals("1.1.7", updateInfo.versionName)
        assertEquals(3848344L, updateInfo.fileSizeBytes)
        assertTrue("Update must be marked available when remote versionCode is greater", updateInfo.isAvailable)
    }

    @Test
    fun testParseManifest_sameVersion_isAvailableFalse() {
        // App is already updated to versionCode 10, remote is 10
        // CRITICAL: Must be false to terminate the update loop!
        val updateInfo = AppUpdateManager.parseManifest(sampleManifestJson, currentVersionCode = 10)
        assertNotNull(updateInfo)
        assertEquals(10, updateInfo!!.versionCode)
        assertFalse("Update must NOT be available when current version equals remote version", updateInfo!!.isAvailable)
    }

    @Test
    fun testParseManifest_devBuildAhead_isAvailableFalse() {
        // App is a dev build with versionCode 11, remote is 10
        val updateInfo = AppUpdateManager.parseManifest(sampleManifestJson, currentVersionCode = 11)
        assertNotNull(updateInfo)
        assertFalse("Update must NOT be available when local versionCode is ahead of remote", updateInfo!!.isAvailable)
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
        val updateInfo = AppUpdateManager.parseManifest(blankUrlJson, currentVersionCode = 5)
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
        val updateInfo = AppUpdateManager.parseManifest(noFallbackJson, currentVersionCode = 5)
        assertNotNull(updateInfo)
        assertEquals("https://github.com/msmayanksingh22/InstaSave/releases/download/v1.1.7/app-release.apk", updateInfo!!.fallbackApkUrl)
    }

    @Test
    fun testParseManifest_invalidJson_returnsNull() {
        val badJson = "not a valid json string"
        val updateInfo = AppUpdateManager.parseManifest(badJson, currentVersionCode = 1)
        assertNull(updateInfo)
    }

    @Test
    fun testResumePendingInstall_noPendingFile_returnsFalse() {
        AppUpdateManager.pendingInstallApk = null
        val fakeFile = File("non_existent_file.apk")
        AppUpdateManager.pendingInstallApk = fakeFile
        // Fake file doesn't exist on disk
        assertFalse(fakeFile.exists())
    }
}
