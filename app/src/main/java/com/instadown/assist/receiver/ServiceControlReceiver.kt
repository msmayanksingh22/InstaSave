package com.instadown.assist.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.instadown.assist.service.FloatingTriggerService

class ServiceControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceControlReceiver"
        const val ACTION_STOP_INSTASAVE = "com.instadown.assist.ACTION_STOP_SERVICE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_INSTASAVE) {
            Log.i(TAG, "onReceive: Stop InstaSave action received, shutting down service")
            FloatingTriggerService.stop(context)
        }
    }
}
