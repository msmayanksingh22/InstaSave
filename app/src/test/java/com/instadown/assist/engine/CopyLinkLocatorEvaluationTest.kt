package com.instadown.assist.engine

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test

class CopyLinkLocatorEvaluationTest {

    @Test
    fun testCopyLinkEvaluationResultHoldsShareRowBounds() {
        val rowBounds = Rect(0, 1600, 1080, 1950)
        val eval = CopyLinkLocator.CopyLinkEvaluationResult(
            bestCandidate = null,
            runnerUpCandidate = null,
            allCandidates = emptyList(),
            scoreMargin = 0,
            isAmbiguous = false,
            isClippedAtEdge = false,
            dynamicRowRevealPlan = null,
            shareRowBounds = rowBounds
        )

        assertNull(eval.bestCandidate)
        assertFalse(eval.isClippedAtEdge)
        assertNotNull(eval.shareRowBounds)
        assertSame(rowBounds, eval.shareRowBounds)
    }

    @Test
    fun testRowRevealPlanCoordinatesCalculation() {
        val startX = 900f
        val endX = 180f
        val y = 1750f
        val plan = CopyLinkLocator.RowRevealPlan(
            startX = startX,
            startY = y,
            endX = endX,
            endY = y,
            rowResId = "com.instagram.android:id/reshare_row"
        )

        assertEquals(900f, plan.startX, 0.001f)
        assertEquals(180f, plan.endX, 0.001f)
        assertEquals(1750f, plan.startY, 0.001f)
        assertEquals(1750f, plan.endY, 0.001f)
        assertEquals("com.instagram.android:id/reshare_row", plan.rowResId)
        assertTrue(plan.startX > plan.endX) // Must be a leftward swipe
    }

    @Test
    fun testCandidateNodeSignalsScoringSafety() {
        // Minimum confidence and margin thresholds defined in locator
        assertTrue(CopyLinkLocator.MIN_CONFIDENCE_THRESHOLD >= 50)
        assertTrue(CopyLinkLocator.MIN_MARGIN_THRESHOLD >= 15)
    }
}
