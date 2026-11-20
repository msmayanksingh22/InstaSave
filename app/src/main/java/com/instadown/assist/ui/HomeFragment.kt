package com.instadown.assist.ui

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

        setupRecentDownloadsRecyclerView()
        setupListeners()
        setupShareIntentListener()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshState()
        checkAndAutoFillClipboard()
    }

    private fun checkAndAutoFillClipboard() {
        if (!binding.etInstagramUrl.text.isNullOrBlank()) return
        val clip = ClipboardReader.readCurrentClip(requireContext())
        if (clip.isValidInstagramUrl) {
            binding.etInstagramUrl.setText(clip.cleanUrl)
            binding.etInstagramUrl.setSelection(clip.cleanUrl.length)
            viewModel.clearUrlError()
            com.instadown.assist.util.AppLogger.d("HomeFragment", "Auto-filled clipboard URL: ${clip.cleanUrl}")
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
            viewModel.toggleService()
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

        // Clear error as user types
        binding.etInstagramUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.clearUrlError()
            }
            override fun afterTextChanged(s: Editable?) {}
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
                viewModel.setUrlError("Please paste an Instagram link")
                return@setOnClickListener
            }
            val (isValid, cleanUrl) = ClipboardReader.validateInstagramUrl(raw)
            if (isValid) {
                viewModel.clearUrlError()
                (activity as? MainActivity)?.executeManualDownload(cleanUrl)
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
                binding.etInstagramUrl.setText(target)
                binding.etInstagramUrl.setSelection(target.length)
                (activity as? MainActivity)?.executeManualDownload(target)
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
                    viewModel.events.collect { event ->
                        when (event) {
                            is HomeEvent.ShowMessage -> {
                                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                            }
                            is HomeEvent.OpenOverlayPermissionSettings -> {
                                requestOverlayPermission()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderUiState(state: HomeUiState) {
        // 1. Service Status Pill UI
        val isRunning = state.isServiceRunning
        val pillDotColor = ContextCompat.getColor(
            requireContext(),
            if (isRunning) R.color.status_green else R.color.status_amber
        )
        binding.dotServicePill.backgroundTintList = ColorStateList.valueOf(pillDotColor)
        binding.tvServicePillText.text = if (isRunning) "Running" else "Stopped"
        binding.pillServiceToggle.contentDescription =
            if (isRunning) "InstaSave is running. Tap to stop." else "InstaSave is stopped. Tap to start."

        // 2. Conditional Permissions Banner
        updatePermissionsBanner(state.isMissingPermissions, state.isA11yEnabled, state.canDrawOverlays)

        // 3. Quick-Download Button & Input State
        binding.btnQuickDownload.isEnabled = !state.isSubmittingUrl
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
                    val current = viewModel.uiState.value
                    openMissingPermissionSettings(current.isA11yEnabled, current.canDrawOverlays)
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
        binding.rvRecentDownloads.adapter = null
        recentAdapter = null
        permissionsBannerBinding = null
        _binding = null
    }
}
