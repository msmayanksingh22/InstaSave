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
            tvAppVersion?.text = "Version 1.1.1"
        }

        // In-App Software Updates (Clean native OTA dialog)
        findViewById<android.view.View>(R.id.cardCheckUpdates).setOnClickListener {
            startInAppUpdateFlow()
        }
        findViewById<Button>(R.id.btnSettingsCheckUpdates).setOnClickListener {
            startInAppUpdateFlow()
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
                btnNegative.visibility = android.view.View.GONE
                btnPositive.isEnabled = true
                btnPositive.text = "Done"
                btnPositive.setOnClickListener { dialog.dismiss() }
                return@launch
            }

            // Update is available!
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
                            pbDownload.progress = percent
                            tvUpdatePercent.text = "$percent%"
                            val readMb = String.format(java.util.Locale.US, "%.1f", bytesRead / (1024.0 * 1024.0))
                            val totMb = String.format(java.util.Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0))
                            tvProgressStatus.text = "Downloading... ($readMb of $totMb)"
                        }
                    }

                    if (downloadedApk != null && downloadedApk.exists()) {
                        tvTitle.text = "Download Complete!"
                        tvSubtitle.text = "Opening Android package installer..."
                        tvProgressStatus.text = "Ready to install"
                        pbDownload.progress = 100
                        tvUpdatePercent.text = "100%"

                        btnPositive.isEnabled = true
                        btnPositive.text = "Install Now"
                        btnNegative.isEnabled = true
                        btnNegative.text = "Dismiss"
                        dialog.setCancelable(true)

                        com.instadown.assist.util.AppUpdateManager.promptInstall(this@SettingsActivity, downloadedApk)

                        btnPositive.setOnClickListener {
                            com.instadown.assist.util.AppUpdateManager.promptInstall(this@SettingsActivity, downloadedApk)
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
        }
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
