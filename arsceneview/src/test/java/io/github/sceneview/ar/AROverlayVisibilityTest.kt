@file:OptIn(io.github.sceneview.ExperimentalSceneViewApi::class)

package io.github.sceneview.ar

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Contract of [LocalAROverlayVisibility]: every centred SDK card reports `true` while it is in
 * composition and a balancing `false` once it leaves, so a host counting the events reads zero
 * exactly when the scene is clear of cards (#4534).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-notnight-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AROverlayVisibilityTest {

    @get:Rule(order = 0)
    val registerActivity = object : ExternalResource() {
        override fun before() {
            val context = RuntimeEnvironment.getApplication()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context.packageName, ComponentActivity::class.java.name),
            )
        }
    }

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    /** Records every notification and the running count a host would keep. */
    private class Recorder {
        val events = mutableListOf<Boolean>()
        val count get() = events.count { it } - events.count { !it }
        val listener: (Boolean) -> Unit = { events += it }
    }

    @Test
    fun cameraInUseCard_reportsVisibleWhileShownAndReleasesWhenItLeaves() {
        val recorder = Recorder()
        var waiting by mutableStateOf(false)
        composeRule.setContent {
            CompositionLocalProvider(LocalAROverlayVisibility provides recorder.listener) {
                Box(Modifier.size(360.dp, 240.dp)) { ARCameraInUseOverlay(waiting) }
            }
        }
        composeRule.runOnIdle { assertEquals(emptyList<Boolean>(), recorder.events) }

        composeRule.runOnIdle { waiting = true }
        composeRule.onNodeWithTag(CAMERA_IN_USE_TAG).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(true), recorder.events) }

        composeRule.runOnIdle { waiting = false }
        composeRule.onNodeWithTag(CAMERA_IN_USE_TAG).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(true, false), recorder.events)
            assertEquals(0, recorder.count)
        }
    }

    @Test
    fun coexistingCards_eachHoldTheirOwnRegistration() {
        val recorder = Recorder()
        var firstShown by mutableStateOf(true)
        var secondShown by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalAROverlayVisibility provides recorder.listener) {
                Column {
                    Box(Modifier.size(360.dp, 240.dp)) { ARCameraInUseOverlay(firstShown) }
                    Box(Modifier.size(360.dp, 240.dp)) { ARCameraInUseOverlay(secondShown) }
                }
            }
        }
        composeRule.runOnIdle { assertEquals(2, recorder.count) }

        // A host assigning the last boolean would wrongly read "clear" here; the count does not.
        composeRule.runOnIdle { firstShown = false }
        composeRule.runOnIdle {
            assertEquals(false, recorder.events.last())
            assertEquals(1, recorder.count)
        }

        composeRule.runOnIdle { secondShown = false }
        composeRule.runOnIdle { assertEquals(0, recorder.count) }
    }

    @Test
    fun coachingCard_staysRegisteredThroughItsExitTransition_andTheFoundPillNeverReports() {
        val recorder = Recorder()
        var cue by mutableStateOf(ArGuidanceCue.INITIALIZING)
        composeRule.setContent {
            CompositionLocalProvider(LocalAROverlayVisibility provides recorder.listener) {
                Box(Modifier.fillMaxSize()) { ARCoachingOverlay(cue) }
            }
        }
        composeRule.runOnIdle { assertEquals(1, recorder.count) }

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { cue = ArGuidanceCue.NONE }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        // The card is still fading out over the scene: it must still hold back snackbars.
        composeRule.runOnIdle { assertEquals(1, recorder.count) }

        composeRule.mainClock.advanceTimeBy(EXIT_SETTLE_MS)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf(true, false), recorder.events)
            assertEquals(0, recorder.count)
        }

        composeRule.runOnIdle { cue = ArGuidanceCue.SURFACE_FOUND }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf(true, false), recorder.events) }
    }

    @Test
    fun replacingTheListener_releasesFromTheOldOneBeforeRegisteringWithTheNewOne() {
        val first = Recorder()
        val second = Recorder()
        var listener by mutableStateOf(first.listener)
        composeRule.setContent {
            CompositionLocalProvider(LocalAROverlayVisibility provides listener) {
                Box(Modifier.size(360.dp, 240.dp)) { ARCameraInUseOverlay(true) }
            }
        }
        composeRule.runOnIdle { assertEquals(listOf(true), first.events) }

        composeRule.runOnIdle { listener = second.listener }
        composeRule.runOnIdle {
            assertEquals(listOf(true, false), first.events)
            assertEquals(listOf(true), second.events)
        }
    }

    @Test
    fun withoutAListener_cardsStillShow() {
        composeRule.setContent {
            Box(Modifier.size(360.dp, 240.dp)) { ARCameraInUseOverlay(true) }
        }
        composeRule.onNodeWithTag(CAMERA_IN_USE_TAG).assertIsDisplayed()
    }

    @Test
    fun planeDiscoveryTips_reportOnlyWhileOpen_andReleaseOnDismissOrPhaseChange() {
        val recorder = Recorder()
        var phase by mutableStateOf(PlaneDiscoveryPhase.HELP_OFFERED)
        composeRule.setContent {
            CompositionLocalProvider(LocalAROverlayVisibility provides recorder.listener) {
                Box(Modifier.fillMaxSize()) {
                    PlaneDiscoveryGuideOverlay(phase = phase, handHint = {})
                }
            }
        }
        composeRule.runOnIdle { assertEquals(emptyList<Boolean>(), recorder.events) }

        val context = RuntimeEnvironment.getApplication()
        val help = context.getString(R.string.sceneview_plane_discovery_help)
        val dismiss = context.getString(R.string.sceneview_plane_discovery_got_it)
        composeRule.onNodeWithText(help).performClick()
        composeRule.onNodeWithText(dismiss).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf(true), recorder.events) }

        composeRule.onNodeWithText(dismiss).performClick()
        composeRule.runOnIdle { assertEquals(listOf(true, false), recorder.events) }

        composeRule.onNodeWithText(help).performClick()
        composeRule.runOnIdle { phase = PlaneDiscoveryPhase.DONE }
        composeRule.onNodeWithText(dismiss).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(true, false, true, false), recorder.events)
            assertEquals(0, recorder.count)
        }
    }

    private companion object {
        const val CAMERA_IN_USE_TAG = "sceneview-camera-in-use"

        /** Comfortably past the coaching card's exit tween. */
        const val EXIT_SETTLE_MS = 2_000L
    }
}
