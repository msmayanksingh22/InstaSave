package com.instadown.assist.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.R
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.databinding.ActivityMainBinding
import com.instadown.assist.download.ClipboardDownloadCoordinator
import com.instadown.assist.overlay.ResilientHudBus
import com.instadown.assist.overlay.ResilientHudState
import com.instadown.assist.overlay.runHudTrackedOperation
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.resolver.UrlNormalizer
import com.instadown.assist.util.AppLogger
import com.instadown.assist.util.ClipboardReader
import com.instadown.assist.util.DownloadNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

object UpdatePromptTracker {
    var hasPromptedThisSession: Boolean = false
}

class MainActivity : AppCompatActivity() {

    private val isBootReady = AtomicBoolean(false)
    private lateinit var binding: ActivityMainBinding
    private var activeTabId: Int = R.id.nav_home

    private lateinit var prefs: InstaSavePreferences
    private val repo by lazy { DownloadRepository.getInstance(this) }
    private val coordinator by lazy { com.instadown.assist.download.InstagramDownloadCoordinator(this) }
    private val clipboardCoordinator by lazy { ClipboardDownloadCoordinator(this) }
    private var hasCheckedUpdateThisSession = false

    private val mediaStoreObserver by lazy {
        com.instadown.assist.data.MediaStoreSyncObserver(this, lifecycleScope)
    }

    private val backToHomeCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            navigateToTab(R.id.nav_home)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_ACTIVE_TAB = "active_tab_id"
        const val REQUEST_SHARED_URL = "request_shared_url"
        const val KEY_SHARED_URL = "shared_url"
        const val EXTRA_TARGET_TAB = "extra_target_tab"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        val isDirectShareIntent =
            intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_VIEW

        splashScreen.setKeepOnScreenCondition { !isDirectShareIntent && !isBootReady.get() }
        splashScreen.setOnExitAnimationListener { splashViewProvider ->
            if (isDirectShareIntent) {
                splashViewProvider.remove()
                return@setOnExitAnimationListener
            }

            val rootView = splashViewProvider.view
            val iconView = runCatching { splashViewProvider.iconView }.getOrNull()

            val fadeOut = ObjectAnimator.ofFloat(rootView, View.ALPHA, 1f, 0f).apply {
                duration = 260L
                interpolator = DecelerateInterpolator()
            }

            if (iconView != null) {
                val scaleX = ObjectAnimator.ofFloat(iconView, View.SCALE_X, 1f, 1.18f).apply { duration = 260L }
                val scaleY = ObjectAnimator.ofFloat(iconView, View.SCALE_Y, 1f, 1.18f).apply { duration = 260L }
                AnimatorSet().apply {
                    playTogether(fadeOut, scaleX, scaleY)
                    doOnEnd { splashViewProvider.remove() }
                    start()
                }
            } else {
                fadeOut.doOnEnd { splashViewProvider.remove() }
                fadeOut.start()
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        isBootReady.set(true)

        prefs = InstaSavePreferences(this)
        DownloadNotificationManager.createNotificationChannels(this)

        setupWindowInsets()
        onBackPressedDispatcher.addCallback(this, backToHomeCallback)

        if (savedInstanceState == null) {
            switchTab(R.id.nav_home)
        } else {
            activeTabId = savedInstanceState.getInt(KEY_ACTIVE_TAB, R.id.nav_home)
            backToHomeCallback.isEnabled = (activeTabId != R.id.nav_home)
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (item.itemId != activeTabId) {
                switchTab(item.itemId)
            }
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onActivityReenter(resultCode: Int, data: Intent?) {
        super.onActivityReenter(resultCode, data)
        val returnPos = data?.getIntExtra(SharedMediaTransition.EXTRA_RETURN_POSITION, -1) ?: -1
        if (returnPos >= 0) {
            (supportFragmentManager.findFragmentByTag("tab_gallery") as? GalleryFragment)?.onActivityReenter(returnPos)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ACTIVE_TAB, activeTabId)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootMainContainer) { _, insets ->
            val sysBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val isKeyboardOpen = insets.isVisible(WindowInsetsCompat.Type.ime())

            // Hide Floating Nav Deck while typing so URL box and button stay visible
            binding.floatingNavContainer.isVisible = !isKeyboardOpen
            val density = resources.displayMetrics.density
            val baseBottomMargin = (16 * density).toInt()
            val navParams = binding.floatingNavContainer.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (navParams != null) {
                navParams.bottomMargin = baseBottomMargin + sysBars.bottom
                binding.floatingNavContainer.layoutParams = navParams
            }

            // Apply insets to fragment container
            binding.mainFragmentContainer.updatePadding(
                left = sysBars.left,
                top = sysBars.top,
                right = sysBars.right,
                bottom = if (isKeyboardOpen) ime.bottom else 0
            )

            WindowInsetsCompat.CONSUMED
        }
    }

    fun navigateToTab(tabId: Int) {
        binding.bottomNavigation.selectedItemId = tabId
    }

    private fun switchTab(targetTabId: Int) {
        val currentTag = tagForTab(activeTabId)
        val targetTag = tagForTab(targetTabId)

        val currentFragment = supportFragmentManager.findFragmentByTag(currentTag)
        val targetFragment = supportFragmentManager.findFragmentByTag(targetTag)

        supportFragmentManager.beginTransaction().apply {
            if (currentFragment != null && currentFragment != targetFragment) {
                hide(currentFragment)
                setMaxLifecycle(currentFragment, Lifecycle.State.STARTED)
            }

            if (targetFragment == null) {
                val created = createFragmentForTab(targetTabId)
                add(R.id.mainFragmentContainer, created, targetTag)
                setMaxLifecycle(created, Lifecycle.State.RESUMED)
            } else {
                show(targetFragment)
                setMaxLifecycle(targetFragment, Lifecycle.State.RESUMED)
            }
        }.commit()

        activeTabId = targetTabId
        backToHomeCallback.isEnabled = (targetTabId != R.id.nav_home)
    }

    private fun tagForTab(tabId: Int) = when (tabId) {
        R.id.nav_home -> "tab_home"
        R.id.nav_gallery -> "tab_gallery"
        R.id.nav_history -> "tab_history"
        R.id.nav_settings -> "tab_settings"
        else -> "tab_home"
    }

    private fun createFragmentForTab(tabId: Int): Fragment = when (tabId) {
        R.id.nav_home -> HomeFragment()
        R.id.nav_gallery -> GalleryFragment()
        R.id.nav_history -> HistoryFragment()
        R.id.nav_settings -> SettingsFragment()
        else -> HomeFragment()
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val targetTab = intent.getIntExtra(EXTRA_TARGET_TAB, -1)
        if (targetTab != -1) {
            navigateToTab(targetTab)
            intent.removeExtra(EXTRA_TARGET_TAB)
        }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: ""
            val normalized = UrlNormalizer.normalize(sharedText)
            if (normalized != null) {
                if (activeTabId != R.id.nav_home) {
                    navigateToTab(R.id.nav_home)
                }
                supportFragmentManager.setFragmentResult(
                    REQUEST_SHARED_URL,
                    bundleOf(KEY_SHARED_URL to normalized.originalUrl)
                )
            } else if (sharedText.isNotBlank()) {
                Toast.makeText(this, "Shared text does not contain a valid Instagram link", Toast.LENGTH_LONG).show()
            }
            intent.action = Intent.ACTION_MAIN
        }
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
        lifecycleScope.launch(Dispatchers.IO) {
            repo.reconcileWithMediaStore()
        }
        checkSilentAutoUpdate()
        com.instadown.assist.util.AppUpdateManager.resumePendingInstallIfPermitted(this)
        checkClipboardForInstagramLink()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkClipboardForInstagramLink()
        }
    }

    private fun checkClipboardForInstagramLink() {
        if (prefs.downloadMode != com.instadown.assist.overlay.DownloadMode.CLIPBOARD) {
            return
        }

        val clip = ClipboardReader.readCurrentClip(this)
        AppLogger.d(TAG, "checkClipboardForInstagramLink: valid=${clip.isValidInstagramUrl}, url='${clip.cleanUrl}'")
        if (!clip.isValidInstagramUrl) return

        val decision = clipboardCoordinator.evaluateProcessDecision(
            cleanUrl = clip.cleanUrl,
            clipTimestamp = clip.timestamp,
            prefs = prefs
        )

        AppLogger.i(TAG, "Clipboard evaluation decision: $decision for '${clip.cleanUrl}'")
        when (decision) {
            ClipboardDownloadCoordinator.ProcessDecision.ALLOW -> {
                clipboardCoordinator.startProcessing(clip.cleanUrl)
                Toast.makeText(this, "Auto-downloading copied link...", Toast.LENGTH_SHORT).show()
                executeManualDownload(clip.cleanUrl)
            }
            ClipboardDownloadCoordinator.ProcessDecision.DEFER_QUEUED -> {
                Toast.makeText(this, "Queued copied link for download", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Already processing or completed
            }
        }
    }

    fun executeManualDownload(cleanUrl: String) {
        Toast.makeText(this, "Resolving Instagram link...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val operationResult = runHudTrackedOperation(initialLinkLabel = "Resolving Link...") { opId ->
                ResilientHudBus.emit(ResilientHudState.Resolving(opId, "Resolving Instagram media..."))

                val resolveResult = withContext(Dispatchers.IO) {
                    coordinator.resolver.resolve(cleanUrl, validateCdn = false)
                }

                if (!resolveResult.success || resolveResult.items.isEmpty()) {
                    val errMsg = resolveResult.formattedTrace
                    throw IllegalStateException(errMsg)
                }

                val behavior = prefs.carouselDownloadBehavior
                val totalItems = resolveResult.items.size

                if (totalItems > 1 && behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.ASK) {
                    ResilientHudBus.dismiss(opId)
                    MediaSelectionActivity.start(this@MainActivity, cleanUrl, resolveResult)
                    return@runHudTrackedOperation null
                }

                var targetIndices: Set<Int>? = null
                if (totalItems > 1 && behavior == com.instadown.assist.overlay.CarouselDownloadBehavior.CURRENT_ITEM) {
                    val observedSlide = com.instadown.assist.service.InstaAccessibilityService.lastObservedSlide
                    val slideEval = com.instadown.assist.download.ClipboardDownloadCoordinator(this@MainActivity).resolveCurrentSlideIndex(
                        autoResult = null, totalMediaItems = totalItems, observedSlide = observedSlide
                    )
                    when (slideEval) {
                        is com.instadown.assist.download.ClipboardDownloadCoordinator.SlideEvaluationResult.Valid ->
                            targetIndices = setOf(slideEval.targetIndex)
                        is com.instadown.assist.download.ClipboardDownloadCoordinator.SlideEvaluationResult.Ambiguous -> {
                            ResilientHudBus.dismiss(opId)
                            Toast.makeText(this@MainActivity, "Could not identify current slide.", Toast.LENGTH_LONG).show()
                            return@runHudTrackedOperation null
                        }
                    }
                }

                ResilientHudBus.emit(
                    ResilientHudState.Downloading(
                        operationId = opId,
                        currentIndex = 1,
                        totalCount = targetIndices?.size ?: totalItems,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        percent = 15
                    )
                )

                val downloadResult = withContext(Dispatchers.IO) {
                    coordinator.executeDirectDownload(
                        url = cleanUrl, selectedIndices = targetIndices, preResolvedResult = resolveResult
                    )
                }

                if (downloadResult.success || downloadResult.completedCount > 0) {
                    val count = downloadResult.completedCount
                    ResilientHudBus.emit(
                        ResilientHudState.Success(
                            operationId = opId,
                            message = if (count > 1) "Saved $count items to gallery!" else "Saved to gallery!",
                            totalItems = count
                        )
                    )
                    downloadResult
                } else {
                    throw IllegalStateException(downloadResult.error ?: "Download write failed")
                }
            }

            operationResult.onSuccess { dlResult ->
                clipboardCoordinator.onProcessingSuccess(cleanUrl, System.currentTimeMillis(), prefs)
                if (dlResult != null) {
                    val count = dlResult.completedCount
                    Toast.makeText(
                        this@MainActivity,
                        if (count > 1) "✔ $count items saved" else "✔ Saved to gallery",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }.onFailure { err ->
                clipboardCoordinator.onProcessingFailed(cleanUrl)
                val errMsg = err.message ?: "Unknown error"
                val (userTitle, userSub) = when {
                    errMsg.contains("timeout", ignoreCase = true) ->
                        "Request Timed Out" to "The Instagram request took too long. Tap retry to try again."
                    errMsg.contains("network", ignoreCase = true) || errMsg.contains("connect", ignoreCase = true) ->
                        "No Internet Connection" to "Could not connect to the network. Please check your Wi-Fi or data connection."
                    errMsg.contains("private", ignoreCase = true) ->
                        "Private Account Post" to "This post is from a private account and cannot be downloaded anonymously."
                    errMsg.contains("not found", ignoreCase = true) || errMsg.contains("404") ->
                        "Post Not Found" to "This Instagram post was deleted or the link is invalid."
                    else ->
                        "Could Not Resolve Media" to "Could not load media streams from Instagram. Tap retry to attempt alternate resolution tiers."
                }
                val sheet = DownloadErrorBottomSheet.newInstance(
                    title = userTitle,
                    subtitle = userSub,
                    diagnostic = errMsg,
                    url = cleanUrl
                )
                sheet.setOnRetryListener { executeManualDownload(cleanUrl) }
                sheet.show(supportFragmentManager, DownloadErrorBottomSheet.TAG)
            }
        }
    }

    private fun checkSilentAutoUpdate() {
        if (hasCheckedUpdateThisSession) return
        hasCheckedUpdateThisSession = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val info = com.instadown.assist.util.AppUpdateManager.checkForUpdates(this@MainActivity)
                withContext(Dispatchers.Main) {
                    if (info != null && info.isAvailable) {
                        if (!UpdatePromptTracker.hasPromptedThisSession && !isFinishing && !isDestroyed) {
                            UpdatePromptTracker.hasPromptedThisSession = true
                            showUpdateAvailablePrompt(info)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Silent update check notice: ${e.message}")
            }
        }
    }

    fun showUpdateAvailablePrompt(info: com.instadown.assist.util.AppUpdateInfo) {
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

        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.80f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()

        val existingApk = com.instadown.assist.util.AppUpdateManager.getDownloadedUpdateApk(this@MainActivity, info.versionName)
        if (existingApk != null) {
            tvTitle.text = "Ready to install"
            tvSubtitle.text = "InstaSave v${info.versionName} is downloaded and ready to install."
            btnPositive.text = "Install Now"
            btnNegative.text = "Re-download"

            btnPositive.setOnClickListener {
                com.instadown.assist.util.AppUpdateManager.promptInstall(
                    this@MainActivity, existingApk, info.apkUrl, info.versionCode, info.sha256
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

        tvTitle.text = "Update available"
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
                context = this@MainActivity,
                updateInfo = info,
                onProgress = { percent, _, _ ->
                    runOnUiThread {
                        pbDownload.progress = percent
                        tvUpdatePercent.text = "$percent%"
                    }
                },
                onProgressSnapshot = { snapshot ->
                    runOnUiThread {
                        pbDownload.progress = snapshot.percent
                        tvUpdatePercent.text = "${snapshot.percent}%"
                        tvProgressStatus.text = "Downloading (${snapshot.formattedBytes}) • ${snapshot.formattedSpeed} • ${snapshot.formattedEta}"
                    }
                }
            )

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
                    this@MainActivity, apk, info.apkUrl, info.versionCode, info.sha256
                )

                btnPositive.setOnClickListener {
                    com.instadown.assist.util.AppUpdateManager.promptInstall(
                        this@MainActivity, apk, info.apkUrl, info.versionCode, info.sha256
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
}
