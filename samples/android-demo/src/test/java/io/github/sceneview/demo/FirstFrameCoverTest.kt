package io.github.sceneview.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [FirstFrameCover] when a demo waits for a frame a second time (#4492): the dollhouse opens its
 * 3D view, or another recorded room, long after the screen itself opened. The cover's timeout was
 * counted once from the screen's opening, so that second wait had no cover: the "Still loading…"
 * card flashed at once and the new view's first frames were shown as they came.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstFrameCoverTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rendered = mutableStateOf(false)

    private fun cover() = composeRule.onNodeWithTag(DemoScaffoldTestTags.FIRST_FRAME_SCRIM)
    private fun stalled() = composeRule.onNodeWithTag(DemoScaffoldTestTags.LOADING_STALLED)

    /** The scene's own signal; the clock is driven by hand, so the write is announced by hand. */
    private fun render(value: Boolean) {
        rendered.value = value
        Snapshot.sendApplyNotifications()
    }

    private fun show() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) { FirstFrameCover(rendered, loadingLabel = null, onRetry = null) }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a second wait long after the screen opened is covered at once, with no stalled card`() {
        show()
        cover().assertIsDisplayed()
        render(true)
        // The user looks at the first view for longer than the timeout.
        composeRule.mainClock.advanceTimeBy(FIRST_FRAME_SCRIM_TIMEOUT_MS + 3_000)
        cover().assertDoesNotExist()

        render(false)
        // One frame: the view that has not drawn yet is composed in this same frame.
        composeRule.mainClock.advanceTimeByFrame()
        cover().assertIsDisplayed()
        stalled().assertDoesNotExist()
    }

    @Test
    fun `the second wait has a timeout of its own`() {
        show()
        render(true)
        composeRule.mainClock.advanceTimeBy(FIRST_FRAME_SCRIM_TIMEOUT_MS + 3_000)
        render(false)
        composeRule.mainClock.advanceTimeBy(FIRST_FRAME_SCRIM_TIMEOUT_MS - 1_000)
        stalled().assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(2_000)
        stalled().assertIsDisplayed()
    }

    @Test
    fun `a wait that never ends still says so, until the frame lands`() {
        show()
        composeRule.mainClock.advanceTimeBy(FIRST_FRAME_SCRIM_TIMEOUT_MS + 1_000)
        stalled().assertIsDisplayed()
        render(true)
        composeRule.mainClock.advanceTimeBy(1_000)
        stalled().assertDoesNotExist()
        cover().assertDoesNotExist()
    }
}
