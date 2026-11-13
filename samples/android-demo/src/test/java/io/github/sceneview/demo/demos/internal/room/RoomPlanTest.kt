package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.model.JvmAtlasEncoder
import io.github.sceneview.demo.demos.internal.model.JvmImages
import io.github.sceneview.demo.demos.internal.model.ModelPhoto
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import io.github.sceneview.demo.demos.internal.model.TexturedSyntheticRoom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * [RoomPlan] on synthetic scans, turned 20° off the world's axes: an L-shaped room gets its six
 * walls and a floor without the notch; a rectangular room with a wardrobe's face standing in it
 * keeps its four-wall box; every quad faces into the room.
 *
 * With `SCENEVIEW_SCAN_OUT` set, the L-room is also drawn as the result shows it, into
 * `synthetic-l-room/` (`doll0.png` … `doll3.png`, `top.png`), its walls painted flat and its floor
 * a 50 cm checker, so seams and the notch read at a glance.
 */
class RoomPlanTest {
    @Test
    fun `an L-shaped room gets its six walls within 3 cm`() {
        val plan = checkNotNull(lShell.plan) { "no plan: built as a box" }
        val box = lShell.box
        assertEquals("walls ${plan.walls}", 6, plan.walls.size)
        val found = plan.walls.map { wall ->
            val (a0, b0) = if (wall.alongA) wall.from to wall.line else wall.line to wall.from
            val (a1, b1) = if (wall.alongA) wall.to to wall.line else wall.line to wall.to
            unturned(box.worldX(a0, b0), box.worldZ(a0, b0)) to unturned(box.worldX(a1, b1), box.worldZ(a1, b1))
        }
        for (k in L.indices) {
            val p = L[k]
            val q = L[(k + 1) % L.size]
            val match = found.minBy { (s, e) -> ends(s, e, p, q) }
            // Where it stands: its middle's distance to the true wall's line.
            val mid = (match.first.first + match.second.first) / 2 to (match.first.second + match.second.second) / 2
            val offset = abs((q.first - p.first) * (mid.second - p.second) - (q.second - p.second) * (mid.first - p.first)) / gap(p, q)
            val ends = ends(match.first, match.second, p, q)
            println("edge $p → $q: middle ${"%.3f".format(offset)} m, ends ${"%.3f".format(ends)} m")
            assertTrue("edge $p → $q stands ${"%.3f".format(offset)} m off", offset <= TOLERANCE_M)
            assertTrue("edge $p → $q ends ${"%.3f".format(ends)} m off", ends <= END_TOLERANCE_M)
        }
    }

    @Test
    fun `an L-shaped room's floor leaves its notch out`() {
        val plan = checkNotNull(lShell.plan)
        val box = lShell.box
        fun inRoom(x: Float, z: Float): Boolean {
            val (wx, wz) = turned(x, z)
            return plan.contains(box.a(wx, wz), box.b(wx, wz))
        }
        assertFalse("the notch is in the room", inRoom(3.75f, 4f))
        assertTrue(inRoom(1f, 1f))
        assertTrue(inRoom(1f, 4f))
        assertTrue(inRoom(4f, 1f))
        val floor = lShell.faces.single { it.name == "floor" }
        var area = 0f
        for (k in 0 until floor.pieceCount) {
            area += (floor.pieces[k * 4 + 2] - floor.pieces[k * 4]) * (floor.pieces[k * 4 + 3] - floor.pieces[k * 4 + 1])
        }
        assertEquals("floor area", L_AREA, area, AREA_TOLERANCE)
        // One texture under every piece: the pieces lie inside the face, so their UVs do.
        for (k in 0 until floor.pieceCount) {
            assertTrue(floor.pieces[k * 4] >= 0f && floor.pieces[k * 4 + 2] <= floor.lengthU + EPSILON)
            assertTrue(floor.pieces[k * 4 + 1] >= 0f && floor.pieces[k * 4 + 3] <= floor.lengthV + EPSILON)
        }
    }

    @Test
    fun `every quad of the L-shaped room faces into it`() {
        val plan = checkNotNull(lShell.plan)
        val box = lShell.box
        val mesh = ShellRender.read(checkNotNull(RoomShellGlb.write(lShell.faces, JvmAtlasEncoder)))
        assertEquals(lShell.faces.size, mesh.faces.size)
        val p = mesh.positions
        var triangles = 0
        for ((indices, _) in mesh.faces) {
            for (t in 0 until indices.size / 3) {
                val (i0, i1, i2) = Triple(indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2])
                val e1 = FloatArray(3) { p[i1 * 3 + it] - p[i0 * 3 + it] }
                val e2 = FloatArray(3) { p[i2 * 3 + it] - p[i0 * 3 + it] }
                val n = floatArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
                val length = hypot(hypot(n[0], n[1]), n[2])
                val c = FloatArray(3) { (p[i0 * 3 + it] + p[i1 * 3 + it] + p[i2 * 3 + it]) / 3f + n[it] / length * STEP_M }
                if (abs(n[1]) > abs(n[0]) + abs(n[2])) {
                    assertTrue("the floor faces away from the room", n[1] > 0f)
                } else {
                    assertTrue("a wall faces out of the room", plan.contains(box.a(c[0], c[2]), box.b(c[0], c[2])))
                }
                triangles++
            }
        }
        assertTrue(triangles >= 2 * (6 + 1))
        // No furniture point stands outside the room.
        val objects = lShell.objects
        for (i in 0 until objects.count) {
            val x = objects.positions[i * 3]
            val z = objects.positions[i * 3 + 2]
            assertTrue(plan.toWall(box.a(x, z), box.b(x, z)) > RoomShellBuilder.OBJECT_WALL_MARGIN_M)
        }
        draw()
    }

    @Test
    fun `a rectangular room with a wardrobe's face in it keeps its box`() {
        val cloud = wardrobeRoom()
        val box = checkNotNull(RoomBox.of(cloud, FLOOR_Y))
        val cuts = checkNotNull(RoomPlan.cuts(cloud, box, alongA = true)).size + checkNotNull(RoomPlan.cuts(cloud, box, alongA = false)).size
        assertTrue("the wardrobe's face is no peak: the test tests nothing", cuts > 4)
        assertEquals("long side", ROOM_W, maxOf(box.width, box.depth), TOLERANCE_M)
        assertEquals("short side", ROOM_D, minOf(box.width, box.depth), TOLERANCE_M)
        val shell = checkNotNull(runBlocking { RoomShellBuilder.build(cloud, cameras(WARDROBE_CAMERAS), FLOOR_Y) })
        assertNull("a box room got a plan: ${shell.plan}", shell.plan)
        assertEquals(listOf("floor", "wx0", "wx1", "wz0", "wz1"), shell.faces.map { it.name })
    }

    /** Draws the L-room into `$SCENEVIEW_SCAN_OUT/synthetic-l-room/`, when set. */
    private fun draw() {
        val out = System.getenv("SCENEVIEW_SCAN_OUT")?.let { File(it, "synthetic-l-room") } ?: return
        out.mkdirs()
        val box = lShell.box
        lShell.faces.forEachIndexed { f, face ->
            face.argb = IntArray(face.columns * face.rows) { at ->
                val i = at % face.columns
                val row = at / face.columns
                val s = (i + HALF) * face.texelU
                val t = (face.rows - 1 - row + HALF) * face.texelV
                if (face.name == "floor") {
                    val even = (floor((box.a0 + s) / CHECK_M) + floor((box.b0 + t) / CHECK_M)).toInt() % 2 == 0
                    if (even) LIGHT else DARK
                } else {
                    val line = (s % CHECK_M) < LINE_M || (t % CHECK_M) < LINE_M
                    if (line) DARK else WALL_TONES[f % WALL_TONES.size]
                }
            }
            face.meanColor = face.argb[face.argb.size / 2]
        }
        val mesh = ShellRender.read(checkNotNull(RoomShellGlb.write(lShell.faces, JvmAtlasEncoder)))
        listOf(35.0, 125.0, 215.0, 305.0).forEachIndexed { i, azimuth ->
            val image = ShellRender.view(mesh, lShell.objects, box, azimuth)
            File(out, "doll$i.png").writeBytes(JvmImages.png(image.argb, image.width, image.height))
        }
        val top = ShellRender.view(mesh, lShell.objects, box, null)
        File(out, "top.png").writeBytes(JvmImages.png(top.argb, top.width, top.height))
    }

    private companion object {
        const val FLOOR_Y = -1f
        const val CEILING_Y = 1.4f
        const val TOLERANCE_M = 0.03f

        /**
         * A wall's ends may be off by more than its middle: the room's frame is read off 1° bins
         * ([RoomBox]), up to 0.5° off — 2.2 cm at the end of a 5 m wall — and 20° is that worst case.
         */
        const val END_TOLERANCE_M = 0.045f
        const val L_AREA = 20f
        const val AREA_TOLERANCE = 0.3f
        const val EPSILON = 1e-4f
        const val STEP_M = 0.05f
        const val WALL_DENSITY = 400f
        const val FLOOR_DENSITY = 1200f
        const val NOISE_M = 0.01f
        val NORMAL_NOISE_RAD = Math.toRadians(3.0)
        const val ROOM_W = 5f
        const val ROOM_D = 4f
        const val HALF = 0.5f
        const val CHECK_M = 0.5f
        const val LINE_M = 0.02f
        const val LIGHT = 0xFFE8E2D6.toInt()
        const val DARK = 0xFF6E6A64.toInt()
        val WALL_TONES = intArrayOf(0xFFC9D6E3.toInt(), 0xFFE3D3C9.toInt(), 0xFFD1E3C9.toInt(), 0xFFE3E0C9.toInt())
        val TURN = Math.toRadians(20.0)

        /** The L: a 5 × 5 m room without its 2.5 × 2 m corner at high x and high z. */
        val L = listOf(0f to 0f, 5f to 0f, 5f to 3f, 2.5f to 3f, 2.5f to 5f, 0f to 5f)
        val L_CAMERAS = listOf(floatArrayOf(1f, 0.4f, 1f), floatArrayOf(1.5f, 0.4f, 4f), floatArrayOf(4f, 0.4f, 1.2f))
        val WARDROBE_CAMERAS = listOf(floatArrayOf(1.5f, 0.4f, 2f), floatArrayOf(3f, 0.4f, 3f))

        val lShell: RoomShell by lazy {
            val cloud = Synth().apply {
                for (k in L.indices) {
                    val (x0, z0) = L[k]
                    val (x1, z1) = L[(k + 1) % L.size]
                    // Inward: the edge's direction turned a quarter, (dx, dz) → (−dz, dx).
                    val dx = x1 - x0
                    val dz = z1 - z0
                    val len = hypot(dx, dz)
                    wall(x0, z0, x1, z1, FLOOR_Y, CEILING_Y, -dz / len, dx / len)
                }
                floor { x, z -> !(x > 2.5f && z > 3f) && x in 0f..5f && z in 0f..5f }
            }.cloud()
            checkNotNull(runBlocking { RoomShellBuilder.build(cloud, cameras(L_CAMERAS), FLOOR_Y) }) { "no shell" }
        }

        /** A 5 × 4 m room, a 2.4 m wardrobe 70 cm deep against its wall at x = 5, the wall behind it unseen. */
        fun wardrobeRoom(): DenseCloud = Synth().apply {
            val wx = 4.3f
            val z0 = 0.5f
            val z1 = 2.9f
            val top = FLOOR_Y + 2.1f
            wall(0f, 0f, 5f, 0f, FLOOR_Y, CEILING_Y, 0f, 1f)
            wall(5f, 4f, 0f, 4f, FLOOR_Y, CEILING_Y, 0f, -1f)
            wall(0f, 4f, 0f, 0f, FLOOR_Y, CEILING_Y, 1f, 0f)
            wall(5f, 0f, 5f, z0, FLOOR_Y, CEILING_Y, -1f, 0f)
            wall(5f, z1, 5f, 4f, FLOOR_Y, CEILING_Y, -1f, 0f)
            wall(5f, z0, 5f, z1, top, CEILING_Y, -1f, 0f)
            wall(wx, z0, wx, z1, FLOOR_Y, top, -1f, 0f)
            wall(wx, z0, 5f, z0, FLOOR_Y, top, 0f, -1f)
            wall(wx, z1, 5f, z1, FLOOR_Y, top, 0f, 1f)
            floor { x, z -> x in 0f..5f && z in 0f..4f && !(x > wx && z > z0 && z < z1) }
        }.cloud()

        fun cameras(at: List<FloatArray>) = ModelPhotos(
            TexturedSyntheticRoom.PHOTO_LENS,
            at.map { c ->
                val (x, z) = turned(c[0], c[2])
                ModelPhoto(DebugPose(x, c[1], z)) { null }
            },
        )

        /** The room's (x, z) turned by [TURN] about Y: `x' = x·cos + z·sin`, `z' = −x·sin + z·cos`. */
        fun turned(x: Float, z: Float): Pair<Float, Float> {
            val c = cos(TURN).toFloat()
            val s = sin(TURN).toFloat()
            return (x * c + z * s) to (-x * s + z * c)
        }

        fun unturned(x: Float, z: Float): Pair<Float, Float> {
            val c = cos(TURN).toFloat()
            val s = sin(TURN).toFloat()
            return (x * c - z * s) to (x * s + z * c)
        }

        /** How far the ends ([s], [e]) of a wall found are from the true one's ([p], [q]), either way round. */
        fun ends(s: Pair<Float, Float>, e: Pair<Float, Float>, p: Pair<Float, Float>, q: Pair<Float, Float>) =
            minOf(maxOf(gap(s, p), gap(e, q)), maxOf(gap(s, q), gap(e, p)))

        fun gap(p: Pair<Float, Float>, q: Pair<Float, Float>) = hypot(p.first - q.first, p.second - q.second)
    }

    /** A scan drawn by hand: vertical wall strips and a floor, noisy by 1 cm and their normals by 3°, turned by [TURN]. */
    private class Synth {
        private val random = Random(1)
        private val positions = ArrayList<Float>()
        private val normals = ArrayList<Float>()

        @Suppress("LongParameterList")
        fun wall(x0: Float, z0: Float, x1: Float, z1: Float, y0: Float, y1: Float, nx: Float, nz: Float) {
            val count = (hypot(x1 - x0, z1 - z0) * (y1 - y0) * WALL_DENSITY).toInt()
            repeat(count) {
                val t = random.nextFloat()
                add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * random.nextFloat(), z0 + (z1 - z0) * t, nx, 0f, nz)
            }
        }

        fun floor(inside: (Float, Float) -> Boolean) {
            repeat((ROOM_W * ROOM_W * FLOOR_DENSITY).toInt()) {
                val x = random.nextFloat() * ROOM_W
                val z = random.nextFloat() * ROOM_W
                if (inside(x, z)) add(x, FLOOR_Y, z, 0f, 1f, 0f)
            }
        }

        @Suppress("LongParameterList")
        private fun add(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            val (tx, tz) = turned(x + noise(), z + noise())
            positions += tx
            positions += y + noise()
            positions += tz
            // A surfel's normal is off by a few degrees, as a scan's are: an exact one would tie the frame's bins.
            val off = (random.nextGaussian() * NORMAL_NOISE_RAD).toFloat()
            val (tnx, tnz) = turned(nx * cos(off) - nz * sin(off), nx * sin(off) + nz * cos(off))
            normals += tnx
            normals += ny
            normals += tnz
        }

        private fun noise() = (random.nextGaussian() * NOISE_M).toFloat()

        fun cloud() = DenseCloud(positions.toFloatArray(), IntArray(positions.size / 3) { GREY }, normals.toFloatArray())

        private companion object {
            const val GREY = 0xFF808080.toInt()
        }
    }
}
