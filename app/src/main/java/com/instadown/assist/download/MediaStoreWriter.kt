package com.instadown.assist.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.instadown.assist.download.model.DownloadErrorCode
import com.instadown.assist.resolver.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

object MediaStoreWriter {

    private const val TAG = "MediaStoreWriter"
    private const val BUFFER_SIZE = 64 * 1024 // High-throughput 64KB stream buffer

    data class WriteVerificationResult(
        val success: Boolean,
        val bytesWritten: Long,
        val uri: Uri?,
        val isVerified: Boolean,
        val error: String? = null,
        val errorCode: DownloadErrorCode? = null
    )

    /**
     * Creates a pending MediaStore record with IS_PENDING = 1.
     * Target collections:
     * - Images: Pictures/InstaDown/
     * - Videos: Videos/InstaDown/
     */
    fun createPendingEntry(
        context: Context,
        fileName: String,
        mimeType: String?,
        mediaType: MediaType
    ): Uri? {
        val prefs = com.instadown.assist.overlay.InstaSavePreferences(context)
        val configuredPath = prefs.saveLocationRelativePath.trimEnd('/')

        // Determine safe MediaStore relative path based on media type & target location
        val relativePath = if (mediaType == MediaType.VIDEO) {
            configuredPath
        } else {
            // For images, if user selected Movies/, fall back to Pictures/InstaSave
            if (configuredPath.startsWith("Movies", ignoreCase = true)) {
                "Pictures/InstaSave"
            } else {
                configuredPath
            }
        }

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType ?: if (mediaType == MediaType.VIDEO) "video/mp4" else "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath.startsWith("Download", ignoreCase = true)) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else if (mediaType == MediaType.VIDEO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        return try {
            val uri = resolver.insert(collectionUri, contentValues)
            if (uri == null) {
                Log.e(TAG, "Failed to insert MediaStore row for $fileName")
            } else {
                Log.d(TAG, "Created pending MediaStore entry: $uri ($fileName)")
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Exception inserting MediaStore entry for $fileName", e)
            null
        }
    }

    /**
     * Streams data from InputStream directly into the MediaStore Uri using buffered I/O,
     * tracks bytes written, verifies container/magic bytes and content integrity,
     * and publishes the file by setting IS_PENDING = 0.
     */
    suspend fun streamAndVerify(
        context: Context,
        uri: Uri,
        inputStream: InputStream,
        expectedBytes: Long?,
        mediaType: MediaType,
        onProgress: ((bytesRead: Long, totalBytes: Long?) -> Unit)? = null,
        isCancelled: () -> Boolean = { false }
    ): WriteVerificationResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var bytesWritten = 0L
        var outputStream: OutputStream? = null

        try {
            outputStream = resolver.openOutputStream(uri)
            if (outputStream == null) {
                deleteIncompleteEntry(resolver, uri)
                return@withContext WriteVerificationResult(
                    success = false,
                    bytesWritten = 0L,
                    uri = uri,
                    isVerified = false,
                    error = "Unable to open MediaStore OutputStream for $uri",
                    errorCode = DownloadErrorCode.STORAGE_ERROR
                )
            }

            val buffer = ByteArray(BUFFER_SIZE)
            var read: Int
            var firstBytes: ByteArray? = null
            var lastEmitMs = 0L
            var lastEmitPercent = -1

            while (inputStream.read(buffer).also { read = it } != -1) {
                if (isCancelled()) {
                    Log.w(TAG, "Download cancelled during stream write for $uri")
                    outputStream.close()
                    deleteIncompleteEntry(resolver, uri)
                    return@withContext WriteVerificationResult(
                        success = false,
                        bytesWritten = bytesWritten,
                        uri = uri,
                        isVerified = false,
                        error = "Download cancelled by user",
                        errorCode = DownloadErrorCode.CANCELLED
                    )
                }

                if (firstBytes == null && read > 0) {
                    firstBytes = buffer.copyOf(minOf(read, 64))
                }

                outputStream.write(buffer, 0, read)
                bytesWritten += read

                val now = System.currentTimeMillis()
                val currentPercent = if (expectedBytes != null && expectedBytes > 0) {
                    ((bytesWritten * 100) / expectedBytes).toInt()
                } else -1

                if (now - lastEmitMs >= 120 || currentPercent != lastEmitPercent || bytesWritten == expectedBytes) {
                    lastEmitMs = now
                    lastEmitPercent = currentPercent
                    onProgress?.invoke(bytesWritten, expectedBytes)
                }
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null

            // 1. Verify byte count
            if (bytesWritten <= 0L) {
                deleteIncompleteEntry(resolver, uri)
                return@withContext WriteVerificationResult(
                    success = false,
                    bytesWritten = 0L,
                    uri = uri,
                    isVerified = false,
                    error = "File written had 0 bytes",
                    errorCode = DownloadErrorCode.VERIFICATION_FAILED
                )
            }

            if (expectedBytes != null && expectedBytes > 0L && bytesWritten != expectedBytes) {
                Log.w(TAG, "Byte count mismatch: expected $expectedBytes, wrote $bytesWritten")
                deleteIncompleteEntry(resolver, uri)
                return@withContext WriteVerificationResult(
                    success = false,
                    bytesWritten = bytesWritten,
                    uri = uri,
                    isVerified = false,
                    error = "Byte count mismatch: expected $expectedBytes, wrote $bytesWritten",
                    errorCode = DownloadErrorCode.VERIFICATION_FAILED
                )
            }

            // 2. Separate "download completed" from "download verified"
            // Verify container & magic bytes from captured initial stream buffer
            val structureError = validateHeaderBytes(firstBytes, mediaType)
            if (structureError != null) {
                Log.e(TAG, "File structure validation failed for $uri: $structureError")
                deleteIncompleteEntry(resolver, uri)
                return@withContext WriteVerificationResult(
                    success = false,
                    bytesWritten = bytesWritten,
                    uri = uri,
                    isVerified = false,
                    error = structureError,
                    errorCode = DownloadErrorCode.VERIFICATION_FAILED
                )
            }

            // 3. Commit file to MediaStore (IS_PENDING = 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val updated = resolver.update(uri, updateValues, null, null)
                if (updated <= 0) {
                    throw java.io.IOException("Unable to publish downloaded media")
                }
            }

            // 4. Final verification: verify that the committed file can now be reopened and read
            val canReopen = try {
                resolver.openInputStream(uri)?.use { it.read() != -1 } ?: false
            } catch (e: Exception) {
                Log.w(TAG, "Reopen check exception for committed file $uri: ${e.message}")
                false
            }

            if (!canReopen) throw java.io.IOException("Downloaded media cannot be reopened")

            Log.i(TAG, "Successfully written, committed, and verified $bytesWritten bytes for $uri (mediaType=$mediaType, readable=$canReopen)")
            WriteVerificationResult(
                success = true,
                bytesWritten = bytesWritten,
                uri = uri,
                isVerified = true
            )

        } catch (e: Exception) {
            Log.e(TAG, "Exception during media stream write to $uri", e)
            try { outputStream?.close() } catch (ignored: Exception) {}
            deleteIncompleteEntry(resolver, uri)
            if (e is kotlinx.coroutines.CancellationException) throw e
            WriteVerificationResult(
                success = false,
                bytesWritten = bytesWritten,
                uri = uri,
                isVerified = false,
                error = "IO Exception: ${e.message}",
                errorCode = DownloadErrorCode.STORAGE_ERROR
            )
        }
    }

    /**
     * Validates that the captured header bytes match the expected media container format.
     */
    private fun validateHeaderBytes(header: ByteArray?, mediaType: MediaType): String? {
        if (header == null || header.size < 4) {
            return "Header buffer too short: ${header?.size ?: 0} bytes"
        }

        if (mediaType == MediaType.VIDEO) {
            // Check for MP4 ftyp or moov box in first 64 bytes
            val headerStr = String(header, 0, header.size, Charsets.ISO_8859_1)
            if (!headerStr.contains("ftyp") && !headerStr.contains("moov")) {
                return "Invalid video container: missing MP4 ftyp/moov box"
            }
        } else {
            // Check for JPEG (FF D8 FF), PNG (89 50 4E 47), or WebP (RIFF....WEBP)
            val isJpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
            val isPng = header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
            val isWebp = header.size >= 12 &&
                    header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()

            if (!isJpeg && !isPng && !isWebp) {
                return "Invalid image header magic bytes (not JPEG, PNG, or WebP)"
            }
        }
        return null
    }

    /**
     * Cleanly purges any incomplete, corrupted, or cancelled MediaStore record.
     */
    fun deleteIncompleteEntry(resolver: ContentResolver, uri: Uri?) {
        if (uri == null) return
        try {
            val deleted = resolver.delete(uri, null, null)
            Log.d(TAG, "Deleted incomplete/failed MediaStore entry: $uri (result=$deleted)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete incomplete MediaStore entry $uri: ${e.message}")
        }
    }

    /**
     * Checks whether an existing MediaStore URI is currently present and readable.
     */
    /**
     * Checks whether an existing MediaStore URI is currently present, not trashed, and readable.
     * Uses cursor-first approach so hard-deletes and trash both return false reliably.
     */
    fun isUriReadable(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            // Step 1: Cursor existence check (fastest; handles hard-delete and trash)
            val projection = mutableListOf(android.provider.MediaStore.MediaColumns._ID)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                projection.add(android.provider.MediaStore.MediaColumns.IS_TRASHED)
            }
            val cursor = resolver.query(uri, projection.toTypedArray(), null, null, null)
            val existsAndNotTrashed = cursor?.use { c ->
                if (!c.moveToFirst()) return@use false  // row gone -> hard deleted
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.IS_TRASHED)
                    if (idx >= 0 && c.getInt(idx) != 0) return@use false  // trashed
                }
                true
            } ?: false
            if (!existsAndNotTrashed) return false
            // Step 2: Confirm byte-level access
            resolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "isUriReadable failed for $uri: ${e.message}")
            false
        }
    }
}
