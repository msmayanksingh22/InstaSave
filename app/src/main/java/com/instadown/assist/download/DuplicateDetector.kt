package com.instadown.assist.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.instadown.assist.resolver.model.MediaType
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object DuplicateDetector {

    private const val TAG = "DuplicateDetector"
    private const val HISTORY_FILE_NAME = "download_history.json"

    // Active in-memory downloads tracked to prevent accidental double-tap races
    private val activeShortcodes = ConcurrentHashMap<String, Long>()

    enum class DuplicateStatus {
        NOT_DUPLICATE,
        SAME_POST_CURRENTLY_DOWNLOADING,
        SAME_POST_ALREADY_DOWNLOADED
    }

    data class HistoryEntry(
        val shortcode: String,
        val index: Int,
        val mediaType: String,
        val uriString: String,
        val fileName: String,
        val timestamp: Long
    )

    fun markActive(shortcode: String): Boolean {
        val prev = activeShortcodes.putIfAbsent(shortcode, System.currentTimeMillis())
        return prev == null
    }

    fun markInactive(shortcode: String) {
        activeShortcodes.remove(shortcode)
    }

    fun isCurrentlyDownloading(shortcode: String): Boolean {
        return activeShortcodes.containsKey(shortcode)
    }

    /**
     * Checks whether an item is currently downloading or has already been successfully downloaded.
     * If the recorded file was deleted from Gallery by the user, this returns NOT_DUPLICATE to permit redownload.
     */
    /**
     * Checks if an item has already been successfully downloaded and is still present/readable in MediaStore.
     */
    fun isAlreadyDownloaded(
        context: Context,
        shortcode: String,
        index: Int,
        mediaType: MediaType
    ): Boolean {
        return getExistingUri(context, shortcode, index, mediaType) != null
    }

    /**
     * Retrieves the existing readable MediaStore Uri for an item, if present.
     */
    fun getExistingUri(
        context: Context,
        shortcode: String,
        index: Int,
        mediaType: MediaType
    ): Uri? {
        val history = loadHistory(context)
        val key = buildKey(shortcode, index, mediaType)
        val entry = history[key] ?: return null

        val uri = try { Uri.parse(entry.uriString) } catch (e: Exception) { null }
        val readable = if (uri != null) MediaStoreWriter.isUriReadable(context.contentResolver, uri) else false
        if (readable) {
            return uri
        }

        // If the file was deleted or cannot be read, remove obsolete entry and permit fresh download
        Log.w(TAG, "History entry exists for $key but file is no longer readable at $uri; removing entry to permit fresh download")
        removeHistoryEntry(context, key)
        return null
    }

    /**
     * Checks whether an item is currently downloading or has already been successfully downloaded.
     */
    fun checkStatus(
        context: Context,
        shortcode: String,
        index: Int,
        mediaType: MediaType
    ): DuplicateStatus {
        if (isAlreadyDownloaded(context, shortcode, index, mediaType)) {
            return DuplicateStatus.SAME_POST_ALREADY_DOWNLOADED
        }
        if (isCurrentlyDownloading(shortcode)) {
            Log.i(TAG, "checkStatus: $shortcode is currently downloading")
            return DuplicateStatus.SAME_POST_CURRENTLY_DOWNLOADING
        }
        return DuplicateStatus.NOT_DUPLICATE
    }

    /**
     * Records a successfully verified download in persistent history.
     */
    fun recordCompleted(
        context: Context,
        shortcode: String,
        index: Int,
        mediaType: MediaType,
        uri: Uri,
        fileName: String
    ) {
        val key = buildKey(shortcode, index, mediaType)
        val entry = HistoryEntry(
            shortcode = shortcode,
            index = index,
            mediaType = mediaType.name,
            uriString = uri.toString(),
            fileName = fileName,
            timestamp = System.currentTimeMillis()
        )

        val history = loadHistory(context).toMutableMap()
        history[key] = entry
        saveHistory(context, history)
        Log.i(TAG, "Recorded download completion in history: $key -> $fileName ($uri)")
    }

    private fun buildKey(shortcode: String, index: Int, mediaType: MediaType): String {
        return "${shortcode}_${index}_${mediaType.name}"
    }

    @Synchronized
    private fun loadHistory(context: Context): Map<String, HistoryEntry> {
        val file = File(context.filesDir, HISTORY_FILE_NAME)
        if (!file.exists()) return emptyMap()

        return try {
            val jsonStr = file.readText(Charsets.UTF_8)
            val json = JSONObject(jsonStr)
            val result = mutableMapOf<String, HistoryEntry>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val obj = json.optJSONObject(k) ?: continue
                result[k] = HistoryEntry(
                    shortcode = obj.optString("shortcode", ""),
                    index = obj.optInt("index", 0),
                    mediaType = obj.optString("mediaType", "IMAGE"),
                    uriString = obj.optString("uriString", ""),
                    fileName = obj.optString("fileName", ""),
                    timestamp = obj.optLong("timestamp", 0L)
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error loading download history", e)
            emptyMap()
        }
    }

    @Synchronized
    private fun saveHistory(context: Context, history: Map<String, HistoryEntry>) {
        val file = File(context.filesDir, HISTORY_FILE_NAME)
        try {
            val json = JSONObject()
            for ((k, entry) in history) {
                val obj = JSONObject().apply {
                    put("shortcode", entry.shortcode)
                    put("index", entry.index)
                    put("mediaType", entry.mediaType)
                    put("uriString", entry.uriString)
                    put("fileName", entry.fileName)
                    put("timestamp", entry.timestamp)
                }
                json.put(k, obj)
            }
            file.writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving download history", e)
        }
    }

    @Synchronized
    private fun removeHistoryEntry(context: Context, key: String) {
        val history = loadHistory(context).toMutableMap()
        if (history.remove(key) != null) {
            saveHistory(context, history)
        }
    }

    @Synchronized
    fun clearHistory(context: Context) {
        val file = File(context.filesDir, HISTORY_FILE_NAME)
        if (file.exists()) {
            file.delete()
        }
        activeShortcodes.clear()
        Log.i(TAG, "Download history cleared.")
    }
}
