package com.instadown.assist.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.service.FloatingTriggerService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        Log.i(TAG, "onReceive: $action received")

        val prefs = InstaSavePreferences(context)
        if (!prefs.isStartOnBootEnabled) {
            Log.i(TAG, "onReceive: Start on boot is disabled by user preference")
            return
        }

        // Best-effort check: Verify overlay permission before attempting background start
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "onReceive: Cannot start floating service - overlay permission not granted")
            return
        }

        try {
            Log.i(TAG, "onReceive: Attempting best-effort start of FloatingTriggerService")
            FloatingTriggerService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "onReceive: Best-effort service launch prevented by OS/OEM restrictions: ${e.message}")
        }
    }
}
