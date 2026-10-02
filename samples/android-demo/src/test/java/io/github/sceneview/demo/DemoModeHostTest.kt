package io.github.sceneview.demo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch-tab routing of [DemoModeHost] (samples step 0): which mode a consolidated card
 * opens on, and whether the launch tab is left for the mode's own demo or consumed.
 */
class DemoModeHostTest {

    @After
    fun clearLaunch() {
        DemoSettings.initialTab = null
        DemoSettings.openRecordAction = false
    }

    @Test
    fun `no launch tab opens the first mode`() {
        assertEquals(0, initialHostMode(mapOf(0 to 0, 1 to 1), defaultModeReadsTab = false, modeCount = 2))
    }

    @Test
    fun `an owned launch tab opens its mode and is consumed`() {
        DemoSettings.initialTab = 1
        assertEquals(1, initialHostMode(mapOf(0 to 0, 1 to 1), defaultModeReadsTab = false, modeCount = 2))
        assertNull("the tab must not pre-select the next demo opened", DemoSettings.initialTab)
    }

    @Test
    fun `a tab the host does not own stays for the default mode's own demo`() {
        // ar-placement: launch tab 1 is the wall placement inside the Place mode.
        DemoSettings.initialTab = 1
        assertEquals(0, initialHostMode(mapOf(2 to 1), defaultModeReadsTab = true, modeCount = 2))
        assertEquals(1, DemoSettings.initialTab)
    }

    @Test
    fun `an owned tab of a host whose default mode reads tabs is still consumed`() {
        // ar-placement: launch tab 2 is the Free pose mode.
        DemoSettings.initialTab = 2
        assertEquals(1, initialHostMode(mapOf(2 to 1), defaultModeReadsTab = true, modeCount = 2))
        assertNull(DemoSettings.initialTab)
    }

    @Test
    fun `the placement-scene launch tab opens the One call mode`() {
        // ar-placement: [Place, Free pose, One call], launch tabs 2 and 3 owned by the host.
        DemoSettings.initialTab = 3
        assertEquals(2, initialHostMode(mapOf(2 to 1, 3 to 2), defaultModeReadsTab = true, modeCount = 3))
        assertNull(DemoSettings.initialTab)
    }

    @Test
    fun `a mode opened by a link is logged, the default mode is not`() {
        val modes = listOf(DemoMode("balls", 0), DemoMode("pendulum", 0))
        assertEquals("mode_pendulum", launchModeControl(modes, 1))
        assertNull("mode 0 is the card itself, already counted by sample_open", launchModeControl(modes, 0))
        assertNull(launchModeControl(modes, 5))
    }

    @Test
    fun `an unknown tab is dropped when the default mode reads none`() {
        DemoSettings.initialTab = 7
        assertEquals(0, initialHostMode(mapOf(0 to 0, 1 to 1), defaultModeReadsTab = false, modeCount = 2))
        assertNull(DemoSettings.initialTab)
    }

    @Test
    fun `a mode index out of range falls back to the first mode`() {
        DemoSettings.initialTab = 1
        assertEquals(0, initialHostMode(mapOf(1 to 5), defaultModeReadsTab = false, modeCount = 2))
    }

    @Test
    fun `the Record link flag is read once`() {
        DemoSettings.openRecordAction = true
        assertTrue(DemoSettings.consumeOpenRecordAction())
        assertFalse(DemoSettings.consumeOpenRecordAction())
    }

    @Test
    fun `the XR card is listed only on an XR device`() {
        val listed = listedDemos(ALL_DEMOS, xrDevice = false).map { it.id }
        assertFalse("ar-xr" in listed)
        assertEquals(ALL_DEMOS.size - XR_ONLY_DEMO_IDS.size, listed.size)
        assertTrue("ar-xr" in listedDemos(ALL_DEMOS, xrDevice = true).map { it.id })
    }
}
