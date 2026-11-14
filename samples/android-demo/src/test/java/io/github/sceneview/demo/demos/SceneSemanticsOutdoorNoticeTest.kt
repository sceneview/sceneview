package io.github.sceneview.demo.demos

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The outdoor-only explanation must not depend on ARCore leaving an indoor scene unclassified. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SceneSemanticsOutdoorNoticeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `outdoor warning is visible before the session is configured`() {
        showNotice(supported = null, received = false)
        composeRule.onNodeWithText(OUTDOOR_WARNING).assertIsDisplayed()
        composeRule.onNodeWithText(WARMING_UP).assertDoesNotExist()
        composeRule.onNodeWithText(UNCLASSIFIED).assertDoesNotExist()
    }

    @Test
    fun `warming up and outdoor warning are visible before the first semantic frame`() {
        showNotice(supported = true, received = false)
        composeRule.onNodeWithText(OUTDOOR_WARNING).assertIsDisplayed()
        composeRule.onNodeWithText(WARMING_UP).assertIsDisplayed()
        composeRule.onNodeWithText(UNCLASSIFIED).assertDoesNotExist()
    }

    @Test
    fun `explicit outdoor warning persists after the first semantic frame and five seconds`() {
        val received = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                SceneSemanticsOutdoorNotice(
                    semanticsSupported = true,
                    semanticsEverReceived = received.value,
                    sceneUnclassified = false,
                )
            }
        }
        composeRule.onNodeWithText(WARMING_UP).assertIsDisplayed()
        composeRule.runOnIdle { received.value = true }
        composeRule.onNodeWithText(OUTDOOR_WARNING).assertIsDisplayed()
        composeRule.onNodeWithText(WARMING_UP).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.onNodeWithText(OUTDOOR_WARNING).assertIsDisplayed()
    }

    @Test
    fun `unclassified scene includes actionable guidance alongside one outdoor warning`() {
        showNotice(supported = true, received = true, unclassified = true)
        composeRule.onAllNodesWithText(OUTDOOR_WARNING).assertCountEquals(1)
        composeRule.onNodeWithText(UNCLASSIFIED).assertIsDisplayed()
        composeRule.onNodeWithText(WARMING_UP).assertDoesNotExist()
    }

    @Test
    fun `unclassified guidance clears when classified labels arrive without hiding the warning`() {
        val unclassified = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                SceneSemanticsOutdoorNotice(
                    semanticsSupported = true,
                    semanticsEverReceived = true,
                    sceneUnclassified = unclassified.value,
                )
            }
        }
        composeRule.onNodeWithText(UNCLASSIFIED).assertIsDisplayed()
        composeRule.runOnIdle { unclassified.value = false }
        composeRule.onNodeWithText(UNCLASSIFIED).assertDoesNotExist()
        composeRule.onNodeWithText(OUTDOOR_WARNING).assertIsDisplayed()
    }

    @Test
    fun `warming up takes precedence over a stale unclassified flag`() {
        showNotice(supported = true, received = false, unclassified = true)
        composeRule.onNodeWithText(WARMING_UP).assertIsDisplayed()
        composeRule.onNodeWithText(UNCLASSIFIED).assertDoesNotExist()
    }

    @Test
    fun `unsupported devices leave room for the unsupported banner`() {
        showNotice(supported = false, received = false)
        assertNoticeAbsent()
    }

    @Test
    fun `unsupported devices hide the notice even after a previously unclassified frame`() {
        showNotice(supported = false, received = true, unclassified = true)
        assertNoticeAbsent()
    }

    private fun assertNoticeAbsent() {
        composeRule.onNodeWithText(OUTDOOR_WARNING).assertDoesNotExist()
        composeRule.onNodeWithText(WARMING_UP).assertDoesNotExist()
        composeRule.onNodeWithText(UNCLASSIFIED).assertDoesNotExist()
    }

    private fun showNotice(supported: Boolean?, received: Boolean, unclassified: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                SceneSemanticsOutdoorNotice(supported, received, unclassified)
            }
        }
    }

    private companion object {
        const val OUTDOOR_WARNING = "Outdoor model — labels unreliable indoors"
        const val WARMING_UP = "Warming up Scene Semantics…"
        const val UNCLASSIFIED = "Nothing classified yet — try a street, park or back yard."
    }
}
