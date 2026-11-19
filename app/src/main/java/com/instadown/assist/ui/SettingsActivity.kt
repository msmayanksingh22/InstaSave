package com.instadown.assist.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.R
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.service.FloatingTriggerService
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: InstaSavePreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = InstaSavePreferences(this)

        val topBar = findViewById<android.view.View>(R.id.settingsTopBar)
        val root = findViewById<android.view.View>(R.id.rootSettingsLayout)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val statusBar = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            val navBar = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            topBar.setPadding(topBar.paddingLeft, statusBar.top, topBar.paddingRight, topBar.paddingBottom)
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, navBar.bottom)
            windowInsets
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Floating Button Switches
        val switchEnable = findViewById<SwitchCompat>(R.id.switchEnableButton)
        switchEnable.isChecked = prefs.isButtonEnabled
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            prefs.isButtonEnabled = isChecked
            if (isChecked) {
                FloatingTriggerService.start(this)
            } else {
                FloatingTriggerService.stop(this)
            }
        }

        val switchDocking = findViewById<SwitchCompat>(R.id.switchEdgeDocking)
        switchDocking.isChecked = prefs.isEdgeDockingEnabled
        switchDocking.setOnCheckedChangeListener { _, isChecked ->
            prefs.isEdgeDockingEnabled = isChecked
            FloatingTriggerService.updatePreferences(this)
        }

        val switchHaptics = findViewById<SwitchCompat>(R.id.switchHaptics)
        switchHaptics.isChecked = prefs.isHapticFeedbackEnabled
        switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            prefs.isHapticFeedbackEnabled = isChecked
        }

        findViewById<Button>(R.id.btnResetPosition).setOnClickListener {
            FloatingTriggerService.resetPosition(this)
        }

        // Downloads Switches
        val switchDuplicates = findViewById<SwitchCompat>(R.id.switchSkipDuplicates)
        switchDuplicates.isChecked = prefs.duplicatePolicy == "SKIP"
        switchDuplicates.setOnCheckedChangeListener { _, isChecked ->
            prefs.duplicatePolicy = if (isChecked) "SKIP" else "ASK"
        }

        val switchNotifs = findViewById<SwitchCompat>(R.id.switchNotifications)
        switchNotifs.isChecked = prefs.isCompletionNotificationsEnabled
        switchNotifs.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                            this,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        androidx.core.app.ActivityCompat.requestPermissions(
                            this,
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            1001
                        )
                    }
                }
            }
            prefs.isCompletionNotificationsEnabled = isChecked
        }

        // Behavior Switches
        val switchBoot = findViewById<SwitchCompat>(R.id.switchStartOnBoot)
        switchBoot.isChecked = prefs.isStartOnBootEnabled
        switchBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.isStartOnBootEnabled = isChecked
        }

        // Bind dynamic version in About section
        val tvAppVersion = findViewById<android.widget.TextView>(R.id.tvSettingsAppVersion)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val buildNum = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) pInfo.longVersionCode else pInfo.versionCode
            tvAppVersion?.text = "Version ${pInfo.versionName} (Build $buildNum)"
        } catch (_: Exception) {
            tvAppVersion?.text = "Version 1.1.2 (Build 4)"
        }

        // In-App Software Updates (Clean native OTA dialog)
        findViewById<android.view.View>(R.id.cardCheckUpdates).setOnClickListener {
            startInAppUpdateFlow()
        }
        findViewById<Button>(R.id.btnSettingsCheckUpdates).setOnClickListener {
            startInAppUpdateFlow()
        }

        // Diagnostic Logs (USB-Free in-app debugging)
        findViewById<android.view.View>(R.id.cardDiagnosticLogs).setOnClickListener {
            showDiagnosticLogsDialog()
        }
        findViewById<Button>(R.id.btnViewDiagnosticLogs).setOnClickListener {
            showDiagnosticLogsDialog()
        }
    }

    private fun showDiagnosticLogsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_diagnostic_logs, null)
        val tvLogsCount = dialogView.findViewById<android.widget.TextView>(R.id.tvLogsCount)
        val tvLogsContent = dialogView.findViewById<android.widget.TextView>(R.id.tvLogsContent)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnLogsDialogClose)
        val btnFilterAll = dialogView.findViewById<android.widget.Button>(R.id.btnFilterAll)
        val btnFilterErrors = dialogView.findViewById<android.widget.Button>(R.id.btnFilterErrors)
        val btnClear = dialogView.findViewById<android.widget.Button>(R.id.btnClearLogs)
        val btnShare = dialogView.findViewById<android.widget.Button>(R.id.btnShareLogs)
        val btnCopy = dialogView.findViewById<android.widget.Button>(R.id.btnCopyLogs)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()

        var currentFilter = "ALL"

        fun refreshLogDisplay() {
            val allLogs = com.instadown.assist.util.AppLogger.getLogs()
            val filtered = if (currentFilter == "ERRORS") {
                allLogs.filter { it.level == "ERROR" || it.level == "WARN" }
            } else {
                allLogs
            }

            tvLogsCount.text = "${filtered.size} of ${allLogs.size} entries"
            if (filtered.isEmpty()) {
                tvLogsContent.text = if (allLogs.isEmpty()) "No logs recorded in memory yet." else "No errors or warnings recorded."
            } else {
                val sb = java.lang.StringBuilder()
                for (entry in filtered) {
                    sb.appendLine(entry.toString())
                }
                tvLogsContent.text = sb.toString()
            }
        }

        refreshLogDisplay()

        btnClose.setOnClickListener { dialog.dismiss() }

        btnFilterAll.setOnClickListener {
            currentFilter = "ALL"
            btnFilterAll.setTextColor(getColor(R.color.white))
            btnFilterErrors.setTextColor(getColor(R.color.text_secondary))
            refreshLogDisplay()
        }

        btnFilterErrors.setOnClickListener {
            currentFilter = "ERRORS"
            btnFilterErrors.setTextColor(getColor(R.color.white))
            btnFilterAll.setTextColor(getColor(R.color.text_secondary))
            refreshLogDisplay()
        }

        btnCopy.setOnClickListener {
            val success = com.instadown.assist.util.AppLogger.copyLogsToClipboard(this)
            if (success) {
                Toast.makeText(this, "Diagnostic logs copied to clipboard!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to copy logs", Toast.LENGTH_SHORT).show()
            }
        }

        btnShare.setOnClickListener {
            try {
                startActivity(com.instadown.assist.util.AppLogger.createShareIntent(this))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open share menu: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            com.instadown.assist.util.AppLogger.clear()
            refreshLogDisplay()
            Toast.makeText(this, "In-memory logs cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startInAppUpdateFlow() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_in_app_update, null)
        val tvTitle = dialogView.findViewById<android.widget.TextView>(R.id.tvUpdateDialogTitle)
        val tvSubtitle = dialogView.findViewById<android.widget.TextView>(R.id.tvUpdateDialogSubtitle)
        val pbChecking = dialogView.findViewById<android.widget.ProgressBar>(R.id.pbUpdateChecking)
        val layoutReleaseNotes = dialogView.findViewById<android.view.View>(R.id.layoutReleaseNotes)
        val tvReleaseVersion = dialogView.findViewById<android.widget.TextView>(R.id.tvReleaseVersion)
        val tvReleaseSize = dialogView.findViewById<android.widget.TextView>(R.id.tvReleaseSize)
        val tvReleaseNotes = dialogView.findViewById<android.widget.TextView>(R.id.tvReleaseNotes)
        val layoutProgress = dialogView.findViewById<android.view.View>(R.id.layoutUpdateProgress)
        val tvProgressStatus = dialogView.findViewById<android.widget.TextView>(R.id.tvDownloadProgressStatus)
        val tvUpdatePercent = dialogView.findViewById<android.widget.TextView>(R.id.tvUpdatePercent)
        val pbDownload = dialogView.findViewById<android.widget.ProgressBar>(R.id.pbUpdateDownload)
        val btnNegative = dialogView.findViewById<android.widget.Button>(R.id.btnUpdateNegative)
        val btnPositive = dialogView.findViewById<android.widget.Button>(R.id.btnUpdatePositive)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()

        btnNegative.setOnClickListener { dialog.dismiss() }
        btnPositive.isEnabled = false

        lifecycleScope.launch {
            val updateInfo = com.instadown.assist.util.AppUpdateManager.checkForUpdates(this@SettingsActivity)
            pbChecking.visibility = android.view.View.GONE

            if (updateInfo == null) {
                tvTitle.text = "Connection Error"
                tvSubtitle.text = "Could not check for updates. Please verify your internet connection."
                btnPositive.isEnabled = true
                btnPositive.text = "Retry"
                btnPositive.setOnClickListener {
                    dialog.dismiss()
                    startInAppUpdateFlow()
                }
                return@launch
            }

            if (!updateInfo.isAvailable) {
                tvTitle.text = "You're Up to Date! 🎉"
                tvSubtitle.text = "InstaSave v${updateInfo.versionName} is the latest available version."
                btnNegative.visibility = android.view.View.VISIBLE
                btnNegative.text = "Re-download APK"
                btnNegative.setOnClickListener {
                    layoutReleaseNotes.visibility = android.view.View.VISIBLE
                    tvReleaseVersion.text = "Version: v${updateInfo.versionName}"
                    val sizeMb = if (updateInfo.fileSizeBytes > 0) String.format(java.util.Locale.US, "%.1f MB", updateInfo.fileSizeBytes / (1024.0 * 1024.0)) else ""
                    tvReleaseSize.text = sizeMb
                    tvReleaseNotes.text = updateInfo.releaseNotes
                    btnNegative.visibility = android.view.View.GONE
                    btnPositive.text = "Download Now"
                    btnPositive.setOnClickListener {
                        performUpdateDownload(
                            updateInfo,
                            dialog,
                            tvTitle,
                            tvSubtitle,
                            layoutProgress,
                            pbDownload,
                            tvUpdatePercent,
                            tvProgressStatus,
                            btnPositive,
                            btnNegative
                        )
                    }
                }
                btnPositive.isEnabled = true
                btnPositive.text = "Done"
                btnPositive.setOnClickListener { dialog.dismiss() }
                return@launch
            }

            // Update is available!
            val existingApk = com.instadown.assist.util.AppUpdateManager.getDownloadedUpdateApk(this@SettingsActivity, updateInfo.versionName)
            if (existingApk != null) {
                tvTitle.text = "Update Ready! 🚀"
                tvSubtitle.text = "InstaSave v${updateInfo.versionName} is downloaded and ready to install."
                layoutReleaseNotes.visibility = android.view.View.VISIBLE
                tvReleaseVersion.text = "New Version: v${updateInfo.versionName}"
                val sizeMb = if (updateInfo.fileSizeBytes > 0) String.format(java.util.Locale.US, "%.1f MB", updateInfo.fileSizeBytes / (1024.0 * 1024.0)) else ""
                tvReleaseSize.text = sizeMb
                tvReleaseNotes.text = updateInfo.releaseNotes

                btnPositive.isEnabled = true
                btnPositive.text = "Install Now"
                btnNegative.text = "Re-download"

                btnPositive.setOnClickListener {
                    com.instadown.assist.util.AppUpdateManager.promptInstall(this@SettingsActivity, existingApk, updateInfo.apkUrl)
                }
                btnNegative.setOnClickListener {
                    existingApk.delete()
                    performUpdateDownload(
                        updateInfo,
                        dialog,
                        tvTitle,
                        tvSubtitle,
                        layoutProgress,
                        pbDownload,
                        tvUpdatePercent,
                        tvProgressStatus,
                        btnPositive,
                        btnNegative
                    )
                }
            } else {
                tvTitle.text = "Update Available! 🚀"
                tvSubtitle.text = "A new version of InstaSave is ready to download."
                layoutReleaseNotes.visibility = android.view.View.VISIBLE
                tvReleaseVersion.text = "New Version: v${updateInfo.versionName}"
                val sizeMb = if (updateInfo.fileSizeBytes > 0) String.format(java.util.Locale.US, "%.1f MB", updateInfo.fileSizeBytes / (1024.0 * 1024.0)) else ""
                tvReleaseSize.text = sizeMb
                tvReleaseNotes.text = updateInfo.releaseNotes

                btnPositive.isEnabled = true
                btnPositive.text = "Update Now"
                btnNegative.text = "Later"

                btnPositive.setOnClickListener {
                    performUpdateDownload(
                        updateInfo,
                        dialog,
                        tvTitle,
                        tvSubtitle,
                        layoutProgress,
                        pbDownload,
                        tvUpdatePercent,
                        tvProgressStatus,
                        btnPositive,
                        btnNegative
                    )
                }
            }
        }
    }

    private fun performUpdateDownload(
        updateInfo: com.instadown.assist.util.AppUpdateInfo,
        dialog: androidx.appcompat.app.AlertDialog,
        tvTitle: android.widget.TextView,
        tvSubtitle: android.widget.TextView,
        layoutProgress: android.view.View,
        pbDownload: android.widget.ProgressBar,
        tvUpdatePercent: android.widget.TextView,
        tvProgressStatus: android.widget.TextView,
        btnPositive: android.widget.Button,
        btnNegative: android.widget.Button
    ) {
        btnPositive.isEnabled = false
        btnPositive.text = "Downloading..."
        btnNegative.isEnabled = false
        dialog.setCancelable(false)
        layoutProgress.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            val downloadedApk = com.instadown.assist.util.AppUpdateManager.downloadUpdate(
                this@SettingsActivity,
                updateInfo
            ) { percent, bytesRead, totalBytes ->
                runOnUiThread {
                    if (percent >= 0) {
                        pbDownload.isIndeterminate = false
                        pbDownload.progress = percent
                        tvUpdatePercent.text = "$percent%"
                    } else {
                        pbDownload.isIndeterminate = true
                        tvUpdatePercent.text = ""
                    }
                    val readMb = String.format(java.util.Locale.US, "%.1f", bytesRead / (1024.0 * 1024.0))
                    val totMb = if (totalBytes > 0) String.format(java.util.Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0)) else "..."
                    tvProgressStatus.text = "Downloading... ($readMb of $totMb)"
                }
            }

            if (downloadedApk != null && downloadedApk.exists()) {
                tvTitle.text = "Download Complete!"
                tvSubtitle.text = "Opening Android package installer..."
                tvProgressStatus.text = "Ready to install"
                pbDownload.isIndeterminate = false
                pbDownload.progress = 100
                tvUpdatePercent.text = "100%"

                btnPositive.isEnabled = true
                btnPositive.text = "Install Now"
                btnNegative.isEnabled = true
                btnNegative.text = "Dismiss"
                dialog.setCancelable(true)

                com.instadown.assist.util.AppUpdateManager.promptInstall(this@SettingsActivity, downloadedApk, updateInfo.apkUrl)

                btnPositive.setOnClickListener {
                    com.instadown.assist.util.AppUpdateManager.promptInstall(this@SettingsActivity, downloadedApk, updateInfo.apkUrl)
                }
            } else {
                tvTitle.text = "Download Failed"
                tvSubtitle.text = "Unable to download the update package. Please try again."
                btnPositive.isEnabled = true
                btnPositive.text = "Retry"
                btnNegative.isEnabled = true
                btnNegative.text = "Cancel"
                dialog.setCancelable(true)

                btnPositive.setOnClickListener {
                    dialog.dismiss()
                    startInAppUpdateFlow()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Automatically resume update installation if user just returned from granting Unknown Sources permission
        com.instadown.assist.util.AppUpdateManager.resumePendingInstallIfPermitted(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            prefs.isCompletionNotificationsEnabled = granted
            findViewById<SwitchCompat>(R.id.switchNotifications).isChecked = granted
            if (!granted) {
                Toast.makeText(this, "Notification permission denied; notifications disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
