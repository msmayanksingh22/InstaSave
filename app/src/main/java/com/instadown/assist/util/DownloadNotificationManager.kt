package com.instadown.assist.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.instadown.assist.R
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.service.FloatingTriggerService
import com.instadown.assist.ui.MainActivity

object DownloadNotificationManager {
    private const val TAG = "DownloadNotification"
    
    const val CHANNEL_ID_SERVICE = "instasave_service_channel"
    const val CHANNEL_ID_DOWNLOADS = "instasave_downloads_channel"
    const val CHANNEL_ID_PROMPTS = "instasave_prompts_channel"

    const val SERVICE_NOTIFICATION_ID = 1001
    const val PROMPT_NOTIFICATION_ID = 2000
    const val PROMPT_RESULT_NOTIFICATION_ID = 2001

    fun createNotificationChannel(context: Context) = createNotificationChannels(context)

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Persistent Foreground Service Channel (Low importance: silent, no popups)
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "InstaSave Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when InstaSave is running in background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 2. Completed Downloads Channel
            val downloadChannel = NotificationChannel(
                CHANNEL_ID_DOWNLOADS,
                "Media Downloads",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for saved photos and reels"
            }
            notificationManager.createNotificationChannel(downloadChannel)

            // 3. High-Priority Heads-up Prompts Channel (for Carousel Ask actions)
            val promptChannel = NotificationChannel(
                CHANNEL_ID_PROMPTS,
                "Carousel Prompts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up prompt when multiple carousel photos are detected"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(promptChannel)
        }
    }

    /**
     * Builds the persistent foreground service notification with an explicit "Stop InstaSave" action.
     */
    fun createServiceRunningNotification(context: Context): Notification {
        createNotificationChannels(context)

        // Tap notification -> Open MainActivity
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action -> Stop InstaSave service directly via broadcast receiver
        val stopIntent = Intent(context, com.instadown.assist.receiver.ServiceControlReceiver::class.java).apply {
            action = FloatingTriggerService.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.drawable.ic_download_arrow)
            .setContentTitle("InstaSave is running")
            .setContentText("Ready to save Instagram media")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_close, "Stop InstaSave", stopPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Dispatches optional user notification when a download completes.
     */
    fun showCompletionNotification(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt() + 2000
    ) {
        val prefs = InstaSavePreferences(context)
        if (!prefs.isCompletionNotificationsEnabled) return

        // On Android 13+, POST_NOTIFICATIONS is a runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Completion notification skipped: POST_NOTIFICATIONS permission not granted")
                return
            }
        }

        try {
            createNotificationChannels(context)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOADS)
                .setSmallIcon(R.drawable.ic_download_arrow)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dispatch completion notification: ${e.message}")
        }
    }

    /**
     * Shows a high-priority heads-up notification when a carousel is detected in ASK mode.
     * Actions:
     * - SAVE ALL: saves all carousel items directly
     * - CHOOSE: opens MediaSelectionActivity to let user choose items
     * - CURRENT: (optional) downloads only the currently visible slide if highConfidenceSlideIndex != null
     */
    fun showCarouselPromptNotification(
        context: Context,
        url: String,
        itemCount: Int,
        highConfidenceSlideIndex: Int? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Prompt notification skipped: POST_NOTIFICATIONS permission not granted")
                return
            }
        }

        try {
            createNotificationChannels(context)
            val notifId = PROMPT_NOTIFICATION_ID

            // 1. SAVE ALL Action
            val saveAllIntent = Intent(context, com.instadown.assist.receiver.CarouselActionReceiver::class.java).apply {
                action = com.instadown.assist.receiver.CarouselActionReceiver.ACTION_SAVE_ALL
                putExtra(com.instadown.assist.receiver.CarouselActionReceiver.EXTRA_URL, url)
                putExtra(com.instadown.assist.receiver.CarouselActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
            }
            val saveAllPending = PendingIntent.getBroadcast(
                context,
                101,
                saveAllIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 2. CHOOSE Action (Opens MediaSelectionActivity)
            val chooseIntent = Intent(context, com.instadown.assist.ui.MediaSelectionActivity::class.java).apply {
                putExtra(com.instadown.assist.ui.MediaSelectionActivity.EXTRA_URL, url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val choosePending = PendingIntent.getActivity(
                context,
                102,
                chooseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID_PROMPTS)
                .setSmallIcon(R.drawable.ic_download_arrow)
                .setContentTitle("Carousel detected")
                .setContentText("$itemCount photos available")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(choosePending)
                .addAction(R.drawable.ic_download_arrow, "SAVE ALL", saveAllPending)
                .addAction(R.drawable.ic_image, "CHOOSE", choosePending)

            // 3. CURRENT Action (Only if high-confidence slide index is available)
            if (highConfidenceSlideIndex != null) {
                val currentIntent = Intent(context, com.instadown.assist.receiver.CarouselActionReceiver::class.java).apply {
                    action = com.instadown.assist.receiver.CarouselActionReceiver.ACTION_DOWNLOAD_CURRENT
                    putExtra(com.instadown.assist.receiver.CarouselActionReceiver.EXTRA_URL, url)
                    putExtra(com.instadown.assist.receiver.CarouselActionReceiver.EXTRA_TARGET_INDEX, highConfidenceSlideIndex)
                    putExtra(com.instadown.assist.receiver.CarouselActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
                }
                val currentPending = PendingIntent.getBroadcast(
                    context,
                    103,
                    currentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_image, "CURRENT", currentPending)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notifId, builder.build())
            Log.i(TAG, "showCarouselPromptNotification: posted prompt for $itemCount items (highConfidenceSlideIndex=$highConfidenceSlideIndex)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dispatch carousel prompt notification: ${e.message}")
        }
    }
}
