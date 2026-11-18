package com.instadown.assist.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.util.regex.Pattern

object ClipboardReader {
    private const val TAG = "InstaDownloaderPOC"

    // Matches https://www.instagram.com/p/... or /reel/... or /reels/... or without www
    val INSTAGRAM_URL_REGEX: Pattern = Pattern.compile(
        "https?://(?:www\\.)?instagram\\.com/(?:p|reel|reels)/([a-zA-Z0-9_-]+)/?.*"
    )

    data class ClipResult(
        val text: String,
        val mimeTypes: List<String>,
        val isValidInstagramUrl: Boolean,
        val cleanUrl: String,
        val timestamp: Long = 0L
    )

    fun readCurrentClip(context: Context): ClipResult {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = try {
            clipboard.primaryClip
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException reading primaryClip: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception reading primaryClip: ${e.message}")
            null
        }

        if (clip == null || clip.itemCount == 0) {
            return ClipResult(
                text = "",
                mimeTypes = emptyList(),
                isValidInstagramUrl = false,
                cleanUrl = "",
                timestamp = 0L
            )
        }

        val description: ClipDescription? = clip.description
        val mimes = mutableListOf<String>()
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                mimes.add(description.getMimeType(i))
            }
        }

        val rawText = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim() ?: ""
        val (isValid, cleanUrl) = validateInstagramUrl(rawText)
        val clipTime = description?.timestamp ?: 0L

        return ClipResult(
            text = rawText,
            mimeTypes = mimes,
            isValidInstagramUrl = isValid,
            cleanUrl = cleanUrl,
            timestamp = clipTime
        )
    }

    fun validateInstagramUrl(text: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to ""
        val matcher = INSTAGRAM_URL_REGEX.matcher(text)
        return if (matcher.find()) {
            val url = matcher.group()
            true to url
        } else {
            false to ""
        }
    }
}
