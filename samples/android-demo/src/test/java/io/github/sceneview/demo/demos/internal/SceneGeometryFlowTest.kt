package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The order in which the Scene Geometry screens rule out why nothing is drawn. Each case
 * is a user in a different place who used to read the same sentence.
 */
class SceneGeometryFlowTest {

    private fun wait(
        locationEnabled: Boolean = true,
        earthTracking: Boolean = true,
        vps: VpsCoverage = VpsCoverage.Unknown,
        waitedLong: Boolean = false,
    ) = sceneGeometryWait(locationEnabled, earthTracking, vps, waitedLong)

    @Test
    fun `Location switched off outranks everything else`() {
        assertEquals(SceneGeometryWait.LocationOff, wait(locationEnabled = false))
        assertEquals(
            SceneGeometryWait.LocationOff,
            wait(locationEnabled = false, earthTracking = false, waitedLong = true),
        )
        // A stale coverage answer must not hide the switch either.
        assertEquals(
            SceneGeometryWait.LocationOff,
            wait(locationEnabled = false, vps = VpsCoverage.Unavailable, waitedLong = true),
        )
    }

    @Test
    fun `Earth without a position is localizing, then not localized`() {
        assertEquals(SceneGeometryWait.Localizing, wait(earthTracking = false))
        assertEquals(
            SceneGeometryWait.NotLocalized,
            wait(earthTracking = false, waitedLong = true),
        )
        // Coverage is only known for a position Earth no longer has.
        assertEquals(
            SceneGeometryWait.NotLocalized,
            wait(earthTracking = false, vps = VpsCoverage.Available, waitedLong = true),
        )
    }

    @Test
    fun `no Street View imagery is said at once, without the grace period`() {
        assertEquals(SceneGeometryWait.NoCoverage, wait(vps = VpsCoverage.Unavailable))
        assertEquals(
            SceneGeometryWait.NoCoverage,
            wait(vps = VpsCoverage.Unavailable, waitedLong = true),
        )
    }

    @Test
    fun `a localized Earth keeps looking through the grace period`() {
        for (vps in listOf(
            VpsCoverage.Unknown,
            VpsCoverage.Checking,
            VpsCoverage.Available,
            VpsCoverage.Error,
        )) {
            assertEquals("vps=$vps", SceneGeometryWait.Looking, wait(vps = vps))
        }
    }

    @Test
    fun `after the grace period a covered spot and an unchecked one read differently`() {
        val covered = wait(vps = VpsCoverage.Available, waitedLong = true)
        assertEquals(SceneGeometryWait.CoveredNothingInView, covered)
        for (vps in listOf(VpsCoverage.Unknown, VpsCoverage.Checking, VpsCoverage.Error)) {
            val unchecked = wait(vps = vps, waitedLong = true)
            assertEquals("vps=$vps", SceneGeometryWait.NothingFound, unchecked)
            assertNotEquals(covered, unchecked)
        }
    }

    @Test
    fun `the coverage cell is about a hundred metres wide`() {
        val here = vpsCell(48.85840, 2.29450)
        // A few metres of Earth jitter stays in the cell: no new coverage request.
        assertEquals(here, vpsCell(48.85843, 2.29452))
        // A block further is a new cell.
        assertNotEquals(here, vpsCell(48.86040, 2.29450))
        // Negative coordinates round, they do not truncate towards zero.
        assertEquals(-33_857 to -70_669, vpsCell(-33.8568, -70.6693))
    }
}
