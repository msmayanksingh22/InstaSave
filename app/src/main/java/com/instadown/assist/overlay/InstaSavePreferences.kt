package com.instadown.assist.overlay

import android.content.Context
import android.content.SharedPreferences

enum class ButtonSize(val dpValue: Int) {
    SMALL(48),
    MEDIUM(56),
    LARGE(64);

    companion object {
        fun fromString(name: String?): ButtonSize {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MEDIUM
        }
    }
}

enum class DownloadMode {
    AUTOMATIC,
    CLIPBOARD;

    companion object {
        fun fromString(name: String?): DownloadMode {
            if (name.equals("CLIPBOARD_MANUAL", ignoreCase = true)) return CLIPBOARD
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AUTOMATIC
        }
    }
}

enum class CarouselDownloadBehavior {
    ENTIRE_CAROUSEL,
    CURRENT_ITEM,
    ASK;

    companion object {
        fun fromString(name: String?): CarouselDownloadBehavior {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ENTIRE_CAROUSEL
        }
    }
}

class InstaSavePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "instasave_settings"
        private const val KEY_BUTTON_ENABLED = "floating_button_enabled"
        private const val KEY_BUTTON_SIZE = "floating_button_size"
        private const val KEY_BUTTON_OPACITY = "floating_button_opacity"
        private const val KEY_EDGE_DOCKING = "edge_docking_enabled"
        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback_enabled"
        private const val KEY_SOUND_FEEDBACK = "sound_feedback_enabled"
        private const val KEY_START_ON_BOOT = "start_on_boot"
        private const val KEY_NOTIFICATIONS = "completion_notifications"
        private const val KEY_DUPLICATE_POLICY = "duplicate_policy"
        private const val KEY_DOWNLOAD_MODE = "download_mode"
        private const val KEY_CAROUSEL_BEHAVIOR = "carousel_download_behavior"
        private const val KEY_SAVE_LOCATION = "save_location_relative_path"
        private const val KEY_LAST_PROCESSED_CLIP_URL = "last_processed_clip_url"
        private const val KEY_LAST_PROCESSED_CLIP_TIMESTAMP = "last_processed_clip_timestamp"
        private const val KEY_SERVICE_RUNNING_DESIRED = "service_running_desired"
        private const val KEY_FLOATING_HUD_ENABLED = "floating_hud_enabled"
        private const val KEY_AUDIO_EXTRACTION_ENABLED = "audio_extraction_enabled"
        private const val KEY_CLIPBOARD_AUTO_START = "clipboard_auto_start_enabled"
        private const val KEY_SIGN_IN_PROMPT_DISMISSED = "sign_in_prompt_dismissed"
        private const val KEY_FLOATING_PILL_OFFSET_DP = "explorer_floating_pill_offset_dp"

        const val DEFAULT_SAVE_LOCATION = "Pictures/InstaSave/"

        val SUPPORTED_SAVE_LOCATIONS = listOf(
            "Pictures/InstaSave/",
            "DCIM/InstaSave/",
            "Download/InstaSave/",
            "Movies/InstaSave/"
        )
    }

    var isServiceRunningDesired: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_RUNNING_DESIRED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_RUNNING_DESIRED, value).apply()

    var isButtonEnabled: Boolean
        get() = prefs.getBoolean(KEY_BUTTON_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BUTTON_ENABLED, value).apply()

    var buttonSize: ButtonSize
        get() = ButtonSize.fromString(prefs.getString(KEY_BUTTON_SIZE, ButtonSize.MEDIUM.name))
        set(value) = prefs.edit().putString(KEY_BUTTON_SIZE, value.name).apply()

    var buttonOpacity: Float
        get() = prefs.getFloat(KEY_BUTTON_OPACITY, 0.95f)
        set(value) = prefs.edit().putFloat(KEY_BUTTON_OPACITY, value.coerceIn(0.4f, 1.0f)).apply()

    var isEdgeDockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_EDGE_DOCKING, false)
        set(value) = prefs.edit().putBoolean(KEY_EDGE_DOCKING, value).apply()

    var isHapticFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()

    var isSoundFeedbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_FEEDBACK, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_FEEDBACK, value).apply()

    var isStartOnBootEnabled: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, false)
        set(value) = prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()

    var isCompletionNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var duplicatePolicy: String
        get() = prefs.getString(KEY_DUPLICATE_POLICY, "SKIP") ?: "SKIP"
        set(value) = prefs.edit().putString(KEY_DUPLICATE_POLICY, value).apply()

    var downloadMode: DownloadMode
        get() = DownloadMode.fromString(prefs.getString(KEY_DOWNLOAD_MODE, DownloadMode.AUTOMATIC.name))
        set(value) = prefs.edit().putString(KEY_DOWNLOAD_MODE, value.name).apply()

    var carouselDownloadBehavior: CarouselDownloadBehavior
        get() = CarouselDownloadBehavior.fromString(prefs.getString(KEY_CAROUSEL_BEHAVIOR, CarouselDownloadBehavior.ENTIRE_CAROUSEL.name))
        set(value) = prefs.edit().putString(KEY_CAROUSEL_BEHAVIOR, value.name).apply()

    var saveLocationRelativePath: String
        get() = prefs.getString(KEY_SAVE_LOCATION, DEFAULT_SAVE_LOCATION) ?: DEFAULT_SAVE_LOCATION
        set(value) {
            val normalized = if (value.endsWith("/")) value else "$value/"
            prefs.edit().putString(KEY_SAVE_LOCATION, normalized).apply()
        }

    var lastProcessedClipboardUrl: String?
        get() = prefs.getString(KEY_LAST_PROCESSED_CLIP_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_PROCESSED_CLIP_URL, value).apply()

    var lastProcessedClipboardTimestamp: Long
        get() = prefs.getLong(KEY_LAST_PROCESSED_CLIP_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PROCESSED_CLIP_TIMESTAMP, value).apply()

    var isFloatingHudEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_HUD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_HUD_ENABLED, value).apply()

    var isAudioExtractionEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_EXTRACTION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_EXTRACTION_ENABLED, value).apply()

    // Opt-in only: prevents unwanted auto-download / auto-selection when user copies a link just to view the creator profile
    var isClipboardAutoStartEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD_AUTO_START, value).apply()

    var hasDismissedSignInPrompt: Boolean
        get() = prefs.getBoolean(KEY_SIGN_IN_PROMPT_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_SIGN_IN_PROMPT_DISMISSED, value).apply()

    var floatingPillOffsetDp: Float
        get() = prefs.getFloat(KEY_FLOATING_PILL_OFFSET_DP, 0f)
        set(value) = prefs.edit().putFloat(KEY_FLOATING_PILL_OFFSET_DP, value).apply()
}
