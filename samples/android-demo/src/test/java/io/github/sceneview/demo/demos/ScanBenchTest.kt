package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.room.RoomDepthMesh
import io.github.sceneview.demo.demos.internal.room.ScanFloorSlab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanBenchTest {
    private val lines = mutableListOf<String>()
    private var now = 1_794_500_000_000L
    private val bench = ScanBench(clockMs = { now }, sink = { lines += it })

    @Test
    fun cadenceCountsTheFramesOfEachOneSecondWindow() {
        repeat(30) { bench.frame("TRACKING", 0f); now += 33 }
        now += 100
        bench.frame("TRACKING", 0f)
        val cadence = lines.single()
        assertTrue(cadence, cadence.contains(""""event":"cadence","fps":30,"window_start_epoch_ms":1794500000000"""))
        assertTrue(cadence, cadence.startsWith("""{"schema":2"""))
    }

    @Test
    fun surfaceReadyIsSentOnceWhenTheFloorIsFirstCovered() {
        bench.frame("TRACKING", 0f)
        bench.frame("TRACKING", 0.04f)
        bench.frame("TRACKING", 0.5f)
        assertEquals(1, lines.size)
        assertEquals(
            """{"schema":2,"event":"surface_ready","surface_ready":true,"covered_m2":0.04,"outside_elements":null}""",
            lines.single(),
        )
    }

    @Test
    fun depthModelLineCarriesTheSegmentsAndTheSlabPieces() {
        val fused = RoomDepthMesh.Result(
            mesh = RerunMesh(FloatArray(9), FloatArray(9), IntArray(3), intArrayOf(0, 1, 2)),
            frames = 300, fused = 120, blocks = 1, bytes = 0, droppedBlocks = 0, rawTriangles = 1,
            droppedComponents = 4, capped = false, holesClosed = 0, floorPlaneY = -1.2346f, guardHeld = 7,
            segments = 2, chosenSegment = 1, segmentCells = listOf(410, 980), segmentFrames = listOf(150, 120),
            fuseMs = 0, meshMs = 0,
        )
        val slab = ScanFloorSlab.Result(
            mesh = fused.mesh, floorY = -1.25f, areaM2 = 3.456f, floorTriangles = 5000, droppedTriangles = 30,
            piecesBeforeM2 = List(10) { 3.456f / (it + 1) }, piecesAfterM2 = listOf(3.456f),
            floorTrianglesOffSlab = 800, floorTrianglesInDroppedPieces = 640, belowTriangles = 10, strayTriangles = 20,
        )
        bench.depthModel("depth_model", fused, floorY = -1.25f, floorSource = "scan", slab = slab)
        val line = lines.single()
        assertTrue(line, line.startsWith("""{"schema":2,"event":"depth_model","opens_on":"depth_model","""))
        assertTrue(line, line.contains(""""frames":300,"fused_frames":120,"guard_held":7,"""))
        assertTrue(line, line.contains(""""segments":2,"segment_chosen":1,"segment_cells":[410,980],"segment_frames":[150,120]"""))
        assertTrue(line, line.contains(""""dropped_components":4,"triangles":1,"""))
        assertTrue(line, line.contains(""""floor_y":-1.250,"floor_source":"scan","arcore_floor_y":-1.235,"""))
        assertTrue(line, line.contains(""""pieces_before":10,"pieces_before_m2":[3.46,1.73,1.15,0.86,0.69,0.58,0.49,0.43],"""))
        assertTrue(line, line.contains(""""pieces_after":1,"pieces_after_m2":[3.46]"""))
        assertTrue(
            line,
            line.contains(""""floor_triangles_removed":5000,"floor_triangles_off_slab":800,"floor_triangles_in_dropped_pieces":640"""),
        )
        assertTrue(line, line.endsWith(""""below_triangles":10,"stray_triangles":20}}"""))
    }

    @Test
    fun depthModelLineWithoutASlabSaysSo() {
        val fused = RoomDepthMesh.Result(
            mesh = RerunMesh(FloatArray(0), FloatArray(0), IntArray(0), IntArray(0)),
            frames = 1, fused = 1, blocks = 0, bytes = 0, droppedBlocks = 0, rawTriangles = 0,
            droppedComponents = 0, capped = false, holesClosed = 0, fuseMs = 0, meshMs = 0,
        )
        bench.depthModel("shell", fused, floorY = null, floorSource = null, slab = null)
        val line = lines.single()
        assertTrue(line, line.endsWith(""""floor_y":null,"floor_source":null,"arcore_floor_y":null,"slab":null}"""))
        lines.clear()
        bench.depthModel("a \"b\"\\c\n", fused, floorY = Float.NaN, floorSource = "x", slab = null)
        assertTrue(lines.single(), lines.single().contains(""""opens_on":"a \"b\"\\c\u000a","""))
        assertTrue(lines.single(), lines.single().contains(""""floor_y":null,"floor_source":"x","""))
        lines.clear()
        bench.depthModel("a\u2028b", fused, floorY = null, floorSource = null, slab = null)
        assertTrue(lines.single(), lines.single().contains("\"opens_on\":\"a\\u2028b\","))
    }

    @Test
    fun aBenchLineNeverFailsTheResult() {
        val failing = ScanBench(sink = { error("no logcat off a device") })
        val fused = RoomDepthMesh.Result(
            mesh = RerunMesh(FloatArray(0), FloatArray(0), IntArray(0), IntArray(0)),
            frames = 1, fused = 1, blocks = 0, bytes = 0, droppedBlocks = 0, rawTriangles = 0,
            droppedComponents = 0, capped = false, holesClosed = 0, fuseMs = 0, meshMs = 0,
        )
        failing.depthModel("shell", fused, floorY = null, floorSource = null, slab = null)
    }

    @Test
    fun aNonFiniteAreaIsSentAsNull() {
        repeat(2) { bench.frame("PAUSED", Float.NaN); now += 600 }
        bench.frame("PAUSED", Float.NaN)
        assertTrue(lines.single(), lines.single().endsWith(""""covered_m2":null}"""))
    }
}
