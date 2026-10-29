package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.model.FloorScenes.Slope
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * A floor that really slopes keeps its slope through the finish ([PlaneFlattening.LEVEL_SNAP_RMS_M]).
 * Levelled, the 1° slope of [FloorScenes.Slope] — 7 cm over 4 m — would sink at its high end
 * under the foot of the furniture standing there, and where it meets the level strip it would
 * leave a step. Scanned with a phone's drift and noise ([FloorScenes.PHONE_SOLID]), the seam
 * between the slope and the strip seen or missed: a missed seam is what cuts the floor in two.
 */
class SlopedFloorTest {
    @Test
    fun theFloorKeepsItsHeightAllAlong() {
        for (seamSeen in listOf(true, false)) {
            val profile = profile(finished(seamSeen, foot = false))
            println("seam ${seen(seamSeen)}, off its true height (cm) every ${cm(BIN_M)} cm from x = ${Slope.LANDING_X0} m: " +
                profile.joinToString(" ") { cm(it.second) })
            for ((x, off) in profile) {
                assertTrue("seam ${seen(seamSeen)}: the floor at x = ${cm(x)} cm lies ${cm(off)} cm off its height", abs(off) <= MAX_OFF_M)
            }
        }
    }

    @Test
    fun noStepWhereTheSlopeMeetsTheLevelStrip() {
        for (seamSeen in listOf(true, false)) {
            val done = finished(seamSeen, foot = false)
            val gap = if (seamSeen) 0f else Slope.SEAM_GAP_M
            val strip = meanOff(done, -SEAM_REACH_M, 0f)
            val slope = meanOff(done, gap, gap + SEAM_REACH_M)
            println("seam ${seen(seamSeen)}: strip ${cm(strip)} cm, slope ${cm(slope)} cm off at the seam, step ${cm(slope - strip)} cm")
            assertTrue("seam ${seen(seamSeen)}: a ${cm(slope - strip)} cm step at the seam", abs(slope - strip) <= MAX_STEP_M)
        }
    }

    @Test
    fun theFloorAroundAFootStaysUnderIt() {
        val foot = Slope.FOOT
        for (seamSeen in listOf(true, false)) {
            val raw = scan(seamSeen, foot = true)
            val done = finished(seamSeen, foot = true)
            val faces = FaceGeometry(done.positions, done.indices)
            var moved = 0f
            for (t in 0 until faces.count) {
                if (faces.normals[t * 3 + 1] < LEVEL_UP) continue
                val x = faces.centroids[t * 3]
                val z = faces.centroids[t * 3 + 2]
                if (!near(foot, x, z, RING_M) || near(foot, x, z, 0f)) continue
                val off = faces.centroids[t * 3 + 1] - Slope.height(x)
                if (abs(off) <= ON_FLOOR_M) moved = maxOf(moved, abs(off))
            }
            // The floor's own cells within a cell of the foot that the finish opened.
            val height = Slope.height((foot.x0 + foot.x1) / 2f)
            val rawFloor = FloorArea.of(raw.positions, raw.indices, height)
            val opened = FloorArea.of(done.positions, done.indices, height).opened(rawFloor).count {
                val x = FloorArea.centreX(it)
                val z = FloorArea.centreZ(it)
                near(foot, x, z, FloorArea.COVER_CELL_M) && !near(foot, x, z, 0f)
            }
            println("seam ${seen(seamSeen)}: the floor round the foot moved up to ${cm(moved)} cm; $opened cells opened at its base")
            assertTrue("seam ${seen(seamSeen)}: the floor round the foot moved ${cm(moved)} cm", moved <= MAX_OFF_M)
            assertTrue("seam ${seen(seamSeen)}: $opened cells opened at the foot's base", opened == 0)
        }
    }

    /** Each [BIN_M] of the floor along x, its centre and how far its upward faces lie off the floor's height there, on average. */
    private fun profile(mesh: WorkMesh): List<Pair<Float, Float>> {
        val bins = ArrayList<Pair<Float, Float>>()
        var x = Slope.LANDING_X0 + EDGE_M
        while (x + BIN_M <= Slope.SLOPE_X1 - EDGE_M + 1e-4f) {
            bins += (x + BIN_M / 2f) to meanOff(mesh, x, x + BIN_M)
            x += BIN_M
        }
        return bins
    }

    /** How far the upward faces of [mesh] between [x0] and [x1] lie off the floor's height, area-weighted. */
    private fun meanOff(mesh: WorkMesh, x0: Float, x1: Float): Float {
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        var sum = 0.0
        var area = 0.0
        for (t in 0 until faces.count) {
            if (faces.normals[t * 3 + 1] < LEVEL_UP) continue
            val x = faces.centroids[t * 3]
            val z = faces.centroids[t * 3 + 2]
            if (x < x0 || x >= x1 || z < EDGE_M || z > Slope.Z1 - EDGE_M) continue
            val off = faces.centroids[t * 3 + 1] - Slope.height(x)
            if (abs(off) > ON_FLOOR_M) continue
            sum += off * faces.areas[t]
            area += faces.areas[t]
        }
        return if (area == 0.0) Float.NaN else (sum / area).toFloat()
    }

    /** Whether ([x], [z]) lies within [reach] of [box]'s footprint, seen from above. */
    private fun near(box: Box, x: Float, z: Float, reach: Float): Boolean =
        x >= box.x0 - reach && x <= box.x1 + reach && z >= box.z0 - reach && z <= box.z1 + reach

    private fun seen(seamSeen: Boolean) = if (seamSeen) "seen" else "missed"

    private fun cm(m: Float): String = String.format(Locale.ROOT, "%.1f", m * CM_PER_M)

    private companion object {
        /** A face this close to level is a face of the floor, not of the step down to the landing. */
        const val LEVEL_UP = 0.9f

        /** The floor faces lie within this of the floor's height; past it, a face is the foot's, not the floor's. */
        const val ON_FLOOR_M = 0.05f
        const val MAX_OFF_M = 0.01f
        const val MAX_STEP_M = 0.01f
        const val BIN_M = 0.2f
        const val SEAM_REACH_M = 0.2f
        const val RING_M = 0.12f

        /** Where the fused volume ends the floor ends too, rounded: kept out of the profile. */
        const val EDGE_M = 0.2f
        const val CM_PER_M = 100f

        private val scans = HashMap<Pair<Boolean, Boolean>, WorkMesh>()
        private val finishes = HashMap<Pair<Boolean, Boolean>, WorkMesh>()

        fun scan(seamSeen: Boolean, foot: Boolean): WorkMesh = scans.getOrPut(seamSeen to foot) {
            Slope.scan(seamSeen, if (foot) Slope.FOOT else null, FloorScenes.PHONE_SOLID)
        }

        fun finished(seamSeen: Boolean, foot: Boolean): WorkMesh = finishes.getOrPut(seamSeen to foot) {
            val raw = scan(seamSeen, foot)
            CleanGeometry.clean(WorkMesh(raw.positions.copyOf(), raw.colors.copyOf(), raw.indices.copyOf())).first
        }
    }
}
