package com.instadown.assist.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.instadown.assist.R
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.service.FloatingTriggerService

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

        // Advanced Diagnostics & Wireless Link
        findViewById<LinearLayout>(R.id.cardDiagnostics).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        // OTA Software Updates
        findViewById<android.view.View>(R.id.cardCheckUpdates).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSettingsCheckUpdates).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
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
