package com.instadown.assist.data

import android.content.Context

object CaptureStore {
    private const val PREFS_NAME = "insta_assist_prefs"
    private const val KEY_LAST_REPORT = "last_report"
    private const val KEY_LAST_TIMESTAMP = "last_timestamp"
    private const val KEY_LAST_VERDICT = "last_verdict"
    private const val KEY_LAST_VERDICT_REASON = "last_verdict_reason"
    private const val KEY_LAST_WEB_URI = "last_web_uri"
    private const val KEY_LAST_IS_APP_PROVIDED = "last_is_app_provided"
    private const val KEY_LAST_STRUCTURE_SUMMARY = "last_structure_summary"

    var lastReport: String = ""
        private set
    var lastTimestamp: String = ""
        private set
    var lastVerdict: String = "NO DATA"
        private set
    var lastVerdictReason: String = ""
        private set
    var lastWebUri: String = "[None]"
        private set
    var lastIsAppProvided: String = "[None]"
        private set
    var lastStructureSummary: String = "[None]"
        private set

    var previousWebUri: String? = null
        private set

    private var onUpdateListener: (() -> Unit)? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        lastReport = prefs.getString(KEY_LAST_REPORT, "") ?: ""
        lastTimestamp = prefs.getString(KEY_LAST_TIMESTAMP, "") ?: ""
        lastVerdict = prefs.getString(KEY_LAST_VERDICT, "NO DATA") ?: "NO DATA"
        lastVerdictReason = prefs.getString(KEY_LAST_VERDICT_REASON, "") ?: ""
        lastWebUri = prefs.getString(KEY_LAST_WEB_URI, "[None]") ?: "[None]"
        lastIsAppProvided = prefs.getString(KEY_LAST_IS_APP_PROVIDED, "[None]") ?: "[None]"
        lastStructureSummary = prefs.getString(KEY_LAST_STRUCTURE_SUMMARY, "[None]") ?: "[None]"
    }

    fun setListener(listener: (() -> Unit)?) {
        onUpdateListener = listener
    }

    fun saveCapture(
        context: Context,
        report: String,
        timestamp: String,
        verdict: String,
        verdictReason: String,
        webUri: String,
        isAppProvided: String,
        structureSummary: String
    ) {
        if (lastWebUri != "[None]" && lastWebUri.isNotBlank()) {
            previousWebUri = lastWebUri
        }

        lastReport = report
        lastTimestamp = timestamp
        lastVerdict = verdict
        lastVerdictReason = verdictReason
        lastWebUri = webUri
        lastIsAppProvided = isAppProvided
        lastStructureSummary = structureSummary

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_REPORT, report)
            .putString(KEY_LAST_TIMESTAMP, timestamp)
            .putString(KEY_LAST_VERDICT, verdict)
            .putString(KEY_LAST_VERDICT_REASON, verdictReason)
            .putString(KEY_LAST_WEB_URI, webUri)
            .putString(KEY_LAST_IS_APP_PROVIDED, isAppProvided)
            .putString(KEY_LAST_STRUCTURE_SUMMARY, structureSummary)
            .apply()

        onUpdateListener?.invoke()
    }

    fun clear(context: Context) {
        lastReport = ""
        lastTimestamp = ""
        lastVerdict = "NO DATA"
        lastVerdictReason = ""
        lastWebUri = "[None]"
        lastIsAppProvided = "[None]"
        lastStructureSummary = "[None]"
        previousWebUri = null

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        onUpdateListener?.invoke()
    }
}
