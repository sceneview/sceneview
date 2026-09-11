package io.github.sceneview.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The façade's double-tap zoom curve (#3608).
 *
 * Same contract as `:sceneview`'s: a double-tap halves the distance, a two-finger tap doubles it
 * back, and the move between the two is geometric so the apparent zoom speed stays constant
 * instead of crawling at the near end.
 *
 * Test names are plain identifiers, not backticked prose, because this is `commonTest`:
 * Kotlin/Native rejects a declaration name containing `,` outright ("Name contains illegal
 * characters"), while the JVM compiles it happily — so the mistake only surfaces in the
 * iOS-simulator job. The module's other common tests follow the same style.
 */
class DoubleTapZoomTest {

    @Test
    fun easing_starts_and_ends_where_it_should() {
        assertEquals(0f, easeInOutCubic(0f), 1e-6f)
        assertEquals(0.5f, easeInOutCubic(0.5f), 1e-6f)
        assertEquals(1f, easeInOutCubic(1f), 1e-6f)
    }

    @Test
    fun easing_is_clamped_and_monotonic() {
        assertEquals(0f, easeInOutCubic(-1f), 1e-6f)
        assertEquals(1f, easeInOutCubic(2f), 1e-6f)
        var previous = -1f
        for (i in 0..20) {
            val value = easeInOutCubic(i / 20f)
            assertTrue(value >= previous, "monotonic at $i")
            previous = value
        }
    }

    @Test
    fun animation_lands_exactly_on_its_end_points() {
        assertEquals(4f, animatedZoomDistance(4f, 2f, 0f), 1e-4f)
        assertEquals(2f, animatedZoomDistance(4f, 2f, 1f), 1e-4f)
    }

    /** Geometric, not linear: halfway through, the distance is the *geometric* mean. */
    @Test
    fun animation_interpolates_the_ratio_not_the_difference() {
        val mid = animatedZoomDistance(4f, 1f, 0.5f)
        assertEquals(2f, mid, 1e-3f)
        assertTrue(mid < 2.5f, "below the arithmetic mean")
    }

    @Test
    fun animation_never_leaves_the_interval_it_was_given() {
        var previous = 4f
        for (i in 1..20) {
            val value = animatedZoomDistance(4f, 2f, i / 20f)
            assertTrue(value in 2f..4f, "inside [2, 4] at $i")
            assertTrue(value <= previous + 1e-5f, "monotonic at $i")
            previous = value
        }
    }

    /** A double-tap then a two-finger tap must land back on the starting framing. */
    @Test
    fun zoom_in_and_zoom_out_steps_are_exact_inverses() {
        val start = 4f
        val zoomedIn = start * (1f / DOUBLE_TAP_ZOOM_FACTOR)
        assertEquals(start, zoomedIn * DOUBLE_TAP_ZOOM_FACTOR, 1e-4f)
    }

    @Test
    fun degenerate_endpoints_degrade_instead_of_producing_NaN() {
        assertEquals(2f, animatedZoomDistance(0f, 2f, 0.5f), 1e-4f)
        assertEquals(4f, animatedZoomDistance(4f, 0f, 0.5f), 1e-4f)
        assertTrue(animatedZoomDistance(4f, 2f, Float.NaN).isFinite())
    }
}
