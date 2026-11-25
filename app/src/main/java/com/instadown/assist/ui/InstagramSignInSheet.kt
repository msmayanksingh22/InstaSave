package com.instadown.assist.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.instadown.assist.R
import com.instadown.assist.browser.ProfileSessionManager
import com.instadown.assist.databinding.SheetInstagramSigninBinding
import com.instadown.assist.overlay.InstaSavePreferences

/**
 * Dedicated optional sign-in and account safety status bottom sheet.
 * Educates the user that login happens strictly on Instagram's official web flow inside
 * Android's local CookieManager sandbox with zero external credential handling.
 */
class InstagramSignInSheet : BottomSheetDialogFragment() {

    private var _binding: SheetInstagramSigninBinding? = null
    private val binding get() = _binding!!

    private var pendingBrowseTarget: String? = null
    private var onContinueAnonymously: (() -> Unit)? = null
    private var onSessionChanged: (() -> Unit)? = null

    companion object {
        const val TAG = "InstagramSignInSheet"
        private const val ARG_PENDING_TARGET = "arg_pending_target"

        fun newInstance(
            pendingTarget: String? = null,
            onContinueAnonymously: (() -> Unit)? = null,
            onSessionChanged: (() -> Unit)? = null
        ): InstagramSignInSheet {
            return InstagramSignInSheet().apply {
                arguments = Bundle().apply {
                    if (!pendingTarget.isNullOrBlank()) {
                        putString(ARG_PENDING_TARGET, pendingTarget)
                    }
                }
                this.onContinueAnonymously = onContinueAnonymously
                this.onSessionChanged = onSessionChanged
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetInstagramSigninBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingBrowseTarget = arguments?.getString(ARG_PENDING_TARGET)
        renderState()
    }

    private fun renderState() {
        val ctx = requireContext()
        val isLoggedIn = ProfileSessionManager.isInstagramLoggedIn()
        val prefs = InstaSavePreferences(ctx)

        if (isLoggedIn) {
            binding.ivSheetShieldIcon.setImageResource(R.drawable.ic_shield_check)
            binding.ivSheetShieldIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.status_green))
            binding.tvSignInSheetTitle.text = "Signed In to Instagram"
            binding.tvSignInSheetStatus.text = "Protected Local WebView Session Active"
            binding.tvSignInSheetStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_green))

            binding.tvSafetyHeadline.text = "Active Session Safeguards"
            binding.tvSafetyBullet1.text = "• Seamless Flow: Scroll full creator profiles, carousels, and reels smoothly inside InstaSave."
            binding.tvSafetyBullet2.text = "• Consistent Identity: Running strictly on your device's real mobile User-Agent (no desktop/bot switching)."
            binding.tvSafetyBullet3.text = "• Zero Cookie Extraction: Media downloads remain isolated and anonymous."

            binding.btnSheetPrimaryAction.text = if (!pendingBrowseTarget.isNullOrBlank()) {
                "Open in Explorer"
            } else {
                "Browse Instagram Feed"
            }
            binding.btnSheetPrimaryAction.setOnClickListener {
                val target = pendingBrowseTarget?.ifBlank { null } ?: "https://www.instagram.com/"
                dismiss()
                ProfileExplorerActivity.start(ctx, target, preferCreatorProfile = true)
            }

            binding.btnSheetSecondaryAction.text = "Sign Out & Clear Cookies"
            binding.btnSheetSecondaryAction.setTextColor(ContextCompat.getColor(ctx, R.color.status_red))
            binding.btnSheetSecondaryAction.strokeColor = ContextCompat.getColorStateList(ctx, R.color.status_red)
            binding.btnSheetSecondaryAction.setOnClickListener {
                ProfileSessionManager.clearCookiesOnly {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        Toast.makeText(ctx, "Signed out and cleared cookies", Toast.LENGTH_SHORT).show()
                        onSessionChanged?.invoke()
                        dismiss()
                    }
                }
            }
        } else {
            binding.ivSheetShieldIcon.setImageResource(R.drawable.ic_shield_check)
            binding.ivSheetShieldIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
            binding.tvSignInSheetTitle.text = "Sign In to Instagram"
            binding.tvSignInSheetStatus.text = "Optional • 100% Local Sandbox"
            binding.tvSignInSheetStatus.setTextColor(ContextCompat.getColor(ctx, R.color.primary))

            binding.btnSheetPrimaryAction.text = if (!pendingBrowseTarget.isNullOrBlank()) {
                "Sign In & View Creator"
            } else {
                "Sign In Safely"
            }
            binding.btnSheetPrimaryAction.setOnClickListener {
                prefs.hasDismissedSignInPrompt = true
                val nextTarget = pendingBrowseTarget?.ifBlank { null }
                dismiss()
                ProfileExplorerActivity.startSignIn(ctx, nextTarget)
            }

            binding.btnSheetSecondaryAction.text = if (!pendingBrowseTarget.isNullOrBlank()) {
                "Continue Anonymously (12-Post Preview)"
            } else {
                "Not Now"
            }
            binding.btnSheetSecondaryAction.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            binding.btnSheetSecondaryAction.strokeColor = ContextCompat.getColorStateList(ctx, R.color.card_stroke)
            binding.btnSheetSecondaryAction.setOnClickListener {
                prefs.hasDismissedSignInPrompt = true
                dismiss()
                onContinueAnonymously?.invoke()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
