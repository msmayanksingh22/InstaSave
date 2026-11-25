package com.instadown.assist.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.InstaSaveApp
import com.instadown.assist.R
import com.instadown.assist.browser.DetectedRoute
import com.instadown.assist.browser.InstagramRouteParser
import com.instadown.assist.browser.ProfileBrowserClient
import com.instadown.assist.browser.ProfileSessionManager
import com.instadown.assist.databinding.ActivityProfileExplorerBinding
import com.instadown.assist.download.InstagramDownloadCoordinator
import com.instadown.assist.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native-feeling Instagram Profile Explorer.
 * Eliminates web browser chrome, strips intrusive "Use the App" banners,
 * and provides seamless 1-tap media capture as users browse public creators.
 */
class ProfileExplorerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProfileExplorer"
        private const val EXTRA_TARGET_URL = "extra_target_url"
        private const val EXTRA_USERNAME = "extra_username"

        fun start(context: Context, usernameOrUrl: String) {
            val intent = Intent(context, ProfileExplorerActivity::class.java).apply {
                val cleanUrl = InstagramRouteParser.resolveProfileUrl(usernameOrUrl)
                val username = InstagramRouteParser.extractUsername(usernameOrUrl)
                putExtra(EXTRA_TARGET_URL, cleanUrl)
                putExtra(EXTRA_USERNAME, username)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityProfileExplorerBinding
    private var currentPostUrl: String? = null
    private var initialUrl: String = "https://www.instagram.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val targetUrl = intent.getStringExtra(EXTRA_TARGET_URL) ?: "https://www.instagram.com/"
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        initialUrl = targetUrl

        setupInsets()
        setupHeader(username)
        setupWebView()
        setupPillActions()
        setupBackNavigation()

        AppLogger.i(TAG, "Opening Profile Explorer with URL: $targetUrl")
        ProfileSessionManager.applyOptimalUserAgent(binding.webViewProfile, targetUrl)
        binding.webViewProfile.loadUrl(targetUrl)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootProfileExplorer) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.layoutTopHeader.setPadding(0, statusBars.top, 0, 0)
            binding.cardFloatingPostAction.let { pill ->
                val params = pill.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                if (params != null) {
                    params.bottomMargin = navBars.bottom + 24
                    pill.layoutParams = params
                }
            }
            insets
        }
    }

    private fun setupHeader(initialUsername: String) {
        val displayUser = if (initialUsername.isNotBlank()) {
            if (initialUsername.startsWith("@")) initialUsername else "@$initialUsername"
        } else {
            "Profile Explorer"
        }
        binding.tvProfileUsername.text = displayUser

        binding.btnBack.setOnClickListener {
            handleBackPress()
        }

        binding.btnRefresh.setOnClickListener {
            ProfileSessionManager.applyOptimalUserAgent(
                binding.webViewProfile,
                binding.webViewProfile.url ?: initialUrl
            )
            binding.webViewProfile.reload()
        }

        binding.btnClearSession.setOnClickListener {
            ProfileSessionManager.clearLocalSession(binding.webViewProfile) {
                runOnUiThread {
                    Toast.makeText(this, "Session and cache cleared", Toast.LENGTH_SHORT).show()
                    binding.webViewProfile.reload()
                }
            }
        }

        binding.swipeRefresh.setColorSchemeResources(R.color.primary, R.color.accent)
        binding.swipeRefresh.setOnRefreshListener {
            ProfileSessionManager.applyOptimalUserAgent(
                binding.webViewProfile,
                binding.webViewProfile.url ?: initialUrl
            )
            binding.webViewProfile.reload()
        }

        binding.btnRetry.setOnClickListener {
            binding.layoutErrorState.visibility = View.GONE
            binding.webViewProfile.visibility = View.VISIBLE
            ProfileSessionManager.applyOptimalUserAgent(binding.webViewProfile, initialUrl)
            binding.webViewProfile.loadUrl(initialUrl)
        }
    }

    private fun setupWebView() {
        val client = ProfileBrowserClient(
            onRouteChanged = { route ->
                runOnUiThread { handleRouteChanged(route) }
            },
            onPageLoadingChanged = { isLoading ->
                runOnUiThread {
                    binding.progressLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
                    if (!isLoading) {
                        binding.swipeRefresh.isRefreshing = false
                    }
                }
            },
            onErrorReceived = { errorMsg ->
                runOnUiThread {
                    binding.progressLoading.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                    binding.layoutErrorState.visibility = View.VISIBLE
                    binding.tvErrorMessage.text = "Connection Error: $errorMsg"
                }
            }
        )

        ProfileSessionManager.configureSafeWebView(binding.webViewProfile, client)
    }

    private fun handleRouteChanged(route: DetectedRoute) {
        when (route) {
            is DetectedRoute.MediaPost -> {
                currentPostUrl = route.canonicalUrl
                binding.tvProfileStatus.text = "Viewing Post: ${route.shortcode}"
                showFloatingPill(route)
            }
            is DetectedRoute.Profile -> {
                currentPostUrl = null
                binding.tvProfileUsername.text = "@${route.username}"
                binding.tvProfileStatus.text = "Profile Explorer • Tap post to save"
                hideFloatingPill()
            }
            is DetectedRoute.Other -> {
                currentPostUrl = null
                hideFloatingPill()
            }
        }
    }

    private fun showFloatingPill(media: DetectedRoute.MediaPost) {
        binding.tvPillTitle.text = if (media.isReel) "Instagram Reel" else "Instagram Post"
        binding.tvPillSubtitle.text = "Shortcode: ${media.shortcode} • Ready to save"
        binding.ivPillMediaIcon.setImageResource(
            if (media.isReel) R.drawable.ic_video else R.drawable.ic_download_arrow
        )
        binding.btnPillSavePost.text = "⬇️ Save Post"
        binding.btnPillSavePost.isEnabled = true

        if (binding.cardFloatingPostAction.visibility != View.VISIBLE) {
            binding.cardFloatingPostAction.alpha = 0f
            binding.cardFloatingPostAction.translationY = 80f
            binding.cardFloatingPostAction.visibility = View.VISIBLE
            binding.cardFloatingPostAction.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(240)
                .start()
        }
    }

    private fun hideFloatingPill() {
        if (binding.cardFloatingPostAction.visibility == View.VISIBLE) {
            binding.cardFloatingPostAction.animate()
                .alpha(0f)
                .translationY(80f)
                .setDuration(180)
                .withEndAction {
                    binding.cardFloatingPostAction.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupPillActions() {
        binding.btnPillDismiss.setOnClickListener {
            hideFloatingPill()
        }

        binding.btnPillSavePost.setOnClickListener {
            val url = currentPostUrl ?: return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            binding.btnPillSavePost.text = "✓ Saving..."
            binding.btnPillSavePost.isEnabled = false

            Toast.makeText(this, "Saving media to Gallery in background...", Toast.LENGTH_SHORT).show()

            // Dispatch download in background application scope
            InstaSaveApp.applicationScope.launch {
                try {
                    val coordinator = InstagramDownloadCoordinator(applicationContext)
                    val resolveResult = withContext(Dispatchers.IO) {
                        coordinator.resolver.resolve(url, validateCdn = false)
                    }

                    if (resolveResult.success && resolveResult.items.isNotEmpty()) {
                        val downloadResult = coordinator.executeDirectDownload(
                            url = url,
                            selectedIndices = null,
                            preResolvedResult = resolveResult
                        )
                        withContext(Dispatchers.Main) {
                            if (downloadResult.success) {
                                binding.btnPillSavePost.text = "✓ Saved!"
                            } else {
                                binding.btnPillSavePost.text = "Retry Save"
                                binding.btnPillSavePost.isEnabled = true
                                Toast.makeText(
                                    this@ProfileExplorerActivity,
                                    "Save failed: ${downloadResult.error ?: "Unknown error"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.btnPillSavePost.text = "Retry Save"
                            binding.btnPillSavePost.isEnabled = true
                            Toast.makeText(
                                this@ProfileExplorerActivity,
                                "Resolution failed: ${resolveResult.error?.message ?: "Unknown"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Save failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        binding.btnPillSavePost.text = "Retry Save"
                        binding.btnPillSavePost.isEnabled = true
                    }
                }
            }
        }

        binding.btnPillSelectMedia.setOnClickListener {
            val url = currentPostUrl ?: return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            MediaSelectionActivity.start(this, url)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    private fun handleBackPress() {
        if (binding.webViewProfile.canGoBack()) {
            val history = binding.webViewProfile.copyBackForwardList()
            val prevUrl = if (history.currentIndex > 0) {
                history.getItemAtIndex(history.currentIndex - 1)?.url
            } else {
                null
            }
            if (prevUrl != null) {
                ProfileSessionManager.applyOptimalUserAgent(binding.webViewProfile, prevUrl)
            }
            binding.webViewProfile.goBack()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        try {
            binding.webViewProfile.stopLoading()
            binding.webViewProfile.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
