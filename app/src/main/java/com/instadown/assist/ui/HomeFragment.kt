package com.instadown.assist.ui

import com.instadown.assist.overlay.ResilientHudState
import com.instadown.assist.overlay.ResilientHudBus
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.instadown.assist.R
import com.instadown.assist.databinding.BannerPermissionsAlertBinding
import com.instadown.assist.databinding.FragmentHomeBinding
import com.instadown.assist.resolver.UrlNormalizer
import com.instadown.assist.ui.viewmodel.HomeEvent
import com.instadown.assist.ui.viewmodel.HomeUiState
import com.instadown.assist.ui.viewmodel.HomeViewModel
import com.instadown.assist.util.ClipboardReader
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()
    private var recentAdapter: RecentDownloadsAdapter? = null
    private var permissionsBannerBinding: BannerPermissionsAlertBinding? = null
    private var lastAutoFilledClipUrl: String? = null
    private var creatorHintJob: kotlinx.coroutines.Job? = null
    private var detectedAuthorForCurrentInput: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val intro = requireContext().getSharedPreferences("home_intro", 0)
        setupRecentDownloadsRecyclerView()
        setupListeners()
        setupShareIntentListener()
        observeViewModel()
        if (activity?.intent?.action == Intent.ACTION_MAIN &&
            !intro.getBoolean("welcomed", false) && childFragmentManager.findFragmentByTag("welcome") == null) {
            WelcomeSheet().show(childFragmentManager, "welcome")
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
        renderAccountPillState()
        checkAndAutoFillClipboard()
    }

    private fun renderAccountPillState() {
        val ctx = context ?: return
        val loggedIn = com.instadown.assist.browser.ProfileSessionManager.isInstagramLoggedIn()
        val colorRes = if (loggedIn) R.color.status_green else R.color.text_muted
        val iconTintRes = if (loggedIn) R.color.status_green else R.color.text_secondary
        binding.dotHomeAccount.backgroundTintList = ContextCompat.getColorStateList(ctx, colorRes)
        binding.ivHomeAccountIcon.setColorFilter(ContextCompat.getColor(ctx, iconTintRes))
        binding.tvHomeAccountBadge.text = if (loggedIn) "Signed in" else "Sign in"
        binding.tvHomeAccountBadge.setTextColor(
            ContextCompat.getColor(ctx, if (loggedIn) R.color.status_green else R.color.text_secondary)
        )
    }

    private fun checkAndAutoFillClipboard() {
        val clip = ClipboardReader.readCurrentClip(requireContext())
        if (!clip.isValidInstagramUrl) return
        val currentText = binding.etInstagramUrl.text?.toString()?.trim().orEmpty()
        if (currentText.isBlank() || (currentText == lastAutoFilledClipUrl && clip.cleanUrl != lastAutoFilledClipUrl)) {
            applyClipboardLinkToInput(clip.cleanUrl, showBannerHint = false)
        } else if (currentText == clip.cleanUrl && binding.tvDetectedCreatorBanner.visibility != View.VISIBLE) {
            updateCreatorHintForInput(clip.cleanUrl)
        }
    }

    private fun applyClipboardLinkToInput(cleanUrl: String, showBannerHint: Boolean) {
        lastAutoFilledClipUrl = cleanUrl
        binding.etInstagramUrl.setText(cleanUrl)
        binding.etInstagramUrl.setSelection(cleanUrl.length)
        viewModel.clearUrlError()
        if (showBannerHint) {
            binding.tvDetectedCreatorBanner.text = "Copied link ready • Tap Explore Profile or Save"
            binding.tvDetectedCreatorBanner.visibility = View.VISIBLE
        }
        updateCreatorHintForInput(cleanUrl)
        com.instadown.assist.util.AppLogger.d("HomeFragment", "Auto-filled clipboard URL: $cleanUrl")
    }

    private fun updateCreatorHintForInput(rawInput: String) {
        creatorHintJob?.cancel()
        val directUser = com.instadown.assist.browser.InstagramRouteParser.extractUsername(rawInput)
        if (directUser.isNotBlank()) {
            detectedAuthorForCurrentInput = directUser
            binding.btnExploreProfile.text = "Explore @$directUser Profile"
            binding.tvDetectedCreatorBanner.text = "Creator: @$directUser • Tap below to browse"
            binding.tvDetectedCreatorBanner.visibility = View.VISIBLE
            return
        }

        if (!com.instadown.assist.browser.InstagramRouteParser.isMediaPostInput(rawInput)) {
            detectedAuthorForCurrentInput = null
            binding.btnExploreProfile.text = "Explore Creator Profile"
            binding.tvDetectedCreatorBanner.visibility = View.GONE
            return
        }

        val appCtx = requireContext().applicationContext
        creatorHintJob = viewLifecycleOwner.lifecycleScope.launch {
            val author = com.instadown.assist.browser.InstagramCreatorResolver.resolveAuthorOnly(appCtx, rawInput)
            if (_binding == null || !isAdded) return@launch
            if (binding.etInstagramUrl.text?.toString()?.trim() != rawInput.trim()) return@launch
            if (!author.isNullOrBlank()) {
                detectedAuthorForCurrentInput = author
                binding.btnExploreProfile.text = "Explore @$author Profile"
                binding.tvDetectedCreatorBanner.text = "Post by @$author • Tap to view profile or save"
                binding.tvDetectedCreatorBanner.visibility = View.VISIBLE
            } else {
                detectedAuthorForCurrentInput = null
                binding.btnExploreProfile.text = "Explore Creator Profile"
            }
        }
    }

    private fun launchProfileExplorerWithOptionalSignIn(rawTarget: String) {
        val ctx = requireContext()
        val resolvedTarget = detectedAuthorForCurrentInput?.takeIf {
            com.instadown.assist.browser.InstagramRouteParser.isMediaPostInput(rawTarget)
        } ?: rawTarget
        val prefs = com.instadown.assist.overlay.InstaSavePreferences(ctx)
        if (!com.instadown.assist.browser.ProfileSessionManager.isInstagramLoggedIn() &&
            !prefs.hasDismissedSignInPrompt &&
            childFragmentManager.findFragmentByTag(InstagramSignInSheet.TAG) == null
        ) {
            InstagramSignInSheet.newInstance(
                pendingTarget = resolvedTarget,
                onContinueAnonymously = {
                    ProfileExplorerActivity.start(ctx, resolvedTarget, preferCreatorProfile = true)
                },
                onSessionChanged = { renderAccountPillState() }
            ).show(childFragmentManager, InstagramSignInSheet.TAG)
        } else {
            ProfileExplorerActivity.start(ctx, resolvedTarget, preferCreatorProfile = true)
        }
    }

    private fun setupRecentDownloadsRecyclerView() {
        binding.rvRecentDownloads.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentDownloads.setHasFixedSize(true)

        recentAdapter = RecentDownloadsAdapter(viewLifecycleOwner.lifecycleScope) { record ->
            MediaViewerActivity.start(requireContext(), record.id, 0)
        }
        binding.rvRecentDownloads.adapter = recentAdapter
    }

    private fun setupListeners() {
        // Live Service Status Pill Toggle
        binding.pillServiceToggle.setOnClickListener {
            val state = viewModel.uiState.value
            if (state.isServiceRunning) {
                viewModel.toggleService()
            } else if (state.isMissingPermissions) {
                showAssistantSetupSheet()
            } else {
                viewModel.toggleService()
            }
        }

        // Navigate to Gallery Tab via "See All"
        binding.btnHomeSeeGallery.setOnClickListener {
            (activity as? MainActivity)?.navigateToTab(R.id.nav_gallery)
        }

        // 1-Tap End-Icon Paste Button (Content Paste)
        binding.tilInstagramUrl.setEndIconOnClickListener {
            val clip = ClipboardReader.readCurrentClip(requireContext())
            if (clip.isValidInstagramUrl) {
                binding.etInstagramUrl.setText(clip.cleanUrl)
                binding.etInstagramUrl.setSelection(clip.cleanUrl.length)
                viewModel.clearUrlError()
            } else if (clip.text.isNotBlank()) {
                binding.etInstagramUrl.setText(clip.text.trim())
                binding.etInstagramUrl.setSelection(binding.etInstagramUrl.text?.length ?: 0)
                viewModel.clearUrlError()
            } else {
                Toast.makeText(requireContext(), "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Safe Account / Optional Sign-In Pill
        binding.pillIgAccount.setOnClickListener {
            if (childFragmentManager.findFragmentByTag(InstagramSignInSheet.TAG) == null) {
                InstagramSignInSheet.newInstance(
                    onSessionChanged = { renderAccountPillState() }
                ).show(childFragmentManager, InstagramSignInSheet.TAG)
            }
        }

        // Detected Creator Banner 1-tap open
        binding.tvDetectedCreatorBanner.setOnClickListener {
            binding.btnExploreProfile.performClick()
        }

        // Clear error & refresh creator hint as user types or pastes
        binding.etInstagramUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.clearUrlError()
            }
            override fun afterTextChanged(s: Editable?) {
                val txt = s?.toString()?.trim().orEmpty()
                if (txt.isBlank()) {
                    detectedAuthorForCurrentInput = null
                    binding.btnExploreProfile.text = "Explore Creator Profile"
                    binding.tvDetectedCreatorBanner.visibility = View.GONE
                } else if (txt != lastAutoFilledClipUrl) {
                    updateCreatorHintForInput(txt)
                }
            }
        })

        // Soft Keyboard "Done" action triggers download
        binding.etInstagramUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.btnQuickDownload.performClick()
                true
            } else {
                false
            }
        }

        // Primary Download Media Button
        binding.btnQuickDownload.setOnClickListener {
            val raw = binding.etInstagramUrl.text?.toString()?.trim() ?: ""
            if (raw.isBlank()) {
                viewModel.setUrlError("Please paste an Instagram link or @username")
                return@setOnClickListener
            }
            val (isValid, cleanUrl) = ClipboardReader.validateInstagramUrl(raw)
            if (isValid) {
                viewModel.clearUrlError()
                (activity as? MainActivity)?.executeManualDownload(cleanUrl)
            } else if (com.instadown.assist.browser.InstagramRouteParser.isValidProfileInput(raw)) {
                viewModel.clearUrlError()
                ProfileExplorerActivity.start(requireContext(), raw)
            } else {
                viewModel.setUrlError("Please enter a valid Instagram link or @username")
            }
        }

        // Explore Creator Profile Button
        binding.btnExploreProfile.setOnClickListener {
            val raw = binding.etInstagramUrl.text?.toString()?.trim() ?: ""
            if (raw.isNotBlank()) {
                if (com.instadown.assist.browser.InstagramRouteParser.isValidProfileInput(raw)) {
                    viewModel.clearUrlError()
                    launchProfileExplorerWithOptionalSignIn(raw)
                } else {
                    viewModel.setUrlError("Enter a valid Instagram link or @username")
                }
                return@setOnClickListener
            }

            val clip = ClipboardReader.readCurrentClip(requireContext())
            val clipText = clip.text.trim()
            if (clip.isValidInstagramUrl || com.instadown.assist.browser.InstagramRouteParser.isValidProfileInput(clipText)) {
                viewModel.clearUrlError()
                launchProfileExplorerWithOptionalSignIn(if (clip.isValidInstagramUrl) clip.cleanUrl else clipText)
            } else {
                showExploreProfilePrompt()
            }
        }

        // Copy Image to Clipboard Button (Zero-Download direct clip)
        binding.btnCopyImage.setOnClickListener {
            val raw = binding.etInstagramUrl.text?.toString()?.trim() ?: ""
            if (raw.isBlank()) {
                viewModel.setUrlError("Please paste an Instagram link")
                return@setOnClickListener
            }
            val (isValid, cleanUrl) = ClipboardReader.validateInstagramUrl(raw)
            if (isValid) {
                viewModel.clearUrlError()
                (activity as? MainActivity)?.executeCopyImage(cleanUrl)
            } else {
                viewModel.setUrlError("Please enter a valid Instagram post or reel link")
            }
        }
    }

    private fun setupShareIntentListener() {
        setFragmentResultListener(MainActivity.REQUEST_SHARED_URL) { _, bundle ->
            val sharedUrl = bundle.getString(MainActivity.KEY_SHARED_URL)
            if (!sharedUrl.isNullOrBlank()) {
                val normalized = UrlNormalizer.normalize(sharedUrl)
                val target = normalized?.originalUrl ?: sharedUrl
                lastAutoFilledClipUrl = target
                binding.etInstagramUrl.setText(target)
                binding.etInstagramUrl.setSelection(target.length)
                (activity as? MainActivity)?.executeManualDownload(target)
            }
        }

        setFragmentResultListener(MainActivity.REQUEST_CLIPBOARD_URL) { _, bundle ->
            val clipUrl = bundle.getString(MainActivity.KEY_CLIPBOARD_URL)
            if (!clipUrl.isNullOrBlank()) {
                applyClipboardLinkToInput(clipUrl, showBannerHint = true)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderUiState(state)
                    }
                }
                launch {
                    ResilientHudBus.state.collect { state ->
                        val busy = state is ResilientHudState.Resolving || state is ResilientHudState.Downloading
                        val progress = binding.saveProgress
                        progress.visibility = if (busy) View.VISIBLE else View.GONE
                        progress.isIndeterminate = state !is ResilientHudState.Downloading || state.totalBytes <= 0
                        if (state is ResilientHudState.Downloading) progress.progress = state.percent.coerceIn(0, 100)
                        binding.btnQuickDownload.isEnabled = !busy
                        binding.btnCopyImage.isEnabled = !busy
                        binding.btnQuickDownload.text = when (state) {
                            is ResilientHudState.Resolving -> "Finding media…"
                            is ResilientHudState.Downloading -> if (state.totalBytes > 0) "Saving · ${progress.progress}%" else "Saving media…"
                            is ResilientHudState.Success -> "Saved to Gallery"
                            else -> "Save media"
                        }
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is HomeEvent.ShowMessage -> {
                                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                            }
                            is HomeEvent.OpenOverlayPermissionSettings -> {
                                showAssistantSetupSheet()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showExploreProfilePrompt() {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, (4 * density).toInt())
        }

        val input = android.widget.EditText(ctx).apply {
            hint = "@username or Instagram link"
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary))
        }
        container.addView(input)

        val recentCreators = viewModel.uiState.value.recentDownloads
            .mapNotNull { it.authorUsername?.removePrefix("@")?.trim()?.takeIf { u -> u.isNotBlank() } }
            .distinct()
            .take(3)

        var dialog: androidx.appcompat.app.AlertDialog? = null

        if (recentCreators.isNotEmpty()) {
            val label = android.widget.TextView(ctx).apply {
                text = "Recent creators"
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, (14 * density).toInt(), 0, (6 * density).toInt())
            }
            container.addView(label)

            val chipRow = com.google.android.material.chip.ChipGroup(ctx).apply {
                isSingleLine = false
            }
            for (creator in recentCreators) {
                val chip = com.google.android.material.chip.Chip(ctx).apply {
                    text = "@$creator"
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.surface_elevated))
                    chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.card_stroke))
                    chipStrokeWidth = 1f * density
                    setOnClickListener {
                        dialog?.dismiss()
                        ProfileExplorerActivity.start(ctx, creator)
                    }
                }
                chipRow.addView(chip)
            }
            container.addView(chipRow)
        }

        dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Explore Creator Profile")
            .setView(container)
            .setPositiveButton("Open") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (com.instadown.assist.browser.InstagramRouteParser.isValidProfileInput(text)) {
                    launchProfileExplorerWithOptionalSignIn(text)
                } else {
                    Toast.makeText(ctx, "Enter a valid @username or Instagram link", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showAssistantSetupSheet() {
        if (childFragmentManager.findFragmentByTag(AssistantSetupBottomSheet.TAG) == null) {
            AssistantSetupBottomSheet.newInstance().apply {
                setOnAssistantActivatedListener {
                    viewModel.startAssistantService()
                }
            }.show(childFragmentManager, AssistantSetupBottomSheet.TAG)
        }
    }

    private fun renderUiState(state: HomeUiState) {
        // 1. Service Status Pill UI
        val isRunning = state.isServiceRunning
        val isA11y = state.isA11yEnabled
        val pillDotColor = ContextCompat.getColor(
            requireContext(),
            when {
                isRunning && isA11y -> R.color.status_green
                isRunning -> R.color.status_amber
                else -> R.color.text_muted
            }
        )
        binding.dotServicePill.backgroundTintList = ColorStateList.valueOf(pillDotColor)
        binding.tvServicePillText.text = when {
            isRunning && isA11y -> "Assistant: ON"
            isRunning -> "Setup needed"
            else -> "Assistant: OFF"
        }
        binding.tvServicePillText.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        binding.pillServiceToggle.contentDescription =
            if (isRunning) "InstaSave assistant is active. Tap to stop." else "InstaSave assistant is off. Tap to configure or start."

        // 2. Conditional Permissions Banner
        updatePermissionsBanner(isRunning && state.isMissingPermissions, state.isA11yEnabled, state.canDrawOverlays)

        // 3. Quick-Download Button & Input State
        // The active operation controls the save button.
        binding.tilInstagramUrl.error = state.urlError

        // 4. Recent Downloads Shelf
        if (state.recentDownloads.isNotEmpty()) {
            binding.rvRecentDownloads.visibility = View.VISIBLE
            binding.tvNoRecent.visibility = View.GONE
            recentAdapter?.submitList(state.recentDownloads)
        } else {
            binding.rvRecentDownloads.visibility = View.GONE
            binding.tvNoRecent.visibility = View.VISIBLE
        }

        // 5. Compact Storage Footprint Chip
        binding.chipStorageFootprint.text = state.storageSummary
    }

    private fun updatePermissionsBanner(isMissing: Boolean, a11y: Boolean, overlay: Boolean) {
        if (!isMissing) {
            binding.permissionsAlertContainer.visibility = View.GONE
            return
        }

        binding.permissionsAlertContainer.visibility = View.VISIBLE
        if (permissionsBannerBinding == null) {
            val stub = binding.permissionsAlertContainer.findViewById<ViewStub>(R.id.stubPermissionsAlert)
            if (stub != null) {
                val inflated = stub.inflate()
                permissionsBannerBinding = BannerPermissionsAlertBinding.bind(inflated)
                // Read live state inside listener to avoid stale boolean closure
                permissionsBannerBinding?.cardPermissionsBanner?.setOnClickListener {
                    showAssistantSetupSheet()
                }
                permissionsBannerBinding?.btnBannerSettings?.setOnClickListener {
                    showAssistantSetupSheet()
                }
            }
        }

        permissionsBannerBinding?.let { banner ->
            val text = when {
                !a11y && !overlay -> "Accessibility Assistant & Overlay permissions are missing."
                !a11y -> "Accessibility Assistant is needed for automated link copying."
                !overlay -> "Display over other apps permission is needed for floating controls."
                else -> "System permissions need attention."
            }
            banner.tvMissingPermissionsText.text = text
        }
    }

    private fun openMissingPermissionSettings(a11y: Boolean, overlay: Boolean) {
        if (!a11y) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else if (!overlay) {
            requestOverlayPermission()
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
        creatorHintJob?.cancel()
        binding.rvRecentDownloads.adapter = null
        recentAdapter = null
        permissionsBannerBinding = null
        _binding = null
    }
}
