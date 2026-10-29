package io.github.sceneview.demo.demos.internal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * What [FloorArea] reads off a finished floor, on scenes built to fool it ([FloorScenes]).
 *
 * The measure that counts is the floor the finish leaves open of the raw one
 * ([FloorArea.openedOn]). The area ratio to the raw floor is only a check that the two agree,
 * and a poor one: the finish moves the floor, so what lies near the band's edge comes into it
 * or goes out of it. A slope rising out of a ±5 cm band came into it once levelled — a quarter more
 * floor for none added; a level lying on the band's edge counts in part raw, where its noise straddles
 * the edge, and whole once flat; and every hole the finish closes counts as area gained. A band
 * of ±8 cm holds a sloped floor whole, raw and finished.
 *
 * A hole is one the eye sees through when its loop has a free edge, no face of the model across
 * it ([FloorArea.visibleHoles]). The other loops of the floor's open edges are holes in the
 * selection — a face the smoothing tipped past [FloorArea.MIN_UP] at the foot of a wall — and
 * are printed, not counted.
 */
class FloorMetricTest {
    @Test
    fun aSlopedFloorKeepsItsAreaInABandThatHoldsIt() {
        for (seamSeen in listOf(true, false)) {
            val raw = FloorScenes.Slope.scan(seamSeen, foot = null, depth = FloorScenes.PHONE_SOLID, landing = false)
            val done = finished(raw)
            val row = row("A, seam ${if (seamSeen) "seen" else "missed"}", raw, done)
            assertTrue("$row: the ±8 cm ratio is not about 1", row.ratio8 in RATIO_RANGE)
            assertEquals("$row: the finish opened the floor", 0f, row.opened8, 0f)
        }
    }

    @Test
    fun aHoleUnderARaisedBlockClosesAndTheSelectionsLoopsDoNotCount() {
        val raw = FloorScenes.Slivers.scan()
        val done = finished(raw)
        val row = row("C, slivers", raw, done)
        val hidden = cellsOf(FloorScenes.Slivers.HIDDEN)
        val rawFloor = FloorArea.of(raw.positions, raw.indices, 0f)
        val doneFloor = FloorArea.of(done.positions, done.indices, 0f)
        println("  ${hidden.size} cells under the raised block: ${hidden.count { rawFloor.covers(it) }} covered raw, ${hidden.count { doneFloor.covers(it) }} finished")
        assertTrue("$row: the scan sees the hole", rawFloor.visibleHoles >= 1 && hidden.none { rawFloor.covers(it) })
        assertTrue("$row: the hole under the raised block stays open", hidden.all { doneFloor.covers(it) })
        assertEquals("$row: a hole the eye sees through", 0, doneFloor.visibleHoles)
        // Without loops of the selection the scene would not tell the two counts apart.
        assertTrue("$row: no loop in the selection to leave out", doneFloor.innerLoops > doneFloor.visibleHoles)
        assertEquals("$row: the finish opened the floor", 0f, row.opened, 0f)
    }

    @Test
    fun realHolesCloseWithPatchesFacingUp() {
        val raw = FloorScenes.Holes.scan()
        val (done, patches) = finishedWithPatches(raw)
        val row = row("D, real holes", raw, done)
        val faces = FaceGeometry(done.positions, done.indices)
        var patchArea = 0.0
        var down = 0
        for (t in done.triangleCount - patches until done.triangleCount) {
            patchArea += faces.areas[t] * faces.normals[t * 3 + 1]
            if (faces.normals[t * 3 + 1] < -FloorArea.MIN_UP) down++
        }
        val doneFloor = FloorArea.of(done.positions, done.indices, 0f)
        val share = (patchArea / doneFloor.area).toFloat()
        println("  $patches patch faces, ${percent(share)} of the floor, $down facing down")
        assertEquals("$row: holes the scan left", FloorScenes.Holes.HOLES.size, FloorArea.of(raw.positions, raw.indices, 0f).visibleHoles)
        assertEquals("$row: a hole the eye sees through", 0, doneFloor.visibleHoles)
        assertTrue("$row: patches ${percent(share)} of the floor", share in PATCH_SHARE)
        assertEquals("$row: patch faces facing down", 0, down)
        assertEquals("$row: the finish opened the floor", 0f, row.opened, 0f)
    }

    /** One line of the scenes' table: the area ratios in both bands, the loops, the holes and the floor opened. */
    private class Row(val ratio5: Float, val ratio8: Float, val opened: Float, val opened8: Float, val text: String) {
        override fun toString() = text
    }

    private fun row(scene: String, raw: WorkMesh, done: WorkMesh): Row {
        val raw5 = FloorArea.of(raw.positions, raw.indices, 0f)
        val done5 = FloorArea.of(done.positions, done.indices, 0f)
        val raw8 = FloorArea.of(raw.positions, raw.indices, 0f, WIDE_BAND_M)
        val done8 = FloorArea.of(done.positions, done.indices, 0f, WIDE_BAND_M)
        val opened = done5.openedOn(raw5)
        val opened8 = done8.openedOn(raw8)
        val ratio5 = done5.area / raw5.area
        val ratio8 = done8.area / raw8.area
        val text = "$scene | ×${f(ratio5)} | ×${f(ratio8)} | ${raw5.innerLoops} → ${done5.innerLoops} | " +
            "${raw5.visibleHoles} → ${done5.visibleHoles} | ${percent(opened)} | ${percent(opened8)}"
        println(text)
        return Row(ratio5, ratio8, opened, opened8, text)
    }

    /** The [FloorArea.COVER_CELL_M] cells whose sample point lies in [box]'s footprint. */
    private fun cellsOf(box: Box): List<Long> {
        val cells = ArrayList<Long>()
        val step = FloorArea.COVER_CELL_M
        var x = box.x0 + step / 2f
        while (x < box.x1) {
            var z = box.z0 + step / 2f
            while (z < box.z1) {
                cells += FloorArea.cellAt(x, z)
                z += step
            }
            x += step
        }
        return cells
    }

    private fun finished(raw: WorkMesh): WorkMesh =
        CleanGeometry.clean(WorkMesh(raw.positions.copyOf(), raw.colors.copyOf(), raw.indices.copyOf())).first

    /** [raw] finished as [CleanGeometry.clean] does it, and how many faces the holes' patches are: the last ones. */
    private fun finishedWithPatches(raw: WorkMesh): Pair<WorkMesh, Int> {
        var mesh = MeshCleanup.removeSmallPieces(WorkMesh(raw.positions.copyOf(), raw.colors.copyOf(), raw.indices.copyOf())).first
        mesh = MeshCleanup.removeDoubledSurfaces(mesh).first
        MeshCleanup.smooth(mesh)
        val flattening = PlaneFlattening.flatten(mesh)
        val filled = MeshCleanup.fillSmallHoles(mesh, facePlane = flattening.facePlane, planes = flattening.planes).first
        // What it removes lies before the patches, which keep their order at the end.
        return MeshCleanup.removeCovered(filled, firstNew = mesh.triangleCount).first to filled.triangleCount - mesh.triangleCount
    }

    private fun f(v: Float): String = String.format(Locale.ROOT, "%.2f", v)

    private fun percent(share: Float): String = String.format(Locale.ROOT, "%.1f%%", share * PERCENT)

    private companion object {
        const val WIDE_BAND_M = 0.08f
        val RATIO_RANGE = 0.97f..1.03f

        /** About 3 % of the floor: the holes' own area, and a voxel round each. */
        val PATCH_SHARE = 0.01f..0.05f
        const val PERCENT = 100f
    }
}
