package com.instadown.assist.data

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.instadown.assist.data.db.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Event-driven MediaStore observer that detects external file deletions or modifications
 * in the Android Gallery / MediaStore and triggers debounced reconciliation.
 */
class MediaStoreSyncObserver(
    private val context: Context,
    private val scope: CoroutineScope
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        private const val TAG = "MediaStoreSyncObserver"
        private const val DEBOUNCE_DELAY_MS = 500L
    }

    private var debounceJob: Job? = null
    private var isRegistered = false

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Log.d(TAG, "MediaStore change detected: $uri")

        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            delay(DEBOUNCE_DELAY_MS)
            try {
                Log.i(TAG, "Executing debounced MediaStore reconciliation...")
                val repo = DownloadRepository.getInstance(context)
                val updatedCount = repo.reconcileWithMediaStore()
                Log.i(TAG, "Debounced reconciliation finished: $updatedCount items updated")
            } catch (e: Exception) {
                Log.w(TAG, "Error in debounced MediaStore reconciliation: ${e.message}")
            }
        }
    }

    fun register() {
        if (isRegistered) return
        try {
            val resolver = context.contentResolver
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, this)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, this)
            isRegistered = true
            Log.d(TAG, "Registered MediaStore observers for images and videos")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register MediaStore observer", e)
        }
    }

    fun unregister() {
        if (!isRegistered) return
        try {
            context.contentResolver.unregisterContentObserver(this)
            debounceJob?.cancel()
            isRegistered = false
            Log.d(TAG, "Unregistered MediaStore observers")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister MediaStore observer", e)
        }
    }
}
