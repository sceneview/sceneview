package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Pins the replay's floor-plan dimensions: the room's rectangle squared to its walls, whatever
 * way the phone faced when the session started, and its dimensions drawn outside it.
 */
class RoomMeasureTest {

    private val floorY = -1.4f

    /** Point ([a], [b]) in a room turned [yaw] radians: a along its width, b along its depth. */
    private fun turned(a: Float, b: Float, yaw: Float) =
        floatArrayOf(a * cos(yaw) - b * sin(yaw), a * sin(yaw) + b * cos(yaw))

    /** A wall from ([a0], [b0]) to ([a1], [b1]) in the turned room, floor to 2.4 m. */
    private fun wall(id: Int, a0: Float, b0: Float, a1: Float, b1: Float, yaw: Float): DebugPlane {
        val (x0, z0) = turned(a0, b0, yaw).let { it[0] to it[1] }
        val (x1, z1) = turned(a1, b1, yaw).let { it[0] to it[1] }
        val top = floorY + 2.4f
        val polygon = floatArrayOf(x0, floorY, z0, x1, floorY, z1, x1, top, z1, x0, top, z0)
        return DebugPlane(id, DebugPlaneKind.Wall, polygon)
    }

    /** A 3.4 × 4.1 m room turned [yaw], its four walls a little short of the corners, as ARCore finds them. */
    private fun room(yaw: Float) = listOf(
        wall(1, 0.2f, 0f, 3.2f, 0f, yaw),
        wall(2, 3.4f, 0.3f, 3.4f, 3.9f, yaw),
        wall(3, 0f, 4.1f, 3.4f, 4.1f, yaw),
        wall(4, 0f, 0.1f, 0f, 4f, yaw),
    )

    @Test
    fun `a turned room is measured square to its walls`() {
        val yaw = Math.toRadians(30.0).toFloat()
        val measure = RoomMeasure.of(room(yaw), floorY)!!
        assertEquals(yaw, measure.yaw, 1e-3f)
        assertEquals(3.4f, measure.width, 1e-3f)
        assertEquals(4.1f, measure.depth, 1e-3f)
        assertEquals("3.40 m × 4.10 m · 13.9 m²", measure.summary(MeasureUnitSystem.Metric, Locale.US))
    }

    @Test
    fun `a room turned past a quarter turn names the same walls`() {
        // 70° is -20° a quarter turn on: width and depth trade places, the room does not change.
        val measure = RoomMeasure.of(room(Math.toRadians(70.0).toFloat()), floorY)!!
        assertEquals(Math.toRadians(-20.0).toFloat(), measure.yaw, 1e-3f)
        assertEquals(3.4f * 4.1f, measure.area, 1e-2f)
    }

    @Test
    fun `a table top alone is no room`() {
        val table = DebugPlane(
            7, DebugPlaneKind.Floor,
            floatArrayOf(
                0f, floorY + 0.45f, 0f, 1.2f, floorY + 0.45f, 0f,
                1.2f, floorY + 0.45f, 0.8f, 0f, floorY + 0.45f, 0.8f,
            ),
        )
        assertNull(RoomMeasure.of(listOf(table), floorY))
        assertNull(RoomMeasure.of(emptyList(), floorY))
    }

    @Test
    fun `a floor patch too narrow to be a room is not measured`() {
        val strip = DebugPlane(
            8, DebugPlaneKind.Floor,
            floatArrayOf(0f, floorY, 0f, 2f, floorY, 0f, 2f, floorY, 0.4f, 0f, floorY, 0.4f),
        )
        assertNull(RoomMeasure.of(listOf(strip), floorY))
    }

    /** A flat floor patch over ([a0], [b0])–([a1], [b1]) of the turned room, at height [y]. */
    private fun floor(id: Int, a0: Float, b0: Float, a1: Float, b1: Float, yaw: Float, y: Float = floorY): DebugPlane {
        val corners = listOf(a0 to b0, a1 to b0, a1 to b1, a0 to b1).map { (a, b) -> turned(a, b, yaw) }
        return DebugPlane(id, DebugPlaneKind.Floor, corners.flatMap { listOf(it[0], y, it[1]) }.toFloatArray())
    }

    @Test
    fun `a floor grown through its walls stops at them`() {
        // ARCore grows a floor plane past the wall it meets, by a metre on a phone: the rectangle
        // around that floor read 6.4 × 4.6 m for this 3.4 × 4.1 m room, 29 m² for 14.
        val yaw = Math.toRadians(20.0).toFloat()
        val overflow = floor(9, -1f, -0.5f, 5.4f, 4.1f, yaw)
        val measure = RoomMeasure.of(room(yaw) + overflow, floorY)!!
        assertEquals(3.4f, measure.width, 0.02f)
        assertEquals(4.1f, measure.depth, 0.02f)
        assertEquals(3.4f * 4.1f, measure.area, 3.4f * 4.1f * 0.03f)
    }

    @Test
    fun `a stray patch under the floor does not hide the floor over it`() {
        // The lowest patch ARCore found sat 42 cm under the floor; standing on it, the measure
        // kept none of the floor's own patches and read only as far as its one wall.
        val decoyY = floorY - 0.42f
        val planes = listOf(
            wall(1, 0f, 0f, 3.4f, 0f, 0f),
            floor(5, 1f, 1f, 1.8f, 1.6f, 0f, decoyY),
            floor(6, 0f, 0f, 3.4f, 4.1f, 0f),
        )
        val measure = RoomMeasure.of(planes, decoyY)!!
        assertEquals(3.4f, measure.width, 0.02f)
        assertEquals(4.1f, measure.depth, 0.02f)
    }

    @Test
    fun `a wall inside the room does not cut it`() {
        // A partition only half as long as the room, in the room's inner half: not its edge.
        val inner = wall(5, 1f, 2f, 2.4f, 2f, 0f)
        val measure = RoomMeasure.of(room(0f) + inner, floorY)!!
        assertEquals(3.4f, measure.width, 0.02f)
        assertEquals(4.1f, measure.depth, 0.02f)
    }

    @Test
    fun `a short interior partition does not clamp the floor`() {
        val ground = floor(1, 0f, 0f, 5f, 4f, 0f)
        val partition = wall(2, 3f, 1.5f, 3f, 2.5f, 0f)

        val measure = RoomMeasure.of(listOf(ground, partition), floorY)!!

        assertEquals(5f, measure.width, 0.02f)
        assertEquals(4f, measure.depth, 0.02f)
    }

    @Test
    fun `a short outer wall slightly inside the floor still clamps it`() {
        val ground = floor(1, 0f, 0f, 5f, 4f, 0f)
        val outer = wall(2, 4.7f, 1.5f, 4.7f, 2.5f, 0f)

        val measure = RoomMeasure.of(listOf(ground, outer), floorY)!!

        assertEquals(4.7f, measure.width, 0.02f)
        assertEquals(4f, measure.depth, 0.02f)
    }

    @Test
    fun `the room reads in the Measure units, metric or imperial`() {
        val measure = RoomMeasure.of(room(0f), floorY)!!
        // One format for every Measure figure: the same strings MeasureUnits gives a segment.
        val metric = measure.summary(MeasureUnitSystem.Metric, Locale.FRANCE)
        assertEquals(
            "${formatMeasureLength(3.4f, MeasureUnitSystem.Metric, Locale.FRANCE)} × " +
                "${formatMeasureLength(4.1f, MeasureUnitSystem.Metric, Locale.FRANCE)} · " +
                formatMeasureArea(3.4f * 4.1f, MeasureUnitSystem.Metric, Locale.FRANCE),
            metric,
        )
        assertEquals("3,40 m × 4,10 m · 13,9 m²", metric)
        assertEquals("11 ft 2 in × 13 ft 5 in · 150 ft²", measure.summary(MeasureUnitSystem.Imperial, Locale.US))
    }

    @Test
    fun `the dimensions go on the two sides facing the eye`() {
        val measure = RoomMeasure.of(room(0f), floorY)!!
        // Room from (0, 0) to (3.4, 4.1); the eye far out on +x and +z.
        val sides = MeasureDrawing.sidesFacing(measure, eyeX = 10f, eyeZ = 12f)
        val out = sides.map { MeasureDrawing.outward(measure, it) }
        assertTrue(out.any { it.first > 0.9f }) // the +x side
        assertTrue(out.any { it.second > 0.9f }) // the +z side
        assertEquals(setOf(0, 1), sides.map { it % 2 }.toSet())
    }

    @Test
    fun `a dimension lies on the floor, outside the room`() {
        val measure = RoomMeasure.of(room(0.4f), floorY)!!
        val mesh = DebugMesh()
        val offset = 0.2f
        MeasureDrawing.addDimension(mesh, measure, 0, floorY, MeasureSize(0.12f, offset), 0.01f, labelWidthPx = 320f)
        assertTrue(mesh.triangleCount > 0)
        val c = measure.corners
        val cx = (c[0] + c[2] + c[4] + c[6]) / 4f
        val cz = (c[1] + c[3] + c[5] + c[7]) / 4f
        val (ox, oz) = MeasureDrawing.outward(measure, 0)
        val half = measure.depth / 2f
        for (i in 0 until mesh.vertexCount) {
            assertEquals(floorY, mesh.positions[i * 3 + 1], 1e-6f)
            // Measured from the room's centre along the side's outside: past the wall, give or
            // take the lines' half width.
            val along = (mesh.positions[i * 3] - cx) * ox + (mesh.positions[i * 3 + 2] - cz) * oz
            assertTrue("vertex $i at $along", along >= half + offset * 0.25f - 0.02f)
            val u = mesh.uvs[i * 2]
            val v = mesh.uvs[i * 2 + 1]
            assertTrue(u in 0f..1f && v in 0f..1f)
        }
        // The line itself runs the side's full length.
        val xs = (0 until mesh.vertexCount).map { mesh.positions[it * 3] to mesh.positions[it * 3 + 2] }
        val spread = xs.maxOf { (x, z) -> hypot(x - cx, z - cz) }
        assertTrue(spread > measure.width / 2f)
    }
    @Test
    fun `lines sample the solid strip and labels read upright, the atlas read bottom-up`() {
        val measure = RoomMeasure.of(room(0.4f), floorY)!!
        val mesh = DebugMesh()
        MeasureDrawing.addDimension(mesh, measure, 1, floorY, MeasureSize(0.12f, 0.2f), 0.01f, labelWidthPx = 320f)
        fun row(vertex: Int) = (1f - mesh.uvs[vertex * 2 + 1]) * MeasureDrawing.ATLAS_HEIGHT
        // The first ribbon is the dimension line: its four corners inside the solid strip.
        for (i in 0 until 4) {
            val strip = MeasureDrawing.ATLAS_HEIGHT - MeasureDrawing.SOLID_HEIGHT
            assertTrue("line vertex $i reads row ${row(i)}", row(i) >= strip)
        }
        // The label is the last quad: top-left, top-right, bottom-right, bottom-left, on row 1.
        val n = mesh.vertexCount
        assertEquals(MeasureDrawing.ROW_HEIGHT.toFloat(), row(n - 4), 1e-3f)
        assertEquals(MeasureDrawing.ROW_HEIGHT.toFloat(), row(n - 3), 1e-3f)
        assertEquals(2f * MeasureDrawing.ROW_HEIGHT, row(n - 2), 1e-3f)
        assertEquals(2f * MeasureDrawing.ROW_HEIGHT, row(n - 1), 1e-3f)
        // Its top edge is the one nearer the room.
        val c = measure.corners
        val cx = (c[0] + c[2] + c[4] + c[6]) / 4f
        val cz = (c[1] + c[3] + c[5] + c[7]) / 4f
        fun distance(vertex: Int) = hypot(mesh.positions[vertex * 3] - cx, mesh.positions[vertex * 3 + 2] - cz)
        assertTrue(distance(n - 4) < distance(n - 1))
    }
}
