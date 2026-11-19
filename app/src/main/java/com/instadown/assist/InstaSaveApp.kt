package com.instadown.assist

import android.app.Application
import android.util.Log
import com.instadown.assist.resolver.HeadlessWebBridgeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InstaSaveApp : Application() {

    companion object {
        private const val TAG = "InstaSaveApp"
        lateinit var instance: InstaSaveApp
            private set

        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "InstaSaveApp initialized. Pre-warming headless web bridge...")
        try {
            HeadlessWebBridgeResolver.preWarm(this)
        } catch (e: Exception) {
            Log.w(TAG, "Pre-warm notice on app startup: ${e.message}")
        }

        // Clean up obsolete APKs in filesDir/updates/ asynchronously
        applicationScope.launch {
            com.instadown.assist.util.AppUpdateManager.cleanupOldUpdateApks(this@InstaSaveApp)
        }
    }
}
