package com.instadown.assist.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.R
import com.instadown.assist.util.AppUpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Beautiful, high-polish Welcome / Splash screen.
 * Displays the InstaSave gradient logo, bold typography, and micro-entry animations
 * for ~850 ms before smoothly transitioning to MainActivity.
 *
 * If started via external Share intent (ACTION_SEND), immediately forwards to
 * MainActivity with 0 ms delay so Instagram sharing is completely frictionless.
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val root = findViewById<View>(R.id.rootSplashLayout)
        val ivLogo = findViewById<ImageView>(R.id.ivSplashLogo)
        val tvTitle = findViewById<TextView>(R.id.tvSplashTitle)
        val tvTagline = findViewById<TextView>(R.id.tvSplashTagline)
        val layoutFooter = findViewById<LinearLayout>(R.id.layoutSplashFooter)
        val tvVersion = findViewById<TextView>(R.id.tvSplashVersion)

        // Safely set installed package version dynamically
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "v${pInfo.versionName} • Ultra-Fast Edition"
        } catch (_: Exception) {}

        // Edge-to-edge system insets handling
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            layoutFooter.setPadding(
                layoutFooter.paddingLeft,
                layoutFooter.paddingTop,
                layoutFooter.paddingRight,
                navBar.bottom + 24
            )
            insets
        }

        // Check if app was opened via external Share Intent (ACTION_SEND or ACTION_VIEW)
        val incomingAction = intent?.action
        if (Intent.ACTION_SEND == incomingAction || Intent.ACTION_VIEW == incomingAction) {
            forwardToMainImmediately(intent)
            return
        }

        // Setup sleek entry animations
        ivLogo.alpha = 0f
        ivLogo.scaleX = 0.65f
        ivLogo.scaleY = 0.65f

        tvTitle.alpha = 0f
        tvTitle.translationY = 30f

        tvTagline.alpha = 0f
        tvTagline.translationY = 20f

        layoutFooter.alpha = 0f

        // 1. Logo overshoot scale & fade-in
        ivLogo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator(1.3f))
            .start()

        // 2. Title & Tagline upward slide
        tvTitle.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(180)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator())
            .start()

        tvTagline.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(260)
            .setDuration(450)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 3. Footer fade-in
        layoutFooter.animate()
            .alpha(1f)
            .setStartDelay(320)
            .setDuration(450)
            .start()

        // Transition to MainActivity after ~850 ms
        lifecycleScope.launch {
            delay(850)
            if (!isFinishing && !isDestroyed) {
                val mainIntent = Intent(this@SplashActivity, MainActivity::class.java).apply {
                    intent?.extras?.let { putExtras(it) }
                }
                startActivity(mainIntent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }
    }

    private fun forwardToMainImmediately(srcIntent: Intent) {
        val forwardIntent = Intent(this, MainActivity::class.java).apply {
            action = srcIntent.action
            type = srcIntent.type
            data = srcIntent.data
            srcIntent.extras?.let { putExtras(it) }
            flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
        }
        startActivity(forwardIntent)
        finish()
    }
}
