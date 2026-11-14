package io.github.sceneview.demo.demos

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
    fun `outdoor explanation is visible before the session is configured`() {
        showNotice(supported = null, received = false)
        composeRule.onNodeWithText(OUTDOOR_EXPLANATION).assertIsDisplayed()
    }

    @Test
    fun `outdoor explanation is visible while semantics warms up`() {
        showNotice(supported = true, received = false)
        composeRule.onNodeWithText(OUTDOOR_EXPLANATION).assertIsDisplayed()
    }

    @Test
    fun `outdoor tag remains after semantic labels arrive`() {
        val received = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                SceneSemanticsOutdoorNotice(
                    semanticsSupported = true,
                    semanticsEverReceived = received.value,
                )
            }
        }
        composeRule.runOnIdle { received.value = true }
        composeRule.onNodeWithText("Outdoor model").assertIsDisplayed()
        composeRule.onNodeWithText(OUTDOOR_EXPLANATION).assertDoesNotExist()
    }

    @Test
    fun `unsupported devices leave room for the unsupported banner`() {
        showNotice(supported = false, received = false)
        composeRule.onNodeWithText(OUTDOOR_EXPLANATION).assertDoesNotExist()
        composeRule.onNodeWithText("Outdoor model").assertDoesNotExist()
    }

    private fun showNotice(supported: Boolean?, received: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                SceneSemanticsOutdoorNotice(supported, received)
            }
        }
    }

    private companion object {
        const val OUTDOOR_EXPLANATION =
            "Scene Semantics is trained on outdoor scenes — step outside for real labels."
    }
}
