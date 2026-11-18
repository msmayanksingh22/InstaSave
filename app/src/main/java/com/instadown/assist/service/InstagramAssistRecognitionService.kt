package com.instadown.assist.service

import android.content.Intent
import android.speech.RecognitionService

/**
 * Minimal RecognitionService stub.
 * In the Android framework (Android 16 / API 36 included), VoiceInteractionServiceInfo
 * constructor checks `mRecognitionService`. If absent in XML metadata, it sets
 * `mParseError = "No recognitionService specified"`, causing the OS to drop the service
 * as an eligible assistant. This stub satisfies that framework contract.
 */
class InstagramAssistRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
