package io.github.sceneview.demo.demos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The share sheet offers its photos switch only for a scan that holds photos. */
class RerunShareSheetPhotosTest {

    @Test
    fun `a scan without photos has no switch to offer`() {
        assertFalse(offersPhotosSwitch(0))
    }

    @Test
    fun `a scan with photos offers the switch`() {
        assertTrue(offersPhotosSwitch(1))
        assertTrue(offersPhotosSwitch(240))
    }
}
