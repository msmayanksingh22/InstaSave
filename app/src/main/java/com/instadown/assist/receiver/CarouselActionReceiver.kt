package com.instadown.assist.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.instadown.assist.download.ClipboardDownloadCoordinator
import com.instadown.assist.download.InstagramDownloadCoordinator
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.util.DownloadNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CarouselActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CarouselActionReceiver"
        const val ACTION_SAVE_ALL = "com.instadown.assist.ACTION_SAVE_ALL"
        const val ACTION_DOWNLOAD_CURRENT = "com.instadown.assist.ACTION_DOWNLOAD_CURRENT"

        const val EXTRA_URL = "extra_instagram_url"
        const val EXTRA_TARGET_INDEX = "extra_target_index"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        Log.i(TAG, "onReceive: action=$action, notifId=$notifId, url=$url")

        // Dismiss the prompt notification immediately
        if (notifId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(notifId)
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        val prefs = InstaSavePreferences(appContext)
        val clipboardCoordinator = ClipboardDownloadCoordinator(appContext)
        val downloadCoordinator = InstagramDownloadCoordinator(appContext)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_SAVE_ALL -> {
                        executeSaveAll(appContext, prefs, clipboardCoordinator, downloadCoordinator, url)
                    }
                    ACTION_DOWNLOAD_CURRENT -> {
                        val targetIndex = intent.getIntExtra(EXTRA_TARGET_INDEX, -1)
                        if (targetIndex >= 0) {
                            executeDownloadCurrent(appContext, prefs, clipboardCoordinator, downloadCoordinator, url, targetIndex)
                        } else {
                            Log.w(TAG, "ACTION_DOWNLOAD_CURRENT called with invalid index: $targetIndex")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing action $action: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun executeSaveAll(
        context: Context,
        prefs: InstaSavePreferences,
        clipCoordinator: ClipboardDownloadCoordinator,
        downloadCoordinator: InstagramDownloadCoordinator,
        url: String
    ) {
        if (clipCoordinator.isProcessing) {
            Log.w(TAG, "executeSaveAll: already processing a download")
            return
        }
        clipCoordinator.startProcessing(url)

        val result = downloadCoordinator.executeDirectDownload(
            url = url,
            selectedIndices = null
        )

        if (result.success || (result.skippedCount > 0 && result.failedCount == 0)) {
            clipCoordinator.onProcessingSuccess(url, System.currentTimeMillis(), prefs)
            val count = result.completedCount
            val title = when {
                count > 1 -> "✓ $count photos saved"
                count == 1 -> "✓ Photo saved"
                else -> "✓ Already saved"
            }
            DownloadNotificationManager.showCompletionNotification(
                context = context,
                title = title,
                message = "Saved to ${prefs.saveLocationRelativePath}",
                notificationId = DownloadNotificationManager.PROMPT_RESULT_NOTIFICATION_ID
            )
        } else {
            clipCoordinator.onProcessingFailed(url)
            DownloadNotificationManager.showCompletionNotification(
                context = context,
                title = "Couldn't save",
                message = result.error ?: "Download failed",
                notificationId = DownloadNotificationManager.PROMPT_RESULT_NOTIFICATION_ID
            )
        }
    }

    private suspend fun executeDownloadCurrent(
        context: Context,
        prefs: InstaSavePreferences,
        clipCoordinator: ClipboardDownloadCoordinator,
        downloadCoordinator: InstagramDownloadCoordinator,
        url: String,
        targetIndex: Int
    ) {
        if (clipCoordinator.isProcessing) {
            Log.w(TAG, "executeDownloadCurrent: already processing a download")
            return
        }
        clipCoordinator.startProcessing(url)

        val result = downloadCoordinator.executeDirectDownload(
            url = url,
            selectedIndices = setOf(targetIndex)
        )

        if (result.success || (result.skippedCount > 0 && result.failedCount == 0)) {
            clipCoordinator.onProcessingSuccess(url, System.currentTimeMillis(), prefs)
            val isVideo = result.items.firstOrNull()?.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO
            val title = if (isVideo) "✓ Reel saved" else "✓ Photo saved"
            DownloadNotificationManager.showCompletionNotification(
                context = context,
                title = title,
                message = "Saved to ${prefs.saveLocationRelativePath}",
                notificationId = DownloadNotificationManager.PROMPT_RESULT_NOTIFICATION_ID
            )
        } else {
            clipCoordinator.onProcessingFailed(url)
            DownloadNotificationManager.showCompletionNotification(
                context = context,
                title = "Couldn't save",
                message = result.error ?: "Download failed",
                notificationId = DownloadNotificationManager.PROMPT_RESULT_NOTIFICATION_ID
            )
        }
    }
}
