package io.github.sceneview.demo

import androidx.compose.ui.test.junit4.createComposeRule
import io.github.sceneview.demo.fragments.ArRerunFragment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether a [DemoModeHost] hands a switch to the scaffold of the demo it shows — the one
 * thing that puts a mode pill on screen.
 *
 * Room Scan (`ar-rerun`) publishes none: its Session MP4 mode was a pill over every screen
 * of the demo, in front of people who came to scan a room. The mode itself has to stay
 * reachable by deep link, because the replay harness and the instrumented playback tests
 * open it that way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoModeHostSwitchTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun clearLaunch() {
        DemoSettings.initialTab = null
    }

    @Test
    fun `a host publishes its switch by default`() {
        var switch: DemoModeSwitch? = null
        composeRule.setContent {
            DemoModeHost(modes = ArRerunFragment.modes, tabToMode = ROOM_SCAN_TABS) {
                switch = LocalDemoModeSwitch.current
            }
        }
        composeRule.waitForIdle()
        assertNotNull("every other consolidated card keeps its mode pill", switch)
        assertEquals(ArRerunFragment.modes, switch?.modes)
    }

    @Test
    fun `a host that shows no switch publishes none and opens on its first mode`() {
        var switch: DemoModeSwitch? = SENTINEL
        var shown = -1
        composeRule.setContent {
            DemoModeHost(
                modes = ArRerunFragment.modes,
                tabToMode = ROOM_SCAN_TABS,
                showSwitch = false,
            ) { mode ->
                switch = LocalDemoModeSwitch.current
                shown = mode
            }
        }
        composeRule.waitForIdle()
        assertNull("no switch, so no pill over the scene", switch)
        assertEquals(0, shown)
    }

    @Test
    fun `the Session MP4 deep link still opens its mode with no switch on screen`() {
        assertOpensSessionMp4(rawId = "ar-rerun", tabParam = "session-mp4")
    }

    @Test
    fun `the retired ar-record-playback id still opens Session MP4 with no switch on screen`() {
        assertOpensSessionMp4(rawId = "ar-record-playback", tabParam = null)
    }

    private fun assertOpensSessionMp4(rawId: String, tabParam: String?) {
        DemoSettings.initialTab = DeepLinkRouter.resolveInitialTab(rawId, tabParam)
        var switch: DemoModeSwitch? = SENTINEL
        var shown = -1
        composeRule.setContent {
            DemoModeHost(
                modes = ArRerunFragment.modes,
                tabToMode = ROOM_SCAN_TABS,
                showSwitch = false,
            ) { mode ->
                switch = LocalDemoModeSwitch.current
                shown = mode
            }
        }
        composeRule.waitForIdle()
        assertEquals("$rawId must open Session MP4", SESSION_MP4, shown)
        assertNull(switch)
        assertNull("the launch tab is consumed", DemoSettings.initialTab)
    }

    private companion object {
        /** The launch tabs `ArRerunFragment` owns: one per mode. */
        val ROOM_SCAN_TABS = mapOf(0 to 0, 1 to 1)
        const val SESSION_MP4 = 1

        /** A non-null start value, so a `null` read is the host's answer and not the default. */
        val SENTINEL = DemoModeSwitch(modes = emptyList(), selected = 0, onSelect = {})
    }
}
