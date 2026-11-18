package com.instadown.assist.service

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

class InstagramAssistVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "InstaAssistInspector"
        private var activeServiceInstance: InstagramAssistVoiceInteractionService? = null

        fun isServiceActive(context: Context): Boolean {
            val component = ComponentName(context, InstagramAssistVoiceInteractionService::class.java)
            return isActiveService(context, component)
        }

        fun triggerAssistSession(): Boolean {
            val service = activeServiceInstance
            if (service != null) {
                try {
                    val args = Bundle()
                    // Request ONLY assist data, NOT screenshots
                    service.showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST)
                    Log.i(TAG, "Triggered assist session with SHOW_WITH_ASSIST")
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to trigger assist session: ${e.message}", e)
                }
            }
            return false
        }
    }

    override fun onReady() {
        super.onReady()
        activeServiceInstance = this
        Log.i(TAG, "InstagramAssistVoiceInteractionService onReady()")

        // Explicitly disable screenshots as required by test parameters
        try {
            setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
            Log.i(TAG, "Configured setDisabledShowContext: SHOW_WITH_SCREENSHOT disabled")
        } catch (e: Exception) {
            Log.w(TAG, "Could not setDisabledShowContext: ${e.message}")
        }
    }

    override fun onShutdown() {
        if (activeServiceInstance === this) {
            activeServiceInstance = null
        }
        super.onShutdown()
        Log.i(TAG, "InstagramAssistVoiceInteractionService onShutdown()")
    }
}
