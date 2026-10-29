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

    private val room = listOf(-3f to -2f, 3f to -2f, 3f to 2f, -3f to 2f)
    private val phone = DebugPose(1f, 1.4f, 0f)

    @Test
    fun whileScanningTheMapLooksOverThePhonesShoulder() {
        // Heading 0 looks down -Z: the map aims a little ahead of the phone, from behind it.
        val pose = ScanLiveMapFraming.poseAt(phone, headingDegrees = 0f, extent = room)
        assertEquals(0f, pose.azimuthDegrees, 1e-3f)
        assertEquals(ScanLiveMapFraming.ELEVATION_DEGREES, pose.elevationDegrees, 0f)
        assertEquals(1f, pose.target.x, 1e-3f)
        assertEquals(-ScanLiveMapFraming.AHEAD_M, pose.target.z, 1e-3f)
        assertTrue(pose.distance in ScanLiveMapFraming.MIN_DISTANCE_M..ScanLiveMapFraming.MAX_DISTANCE_M)
        // A three-quarter view, lower than the overview's.
        assertTrue(ScanLiveMapFraming.ELEVATION_DEGREES < ScanLiveMapFraming.OVERVIEW_ELEVATION_DEGREES)
    }

    @Test
    fun pausedTheMapStepsBackOverTheWholeRoom() {
        val pose = ScanLiveMapFraming.overview(room, headingDegrees = 30f, phone = phone)
        assertEquals(0f, pose.target.x, 1e-3f)
        assertEquals(0f, pose.target.z, 1e-3f)
        assertEquals(30f, pose.azimuthDegrees, 1e-3f)
        assertEquals(ScanLiveMapFraming.OVERVIEW_ELEVATION_DEGREES, pose.elevationDegrees, 0f)
        val follow = ScanLiveMapFraming.poseAt(phone, 30f, room)
        assertTrue("${pose.distance} > ${follow.distance}", pose.distance > follow.distance)
    }

    @Test
    fun anOverviewWithNoFloorYetStaysOnThePhone() {
        val pose = ScanLiveMapFraming.overview(emptyList(), headingDegrees = 0f, phone = phone)
        assertEquals(1f, pose.target.x, 1e-3f)
        assertEquals(ScanLiveMapFraming.OVERVIEW_MIN_DISTANCE_M, pose.distance, 1e-3f)
    }

    @Test
    fun nearAMissedZoneTheMapTurnsTowardIt() {
        // A hole 2 m to the phone's right (+X) while it looks down -Z.
        val zone = listOf(3f to 0f)
        val pose = ScanLiveMapFraming.poseAt(phone, headingDegrees = 0f, extent = room, missed = zone)
        // Its bearing is -90°: the map turns part of the way, never more than the cap.
        val turn = (-90f * ScanLiveMapFraming.PIVOT_SHARE).coerceAtLeast(-ScanLiveMapFraming.MAX_PIVOT_DEGREES)
        assertEquals(turn, pose.azimuthDegrees, 1e-3f)
        assertTrue("${pose.target.x} toward the hole", pose.target.x > 1.5f)
    }

    @Test
    fun aFarMissedZoneLeavesTheFollowAlone() {
        val far = listOf(-8f to 0f)
        val pose = ScanLiveMapFraming.poseAt(phone, headingDegrees = 0f, extent = room, missed = far)
        assertEquals(0f, pose.azimuthDegrees, 1e-3f)
        assertEquals(1f, pose.target.x, 1e-3f)
    }

    @Test
    fun theNearestMissedZoneWins() {
        val zones = listOf(3f to 0f, 1f to -1.5f)
        assertEquals(1f to -1.5f, ScanLiveMapFraming.nearestMissed(phone.x, phone.z, zones))
        assertEquals(null, ScanLiveMapFraming.nearestMissed(phone.x, phone.z, listOf(9f to 9f)))
    }

    @Test
    fun missedZonesAreTheHolesLargeEnoughToGoBackTo() {
        // A 2 × 2 m room of 25 cm cells, all covered but one lone cell and a 2 × 2 block.
        val covered = ArrayList<Long>()
        for (c in 0 until 8) for (r in 0 until 8) {
            val lone = c == 1 && r == 1
            val block = c in 5..6 && r in 5..6
            if (!lone && !block) covered += CoverageGrid.key(c, r)
        }
        val ground = requireNotNull(LiveCoverageGround.of(covered.toLongArray(), 0.25f, room2, 0f))
        val zones = ScanLiveMapFraming.zonesOf(ground)
        assertEquals(1, zones.size)
        assertEquals(1.5f, zones[0].first, 1e-3f)
        assertEquals(1.5f, zones[0].second, 1e-3f)
    }

    @Test
    fun theControlsBelowAreLeftClearToo() {
        // A 1,000 px map, the card over its top 200 px, the controls over its last 220.
        val band = ScanLiveMapFraming.band(clearTop = 200f, viewHeight = 1000f, clearBottom = 780f)
        // The phone 62 % down the 580 px between: at 560 px, 0.06 below the middle.
        assertEquals(-0.06f, band.lift, 0.011f)
        assertEquals(0.58f * OrbitBand.MEASURED_FILL, band.halfHeight, 0.011f)
    }

    private val room2 = listOf(0f to 0f, 2f to 0f, 2f to 2f, 0f to 2f)
}
