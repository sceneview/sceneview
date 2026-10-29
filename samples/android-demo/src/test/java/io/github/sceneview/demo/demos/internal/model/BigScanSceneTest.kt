package io.github.sceneview.demo.demos.internal.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** The finish of a scan as big as the longest recordings mesh ([BigScanScene]). */
class BigScanSceneTest {
    @Test
    fun `a big scan over the size cap keeps its photo texture, its faces cut first`() {
        // A dim room's model stays well over its budget, and its atlas compresses as badly as
        // noisy photos do: together over the cap. The faces pay first, then the atlas's
        // resolution; the photos' texture goes only when neither is left to give.
        val result = CleanModel.build(BigScanScene.raw(BigScanScene.DIM), BigScanScene.photos(1), HEAVY_ATLAS)
        val stats = result.stats
        val report = "big dim scan: raw ${stats.rawTriangles} triangles -> ${stats.simplifiedTriangles} simplified" +
            ", ${stats.finalTriangles} final, glb ${result.glb.size / KB} KB, guarded ${stats.glbGuarded}" +
            ", halved ${stats.atlasHalvings}, textured ${stats.texture?.texturedShare ?: "none"}, dropped at ${stats.droppedTextureShare}"
        println(report)
        assertTrue("the size guard never ran | $report", stats.glbGuarded)
        assertNotNull("the photo texture was dropped | $report", stats.texture)
        assertTrue("the .glb holds no texture | $report", ModelGlb.isTextured(result.glb))
        assertEquals("the atlas was halved before the faces were cut | $report", 0, stats.atlasHalvings)
        assertTrue(
            "faces cut under the textured budget | $report",
            stats.finalTriangles >= CleanModel.TEXTURED_TARGET_TRIANGLES,
        )
        assertTrue("glb ${result.glb.size} over the cap | $report", result.glb.size <= CleanModel.MAX_GLB_BYTES)
        assertTrue("glb: ${GlbCheck.problems(result.glb)} | $report", GlbCheck.problems(result.glb).isEmpty())
    }

    @Test
    fun `a floor scanned twice keeps the floor the scan saw, through every stage of the finish`() {
        // The furnished room's floor scanned twice, a pass drifted 4 cm below the other, each
        // missing its own patches: from above, the two sheets together cover the floor. The
        // lower sheet must go where the upper one covers it, and stay where the upper one has a
        // hole — the floor the scan saw there — or the finished floor opens onto whatever the
        // model stands on.
        val raw = BigScanScene.raw(driftedFloor = true)
        val finishes = listOf(null, BigScanScene.photos(bareEvery = 1)).map { photos ->
            val result = CleanModel.build(raw, photos, photos?.let { JvmAtlasEncoder })
            FinishStages.floors(raw, textured = result.stats.texture != null, finished = checkNotNull(result.model)) to
                (if (result.stats.texture != null) "textured" else "untextured")
        }
        val report = finishes.joinToString("\n") { (stages, kind) -> describe(kind, stages) }
        println("drifted floor, $report")
        for ((stages, kind) in finishes) {
            val rawFloor = stages.first().floor
            for (stage in stages) {
                val opened = stage.floor.opened(rawFloor).filterTo(HashSet(), ::seen)
                assertTrue(
                    "$kind, ${stage.stage}: the floor opens ${opened.size} of ${rawFloor.cellCount} cells | $report",
                    opened.size <= rawFloor.cellCount * MAX_OPENED_SHARE,
                )
                assertTrue(
                    "$kind, ${stage.stage}: a hole of ${FloorArea.largestPatch(opened)} cells opens in the floor | $report",
                    FloorArea.largestPatch(opened) <= MAX_HOLE_CELLS,
                )
            }
            val finished = stages.last().floor
            assertTrue("$kind: the finished floor has more holes than the raw one | $report", finished.innerLoops <= rawFloor.innerLoops)
        }
    }

    /** One line per stage: its triangles, its floor's area and holes, and how much of the raw floor it leaves open where the eye sees it. */
    private fun describe(kind: String, stages: List<FinishStages.Floor>): String {
        val rawFloor = stages.first().floor
        val lines = StringBuilder("$kind: raw floor ${rawFloor.cellCount} cells, ${rawFloor.innerLoops} holes")
        for (stage in stages) {
            val opened = stage.floor.opened(rawFloor).filterTo(HashSet(), ::seen)
            lines.append(
                String.format(
                    Locale.ROOT,
                    "\n  %-17s %7d triangles, floor %6.2f m², %4d holes, opened %5.2f %% (widest %d cells)",
                    stage.stage, stage.triangles, stage.floor.area, stage.floor.innerLoops,
                    PERCENT * opened.size / rawFloor.cellCount, FloorArea.largestPatch(opened),
                ),
            )
        }
        return lines.toString()
    }

    /** Whether the eye sees [cell] in the finished room: not under the sofa, where no camera looked. */
    private fun seen(cell: Long): Boolean {
        val sofa = RealScanRoom.SOFA
        val x = FloorArea.centreX(cell)
        val z = FloorArea.centreZ(cell)
        return x !in sofa.x0 - SOFA_MARGIN_M..sofa.x1 + SOFA_MARGIN_M || z !in sofa.z0 - SOFA_MARGIN_M..sofa.z1 + SOFA_MARGIN_M
    }

    private companion object {
        const val KB = 1024
        const val PERCENT = 100f

        /**
         * The share of the raw floor a stage may leave open: a few per cent, the rims of the
         * holes the simplification wears.
         */
        const val MAX_OPENED_SHARE = 0.05f

        /** The widest hole a stage may open, in [FloorArea.COVER_CELL_M] cells: under a hand's width. */
        const val MAX_HOLE_CELLS = 20

        /** How far past the sofa's footprint its legs and shadow keep the floor out of sight. */
        const val SOFA_MARGIN_M = 0.05f

        /** What the photos of a dim room add to an atlas's JPEG: about half the cap. */
        const val HEAVY_ATLAS_PADDING = 3_900 * KB

        val HEAVY_ATLAS = AtlasEncoder { argb, width, height ->
            JvmAtlasEncoder.jpeg(argb, width, height) + ByteArray(HEAVY_ATLAS_PADDING)
        }
    }
}
