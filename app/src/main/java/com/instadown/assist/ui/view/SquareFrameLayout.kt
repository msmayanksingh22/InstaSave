package com.instadown.assist.ui.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A FrameLayout that enforces a strict 1:1 square aspect ratio based on measured width.
 * Prevents stretching, letterboxing, and card height variance in gallery grids.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Enforce 1:1 square geometry
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
