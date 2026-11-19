package com.instadown.assist.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

enum class AccessibilityHealth {
    HEALTHY,            // Enabled in Settings AND Binder is live
    ZOMBIE_UNBOUND,     // Enabled in Settings, but OEM killed the service process/binder
    DISABLED_BY_USER    // Turned off in Android Accessibility Settings
}

/**
 * Tracks live AccessibilityService binder health and exposes OEM-aware recovery intents.
 * Call onBinderConnected() / onBinderDisconnected() from InstaAccessibilityService.
 */
object AccessibilityBinderWatchdog {
    private val binderAlive = AtomicBoolean(false)
    private val _health = MutableStateFlow(AccessibilityHealth.DISABLED_BY_USER)
    val health: StateFlow<AccessibilityHealth> = _health.asStateFlow()

    private var settingsObserver: ContentObserver? = null

    fun startMonitoring(context: Context) {
        val appCtx = context.applicationContext
        evaluateHealth(appCtx)

        if (settingsObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                evaluateHealth(appCtx)
            }
        }
        try {
            appCtx.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
            settingsObserver = observer
        } catch (e: Exception) {
            // Content observer registration fallback
        }
    }

    fun onBinderConnected(context: Context) {
        binderAlive.set(true)
        evaluateHealth(context.applicationContext)
    }

    fun onBinderDisconnected(context: Context) {
        binderAlive.set(false)
        evaluateHealth(context.applicationContext)
    }

    fun evaluateHealth(context: Context): AccessibilityHealth {
        val isEnabledInSettings = isListedInEnabledServices(context)
        val state = when {
            isEnabledInSettings && binderAlive.get() -> AccessibilityHealth.HEALTHY
            isEnabledInSettings && !binderAlive.get() -> AccessibilityHealth.ZOMBIE_UNBOUND
            else -> AccessibilityHealth.DISABLED_BY_USER
        }
        _health.value = state
        return state
    }

    private fun isListedInEnabledServices(context: Context): Boolean {
        val expectedComponent = ComponentName(context, InstaAccessibilityService::class.java)
        val enabledRaw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

        return enabledRaw.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expectedComponent
        }
    }

    /**
     * Opens Accessibility Settings and highlights InstaSave directly on Pixel, One UI, and HyperOS.
     */
    fun buildHighlightedAccessibilityIntent(context: Context): Intent {
        val componentString = ComponentName(context, InstaAccessibilityService::class.java).flattenToString()
        val args = Bundle().apply {
            putString(":settings:fragment_args_key", componentString)
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", componentString)
            putExtra(":settings:show_fragment_args", args)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun buildBatteryWhitelistIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Resolves the first existing OEM Autostart activity (HyperOS/MIUI, ColorOS, OriginOS, One UI).
     * Returns null if running stock Android / Pixel.
     */
    fun resolveOemAutoStartIntent(context: Context): Intent? {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
        )
        val pm = context.packageManager
        for (component in candidates) {
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pm.resolveActivity(intent, 0) != null) return intent
        }
        return null
    }
}
