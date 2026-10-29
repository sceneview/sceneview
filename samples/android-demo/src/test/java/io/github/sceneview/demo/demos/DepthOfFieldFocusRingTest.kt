package io.github.sceneview.demo.demos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DepthOfFieldFocusRingTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `focus point leaves the accessibility tree after its fade`() {
        rule.mainClock.autoAdvance = false
        var ringAt by mutableStateOf<Offset?>(Offset(100f, 100f))
        rule.setContent {
            ringAt?.let { FocusRing(it, tap = 1, onFinished = { ringAt = null }) }
        }
        rule.mainClock.advanceTimeByFrame()

        val ring = rule.onNodeWithContentDescription("Focus point")
        ring.assertExists()
        rule.mainClock.advanceTimeBy(FOCUS_RING_HOLD_MS + FOCUS_RING_FADE_MS - 16L)
        ring.assertExists()
        rule.mainClock.advanceTimeBy(32L)
        ring.assertDoesNotExist()
    }
}
