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
    private var currentPostIsReel: Boolean = false
    private var currentProfileUsername: String? = null
    private var initialUrl: String = "https://www.instagram.com/"
    private var isSignInMode: Boolean = false
    private var pendingAfterSignInTarget: String? = null
    private var pendingAutoRedirectToAuthor: Boolean = false
    private var isOnDedicatedPostRoute: Boolean = false
    private var dismissedShortcode: String? = null
    private var igNavBarVisible: Boolean = false
    private var igNavBarHeightCssPx: Int = 0
    private var lastInsetNavBottomPx: Int = 0

    private inner class InstaSaveJsBridge {
        @JavascriptInterface
        fun onPostAuthorDetected(author: String?) {
            val clean = author?.removePrefix("@")?.trim()?.lowercase().orEmpty()
            if (clean.isBlank()) return
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                currentPostAuthor = clean
                if (currentPostUrl != null) {
                    if (isOnDedicatedPostRoute) {
                        binding.tvProfileUsername.text = "@$clean"
                        binding.tvProfileStatus.text = "Tap to open @$clean's profile"
                    }
                    binding.tvPillTitle.text = "@$clean"
                    binding.tvPillProfileHint.visibility = View.VISIBLE
                }
                if (pendingAutoRedirectToAuthor) {
                    pendingAutoRedirectToAuthor = false
                    openCreatorProfileUrl(clean)
                }
            }
        }

        @JavascriptInterface
        fun onRouteDetected(url: String?) {
            if (url.isNullOrBlank()) return
            val route = InstagramRouteParser.parse(url)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                handleRouteChanged(route)
            }
        }

        @JavascriptInterface
        fun onFocusedMediaDetected(url: String?, shortcode: String?, author: String?, isReel: Boolean) {
            val cleanCode = shortcode?.trim().orEmpty()
            val cleanUrl = url?.trim().orEmpty()
            val cleanAuthor = author?.removePrefix("@")?.trim()?.lowercase()?.ifBlank { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (isOnDedicatedPostRoute) return@runOnUiThread

                if (cleanCode.isBlank() || cleanUrl.isBlank()) {
                    currentPostUrl = null
                    currentPostShortcode = null
                    currentPostIsReel = false
                    dismissedShortcode = null
                    hideFloatingPill()
                    return@runOnUiThread
                }

                if (cleanCode == dismissedShortcode) {
                    return@runOnUiThread
                }

                dismissedShortcode = null
                currentPostUrl = cleanUrl
                currentPostShortcode = cleanCode
                currentPostIsReel = isReel
                if (cleanAuthor != null) {
                    currentPostAuthor = cleanAuthor
                }
                showFloatingPill(
                    media = DetectedRoute.MediaPost(
                        canonicalUrl = cleanUrl,
                        shortcode = cleanCode,
                        isReel = isReel,
                        authorHint = cleanAuthor ?: currentPostAuthor
                    )
                )
            }
        }

        @JavascriptInterface
        fun onNavBarStateChanged(visible: Boolean, heightCssPx: Int) {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (igNavBarVisible != visible || igNavBarHeightCssPx != heightCssPx) {
                    igNavBarVisible = visible
                    igNavBarHeightCssPx = heightCssPx
                    applyFloatingPillBottomMargin()
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
            lastInsetNavBottomPx = navBars.bottom

            binding.layoutTopHeader.setPadding(0, statusBars.top, 0, 0)
            applyFloatingPillBottomMargin()
            insets
        }
    }

    private fun applyFloatingPillBottomMargin() {
        val pill = binding.cardFloatingPostAction
        val params = pill.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams ?: return
        val density = resources.displayMetrics.density
        val clearanceDp = if (igNavBarVisible && igNavBarHeightCssPx > 0) {
            igNavBarHeightCssPx + 10
        } else if (ProfileSessionManager.isInstagramLoggedIn() && !isSignInMode) {
            64
        } else {
            16
        }
        params.bottomMargin = lastInsetNavBottomPx + (clearanceDp * density).toInt()
        pill.layoutParams = params
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
        applyFloatingPillBottomMargin()
        when (route) {
            is DetectedRoute.MediaPost -> {
                val previousCode = currentPostShortcode
                val previousAuthor = if (currentProfileUsername != null) currentProfileUsername else if (previousCode == route.shortcode) currentPostAuthor else null
                isOnDedicatedPostRoute = true
                dismissedShortcode = null
                currentPostUrl = route.canonicalUrl
                currentPostShortcode = route.shortcode
                currentPostIsReel = route.isReel
                val resolvedAuthor = route.authorHint ?: previousAuthor
                currentPostAuthor = resolvedAuthor
                if (!resolvedAuthor.isNullOrBlank()) {
                    binding.tvProfileUsername.text = "@$resolvedAuthor"
                    binding.tvProfileStatus.text = "Tap to open @$resolvedAuthor's profile"
                } else {
                    binding.tvProfileUsername.text = if (route.isReel) "Instagram Reel" else "Instagram Post"
                    binding.tvProfileStatus.text = "Viewing ${if (route.isReel) "Reel" else "Post"} • ${route.shortcode}"
                }
                showFloatingPill(route)
            }
            is DetectedRoute.Profile -> {
                isOnDedicatedPostRoute = false
                dismissedShortcode = null
                currentPostUrl = null
                currentPostShortcode = null
                currentPostIsReel = false
                currentProfileUsername = route.username
                currentPostAuthor = route.username
                binding.tvProfileUsername.text = "@${route.username}"
                val modeSuffix = if (ProfileSessionManager.isInstagramLoggedIn()) "Full Feed" else "Public Preview"
                binding.tvProfileStatus.text = "Profile Explorer • $modeSuffix"
                hideFloatingPill()
            }
            is DetectedRoute.Feed -> {
                isOnDedicatedPostRoute = false
                currentProfileUsername = null
                currentPostAuthor = null
                if (isSignInMode) {
                    binding.tvProfileUsername.text = "Instagram Sign In"
                    binding.tvProfileStatus.text = "Official Login • 100% Local Sandbox"
                    hideFloatingPill()
                } else {
                    binding.tvProfileUsername.text = if (route.isReelsTab) "Instagram Reels" else "Instagram"
                    binding.tvProfileStatus.text = if (route.isReelsTab) {
                        "Reels Feed • Scroll to save"
                    } else {
                        "Home Feed • Scroll to save posts"
                    }
                    // Keep any currently focused feed pill visible until scroll scanner updates it
                    if (currentPostUrl == null) hideFloatingPill()
                }
            }
            is DetectedRoute.Other -> {
                isOnDedicatedPostRoute = false
                currentPostUrl = null
                currentPostShortcode = null
                currentPostIsReel = false
                if (isSignInMode) {
                    binding.tvProfileUsername.text = "Instagram Sign In"
                    binding.tvProfileStatus.text = "Official Login • 100% Local Sandbox"
                }
                hideFloatingPill()
            }
        }
    }

    private fun showFloatingPill(media: DetectedRoute.MediaPost) {
        val effectiveAuthor = media.authorHint ?: currentPostAuthor
        if (!effectiveAuthor.isNullOrBlank()) {
            binding.tvPillTitle.text = "@$effectiveAuthor"
            binding.tvPillProfileHint.visibility = View.VISIBLE
        } else {
            val kind = if (media.isReel) "Reel" else "Post"
            binding.tvPillTitle.text = "$kind · ${media.shortcode}"
            binding.tvPillProfileHint.visibility = View.GONE
        }
        binding.ivPillMediaIcon.setImageResource(
            if (media.isReel) R.drawable.ic_video else R.drawable.ic_download_arrow
        )
        binding.btnPillSavePost.text = "Save"
        binding.btnPillSavePost.isEnabled = true

        if (binding.cardFloatingPostAction.visibility != View.VISIBLE) {
            binding.cardFloatingPostAction.alpha = 0f
            binding.cardFloatingPostAction.translationY = 64f
            binding.cardFloatingPostAction.visibility = View.VISIBLE
            binding.cardFloatingPostAction.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(200)
                .start()
        }
    }

    private fun hideFloatingPill() {
        if (binding.cardFloatingPostAction.visibility == View.VISIBLE) {
            binding.cardFloatingPostAction.animate()
                .alpha(0f)
                .translationY(64f)
                .setDuration(160)
                .withEndAction {
                    binding.cardFloatingPostAction.visibility = View.GONE
                }
                .start()
        }
    }

    private fun setupPillActions() {
        binding.btnPillDismiss.setOnClickListener {
            dismissedShortcode = currentPostShortcode
            hideFloatingPill()
        }

        binding.btnPillCreatorChip.setOnClickListener {
            val author = currentPostAuthor ?: return@setOnClickListener
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            openCreatorProfileUrl(author)
        }

        binding.btnPillSavePost.setOnClickListener {
            val url = currentPostUrl ?: return@setOnClickListener
            val targetCode = currentPostShortcode.orEmpty()
            val expectedReel = currentPostIsReel
            val capturedAuthor = currentPostAuthor
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            binding.btnPillSavePost.text = "Saving"
            binding.btnPillSavePost.isEnabled = false

            Toast.makeText(this, "Saving media in background...", Toast.LENGTH_SHORT).show()

            // Prioritize read-only passive WebView cache/fiber first (0 extra API calls, full HD MP4s + carousels!),
            // then fall back cleanly to anonymous CompositeInstagramResolver.
            InstaSaveApp.applicationScope.launch {
                try {
                    val coordinator = InstagramDownloadCoordinator(applicationContext)
                    var resolveResult = if (!isFinishing && !isDestroyed) {
                        extractRenderedDomFallback(url, targetCode, capturedAuthor, expectedReel)
                    } else null

                    if (resolveResult == null || !resolveResult.success || resolveResult.items.isEmpty()) {
                        resolveResult = withContext(Dispatchers.IO) {
                            coordinator.resolver.resolve(url, validateCdn = false)
                        }
                    }

                    if (resolveResult.success && resolveResult.items.isNotEmpty()) {
                        val downloadResult = coordinator.executeDirectDownload(
                            url = url,
                            selectedIndices = null,
                            preResolvedResult = resolveResult
                        )
                        withContext(Dispatchers.Main) {
                            if (isFinishing || isDestroyed) return@withContext
                            if (downloadResult.success) {
                                binding.btnPillSavePost.text = "Saved!"
                            } else {
                                binding.btnPillSavePost.text = "Retry"
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
                            if (isFinishing || isDestroyed) return@withContext
                            binding.btnPillSavePost.text = "Retry"
                            binding.btnPillSavePost.isEnabled = true
                            Toast.makeText(
                                this@ProfileExplorerActivity,
                                "Resolution failed: ${resolveResult.error?.message ?: "No playable media stream"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Save failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        binding.btnPillSavePost.text = "Retry"
                        binding.btnPillSavePost.isEnabled = true
                    }
                }
            }
        }

        binding.btnPillSelectMedia.setOnClickListener {
            val url = currentPostUrl ?: return@setOnClickListener
            val targetCode = currentPostShortcode.orEmpty()
            val expectedReel = currentPostIsReel
            val capturedAuthor = currentPostAuthor
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            lifecycleScope.launch {
                val cached = extractRenderedDomFallback(url, targetCode, capturedAuthor, expectedReel)
                if (!isFinishing && !isDestroyed) {
                    MediaSelectionActivity.start(
                        this@ProfileExplorerActivity,
                        url,
                        preloaded = cached?.takeIf { it.success && it.items.isNotEmpty() }
                    )
                }
            }
        }
    }

    private suspend fun extractRenderedDomFallback(
        url: String,
        shortcode: String,
        author: String?,
        isVideoExpected: Boolean
    ): com.instadown.assist.resolver.model.ResolveResult? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                if (cont.isActive) cont.resumeWith(Result.success(null))
                return@runOnUiThread
            }
            val script = ProfileSessionManager.buildVisibleDomMediaExtractorScript(shortcode, isVideoExpected)
            binding.webViewProfile.evaluateJavascript(script) { raw ->
                try {
                    val decoded = org.json.JSONTokener(raw ?: "\"\"").nextValue()?.toString().orEmpty()
                    val obj = org.json.JSONObject(decoded)
                    val resolvedAuthor = obj.optString("author").takeIf { it.isNotBlank() } ?: author
                    val arr = obj.optJSONArray("urls")
                    val items = mutableListOf<com.instadown.assist.resolver.model.ResolvedMedia>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val entry = arr.optJSONObject(i) ?: continue
                            val mediaUrl = entry.optString("url")
                            if (mediaUrl.startsWith("http")) {
                                val isVid = entry.optBoolean("isVideo")
                                val thumb = entry.optString("thumb").takeIf { it.startsWith("http") }
                                    ?: if (!isVid) mediaUrl else null
                                val w = entry.optInt("width", 0).takeIf { it > 0 }
                                val h = entry.optInt("height", 0).takeIf { it > 0 }
                                val mediaType = if (isVid) {
                                    com.instadown.assist.resolver.model.MediaType.VIDEO
                                } else {
                                    com.instadown.assist.resolver.model.MediaType.IMAGE
                                }
                                items.add(
                                    com.instadown.assist.resolver.model.ResolvedMedia(
                                        index = i,
                                        mediaType = mediaType,
                                        mediaUrl = mediaUrl,
                                        thumbnailUrl = thumb,
                                        width = w,
                                        height = h,
                                        durationMs = null,
                                        bitRate = null,
                                        qualityClassification = if (isVid) "HD Video" else "HD Photo",
                                        selectionReason = "WebView In-Session Stream",
                                        candidates = listOf(
                                            com.instadown.assist.resolver.model.MediaCandidate(
                                                url = mediaUrl,
                                                width = w,
                                                height = h,
                                                mimeType = if (isVid) "video/mp4" else "image/jpeg",
                                                isSelected = true
                                            )
                                        )
                                    )
                                )
                            }
                        }
                    }
                    val postType = when {
                        items.size > 1 -> com.instadown.assist.resolver.model.PostType.CAROUSEL
                        items.firstOrNull()?.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO ->
                            com.instadown.assist.resolver.model.PostType.REEL
                        else -> com.instadown.assist.resolver.model.PostType.PHOTO
                    }
                    val validForExpected = items.isNotEmpty() && (!isVideoExpected || items.any {
                        it.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO
                    })
                    val res = if (validForExpected) {
                        com.instadown.assist.resolver.model.ResolveResult(
                            success = true,
                            originalInputUrl = url,
                            canonicalUrl = url,
                            shortcode = shortcode.ifBlank { null },
                            sourcePathType = if (postType == com.instadown.assist.resolver.model.PostType.REEL) "REEL" else "POST",
                            postType = postType,
                            identityVerified = true,
                            identityVerificationMethod = "WebViewSessionCache",
                            items = items,
                            methodUsed = "WebViewSessionCache",
                            totalResolutionTimeMs = 10L,
                            authorUsername = resolvedAuthor
                        )
                    } else null
                    if (cont.isActive) cont.resumeWith(Result.success(res))
                } catch (_: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            }
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
