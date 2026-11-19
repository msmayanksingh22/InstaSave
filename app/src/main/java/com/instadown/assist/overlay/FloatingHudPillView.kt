package com.instadown.assist.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.instadown.assist.R

/**
 * Dynamic Island-Style Floating Progress HUD Pill.
 * Floats sleekly under the camera cutout, rendering real-time progress
 * without focus theft or full-screen interruptions.
 */
class FloatingHudPillView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val ivStatusIcon: ImageView
    private val pbIndeterminate: ProgressBar
    private val tvTitle: TextView
    private val tvSubtitle: TextView
    private val tvTrailingPercent: TextView
    private val pbDeterminateBar: ProgressBar
    private val pillContainer: LinearLayout

    private var currentAnim: AnimatorSet? = null
    private var isExpanded = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        // Density scaling helper
        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).toInt()

        clipChildren = false
        clipToPadding = false

        // Main pill body
        pillContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_floating_hud_pill)
            setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
            minimumWidth = dp(180f)
            elevation = dp(8f).toFloat()
        }

        val pillParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(48f)).apply {
            gravity = Gravity.CENTER
        }
        addView(pillContainer, pillParams)

        // Left Status Icon
        ivStatusIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22f), dp(22f)).apply {
                marginEnd = dp(10f)
            }
            setColorFilter(Color.WHITE)
        }
        pillContainer.addView(ivStatusIcon)

        // Indeterminate Spinner (for Resolving state)
        pbIndeterminate = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LinearLayout.LayoutParams(dp(20f), dp(20f)).apply {
                marginEnd = dp(10f)
            }
            indeterminateTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.insta_pink))
            visibility = View.GONE
        }
        pillContainer.addView(pbIndeterminate)

        // Center Text Container
        val textLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f)
        }

        tvTitle = TextView(context).apply {
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textLayout.addView(tvTitle)

        tvSubtitle = TextView(context).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor("#9CA3AF"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textLayout.addView(tvSubtitle)

        pillContainer.addView(textLayout)

        // Right Percent Text (for Downloading state)
        tvTrailingPercent = TextView(context).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.insta_pink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8f), 0, 0, 0)
            visibility = View.GONE
        }
        pillContainer.addView(tvTrailingPercent)

        // Bottom Sleek Progress Line
        pbDeterminateBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(2.5f)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                setMargins(dp(16f), 0, dp(16f), dp(2f))
            }
            max = 100
            progressTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.insta_pink))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#26FFFFFF"))
            visibility = View.GONE
        }
        addView(pbDeterminateBar)

        alpha = 0f
        visibility = View.GONE
    }

    /**
     * Binds mathematically bounded resilient state and triggers fluid micro-animations.
     */
    fun bindResilientState(state: ResilientHudState) {
        mainHandler.post {
            when (state) {
                is ResilientHudState.Hidden -> {
                    hidePill()
                }
                is ResilientHudState.LinkDetected -> {
                    showPill()
                    pbIndeterminate.visibility = View.GONE
                    ivStatusIcon.visibility = View.VISIBLE
                    ivStatusIcon.setImageResource(R.drawable.ic_link)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.insta_pink))
                    tvTitle.text = "Instagram Post Detected"
                    tvSubtitle.text = state.label.ifBlank { "Analyzing link..." }
                    tvTrailingPercent.visibility = View.GONE
                    pbDeterminateBar.visibility = View.GONE
                }
                is ResilientHudState.Resolving -> {
                    showPill()
                    ivStatusIcon.visibility = View.GONE
                    pbIndeterminate.visibility = View.VISIBLE
                    tvTitle.text = "Resolving Media..."
                    tvSubtitle.text = state.stageLabel.ifBlank { "Extracting high-res streams" }
                    tvTrailingPercent.visibility = View.GONE
                    pbDeterminateBar.visibility = View.GONE
                }
                is ResilientHudState.Downloading -> {
                    showPill()
                    pbIndeterminate.visibility = View.GONE
                    ivStatusIcon.visibility = View.VISIBLE
                    ivStatusIcon.setImageResource(R.drawable.ic_download_arrow)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.insta_pink))

                    tvTitle.text = if (state.totalCount > 1) {
                        "Saving Item ${state.currentIndex}/${state.totalCount}"
                    } else {
                        "Downloading Media"
                    }
                    tvSubtitle.text = if (state.totalBytes > 0) {
                        "${state.percent}% (${state.downloadedBytes / 1024} KB / ${state.totalBytes / 1024} KB)"
                    } else {
                        "Downloading... ${state.percent}%"
                    }
                    tvTrailingPercent.visibility = View.VISIBLE
                    tvTrailingPercent.text = "${state.percent}%"

                    pbDeterminateBar.visibility = View.VISIBLE
                    pbDeterminateBar.progress = state.percent
                }
                is ResilientHudState.Success -> {
                    showPill()
                    pbIndeterminate.visibility = View.GONE
                    ivStatusIcon.visibility = View.VISIBLE
                    ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_green))

                    tvTitle.text = state.message
                    tvSubtitle.text = if (state.totalItems > 1) "${state.totalItems} items saved to Gallery" else "Ready in Gallery"
                    tvTrailingPercent.visibility = View.GONE
                    pbDeterminateBar.visibility = View.GONE
                }
                is ResilientHudState.Error -> {
                    showPill()
                    pbIndeterminate.visibility = View.GONE
                    ivStatusIcon.visibility = View.VISIBLE
                    ivStatusIcon.setImageResource(R.drawable.ic_close)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_red))

                    tvTitle.text = "Download Failed"
                    tvSubtitle.text = state.message.take(35)
                    tvTrailingPercent.visibility = View.GONE
                    pbDeterminateBar.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Legacy bind state overload bridging to FloatingHudState.
     */
    fun bindState(state: FloatingHudState) {
        mainHandler.post {
            when (state) {
                is FloatingHudState.Idle -> {
                    hidePill()
                }
                is FloatingHudState.LinkDetected -> {
                    showPill()
                    pbIndeterminate.visibility = View.GONE
                    ivStatusIcon.visibility = View.VISIBLE
                    ivStatusIcon.setImageResource(R.drawable.ic_link)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.insta_pink))
                    tvTitle.text = "Instagram Post Detected"
                    tvSubtitle.text = state.shortcode.ifBlank { "Analyzing link..." }
                    tvTrailingPercent.visibility = View.GONE
                    pbDeterminateBar.visibility = View.GONE
                }
                is FloatingHudState.Resolving -> {
                    showPill()
                    ivStatusIcon.visibility = View.GONE
                    pbIndeterminate.visibility = View.VISIBLE
                    tvTitle.text = "Resolving Media..."
                    tvSubtitle.text = state.shortcode.ifBlank { "Extracting high-res streams" }
                    tvTrailingPercent.visibility = View.GONE
                    pbDeterminateBar.visibility = View.GONE
                }
                is FloatingHudState.Downloading -> {
                    showPill()
                    pbIndeterminate.visibility = View.GONE
                    ivStatusIcon.visibility = View.VISIBLE
                    ivStatusIcon.setImageResource(R.drawable.ic_download_arrow)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.insta_pink))

                    tvTitle.text = if (state.totalItems > 1) {
                        "Saving Item ${state.currentItem}/${state.totalItems}"
                    } else {
                        "Downloading Media"
                    }
                    tvSubtitle.text = state.message
                    tvTrailingPercent.visibility = View.VISIBLE
                    tvTrailingPercent.text = "${state.percent}%"

                    pbDeterminateBar.visibility = View.VISIBLE
                    pbDeterminateBar.progress = state.percent
                }
                is FloatingHudState.Success -> {
                    showPill()
                    pbIndeterminate.visibility = View.GONE
                    ivStatusIcon.visibility = View.VISIBLE
                    ivStatusIcon.setImageResource(R.drawable.ic_check_circle)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_green))

                    tvTitle.text = state.message
                    tvSubtitle.text = if (state.totalItems > 1) "${state.totalItems} items saved to Gallery" else "Ready in Gallery"
                    tvTrailingPercent.visibility = View.GONE
                    pbDeterminateBar.visibility = View.GONE
                }
                is FloatingHudState.Error -> {
                    showPill()
                    pbIndeterminate.visibility = View.GONE
                    ivStatusIcon.visibility = View.VISIBLE
                    ivStatusIcon.setImageResource(R.drawable.ic_close)
                    ivStatusIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.status_red))

                    tvTitle.text = "Download Failed"
                    tvSubtitle.text = state.message.take(35)
                    tvTrailingPercent.visibility = View.GONE
                    pbDeterminateBar.visibility = View.GONE
                }
            }
        }
    }

    private fun showPill() {
        if (visibility == View.VISIBLE && alpha > 0.8f) return
        currentAnim?.cancel()

        visibility = View.VISIBLE
        translationY = -60f
        scaleX = 0.85f
        scaleY = 0.85f
        alpha = 0f

        val transY = ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, -60f, 0f)
        val scaleXAnim = ObjectAnimator.ofFloat(this, View.SCALE_X, 0.85f, 1f)
        val scaleYAnim = ObjectAnimator.ofFloat(this, View.SCALE_Y, 0.85f, 1f)
        val alphaAnim = ObjectAnimator.ofFloat(this, View.ALPHA, 0f, 1f)

        currentAnim = AnimatorSet().apply {
            playTogether(transY, scaleXAnim, scaleYAnim, alphaAnim)
            duration = 320
            interpolator = OvershootInterpolator(1.2f)
            start()
        }
        isExpanded = true
    }

    private fun hidePill() {
        if (visibility != View.VISIBLE || alpha <= 0.05f) {
            visibility = View.GONE
            return
        }
        currentAnim?.cancel()

        val transY = ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0f, -60f)
        val scaleXAnim = ObjectAnimator.ofFloat(this, View.SCALE_X, 1f, 0.85f)
        val scaleYAnim = ObjectAnimator.ofFloat(this, View.SCALE_Y, 1f, 0.85f)
        val alphaAnim = ObjectAnimator.ofFloat(this, View.ALPHA, alpha, 0f)

        currentAnim = AnimatorSet().apply {
            playTogether(transY, scaleXAnim, scaleYAnim, alphaAnim)
            duration = 240
            interpolator = DecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visibility = View.GONE
                    isExpanded = false
                }
            })
            start()
        }
    }
}
