package io.github.sceneview.demo.demos

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression coverage for resetting Contact Shadow while its on-demand scene is parked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactShadowCameraRenderEffectTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reset_requests_a_frame_for_the_rebuilt_home_orbit() {
        val state = ContactShadowDemoState().apply { motionEnabled = false }
        val homeShot = contactShadowHomeShot(strip = false)
        var renderRequests = 0

        composeRule.setContent {
            RequestContactShadowCameraRenderOnHomeChange(
                cameraHomeGeneration = state.cameraHomeGeneration,
                homeShot = homeShot,
                requestRender = { renderRequests++ },
            )
        }
        composeRule.runOnIdle { assertEquals(1, renderRequests) }

        composeRule.runOnIdle { state.reset() }

        composeRule.runOnIdle { assertEquals(2, renderRequests) }
    }
}
