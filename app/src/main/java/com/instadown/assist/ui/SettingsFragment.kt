package com.instadown.assist.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.instadown.assist.R
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.databinding.FragmentSettingsBinding
import com.instadown.assist.overlay.ButtonSize
import com.instadown.assist.overlay.CarouselDownloadBehavior
import com.instadown.assist.overlay.DownloadMode
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.service.AccessibilityBinderWatchdog
import com.instadown.assist.service.AccessibilityHealth
import com.instadown.assist.service.FloatingTriggerService
import com.instadown.assist.util.AppLogger
import com.instadown.assist.util.AppUpdateInfo
import com.instadown.assist.util.AppUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: InstaSavePreferences

    private var refreshJob: Job? = null
    private var activeDialog: AlertDialog? = null
    private var isSyncingUi = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = InstaSavePreferences(requireContext())

        listOf(
            binding.downloadsHeading to binding.downloadsPanel,
            binding.assistantHeading to binding.assistantPanel,
            binding.storageHeading to binding.storagePanel
        ).forEach { (heading, panel) ->
            fun expand(open: Boolean) {
                panel.visibility = if (open) View.VISIBLE else View.GONE
                val icons = heading.compoundDrawablesRelative
                val endIcon = if (open) R.drawable.ic_collapse_section else R.drawable.ic_expand_section
                heading.setCompoundDrawablesRelativeWithIntrinsicBounds(icons[0], null, requireContext().getDrawable(endIcon), null)
                androidx.core.view.ViewCompat.setStateDescription(heading, if (open) "Expanded" else "Collapsed")
            }
            expand(savedInstanceState?.getBoolean("section_${panel.id}") == true)
            heading.setOnClickListener { expand(panel.visibility != View.VISIBLE) }
        }
        setupWatchdogCard()
        setupDownloadPreferences()
        setupFloatingControls()
        setupMediaAndStorage()
        setupAppInfoAndUpdates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { views ->
            listOf(views.downloadsPanel, views.assistantPanel, views.storagePanel).forEach {
                outState.putBoolean("section_${it.id}", it.visibility == View.VISIBLE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isSyncingUi = true
        binding.switchEnableButton.isChecked = FloatingTriggerService.isRunning
        isSyncingUi = false
        refreshStorageAndWatchdog()
    }

    private fun setupWatchdogCard() {
        // Wired reactively in refreshStorageAndWatchdog()
    }

    private fun refreshStorageAndWatchdog() {
        val appContext = requireContext().applicationContext
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val (storage, health) = withContext(Dispatchers.IO) {
                val s = StorageAccountant.getStorageSummary(appContext)
                val h = AccessibilityBinderWatchdog.evaluateHealth(appContext)
                Pair(s, h)
            }
            if (_binding == null || !isAdded) return@launch

            binding.tvSettingsStorageTotal.text = storage.formattedSize
            binding.tvSettingsStorageSubtitle.text =
                "${storage.totalItems} saved items (${storage.photosCount} photos, ${storage.videosCount} reels)"
            binding.tvWatchdogStatus.text = when (health) {
                AccessibilityHealth.HEALTHY -> "Connected"
                AccessibilityHealth.ZOMBIE_UNBOUND -> "Reconnect"
                else -> "Off"
            }
            val colorRes = when (health) {
                AccessibilityHealth.HEALTHY -> R.color.status_green
                AccessibilityHealth.ZOMBIE_UNBOUND -> R.color.status_amber
                AccessibilityHealth.DISABLED_BY_USER -> R.color.status_red
            }
            val colorInt = ContextCompat.getColor(appContext, colorRes)
            binding.dotWatchdogStatus.backgroundTintList =
                ContextCompat.getColorStateList(appContext, colorRes)
            binding.tvWatchdogStatus.setTextColor(colorInt)
            binding.tvWatchdogDetails.text = when (health) {
                AccessibilityHealth.HEALTHY ->
                    "Accessibility service is bound and ready for 1-tap saving."
                AccessibilityHealth.ZOMBIE_UNBOUND ->
                    "Service is enabled in Settings but unbound by OEM background killer. Tap below to fix."
                AccessibilityHealth.DISABLED_BY_USER ->
                    "Accessibility service is disabled. Enable it to allow 1-tap auto-detection inside Instagram."
            }
            binding.btnFixWatchdog.visibility =
                if (health == AccessibilityHealth.HEALTHY) View.GONE else View.VISIBLE
            binding.btnFixWatchdog.text = if (health == AccessibilityHealth.ZOMBIE_UNBOUND) {
                "Fix OEM Background Restriction"
            } else {
                "Accessibility Settings"
            }
            binding.btnFixWatchdog.setOnClickListener {
                if (health == AccessibilityHealth.ZOMBIE_UNBOUND) {
                    val oemIntent = AccessibilityBinderWatchdog.resolveOemAutoStartIntent(requireContext())
                    if (oemIntent != null) {
                        try {
                            startActivity(oemIntent)
                            return@setOnClickListener
                        } catch (_: Exception) {}
                    }
                }
                startActivity(AccessibilityBinderWatchdog.buildHighlightedAccessibilityIntent(requireContext()))
            }
        }
    }

    private fun setupDownloadPreferences() {
        // 1. Download Mode
        fun updateModeSelection(mode: DownloadMode) {
            val checkId = if (mode == DownloadMode.AUTOMATIC) R.id.btnModeAuto else R.id.btnModeClipboard
            if (binding.toggleDownloadMode.checkedButtonId != checkId) {
                binding.toggleDownloadMode.check(checkId)
            }
            binding.tvModeDescription.text = if (mode == DownloadMode.AUTOMATIC) {
                "Automatic: Tap the floating button in Instagram to save"
            } else {
                "Clipboard: Auto-downloads copied links when you open InstaSave"
            }
        }
        updateModeSelection(prefs.downloadMode)

        binding.toggleDownloadMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = if (checkedId == R.id.btnModeAuto) DownloadMode.AUTOMATIC else DownloadMode.CLIPBOARD
            prefs.downloadMode = mode
            updateModeSelection(mode)
            if (FloatingTriggerService.isRunning) {
                FloatingTriggerService.updatePreferences(requireContext())
            }
        }

        // 2. Carousel Behavior
        fun updateCarouselSelection(behavior: CarouselDownloadBehavior) {
            val checkId = when (behavior) {
                CarouselDownloadBehavior.ENTIRE_CAROUSEL -> R.id.btnCarouselEntire
                CarouselDownloadBehavior.CURRENT_ITEM -> R.id.btnCarouselCurrent
                CarouselDownloadBehavior.ASK -> R.id.btnCarouselAsk
            }
            if (binding.toggleCarouselBehavior.checkedButtonId != checkId) {
                binding.toggleCarouselBehavior.check(checkId)
            }
            binding.tvCarouselDescription.text = when (behavior) {
                CarouselDownloadBehavior.ENTIRE_CAROUSEL -> "Entire: Downloads all slides in the carousel at once"
                CarouselDownloadBehavior.CURRENT_ITEM -> "Current: Downloads only the currently visible slide"
                CarouselDownloadBehavior.ASK -> "Ask: Opens review sheet to choose specific slides"
            }
        }
        updateCarouselSelection(prefs.carouselDownloadBehavior)

        binding.toggleCarouselBehavior.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val behavior = when (checkedId) {
                R.id.btnCarouselEntire -> CarouselDownloadBehavior.ENTIRE_CAROUSEL
                R.id.btnCarouselCurrent -> CarouselDownloadBehavior.CURRENT_ITEM
                else -> CarouselDownloadBehavior.ASK
            }
            prefs.carouselDownloadBehavior = behavior
            updateCarouselSelection(behavior)
        }

        // 3. Save Location
        binding.tvSaveLocationPath.text = prefs.saveLocationRelativePath
        binding.btnChangeSaveLocation.setOnClickListener {
            showSaveLocationDialog()
        }
    }

    private fun showSaveLocationDialog() {
        val options = InstaSavePreferences.SUPPORTED_SAVE_LOCATIONS.toTypedArray()
        val selectedIdx = options.indexOfFirst {
            it.equals(prefs.saveLocationRelativePath, ignoreCase = true)
        }.coerceAtLeast(0)

        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Save Location")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                prefs.saveLocationRelativePath = options[which]
                binding.tvSaveLocationPath.text = options[which]
                StorageAccountant.invalidateCache()
                refreshStorageAndWatchdog()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupFloatingControls() {
        val appContext = requireContext().applicationContext

        // Main Service Switch
        binding.switchEnableButton.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncingUi) return@setOnCheckedChangeListener
            if (isChecked && !Settings.canDrawOverlays(appContext)) {
                binding.switchEnableButton.isChecked = false
                requestOverlayPermission()
                return@setOnCheckedChangeListener
            }
            prefs.isButtonEnabled = isChecked
            prefs.isServiceRunningDesired = isChecked
            if (isChecked) FloatingTriggerService.start(appContext)
            else FloatingTriggerService.stop(appContext)
        }

        // Dynamic Island HUD
        binding.switchEnableHud.isChecked = prefs.isFloatingHudEnabled
        binding.switchEnableHud.setOnCheckedChangeListener { _, isChecked ->
            prefs.isFloatingHudEnabled = isChecked
            FloatingTriggerService.updatePreferences(appContext)
        }

        // Edge Docking
        binding.switchEdgeDocking.isChecked = prefs.isEdgeDockingEnabled
        binding.switchEdgeDocking.setOnCheckedChangeListener { _, isChecked ->
            prefs.isEdgeDockingEnabled = isChecked
            FloatingTriggerService.updatePreferences(appContext)
        }

        // Haptic Feedback
        binding.switchHaptics.isChecked = prefs.isHapticFeedbackEnabled
        binding.switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            prefs.isHapticFeedbackEnabled = isChecked
        }

        // Button Size Toggle Group
        val currentSize = prefs.buttonSize
        val sizeCheckId = when (currentSize) {
            ButtonSize.SMALL -> R.id.btnSizeSmall
            ButtonSize.MEDIUM -> R.id.btnSizeMedium
            ButtonSize.LARGE -> R.id.btnSizeLarge
        }
        binding.toggleButtonSize.check(sizeCheckId)
        binding.toggleButtonSize.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newSize = when (checkedId) {
                R.id.btnSizeSmall -> ButtonSize.SMALL
                R.id.btnSizeLarge -> ButtonSize.LARGE
                else -> ButtonSize.MEDIUM
            }
            prefs.buttonSize = newSize
            FloatingTriggerService.updatePreferences(appContext)
        }

        // Button Opacity Slider
        val currentOpacityPercent = (prefs.buttonOpacity * 100).toInt()
        binding.sliderOpacity.value = currentOpacityPercent.toFloat().coerceIn(40f, 100f)
        binding.tvOpacityValue.text = "$currentOpacityPercent%"

        binding.sliderOpacity.addOnChangeListener { _, value, _ ->
            binding.tvOpacityValue.text = "${value.toInt()}%"
        }
        binding.sliderOpacity.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val fraction = slider.value / 100f
                prefs.buttonOpacity = fraction
                FloatingTriggerService.updatePreferences(appContext)
            }
        })

        // Reset Position
        binding.btnResetPosition.setOnClickListener {
            FloatingTriggerService.resetPosition(appContext)
            Toast.makeText(requireContext(), "Floating bubble reset to default position", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMediaAndStorage() {
        val appContext = requireContext().applicationContext

        binding.switchAudioExtraction.isChecked = prefs.isAudioExtractionEnabled
        binding.switchAudioExtraction.setOnCheckedChangeListener { _, isChecked ->
            prefs.isAudioExtractionEnabled = isChecked
        }

        binding.switchSkipDuplicates.isChecked = prefs.duplicatePolicy == "SKIP"
        binding.switchSkipDuplicates.setOnCheckedChangeListener { _, isChecked ->
            prefs.duplicatePolicy = if (isChecked) "SKIP" else "ASK"
        }

        binding.switchNotifications.isChecked = prefs.isCompletionNotificationsEnabled
        binding.switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.isCompletionNotificationsEnabled = isChecked
        }

        binding.switchStartOnBoot.isChecked = prefs.isStartOnBootEnabled
        binding.switchStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.isStartOnBootEnabled = isChecked
        }

        // Clean Cache (Safe selective deletion)
        binding.btnClearCache.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val cacheDir = appContext.cacheDir
                var deletedCount = 0
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && !file.name.endsWith(".apk")) {
                        if (file.delete()) deletedCount++
                    }
                }
                withContext(Dispatchers.Main) {
                    if (_binding == null || !isAdded) return@withContext
                    Toast.makeText(
                        requireContext(),
                        if (deletedCount > 0) "Cache cleared ($deletedCount files)" else "Cache already empty",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshStorageAndWatchdog()
                }
            }
        }

        // Re-index MediaStore
        binding.btnReindexMedia.setOnClickListener {
            Toast.makeText(requireContext(), "Re-indexing MediaStore...", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                DownloadRepository.getInstance(appContext).reconcileWithMediaStore()
                StorageAccountant.invalidateCache()
                withContext(Dispatchers.Main) {
                    if (_binding == null || !isAdded) return@withContext
                    Toast.makeText(requireContext(), "MediaStore synchronized!", Toast.LENGTH_SHORT).show()
                    refreshStorageAndWatchdog()
                }
            }
        }
    }

    private fun setupAppInfoAndUpdates() {
        val appContext = requireContext().applicationContext
        try {
            val pInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val buildNum = PackageInfoCompat.getLongVersionCode(pInfo)
            binding.tvSettingsAppVersion.text = "Version ${pInfo.versionName} (Build $buildNum)"
        } catch (_: Exception) {
            binding.tvSettingsAppVersion.text = "InstaSave for Android"
        }

        binding.cardCheckUpdates.setOnClickListener { startInAppUpdateFlow() }
        binding.btnSettingsCheckUpdates.setOnClickListener { startInAppUpdateFlow() }

        binding.cardDiagnosticLogs.setOnClickListener { showDiagnosticLogsDialog() }
        binding.btnViewDiagnosticLogs.setOnClickListener { showDiagnosticLogsDialog() }

        binding.btnShareApp.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "InstaSave for Android")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Download InstaSave — the fastest, private, watermark-free Instagram media downloader for Android!\n\nDownload latest APK:\nhttps://github.com/msmayanksingh22/InstaSave/releases/latest"
                )
            }
            startActivity(Intent.createChooser(shareIntent, "Share InstaSave with Friends"))
        }
    }

    private fun startInAppUpdateFlow() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_in_app_update, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvUpdateDialogTitle)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvUpdateDialogSubtitle)
        val pbChecking = dialogView.findViewById<ProgressBar>(R.id.pbUpdateChecking)
        val btnNegative = dialogView.findViewById<Button>(R.id.btnUpdateNegative)
        val btnPositive = dialogView.findViewById<Button>(R.id.btnUpdatePositive)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.80f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()

        btnNegative.setOnClickListener { dialog.dismiss() }
        btnNegative.setText(android.R.string.cancel)
        btnPositive.visibility = View.GONE

        val appContext = requireContext().applicationContext
        val check = viewLifecycleOwner.lifecycleScope.launch {
            val updateInfo = withContext(Dispatchers.IO) {
                AppUpdateManager.checkForUpdates(appContext)
            }
            if (!dialog.isShowing) return@launch
            pbChecking.visibility = View.GONE

            if (updateInfo == null) {
                tvTitle.text = "Connection Error"
                tvSubtitle.text = "Could not check for updates. Please verify your internet connection."
                btnPositive.visibility = View.VISIBLE
                btnPositive.text = "Retry"
                btnPositive.setOnClickListener {
                    dialog.dismiss()
                    startInAppUpdateFlow()
                }
                return@launch
            }

            if (!updateInfo.isAvailable) {
                tvTitle.text = "You’re up to date"
                tvSubtitle.text = binding.tvSettingsAppVersion.text
                btnNegative.visibility = View.VISIBLE
                btnNegative.text = "Dismiss"
                btnNegative.setOnClickListener { dialog.dismiss() }
                btnPositive.visibility = View.GONE
            } else {
                dialog.dismiss()
                (activity as? MainActivity)?.showUpdateAvailablePrompt(updateInfo)
            }
        }
        dialog.setOnDismissListener { check.cancel() }
    }

    private fun showDiagnosticLogsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_diagnostic_logs, null)
        val tvLogsCount = dialogView.findViewById<TextView>(R.id.tvLogsCount)
        val tvLogsContent = dialogView.findViewById<TextView>(R.id.tvLogsContent)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnLogsDialogClose)
        val btnFilterAll = dialogView.findViewById<Button>(R.id.btnFilterAll)
        val btnFilterErrors = dialogView.findViewById<Button>(R.id.btnFilterErrors)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearLogs)
        val btnShare = dialogView.findViewById<Button>(R.id.btnShareLogs)
        val btnCopy = dialogView.findViewById<Button>(R.id.btnCopyLogs)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.80f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()

        var currentFilter = "ALL"

        fun refreshLogDisplay() {
            val allLogs = AppLogger.getLogs()
            val filtered = if (currentFilter == "ERRORS") {
                allLogs.filter { it.level == "ERROR" || it.level == "WARN" }
            } else {
                allLogs
            }

            tvLogsCount.text = "${filtered.size} of ${allLogs.size} entries"
            if (filtered.isEmpty()) {
                tvLogsContent.text = if (allLogs.isEmpty()) "No logs recorded in memory yet." else "No errors or warnings recorded."
            } else {
                val sb = StringBuilder()
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
            btnFilterAll.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            btnFilterErrors.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            refreshLogDisplay()
        }

        btnFilterErrors.setOnClickListener {
            currentFilter = "ERRORS"
            btnFilterErrors.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            btnFilterAll.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            refreshLogDisplay()
        }

        btnCopy.setOnClickListener {
            val success = AppLogger.copyLogsToClipboard(requireContext())
            if (success) {
                Toast.makeText(requireContext(), "Diagnostic logs copied to clipboard!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Failed to copy logs", Toast.LENGTH_SHORT).show()
            }
        }

        btnShare.setOnClickListener {
            try {
                startActivity(AppLogger.createShareIntent(requireContext()))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Could not open share menu: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            AppLogger.clear()
            refreshLogDisplay()
            Toast.makeText(requireContext(), "In-memory logs cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${requireContext().packageName}")
        )
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activeDialog?.dismiss()
        activeDialog = null
        refreshJob?.cancel()
        _binding = null
    }
}
