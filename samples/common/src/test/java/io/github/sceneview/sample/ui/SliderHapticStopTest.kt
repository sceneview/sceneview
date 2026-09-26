package io.github.sceneview.sample.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins when [LabeledSlider] ticks: once per stop crossed, or at the ends of a continuous track. */
class SliderHapticStopTest {

    @Test
    fun `stepped track reports the nearest stop`() {
        // 0..10 with 4 stops between the ends → 6 positions, 2.0 apart.
        assertEquals(0, sliderHapticStop(0f, 0f..10f, steps = 4))
        assertEquals(0, sliderHapticStop(0.9f, 0f..10f, steps = 4))
        assertEquals(1, sliderHapticStop(1.1f, 0f..10f, steps = 4))
        assertEquals(5, sliderHapticStop(10f, 0f..10f, steps = 4))
    }

    @Test
    fun `continuous track only changes at the ends`() {
        assertEquals(-1, sliderHapticStop(0f, 0f..2f, steps = 0))
        assertEquals(0, sliderHapticStop(0.01f, 0f..2f, steps = 0))
        assertEquals(0, sliderHapticStop(1.99f, 0f..2f, steps = 0))
        assertEquals(1, sliderHapticStop(2f, 0f..2f, steps = 0))
    }

    @Test
    fun `degenerate range never ticks`() {
        assertEquals(0, sliderHapticStop(1f, 1f..1f, steps = 0))
    }
}
