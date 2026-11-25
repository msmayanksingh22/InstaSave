package com.instadown.assist.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.InstaSaveApp
import com.instadown.assist.R
import com.instadown.assist.browser.DetectedRoute
import com.instadown.assist.browser.InstagramCreatorResolver
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
 * Strips web chrome, resolves post links directly into creator profiles when requested,
 * and supports safe in-app sign-in using the device's authentic WebView profile.
 */
class ProfileExplorerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ProfileExplorer"
        private const val EXTRA_TARGET_URL = "extra_target_url"
        private const val EXTRA_RAW_INPUT = "extra_raw_input"
        private const val EXTRA_USERNAME = "extra_username"
        private const val EXTRA_PREFER_CREATOR_PROFILE = "extra_prefer_creator_profile"
        private const val EXTRA_SIGN_IN_MODE = "extra_sign_in_mode"
        private const val EXTRA_AFTER_SIGN_IN_TARGET = "extra_after_sign_in_target"
        private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

        fun start(
            context: Context,
            usernameOrUrl: String,
            preferCreatorProfile: Boolean = true
        ) {
            val intent = Intent(context, ProfileExplorerActivity::class.java).apply {
                val cleanUrl = InstagramRouteParser.resolveProfileUrl(usernameOrUrl)
                val username = InstagramRouteParser.extractUsername(usernameOrUrl)
                putExtra(EXTRA_TARGET_URL, cleanUrl)
                putExtra(EXTRA_RAW_INPUT, usernameOrUrl.trim())
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PREFER_CREATOR_PROFILE, preferCreatorProfile)
                putExtra(EXTRA_SIGN_IN_MODE, false)
            }
            context.startActivity(intent)
        }

        fun startSignIn(context: Context, afterSignInTarget: String? = null) {
            val intent = Intent(context, ProfileExplorerActivity::class.java).apply {
                putExtra(EXTRA_TARGET_URL, LOGIN_URL)
                putExtra(EXTRA_RAW_INPUT, LOGIN_URL)
                putExtra(EXTRA_USERNAME, "")
                putExtra(EXTRA_PREFER_CREATOR_PROFILE, false)
                putExtra(EXTRA_SIGN_IN_MODE, true)
                if (!afterSignInTarget.isNullOrBlank()) {
                    putExtra(EXTRA_AFTER_SIGN_IN_TARGET, afterSignInTarget.trim())
                }
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityProfileExplorerBinding
    private var currentPostUrl: String? = null
    private var currentPostShortcode: String? = null
    private var currentPostAuthor: String? = null
    private var currentProfileUsername: String? = null
    private var initialUrl: String = "https://www.instagram.com/"
    private var isSignInMode: Boolean = false
    private var pendingAfterSignInTarget: String? = null
    private var pendingAutoRedirectToAuthor: Boolean = false

    private inner class InstaSaveJsBridge {
        @JavascriptInterface
        fun onPostAuthorDetected(author: String?) {
            val clean = author?.removePrefix("@")?.trim()?.lowercase().orEmpty()
            if (clean.isBlank()) return
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                currentPostAuthor = clean
                if (currentPostUrl != null) {
                    binding.tvProfileUsername.text = "@$clean"
                    binding.tvProfileStatus.text = "Tap here to open @$clean's profile"
                    binding.btnPillViewProfile.text = "Profile"
                    binding.btnPillViewProfile.visibility = View.VISIBLE
                    currentPostShortcode?.let { sc ->
                        binding.tvPillSubtitle.text = "@$clean • Shortcode: $sc"
                    }
                }
                if (pendingAutoRedirectToAuthor) {
                    pendingAutoRedirectToAuthor = false
                    openCreatorProfileUrl(clean)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val targetUrl = intent.getStringExtra(EXTRA_TARGET_URL) ?: "https://www.instagram.com/"
        val rawInput = intent.getStringExtra(EXTRA_RAW_INPUT) ?: targetUrl
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val preferCreatorProfile = intent.getBooleanExtra(EXTRA_PREFER_CREATOR_PROFILE, true)
        isSignInMode = intent.getBooleanExtra(EXTRA_SIGN_IN_MODE, false)
        pendingAfterSignInTarget = intent.getStringExtra(EXTRA_AFTER_SIGN_IN_TARGET)
        initialUrl = targetUrl
        currentProfileUsername = username.ifBlank { null }

        setupInsets()
        setupHeader(username)
        setupWebView()
        setupPillActions()
        setupBackNavigation()
        updateAccountSafetyIcon()

        if (isSignInMode) {
            AppLogger.i(TAG, "Opening Instagram Sign-In inside safe local sandbox...")
            binding.tvProfileUsername.text = "Instagram Sign In"
            binding.tvProfileStatus.text = "Official Login • 100% Local Sandbox"
            ProfileSessionManager.applyOptimalUserAgent(binding.webViewProfile, LOGIN_URL)
            binding.webViewProfile.loadUrl(LOGIN_URL)
            return
        }

        if (preferCreatorProfile && InstagramRouteParser.isMediaPostInput(rawInput)) {
            resolveCreatorAndLoad(rawInput, targetUrl)
        } else {
            AppLogger.i(TAG, "Opening Profile Explorer with URL: $targetUrl")
            ProfileSessionManager.applyOptimalUserAgent(binding.webViewProfile, targetUrl)
            binding.webViewProfile.loadUrl(targetUrl)
        }
    }

    private fun resolveCreatorAndLoad(rawInput: String, fallbackUrl: String) {
        binding.tvProfileUsername.text = "Finding Creator..."
        binding.tvProfileStatus.text = "Opening creator profile from post link"
        binding.progressLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            val resolved = InstagramCreatorResolver.resolveExplorerTarget(
                context = applicationContext,
                input = rawInput,
                preferCreatorProfile = true
            )
            if (isFinishing || isDestroyed) return@launch

            initialUrl = resolved.loadUrl
            if (!resolved.creatorUsername.isNullOrBlank()) {
                currentProfileUsername = resolved.creatorUsername
                currentPostAuthor = resolved.creatorUsername
                pendingAutoRedirectToAuthor = false
                binding.tvProfileUsername.text = "@${resolved.creatorUsername}"
                binding.tvProfileStatus.text = if (resolved.resolvedFromPost) {
                    "Creator Profile • Opened from post link"
                } else {
                    "Profile Explorer • Tap post to save"
                }
            } else {
                pendingAutoRedirectToAuthor = true
                AppLogger.i(TAG, "Upfront author missing; falling back to $fallbackUrl with DOM author auto-redirect")
            }

            ProfileSessionManager.applyOptimalUserAgent(binding.webViewProfile, resolved.loadUrl)
            binding.webViewProfile.loadUrl(resolved.loadUrl)
        }
    }

    private fun openCreatorProfileUrl(username: String) {
        val clean = username.removePrefix("@").trim()
        if (clean.isBlank()) return
        val profileUrl = "https://www.instagram.com/$clean/"
        currentProfileUsername = clean
        currentPostAuthor = clean
        initialUrl = profileUrl
        ProfileSessionManager.applyOptimalUserAgent(binding.webViewProfile, profileUrl)
        binding.webViewProfile.loadUrl(profileUrl)
        Toast.makeText(this, "Opening @$clean's profile...", Toast.LENGTH_SHORT).show()
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

        binding.layoutHeaderTitle.setOnClickListener {
            val author = currentPostAuthor
            if (!author.isNullOrBlank() && currentPostUrl != null) {
                openCreatorProfileUrl(author)
            }
        }

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
            InstagramSignInSheet.newInstance(
                pendingTarget = currentProfileUsername ?: currentPostAuthor,
                onSessionChanged = {
                    updateAccountSafetyIcon()
                    binding.webViewProfile.reload()
                }
            ).show(supportFragmentManager, InstagramSignInSheet.TAG)
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

    private fun updateAccountSafetyIcon() {
        val loggedIn = ProfileSessionManager.isInstagramLoggedIn()
        val colorRes = if (loggedIn) R.color.status_green else R.color.primary
        binding.ivAccountSafetyIcon.setColorFilter(ContextCompat.getColor(this, colorRes))
        binding.ivAccountSafetyIcon.setImageResource(
            if (loggedIn) R.drawable.ic_shield_check else R.drawable.ic_person
        )
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
            },
            onSessionStateChanged = { loggedIn ->
                runOnUiThread {
                    updateAccountSafetyIcon()
                    if (isSignInMode && loggedIn) {
                        isSignInMode = false
                        Toast.makeText(
                            this,
                            "Signed in safely! Session stays inside local sandbox.",
                            Toast.LENGTH_LONG
                        ).show()
                        val target = pendingAfterSignInTarget
                        pendingAfterSignInTarget = null
                        if (!target.isNullOrBlank()) {
                            resolveCreatorAndLoad(target, InstagramRouteParser.resolveProfileUrl(target))
                        } else {
                            finish()
                        }
                    }
                }
            }
        )

        ProfileSessionManager.configureSafeWebView(binding.webViewProfile, client)
        binding.webViewProfile.addJavascriptInterface(InstaSaveJsBridge(), "InstaSaveBridge")
    }

    private fun handleRouteChanged(route: DetectedRoute) {
        when (route) {
            is DetectedRoute.MediaPost -> {
                currentPostUrl = route.canonicalUrl
                currentPostShortcode = route.shortcode
                val knownAuthor = currentPostAuthor
                if (!knownAuthor.isNullOrBlank()) {
                    binding.tvProfileUsername.text = "@$knownAuthor"
                    binding.tvProfileStatus.text = "Tap here to view @$knownAuthor's profile"
                } else {
                    binding.tvProfileStatus.text = "Viewing Post: ${route.shortcode}"
                }
                showFloatingPill(route)
            }
            is DetectedRoute.Profile -> {
                currentPostUrl = null
                currentPostShortcode = null
                currentProfileUsername = route.username
                currentPostAuthor = route.username
                binding.tvProfileUsername.text = "@${route.username}"
                val modeSuffix = if (ProfileSessionManager.isInstagramLoggedIn()) "Full Feed" else "Public Preview"
                binding.tvProfileStatus.text = "Profile Explorer • $modeSuffix"
                hideFloatingPill()
            }
            is DetectedRoute.Other -> {
                currentPostUrl = null
                currentPostShortcode = null
                if (isSignInMode) {
                    binding.tvProfileUsername.text = "Instagram Sign In"
                    binding.tvProfileStatus.text = "Official Login • 100% Local Sandbox"
                }
                hideFloatingPill()
            }
        }
    }

    private fun showFloatingPill(media: DetectedRoute.MediaPost) {
        binding.tvPillTitle.text = if (media.isReel) "Instagram Reel" else "Instagram Post"
        val authorPrefix = currentPostAuthor?.let { "@$it • " }.orEmpty()
        binding.tvPillSubtitle.text = "${authorPrefix}Shortcode: ${media.shortcode}"
        binding.ivPillMediaIcon.setImageResource(
            if (media.isReel) R.drawable.ic_video else R.drawable.ic_download_arrow
        )
        binding.btnPillSavePost.text = "⬇️ Save Post"
        binding.btnPillSavePost.isEnabled = true
        binding.btnPillViewProfile.visibility = if (!currentPostAuthor.isNullOrBlank()) View.VISIBLE else View.GONE

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

        binding.btnPillViewProfile.setOnClickListener {
            val author = currentPostAuthor ?: return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openCreatorProfileUrl(author)
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
