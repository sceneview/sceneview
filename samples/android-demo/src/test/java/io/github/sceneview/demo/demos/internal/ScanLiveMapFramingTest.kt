package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live 3D map's framing: the whole floor found so far round the phone, the phone low in it. */
class ScanLiveMapFramingTest {

    @Test
    fun noFloorYetStandsAtTheNearestDistance() {
        assertEquals(ScanLiveMapFraming.MIN_DISTANCE_M, ScanLiveMapFraming.distanceFor(emptyList(), 0f, 0f), 0f)
    }

    @Test
    fun aLargerRoomStandsFurther() {
        val small = listOf(-2f to -2f, 2f to -2f, 2f to 2f, -2f to 2f)
        val large = listOf(-5f to -5f, 5f to -5f, 5f to 5f, -5f to 5f)
        val near = ScanLiveMapFraming.distanceFor(small, 0f, 0f)
        val far = ScanLiveMapFraming.distanceFor(large, 0f, 0f)
        assertTrue("$far > $near", far > near)
        // The farthest corner, sqrt(50) m away, times the per-reach factor.
        assertEquals(kotlin.math.sqrt(50f) * ScanLiveMapFraming.DISTANCE_PER_REACH, far, 1e-3f)
    }

    @Test
    fun aHallIsCapped() {
        val hall = listOf(-50f to 0f, 50f to 0f, 0f to 50f)
        assertEquals(ScanLiveMapFraming.MAX_DISTANCE_M, ScanLiveMapFraming.distanceFor(hall, 0f, 0f), 0f)
    }

    @Test
    fun thePhoneSitsLowInTheClearPart() {
        // A 1,000 px map whose top 300 px are under the scan's status card.
        val band = ScanLiveMapFraming.band(clearTop = 300f, viewHeight = 1000f)
        // The phone 62 % down the 700 px left: at 734 px, 0.23 below the middle.
        assertEquals(-0.23f, band.lift, 0.011f)
        assertEquals(0.64f, band.halfHeight, 0.011f)
    }

    @Test
    fun theChromeNeverTakesTheWholeMap() {
        val band = ScanLiveMapFraming.band(clearTop = 990f, viewHeight = 1000f)
        assertTrue(band.halfHeight > 0.3f)
    }

    @Test
    fun anUnmeasuredMapKeepsTheFollowBand() {
        assertEquals(ScanFollow.BAND, ScanLiveMapFraming.band(clearTop = 0f, viewHeight = 0f))
    }
}
