package com.instadown.assist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.R
import com.instadown.assist.data.ReliabilityTracker
import com.instadown.assist.util.AppLogger
import com.instadown.assist.util.AppUpdateInfo
import com.instadown.assist.util.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var tvWifiIp: TextView
    private lateinit var btnCopyAdbCommand: Button
    private lateinit var btnRefreshWifi: Button

    private lateinit var tvCurrentVersionBadge: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var pbUpdateDownload: ProgressBar
    private lateinit var btnCheckUpdates: Button
    private lateinit var btnInstallUpdate: Button

    private lateinit var tvScorecard: TextView
    private lateinit var tvDiagnosticsLog: TextView
    private lateinit var btnCopyReport: Button
    private lateinit var btnClearHistory: Button
    private lateinit var btnOpenResolverPoc: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnBack: ImageButton

    private var downloadedApkFile: File? = null
    private var detectedWifiIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        val topBar = findViewById<View>(R.id.diagnosticsTopBar)
        val root = findViewById<View>(R.id.rootDiagnosticsLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val statusBar = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            topBar.setPadding(topBar.paddingLeft, statusBar.top, topBar.paddingRight, topBar.paddingBottom)
            root.setPadding(root.paddingLeft, root.paddingTop, root.paddingRight, navBar.bottom)
            windowInsets
        }

        tvWifiIp = findViewById(R.id.tvWifiIp)
        btnCopyAdbCommand = findViewById(R.id.btnCopyAdbCommand)
        btnRefreshWifi = findViewById(R.id.btnRefreshWifi)

        tvCurrentVersionBadge = findViewById(R.id.tvCurrentVersionBadge)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        pbUpdateDownload = findViewById(R.id.pbUpdateDownload)
        btnCheckUpdates = findViewById(R.id.btnCheckUpdates)
        btnInstallUpdate = findViewById(R.id.btnInstallUpdate)

        tvScorecard = findViewById(R.id.tvScorecard)
        tvDiagnosticsLog = findViewById(R.id.tvDiagnosticsLog)
        btnCopyReport = findViewById(R.id.btnCopyReport)
        btnClearHistory = findViewById(R.id.btnClearHistory)
        btnOpenResolverPoc = findViewById(R.id.btnOpenResolverPoc)
        btnExportLogs = findViewById(R.id.btnExportLogs)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }

        // Set version badge
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvCurrentVersionBadge.text = "v${pInfo.versionName}"
        } catch (ignored: Exception) {}

        // Wireless ADB Handlers
        refreshWifiIp()
        btnRefreshWifi.setOnClickListener { refreshWifiIp() }
        btnCopyAdbCommand.setOnClickListener {
            val ip = detectedWifiIp
            if (ip != null) {
                val command = "adb connect $ip:5555"
                copyToClipboard("ADB Wireless Command", command)
                Toast.makeText(this, "Copied: $command", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No active Wi-Fi connection detected", Toast.LENGTH_SHORT).show()
            }
        }

        // OTA In-App Update Handlers
        btnCheckUpdates.setOnClickListener {
            checkAndDownloadUpdate()
        }

        btnInstallUpdate.setOnClickListener {
            val apk = downloadedApkFile
            if (apk != null && apk.exists()) {
                AppUpdateManager.promptInstall(this, apk)
            } else {
                Toast.makeText(this, "Update APK not ready", Toast.LENGTH_SHORT).show()
            }
        }

        // Share Log Handler
        btnExportLogs.setOnClickListener {
            shareLogs()
        }

        btnCopyReport.setOnClickListener {
            val report = AppLogger.exportFormattedLogs()
            copyToClipboard("Diagnostics Log", report)
            Toast.makeText(this, "All runtime logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnClearHistory.setOnClickListener {
            ReliabilityTracker.clear(this)
            AppLogger.clear()
            refreshDiagnostics()
            Toast.makeText(this, "Diagnostics & logs cleared", Toast.LENGTH_SHORT).show()
        }

        btnOpenResolverPoc.setOnClickListener {
            startActivity(Intent(this, ResolverTestActivity::class.java))
        }

        refreshDiagnostics()

        // Real-time live log collection
        lifecycleScope.launch {
            AppLogger.logEvents.collect {
                withContext(Dispatchers.Main) {
                    val currentText = tvDiagnosticsLog.text.toString()
                    val newText = "${it}\n$currentText".take(15000)
                    tvDiagnosticsLog.text = newText
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWifiIp()
        refreshDiagnostics()
        AppUpdateManager.resumePendingInstallIfPermitted(this)
    }

    private fun refreshWifiIp() {
        val ip = AppLogger.getLocalWifiIpAddress()
        detectedWifiIp = ip
        if (ip != null) {
            tvWifiIp.text = "Wi-Fi IP: $ip (Port 5555)"
            btnCopyAdbCommand.isEnabled = true
        } else {
            tvWifiIp.text = "Wi-Fi IP: Not connected to Wi-Fi"
            btnCopyAdbCommand.isEnabled = false
        }
    }

    private fun checkAndDownloadUpdate() {
        btnCheckUpdates.isEnabled = false
        tvUpdateStatus.text = "Checking release server for updates..."

        lifecycleScope.launch {
            val updateInfo = withContext(Dispatchers.IO) {
                AppUpdateManager.checkForUpdates(this@DiagnosticsActivity)
            }

            if (updateInfo == null) {
                tvUpdateStatus.text = "Could not reach update server. You are on the latest local build."
                btnCheckUpdates.isEnabled = true
                return@launch
            }

            if (!updateInfo.isAvailable) {
                tvUpdateStatus.text = "✓ You are running the newest version (${updateInfo.versionName})."
                btnCheckUpdates.isEnabled = true
                return@launch
            }

            tvUpdateStatus.text = "New update available: v${updateInfo.versionName}\nNotes: ${updateInfo.releaseNotes}"
            pbUpdateDownload.visibility = View.VISIBLE
            pbUpdateDownload.progress = 0

            val apk = withContext(Dispatchers.IO) {
                AppUpdateManager.downloadUpdate(
                    context = this@DiagnosticsActivity,
                    updateInfo = updateInfo,
                    onProgress = { percent, _, _ ->
                        runOnUiThread {
                            pbUpdateDownload.progress = percent
                            tvUpdateStatus.text = "Downloading update: $percent% (v${updateInfo.versionName})"
                        }
                    }
                )
            }

            btnCheckUpdates.isEnabled = true
            pbUpdateDownload.visibility = View.GONE

            if (apk != null && apk.exists()) {
                downloadedApkFile = apk
                tvUpdateStatus.text = "✓ Update v${updateInfo.versionName} ready to install!"
                btnInstallUpdate.visibility = View.VISIBLE
                AppUpdateManager.promptInstall(this@DiagnosticsActivity, apk)
            } else {
                tvUpdateStatus.text = "Update download failed. Check internet connection."
            }
        }
    }

    private fun shareLogs() {
        val fullLog = AppLogger.exportFormattedLogs()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "InstaSave Runtime Diagnostic Report")
            putExtra(Intent.EXTRA_TEXT, fullLog)
        }
        startActivity(Intent.createChooser(intent, "Share Diagnostic Logs"))
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }

    private fun refreshDiagnostics() {
        ReliabilityTracker.init(this)
        val scorecard = ReliabilityTracker.getCategoryStats("ALL")
        val history = ReliabilityTracker.getHistory()

        tvScorecard.text = """
            Total Attempts: ${scorecard.totalAttempts}
            Share Found: ${String.format(java.util.Locale.US, "%.1f%%", scorecard.shareSuccessRate)}
            Copy Link Found: ${String.format(java.util.Locale.US, "%.1f%%", scorecard.copyLinkSuccessRate)}
            Pass Rate: ${String.format(java.util.Locale.US, "%.1f%%", scorecard.completeSuccessRate)}
            Avg Latency: ${scorecard.avgTimeMs} ms (P95: ${scorecard.p95TimeMs} ms)
        """.trimIndent()

        val logs = AppLogger.getLogs()
        if (logs.isNotEmpty()) {
            val sb = StringBuilder()
            logs.reversed().take(50).forEach { entry ->
                sb.append(entry.toString()).append("\n")
            }
            tvDiagnosticsLog.text = sb.toString()
        } else if (history.isNotEmpty()) {
            val sb = StringBuilder()
            sb.appendLine(ReliabilityTracker.formatSummaryTable())
            sb.appendLine("\n=== RECENT RUN DETAILS ===")
            history.reversed().take(10).forEach { res ->
                sb.append(res.formattedReport)
                sb.append("\n----------------------------------------\n\n")
            }
            tvDiagnosticsLog.text = sb.toString()
        } else {
            tvDiagnosticsLog.text = "No runtime logs recorded yet."
        }
    }
}
