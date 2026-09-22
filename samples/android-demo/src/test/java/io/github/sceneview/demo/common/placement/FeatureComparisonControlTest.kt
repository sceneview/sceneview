package io.github.sceneview.demo.common.placement

import org.junit.Assert.*
import org.junit.Test

class FeatureComparisonControlTest {
    @Test fun `unknown and unsupported devices cannot apply a comparison`() {
        val control = FeatureComparisonControl(true)
        var calls = 0
        assertFalse(control.toggle { calls++; true })
        control.confirmSupport(false)
        assertFalse(control.toggle { calls++; true })
        assertEquals(0, calls)
    }

    @Test fun `failed configuration retains the last applied effect state`() {
        val control = FeatureComparisonControl(false)
        control.confirmSupport(true)
        assertFalse(control.toggle { false })
        assertFalse(control.enabled)
        assertTrue(control.toggle { true })
        assertTrue(control.enabled)
        assertFalse(control.toggle { false })
        assertTrue(control.enabled)
    }

    @Test fun `each explicit toggle applies exactly once and reports the applied state`() {
        val control = FeatureComparisonControl(true)
        control.confirmSupport(true)
        val applied = mutableListOf<Boolean>()
        repeat(10) {
            assertTrue(control.toggle { enabled -> applied += enabled; true })
            assertEquals(applied.last(), control.enabled)
        }
        assertEquals(List(10) { it % 2 != 0 }, applied)
    }
}
