package com.instadown.assist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.service.FloatingTriggerService
import com.instadown.assist.service.InstaAccessibilityService
import com.instadown.assist.util.ClipboardReader
import com.instadown.assist.util.DownloadNotificationManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var dotService: View
    private lateinit var tvServiceTitle: TextView
    private lateinit var tvServiceSubtitle: TextView
    private lateinit var btnToggleService: Button

    private lateinit var dotA11y: View
    private lateinit var tvA11ySubtitle: TextView
    private lateinit var btnToggleA11y: Button

    private lateinit var dotOverlay: View
    private lateinit var tvOverlaySubtitle: TextView
    private lateinit var btnToggleOverlay: Button
    private lateinit var tvPrereqWarning: TextView

    private lateinit var tvStorageTotalSize: TextView
    private lateinit var tvStorageBreakdown: TextView

    private lateinit var rvRecentDownloads: RecyclerView
    private lateinit var tvNoRecent: TextView
    private lateinit var recentAdapter: RecentDownloadsAdapter

    private lateinit var layoutClipboardBanner: LinearLayout
    private lateinit var tvClipBannerTitle: TextView
    private lateinit var tvClipBannerUrl: TextView
    private lateinit var btnClipBannerDownload: Button
    private lateinit var btnClipBannerDismiss: ImageButton

    private lateinit var layoutUpdateBanner: LinearLayout
    private lateinit var tvUpdateBannerSubtitle: TextView
    private lateinit var btnUpdateBannerAction: Button
    private lateinit var btnUpdateBannerDismiss: ImageButton

    private lateinit var prefs: InstaSavePreferences
    private val repo by lazy { DownloadRepository.getInstance(this) }
    private val coordinator by lazy { com.instadown.assist.download.InstagramDownloadCoordinator(this) }
    private val clipboardCoordinator by lazy { com.instadown.assist.download.ClipboardDownloadCoordinator(this) }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastDismissedClipUrl: String? = null
    private var hasCheckedUpdateThisSession = false

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = InstaSavePreferences(this)
        DownloadNotificationManager.createNotificationChannels(this)

        // Handle edge-to-edge window insets for status bar & gesture navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootScrollView)) { v, windowInsets ->
            val statusBar = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, statusBar.top, v.paddingRight, navBar.bottom)
            windowInsets
        }

        layoutClipboardBanner = findViewById(R.id.layoutClipboardBanner)
        tvClipBannerTitle = findViewById(R.id.tvClipBannerTitle)
        tvClipBannerUrl = findViewById(R.id.tvClipBannerUrl)
        btnClipBannerDownload = findViewById(R.id.btnClipBannerDownload)
        btnClipBannerDismiss = findViewById(R.id.btnClipBannerDismiss)

        layoutUpdateBanner = findViewById(R.id.layoutUpdateAvailableBanner)
        tvUpdateBannerSubtitle = findViewById(R.id.tvUpdateBannerSubtitle)
        btnUpdateBannerAction = findViewById(R.id.btnUpdateBannerAction)
        btnUpdateBannerDismiss = findViewById(R.id.btnUpdateBannerDismiss)

        dotService = findViewById(R.id.dotServiceStatus)
        tvServiceTitle = findViewById(R.id.tvServiceStatusTitle)
        tvServiceSubtitle = findViewById(R.id.tvServiceStatusSubtitle)
        btnToggleService = findViewById(R.id.btnToggleService)

        dotA11y = findViewById(R.id.dotA11yStatus)
        tvA11ySubtitle = findViewById(R.id.tvA11ySubtitle)
        btnToggleA11y = findViewById(R.id.btnToggleA11y)

        dotOverlay = findViewById(R.id.dotOverlayStatus)
        tvOverlaySubtitle = findViewById(R.id.tvOverlaySubtitle)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        tvPrereqWarning = findViewById(R.id.tvPrereqWarning)

        tvStorageTotalSize = findViewById(R.id.tvStorageTotalSize)
        tvStorageBreakdown = findViewById(R.id.tvStorageBreakdown)

        rvRecentDownloads = findViewById(R.id.rvRecentDownloads)
        tvNoRecent = findViewById(R.id.tvNoRecent)

        rvRecentDownloads.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recentAdapter = RecentDownloadsAdapter(lifecycleScope) { record ->
            MediaViewerActivity.start(this, record.id, 0)
        }
        rvRecentDownloads.adapter = recentAdapter

        findViewById<ImageButton>(R.id.btnHeaderSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.cardNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.cardNavGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        findViewById<View>(R.id.cardNavHistory).setOnClickListener {
            startActivity(Intent(this, DownloadHistoryActivity::class.java))
        }

        findViewById<TextView>(R.id.btnViewAllRecent).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        // Manual URL Download Card
        findViewById<View>(R.id.cardPasteLink).setOnClickListener {
            DialogManualDownload.show(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        btnToggleA11y.setOnClickListener {
            openAccessibilitySettings()
        }

        btnToggleOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        btnToggleService.setOnClickListener {
            val isRunning = FloatingTriggerService.isRunning
            if (isRunning) {
                stopInstaSaveService()
            } else {
                startInstaSaveService()
            }
        }

        // Reactive synchronization: re-query SQLite state whenever a transaction occurs
        lifecycleScope.launch {
            repo.transactionEvents.collect {
                loadRecentDownloads()
                loadStorageSummary()
            }
        }

        // Initialize Home Control Center (Download Mode, Carousel Behavior, Save Location)
        setupControlCenter()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: ""
            val normalized = com.instadown.assist.resolver.UrlNormalizer.normalize(sharedText)
            if (normalized != null) {
                Toast.makeText(this, "InstaSave: Downloading shared media...", Toast.LENGTH_SHORT).show()
                executeManualDownload(normalized.originalUrl)
            } else if (sharedText.isNotBlank()) {
                Toast.makeText(this, "Shared text does not contain a valid Instagram link", Toast.LENGTH_LONG).show()
            }
            return
        }

        intent.getStringExtra("SET_CLIPBOARD")?.let { text ->
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("instagram_test", text))
                Log.i(TAG, "handleIntent: updated primary clip to $text")
                mainHandler.postDelayed({
                    checkClipboardForInstagramLink()
                }, 100)
            } catch (e: Exception) {
                Log.e(TAG, "handleIntent: failed to set clipboard", e)
            }
        }
    }

    private val mediaStoreObserver by lazy {
        com.instadown.assist.data.MediaStoreSyncObserver(this, lifecycleScope)
    }

    override fun onStart() {
        super.onStart()
        mediaStoreObserver.register()
    }

    override fun onStop() {
        super.onStop()
        mediaStoreObserver.unregister()
    }

    override fun onResume() {
        super.onResume()

        // 1. Fast UI query first
        loadRecentDownloads()
        loadStorageSummary()

        // 2. Background MediaStore reconciliation upon app resume
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            repo.reconcileWithMediaStore()
        }

        // 3. User-initiated clipboard detection on focus
        checkClipboardForInstagramLink()

        // 4. Always re-read actual hardware/OS state on resume; never infer
        updateServiceState()

        // 5. Silent background auto-update check (once per app session)
        checkSilentAutoUpdate()

        // 6. Resume pending update install if user just granted Unknown Sources permission
        com.instadown.assist.util.AppUpdateManager.resumePendingInstallIfPermitted(this)
    }

    private fun checkSilentAutoUpdate() {
        if (hasCheckedUpdateThisSession) return
        hasCheckedUpdateThisSession = true

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val info = com.instadown.assist.util.AppUpdateManager.checkForUpdates(this@MainActivity)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (info != null && info.isAvailable) {
                        layoutUpdateBanner.visibility = View.VISIBLE
                        tvUpdateBannerSubtitle.text = "InstaSave v${info.versionName} is ready to install"
                        btnUpdateBannerAction.setOnClickListener {
                            showUpdateAvailablePrompt(info)
                        }
                        layoutUpdateBanner.setOnClickListener {
                            showUpdateAvailablePrompt(info)
                        }
                        btnUpdateBannerDismiss.setOnClickListener {
                            layoutUpdateBanner.visibility = View.GONE
                        }
                    } else {
                        layoutUpdateBanner.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Silent update check notice: ${e.message}")
            }
        }
    }

    private fun showUpdateAvailablePrompt(info: com.instadown.assist.util.AppUpdateInfo) {
        if (isFinishing || isDestroyed) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_in_app_update, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvUpdateDialogTitle)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvUpdateDialogSubtitle)
        val pbChecking = dialogView.findViewById<ProgressBar>(R.id.pbUpdateChecking)
        val layoutReleaseNotes = dialogView.findViewById<View>(R.id.layoutReleaseNotes)
        val tvReleaseVersion = dialogView.findViewById<TextView>(R.id.tvReleaseVersion)
        val tvReleaseSize = dialogView.findViewById<TextView>(R.id.tvReleaseSize)
        val tvReleaseNotes = dialogView.findViewById<TextView>(R.id.tvReleaseNotes)
        val layoutProgress = dialogView.findViewById<View>(R.id.layoutUpdateProgress)
        val tvProgressStatus = dialogView.findViewById<TextView>(R.id.tvDownloadProgressStatus)
        val tvUpdatePercent = dialogView.findViewById<TextView>(R.id.tvUpdatePercent)
        val pbDownload = dialogView.findViewById<ProgressBar>(R.id.pbUpdateDownload)
        val btnNegative = dialogView.findViewById<Button>(R.id.btnUpdateNegative)
        val btnPositive = dialogView.findViewById<Button>(R.id.btnUpdatePositive)

        pbChecking.visibility = View.GONE
        layoutReleaseNotes.visibility = View.VISIBLE
        tvReleaseVersion.text = "Version v${info.versionName}"
        val sizeMb = if (info.fileSizeBytes > 0) String.format(java.util.Locale.US, "%.1f MB", info.fileSizeBytes / (1024.0 * 1024.0)) else ""
        tvReleaseSize.text = sizeMb
        tvReleaseNotes.text = info.releaseNotes

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()

        val existingApk = com.instadown.assist.util.AppUpdateManager.getDownloadedUpdateApk(this@MainActivity, info.versionName)
        if (existingApk != null) {
            tvTitle.text = "Update Ready! 🚀"
            tvSubtitle.text = "InstaSave v${info.versionName} is downloaded and ready to install."
            btnPositive.text = "Install Now"
            btnNegative.text = "Re-download"

            btnPositive.setOnClickListener {
                com.instadown.assist.util.AppUpdateManager.promptInstall(
                    this@MainActivity,
                    existingApk,
                    info.apkUrl,
                    info.versionCode,
                    info.sha256
                )
            }
            btnNegative.setOnClickListener {
                existingApk.delete()
                btnNegative.text = "Later"
                btnNegative.setOnClickListener { dialog.dismiss() }
                startDownloadInDialog(info, dialog, tvTitle, tvSubtitle, layoutProgress, pbDownload, tvUpdatePercent, tvProgressStatus, btnPositive, btnNegative)
            }
            return
        }

        tvTitle.text = "Update Available! 🚀"
        tvSubtitle.text = "A new version of InstaSave is ready to install."
        btnNegative.text = "Later"
        btnNegative.setOnClickListener { dialog.dismiss() }

        btnPositive.text = "Update Now"
        btnPositive.setOnClickListener {
            startDownloadInDialog(info, dialog, tvTitle, tvSubtitle, layoutProgress, pbDownload, tvUpdatePercent, tvProgressStatus, btnPositive, btnNegative)
        }
    }

    private fun startDownloadInDialog(
        info: com.instadown.assist.util.AppUpdateInfo,
        dialog: androidx.appcompat.app.AlertDialog,
        tvTitle: TextView,
        tvSubtitle: TextView,
        layoutProgress: View,
        pbDownload: ProgressBar,
        tvUpdatePercent: TextView,
        tvProgressStatus: TextView,
        btnPositive: Button,
        btnNegative: Button
    ) {
        btnPositive.isEnabled = false
        btnPositive.text = "Downloading..."
        btnNegative.isEnabled = false
        dialog.setCancelable(false)
        layoutProgress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val apk = com.instadown.assist.util.AppUpdateManager.downloadUpdate(
                this@MainActivity,
                info
            ) { percent, bytesRead, totalBytes ->
                runOnUiThread {
                    pbDownload.progress = percent
                    tvUpdatePercent.text = "$percent%"
                    val readMb = String.format(java.util.Locale.US, "%.1f", bytesRead / (1024.0 * 1024.0))
                    val totMb = String.format(java.util.Locale.US, "%.1f MB", totalBytes / (1024.0 * 1024.0))
                    tvProgressStatus.text = "Downloading... ($readMb of $totMb)"
                }
            }

            if (apk != null && apk.exists()) {
                tvTitle.text = "Ready to Install"
                tvSubtitle.text = "Opening installer..."
                tvProgressStatus.text = "Download complete"
                pbDownload.progress = 100
                tvUpdatePercent.text = "100%"

                btnPositive.isEnabled = true
                btnPositive.text = "Install Now"
                btnNegative.isEnabled = true
                btnNegative.text = "Dismiss"
                dialog.setCancelable(true)

                com.instadown.assist.util.AppUpdateManager.promptInstall(
                    this@MainActivity,
                    apk,
                    info.apkUrl,
                    info.versionCode,
                    info.sha256
                )

                btnPositive.setOnClickListener {
                    com.instadown.assist.util.AppUpdateManager.promptInstall(
                        this@MainActivity,
                        apk,
                        info.apkUrl,
                        info.versionCode,
                        info.sha256
                    )
                }
            } else {
                tvTitle.text = "Download Failed"
                tvSubtitle.text = "Please check your network and try again."
                btnPositive.isEnabled = true
                btnPositive.text = "Retry"
                btnNegative.isEnabled = true
                btnNegative.text = "Cancel"
                dialog.setCancelable(true)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkClipboardForInstagramLink()
        }
    }

    private fun setupControlCenter() {
        val btnModeAuto = findViewById<Button>(R.id.btnModeAuto)
        val btnModeClipboard = findViewById<Button>(R.id.btnModeClipboard)
        val tvModeDescription = findViewById<TextView>(R.id.tvModeDescription)

        fun updateModeUI(mode: com.instadown.assist.overlay.DownloadMode) {
            if (mode == com.instadown.assist.overlay.DownloadMode.AUTOMATIC) {
                btnModeAuto.setBackgroundResource(R.drawable.btn_segment_active)
                btnModeAuto.setTextColor(getColor(R.color.white))
                btnModeClipboard.setBackgroundResource(R.drawable.btn_segment_inactive)
                btnModeClipboard.setTextColor(getColor(R.color.text_secondary))
                tvModeDescription.text = "Automatic: Tap the floating button in Instagram to save"
            } else {
                btnModeClipboard.setBackgroundResource(R.drawable.btn_segment_active)
                btnModeClipboard.setTextColor(getColor(R.color.white))
                btnModeAuto.setBackgroundResource(R.drawable.btn_segment_inactive)
                btnModeAuto.setTextColor(getColor(R.color.text_secondary))
                tvModeDescription.text = "Clipboard: Auto-downloads copied links when you open InstaSave"
            }
        }

        btnModeAuto.setOnClickListener {
            prefs.downloadMode = com.instadown.assist.overlay.DownloadMode.AUTOMATIC
            updateModeUI(com.instadown.assist.overlay.DownloadMode.AUTOMATIC)
            if (com.instadown.assist.service.FloatingTriggerService.isRunning) {
                com.instadown.assist.service.FloatingTriggerService.updatePreferences(this)
            }
            updateServiceState()
        }
        btnModeClipboard.setOnClickListener {
            prefs.downloadMode = com.instadown.assist.overlay.DownloadMode.CLIPBOARD
            updateModeUI(com.instadown.assist.overlay.DownloadMode.CLIPBOARD)
            if (com.instadown.assist.service.FloatingTriggerService.isRunning) {
                com.instadown.assist.service.FloatingTriggerService.updatePreferences(this)
            }
            updateServiceState()
        }
        updateModeUI(prefs.downloadMode)

        // Carousel Behavior
        val btnEntire = findViewById<Button>(R.id.btnCarouselEntire)
        val btnCurrent = findViewById<Button>(R.id.btnCarouselCurrent)
        val btnAsk = findViewById<Button>(R.id.btnCarouselAsk)
        val tvCarouselDesc = findViewById<TextView>(R.id.tvCarouselDescription)

        fun updateCarouselUI(behavior: com.instadown.assist.overlay.CarouselDownloadBehavior) {
            btnEntire.setBackgroundResource(if (behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.ENTIRE_CAROUSEL) R.drawable.btn_segment_active else R.drawable.btn_segment_inactive)
            btnEntire.setTextColor(getColor(if (behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.ENTIRE_CAROUSEL) R.color.white else R.color.text_secondary))

            btnCurrent.setBackgroundResource(if (behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM) R.drawable.btn_segment_active else R.drawable.btn_segment_inactive)
            btnCurrent.setTextColor(getColor(if (behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM) R.color.white else R.color.text_secondary))

            btnAsk.setBackgroundResource(if (behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.ASK) R.drawable.btn_segment_active else R.drawable.btn_segment_inactive)
            btnAsk.setTextColor(getColor(if (behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.ASK) R.color.white else R.color.text_secondary))

            tvCarouselDesc.text = when (behavior) {
                com.instadown.assist.overlay.CarouselDownloadBehavior.ENTIRE_CAROUSEL -> "Entire: Downloads all slides in the carousel at once"
                com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM -> "Current: Downloads only the currently visible slide"
                com.instadown.assist.overlay.CarouselDownloadBehavior.ASK -> "Ask: Opens review sheet to choose specific slides"
            }
        }

        btnEntire.setOnClickListener {
            prefs.carouselDownloadBehavior = com.instadown.assist.overlay.CarouselDownloadBehavior.ENTIRE_CAROUSEL
            updateCarouselUI(com.instadown.assist.overlay.CarouselDownloadBehavior.ENTIRE_CAROUSEL)
        }
        btnCurrent.setOnClickListener {
            prefs.carouselDownloadBehavior = com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM
            updateCarouselUI(com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM)
        }
        btnAsk.setOnClickListener {
            prefs.carouselDownloadBehavior = com.instadown.assist.overlay.CarouselDownloadBehavior.ASK
            updateCarouselUI(com.instadown.assist.overlay.CarouselDownloadBehavior.ASK)
        }
        updateCarouselUI(prefs.carouselDownloadBehavior)

        // Save Location
        val tvSaveLocation = findViewById<TextView>(R.id.tvSaveLocationPath)
        val btnChangeLocation = findViewById<Button>(R.id.btnChangeSaveLocation)

        fun updateLocationUI() {
            tvSaveLocation.text = prefs.saveLocationRelativePath
        }
        updateLocationUI()

        btnChangeLocation.setOnClickListener {
            showSaveLocationDialog {
                updateLocationUI()
                StorageAccountant.invalidateCache()
                loadStorageSummary()
            }
        }
    }

    private fun showSaveLocationDialog(onUpdated: () -> Unit) {
        val options = com.instadown.assist.overlay.InstaSavePreferences.SUPPORTED_SAVE_LOCATIONS.toTypedArray()
        val current = prefs.saveLocationRelativePath
        val selectedIdx = options.indexOfFirst { it.equals(current, ignoreCase = true) }.coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Save Location")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                val chosen = options[which]
                prefs.saveLocationRelativePath = chosen
                onUpdated()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkClipboardForInstagramLink() {
        val currentClip = com.instadown.assist.util.ClipboardReader.readCurrentClip(this)
        android.util.Log.d("MainActivityClip", "checkClipboard: hasFocus=${hasWindowFocus()}, mode=${prefs.downloadMode}, clipValid=${currentClip.isValidInstagramUrl}, url=${currentClip.cleanUrl}")

        if (prefs.downloadMode == com.instadown.assist.overlay.DownloadMode.CLIPBOARD) {
            if (!currentClip.isValidInstagramUrl) {
                if (!clipboardCoordinator.isProcessing) {
                    layoutClipboardBanner.visibility = View.GONE
                }
                return
            }

            val decision = clipboardCoordinator.evaluateProcessDecision(
                cleanUrl = currentClip.cleanUrl,
                clipTimestamp = currentClip.timestamp,
                prefs = prefs
            )

            when (decision) {
                com.instadown.assist.download.ClipboardDownloadCoordinator.ProcessDecision.ALLOW -> {
                    clipboardCoordinator.startProcessing(currentClip.cleanUrl)
                    layoutClipboardBanner.visibility = View.VISIBLE
                    tvClipBannerTitle.text = "Downloading copied link..."
                    tvClipBannerUrl.text = currentClip.cleanUrl
                    btnClipBannerDownload.visibility = View.GONE
                    btnClipBannerDismiss.visibility = View.GONE
                    executeClipboardDownload(currentClip.cleanUrl, currentClip.timestamp)
                }
                com.instadown.assist.download.ClipboardDownloadCoordinator.ProcessDecision.REJECT_ALREADY_PROCESSING -> {
                    layoutClipboardBanner.visibility = View.VISIBLE
                    tvClipBannerTitle.text = "Downloading copied link..."
                    tvClipBannerUrl.text = currentClip.cleanUrl
                    btnClipBannerDownload.visibility = View.GONE
                    btnClipBannerDismiss.visibility = View.GONE
                }
                com.instadown.assist.download.ClipboardDownloadCoordinator.ProcessDecision.DEFER_QUEUED -> {
                    layoutClipboardBanner.visibility = View.VISIBLE
                    tvClipBannerTitle.text = "Queued for download"
                    tvClipBannerUrl.text = currentClip.cleanUrl
                    btnClipBannerDownload.visibility = View.GONE
                    btnClipBannerDismiss.visibility = View.GONE
                }
                com.instadown.assist.download.ClipboardDownloadCoordinator.ProcessDecision.REJECT_ALREADY_COMPLETED,
                com.instadown.assist.download.ClipboardDownloadCoordinator.ProcessDecision.REJECT_INVALID -> {
                    if (!clipboardCoordinator.isProcessing) {
                        layoutClipboardBanner.visibility = View.GONE
                    }
                }
            }
        } else {
            // AUTOMATIC mode: Show manual review banner if a fresh Instagram link is copied
            if (currentClip.isValidInstagramUrl && currentClip.cleanUrl != lastDismissedClipUrl) {
                layoutClipboardBanner.visibility = View.VISIBLE
                tvClipBannerTitle.text = "Instagram Link Copied"
                tvClipBannerUrl.text = currentClip.cleanUrl
                btnClipBannerDownload.visibility = View.VISIBLE
                btnClipBannerDownload.text = "Download"
                btnClipBannerDismiss.visibility = View.VISIBLE

                btnClipBannerDownload.setOnClickListener {
                    layoutClipboardBanner.visibility = View.GONE
                    executeManualDownload(currentClip.cleanUrl)
                }

                btnClipBannerDismiss.setOnClickListener {
                    lastDismissedClipUrl = currentClip.cleanUrl
                    layoutClipboardBanner.visibility = View.GONE
                }
            } else {
                if (!clipboardCoordinator.isProcessing) {
                    layoutClipboardBanner.visibility = View.GONE
                }
            }
        }
    }

    private fun executeClipboardDownload(cleanUrl: String, clipTimestamp: Long) {
        lifecycleScope.launch {
            val resolveResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                coordinator.resolver.resolve(cleanUrl, validateCdn = false)
            }

            if (!resolveResult.success) {
                clipboardCoordinator.onProcessingFailed(cleanUrl)
                layoutClipboardBanner.visibility = View.VISIBLE
                tvClipBannerTitle.text = "Failed to resolve link"
                tvClipBannerUrl.text = resolveResult.error?.message ?: "Network error"
                mainHandler.postDelayed({
                    if (!clipboardCoordinator.isProcessing) layoutClipboardBanner.visibility = View.GONE
                }, 3000)
                checkPendingClipboard()
                return@launch
            }

            val behavior = prefs.carouselDownloadBehavior
            val totalItems = resolveResult.items.size

            if (totalItems > 1 && behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.ASK) {
                clipboardCoordinator.onProcessingSuccess(cleanUrl, clipTimestamp, prefs)
                layoutClipboardBanner.visibility = View.GONE
                com.instadown.assist.ui.MediaSelectionActivity.start(this@MainActivity, cleanUrl, resolveResult)
                checkPendingClipboard()
                return@launch
            }

            var targetIndices: Set<Int>? = null
            if (totalItems > 1 && behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM) {
                val observedSlide = com.instadown.assist.service.InstaAccessibilityService.lastObservedSlide
                val slideEval = clipboardCoordinator.resolveCurrentSlideIndex(
                    autoResult = null,
                    totalMediaItems = totalItems,
                    observedSlide = observedSlide
                )

                when (slideEval) {
                    is com.instadown.assist.download.ClipboardDownloadCoordinator.SlideEvaluationResult.Valid -> {
                        targetIndices = setOf(slideEval.targetIndex)
                    }
                    is com.instadown.assist.download.ClipboardDownloadCoordinator.SlideEvaluationResult.Ambiguous -> {
                        clipboardCoordinator.onProcessingFailed(cleanUrl)
                        layoutClipboardBanner.visibility = View.VISIBLE
                        tvClipBannerTitle.text = "Safe Refusal"
                        tvClipBannerUrl.text = "Couldn't identify current slide (0 saved)"
                        Toast.makeText(this@MainActivity, "Couldn't identify current slide. 0 items downloaded.", Toast.LENGTH_LONG).show()
                        mainHandler.postDelayed({
                            if (!clipboardCoordinator.isProcessing) layoutClipboardBanner.visibility = View.GONE
                        }, 3500)
                        checkPendingClipboard()
                        return@launch
                    }
                }
            }

            tvClipBannerTitle.text = if (targetIndices != null) {
                "Downloading slide ${targetIndices.first() + 1}/$totalItems..."
            } else if (totalItems > 1) {
                "Downloading $totalItems items..."
            } else {
                "Downloading..."
            }

            val downloadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                coordinator.executeDirectDownload(
                    url = cleanUrl,
                    selectedIndices = targetIndices,
                    preResolvedResult = resolveResult,
                    onStatusUpdate = { status ->
                        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            tvClipBannerTitle.text = status
                        }
                    }
                )
            }

            if (downloadResult.success || (downloadResult.skippedCount > 0 && downloadResult.failedCount == 0)) {
                clipboardCoordinator.onProcessingSuccess(cleanUrl, clipTimestamp, prefs)
                val count = downloadResult.completedCount
                tvClipBannerTitle.text = when {
                    count > 1 -> "✓ $count saved"
                    count == 1 -> "✓ Saved"
                    else -> "✓ Already saved"
                }
                tvClipBannerUrl.text = if (count > 0) "Saved to ${prefs.saveLocationRelativePath}" else "Already in Gallery"
                loadRecentDownloads()
                loadStorageSummary()
                mainHandler.postDelayed({
                    if (!clipboardCoordinator.isProcessing) layoutClipboardBanner.visibility = View.GONE
                }, 2500)
            } else {
                clipboardCoordinator.onProcessingFailed(cleanUrl)
                tvClipBannerTitle.text = "Download failed"
                tvClipBannerUrl.text = downloadResult.error ?: "Failed to save"
                mainHandler.postDelayed({
                    if (!clipboardCoordinator.isProcessing) layoutClipboardBanner.visibility = View.GONE
                }, 3000)
            }

            checkPendingClipboard()
        }
    }

    private fun checkPendingClipboard() {
        val pending = clipboardCoordinator.pollPending() ?: return
        executeClipboardDownload(pending.first, pending.second)
    }

    fun executeManualDownload(cleanUrl: String) {
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Resolving Link")
            .setMessage("Fetching media info\u2026")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            var resolveResult: com.instadown.assist.resolver.model.ResolveResult? = null
            try {
                resolveResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    coordinator.resolver.resolve(cleanUrl, validateCdn = false)
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Error")
                    .setMessage("Could not connect. Check internet and try again.\n${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            if (resolveResult == null || !resolveResult.success) {
                progressDialog.dismiss()
                val errMsg = resolveResult?.error?.message ?: "Unknown error"
                val userMsg = when {
                    errMsg.contains("timeout", ignoreCase = true) -> "Request timed out. Please try again."
                    errMsg.contains("network", ignoreCase = true) || errMsg.contains("connect", ignoreCase = true) -> "No internet connection."
                    errMsg.contains("private", ignoreCase = true) -> "This post is from a private account."
                    errMsg.contains("not found", ignoreCase = true) || errMsg.contains("404") -> "Post not found or deleted."
                    else -> "Could not load media. Please try again.\n\n${errMsg}"
                }
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Could Not Resolve")
                    .setMessage(userMsg)
                    .setPositiveButton("Retry") { _, _ -> executeManualDownload(cleanUrl) }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@launch
            }

            val behavior = prefs.carouselDownloadBehavior
            val totalItems = resolveResult.items.size

            if (totalItems > 1 && behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.ASK) {
                progressDialog.dismiss()
                com.instadown.assist.ui.MediaSelectionActivity.start(this@MainActivity, cleanUrl, resolveResult)
                return@launch
            }

            var targetIndices: Set<Int>? = null
            if (totalItems > 1 && behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM) {
                val observedSlide = com.instadown.assist.service.InstaAccessibilityService.lastObservedSlide
                val slideEval = clipboardCoordinator.resolveCurrentSlideIndex(
                    autoResult = null, totalMediaItems = totalItems, observedSlide = observedSlide
                )
                when (slideEval) {
                    is com.instadown.assist.download.ClipboardDownloadCoordinator.SlideEvaluationResult.Valid ->
                        targetIndices = setOf(slideEval.targetIndex)
                    is com.instadown.assist.download.ClipboardDownloadCoordinator.SlideEvaluationResult.Ambiguous -> {
                        progressDialog.dismiss()
                        Toast.makeText(this@MainActivity, "Could not identify current slide.", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
            }

            progressDialog.setTitle("Saving")
            progressDialog.setMessage(
                if (targetIndices != null) "Saving slide ${targetIndices!!.first() + 1} of ${totalItems}\u2026"
                else if (totalItems > 1) "Saving ${totalItems} items\u2026"
                else "Saving to gallery\u2026"
            )

            val downloadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                coordinator.executeDirectDownload(
                    url = cleanUrl, selectedIndices = targetIndices, preResolvedResult = resolveResult
                )
            }

            progressDialog.dismiss()
            if (downloadResult.success || downloadResult.completedCount > 0) {
                val count = downloadResult.completedCount
                Toast.makeText(this@MainActivity, if (count > 1) "\u2714 ${count} items saved" else "\u2714 Saved to gallery", Toast.LENGTH_SHORT).show()
                loadRecentDownloads()
                loadStorageSummary()
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Download Failed")
                    .setMessage("Failed to save.\n\n${downloadResult.error ?: "Unknown error"}")
                    .setPositiveButton("Retry") { _, _ -> executeManualDownload(cleanUrl) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    private fun startInstaSaveService() {
        if (prefs.downloadMode == com.instadown.assist.overlay.DownloadMode.AUTOMATIC) {
            val isA11yEnabled = InstaAccessibilityService.isServiceEnabled(this)
            val canOverlay = Settings.canDrawOverlays(this)

            if (!isA11yEnabled) {
                tvPrereqWarning.text = "⚠️ Please enable InstaSave Accessibility Assistant before starting."
                tvPrereqWarning.visibility = View.VISIBLE
                openAccessibilitySettings()
                return
            }

            if (!canOverlay) {
                tvPrereqWarning.text = "⚠️ Please grant 'Display over other apps' permission before starting."
                tvPrereqWarning.visibility = View.VISIBLE
                requestOverlayPermission()
                return
            }
        }

        tvPrereqWarning.visibility = View.GONE

        try {
            prefs.isButtonEnabled = true
            prefs.isServiceRunningDesired = true
            FloatingTriggerService.start(this)
            mainHandler.postDelayed({ updateServiceState() }, 200)
        } catch (e: Exception) {
            prefs.isButtonEnabled = false
            prefs.isServiceRunningDesired = false
            Log.e(TAG, "Failed to start service: ${e.message}", e)
            Toast.makeText(this, "Could not start service: ${e.message}", Toast.LENGTH_LONG).show()
            updateServiceState()
        }
    }

    private fun stopInstaSaveService() {
        prefs.isButtonEnabled = false
        prefs.isServiceRunningDesired = false
        FloatingTriggerService.stop(this)
        mainHandler.postDelayed({ updateServiceState() }, 100)
    }

    private fun updateServiceState() {
        val isA11yEnabled = InstaAccessibilityService.isServiceEnabled(this)
        val canOverlay = Settings.canDrawOverlays(this)
        val isOverlayRunning = FloatingTriggerService.isRunning

        // 1. Accessibility Status
        if (isA11yEnabled) {
            dotA11y.setBackgroundColor(getColor(R.color.status_green))
            tvA11ySubtitle.text = "Service is active and ready"
            btnToggleA11y.text = "Active"
            btnToggleA11y.setTextColor(getColor(R.color.status_green))
            btnToggleA11y.isEnabled = false
        } else {
            dotA11y.setBackgroundColor(getColor(R.color.status_amber))
            tvA11ySubtitle.text = "Required for automated Copy Link"
            btnToggleA11y.text = "Enable"
            btnToggleA11y.setTextColor(getColor(R.color.insta_pink))
            btnToggleA11y.isEnabled = true
        }

        // 2. Overlay Permission Status
        if (canOverlay) {
            dotOverlay.setBackgroundColor(getColor(R.color.status_green))
            tvOverlaySubtitle.text = "Permission granted"
            btnToggleOverlay.text = "Granted"
            btnToggleOverlay.setTextColor(getColor(R.color.status_green))
            btnToggleOverlay.isEnabled = false
        } else {
            dotOverlay.setBackgroundColor(getColor(R.color.status_red))
            tvOverlaySubtitle.text = "Required for floating button overlay"
            btnToggleOverlay.text = "Grant"
            btnToggleOverlay.setTextColor(getColor(R.color.insta_pink))
            btnToggleOverlay.isEnabled = true
        }

        // 3. InstaSave Overlay Service Status
        if (isOverlayRunning) {
            dotService.setBackgroundColor(getColor(R.color.status_green))
            tvServiceTitle.text = "InstaSave is running"
            tvServiceSubtitle.text = if (prefs.downloadMode == com.instadown.assist.overlay.DownloadMode.CLIPBOARD) {
                "Clipboard mode active (floating button hidden)"
            } else {
                "Overlay button is active on screen"
            }
            btnToggleService.text = "Stop InstaSave"
            btnToggleService.setTextColor(getColor(R.color.status_red))
            btnToggleService.setBackgroundColor(getColor(R.color.card_bg))
        } else {
            dotService.setBackgroundColor(getColor(R.color.text_muted))
            tvServiceTitle.text = "InstaSave is stopped"
            tvServiceSubtitle.text = if (!isA11yEnabled || !canOverlay) "Prerequisites required to start" else "Ready to start"
            btnToggleService.text = "Start InstaSave"
            btnToggleService.setTextColor(getColor(R.color.white))
            btnToggleService.setBackgroundColor(getColor(R.color.insta_pink))
        }

        if (isA11yEnabled && canOverlay) {
            tvPrereqWarning.visibility = View.GONE
        }
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "Please grant 'Display over other apps' to use InstaSave", Toast.LENGTH_LONG).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Find 'InstaSave Accessibility Assistant' and enable it", Toast.LENGTH_LONG).show()
    }

    private fun loadStorageSummary() {
        lifecycleScope.launch {
            val summary = StorageAccountant.getStorageSummary(this@MainActivity)
            tvStorageTotalSize.text = summary.formattedSize
            tvStorageBreakdown.text = "${summary.totalItems} items saved (${summary.photosCount} photos, ${summary.videosCount} reels)"
        }
    }

    private fun loadRecentDownloads() {
        lifecycleScope.launch {
            val successfulRecent = repo.getRecentTransactions(limit = 10)
            if (successfulRecent.isEmpty()) {
                rvRecentDownloads.visibility = View.GONE
                tvNoRecent.visibility = View.VISIBLE
            } else {
                tvNoRecent.visibility = View.GONE
                rvRecentDownloads.visibility = View.VISIBLE
                recentAdapter.submitList(successfulRecent)
            }

            val all = repo.getFullHistory()
            val deleted = all.count { it.isDeletedFromDevice }
            findViewById<TextView>(R.id.tvNavHistorySubtitle)?.text = if (deleted > 0) {
                "${all.size} saves • $deleted deleted (Tap to re-download)"
            } else {
                "${all.size} saves in archive • All on device"
            }
        }
    }
}
