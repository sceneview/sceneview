package io.github.sceneview.demo.demos

import io.github.sceneview.demo.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlacementQaTest {
    @Test
    fun `session failure is pinned only in a debug build under QA mode`() {
        val state = AR_START_FAILED_QA_STATE
        assertEquals("ar_start_failed", state)
        assertTrue(arStartFailedForced(state, overridesAllowed = true, debugBuild = true))
        assertFalse(arStartFailedForced(state, overridesAllowed = false, debugBuild = true))
        assertFalse(arStartFailedForced(state, overridesAllowed = true, debugBuild = false))
        assertFalse(arStartFailedForced(state, overridesAllowed = false, debugBuild = false))
        assertFalse(arStartFailedForced("motion_sensor_silent", overridesAllowed = true, debugBuild = true))
        assertFalse(arStartFailedForced(null, overridesAllowed = true, debugBuild = true))
    }

    @Test
    fun `this variant honours session failure by its own BuildConfig`() {
        assertEquals(BuildConfig.DEBUG, arStartFailedForced(AR_START_FAILED_QA_STATE, overridesAllowed = true))
    }
}
