package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.model.JvmAtlasEncoder
import io.github.sceneview.demo.demos.internal.model.ModelPhoto
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import io.github.sceneview.demo.demos.internal.model.TexturedSyntheticRoom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** [RoomShellBuilder] on the ray-cast room: its box, its textures, its objects; and no box where there is no room. */
class RoomShellTest {
    @Test
    fun `the synthetic room's walls, floor and ceiling are found within 3 cm`() {
        val box = checkNotNull(RoomBox.of(cloud, RerunSyntheticRoom.MIN.y)) { "no box" }
        val min = RerunSyntheticRoom.MIN
        val max = RerunSyntheticRoom.MAX
        // The frame is folded to a quarter turn: the room's axes are the world's.
        assertTrue("yaw ${box.yaw}", abs(sin(box.yaw)) < MAX_YAW_SIN)
        assertEquals("a0", min.x, box.a0, TOLERANCE_M)
        assertEquals("a1", max.x, box.a1, TOLERANCE_M)
        assertEquals("b0", min.z, box.b0, TOLERANCE_M)
        assertEquals("b1", max.z, box.b1, TOLERANCE_M)
        assertEquals("ceiling", max.y, box.ceilingY, TOLERANCE_M)
    }

    @Test
    fun `a wall's print lands where it hangs, its dark and light checks in place`() {
        val wall = shell.faces.single { it.name == "wz0" }
        // The sage wall is the one at the room's low z: wz0, facing +z.
        assertTrue(wall.normal[2] > 0.99f)
        var checked = 0
        var right = 0
        // Each 5 cm check of the print clear of its frame, read at its centre.
        var x = PRINT_X0 + CHECK_M * 1.5f
        while (x < PRINT_X1 - FRAME_M - CHECK_M / 2) {
            var y = PRINT_Y0 + CHECK_M * 1.5f
            while (y < PRINT_Y1 - FRAME_M - CHECK_M / 2) {
                val expected = TexturedSyntheticRoom.scene.shapes.first().color(x, y, RerunSyntheticRoom.MIN.z)
                val seen = texel(wall, x, y)
                checked++
                if (abs(luma(seen) - luma(expected)) < MAX_LUMA_ERROR) right++
                y += CHECK_M
            }
            x += CHECK_M
        }
        assertTrue("no check read", checked > MIN_CHECKS)
        assertTrue("$right of $checked checks right", right >= checked * MIN_RIGHT_SHARE)
        // Away from the print, the wall's own paint.
        val paint = TexturedSyntheticRoom.scene.shapes.first().color(-1f, 1.5f, RerunSyntheticRoom.MIN.z)
        assertTrue(abs(luma(texel(wall, -1f, 1.5f)) - luma(paint)) < MAX_LUMA_ERROR)
        assertTrue("seen ${wall.seenShare}", wall.seenShare > MIN_SEEN)
    }

    @Test
    fun `the furniture stands in the room as points, the walls left out`() {
        val objects = shell.objects
        assertTrue("${objects.count} object points", objects.count > MIN_OBJECT_POINTS)
        val box = shell.box
        for (i in 0 until objects.count) {
            val x = objects.positions[i * 3]
            val z = objects.positions[i * 3 + 2]
            val a = box.a(x, z)
            val b = box.b(x, z)
            assertTrue(a > box.a0 + RoomShellBuilder.OBJECT_WALL_MARGIN_M && a < box.a1 - RoomShellBuilder.OBJECT_WALL_MARGIN_M)
            assertTrue(b > box.b0 + RoomShellBuilder.OBJECT_WALL_MARGIN_M && b < box.b1 - RoomShellBuilder.OBJECT_WALL_MARGIN_M)
        }
    }

    @Test
    fun `the glb holds five textured quads facing into the room`() {
        val glb = checkNotNull(RoomShellGlb.write(shell.faces, JvmAtlasEncoder))
        val mesh = ShellRender.read(glb)
        assertEquals(5, mesh.faces.size)
        val centre = floatArrayOf(
            (RerunSyntheticRoom.MIN.x + RerunSyntheticRoom.MAX.x) / 2,
            1f,
            (RerunSyntheticRoom.MIN.z + RerunSyntheticRoom.MAX.z) / 2,
        )
        for ((indices, _) in mesh.faces) {
            assertEquals(6, indices.size)
            // Counter-clockwise seen from the room's inside: the geometric normal points at its centre.
            val p = mesh.positions
            val (i0, i1, i2) = Triple(indices[0], indices[1], indices[2])
            val e1 = FloatArray(3) { p[i1 * 3 + it] - p[i0 * 3 + it] }
            val e2 = FloatArray(3) { p[i2 * 3 + it] - p[i0 * 3 + it] }
            val n = floatArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
            val toCentre = (0 until 3).sumOf { ((centre[it] - p[i0 * 3 + it]) * n[it]).toDouble() }
            assertTrue("a face turned away from the room", toCentre > 0)
        }
    }

    @Test
    fun `an object on its own is no room`() {
        val n = 20_000
        val positions = FloatArray(n * 3)
        val normals = FloatArray(n * 3)
        val colors = IntArray(n) { GREY }
        for (i in 0 until n) {
            // A 60 cm ball standing on the floor: a scanned object, not a room.
            val t = Math.PI * (i + 0.5) / n
            val phi = i * GOLDEN
            val nx = (sin(t) * cos(phi)).toFloat()
            val ny = cos(t).toFloat()
            val nz = (sin(t) * sin(phi)).toFloat()
            positions[i * 3] = nx * BALL_M
            positions[i * 3 + 1] = BALL_M + ny * BALL_M
            positions[i * 3 + 2] = nz * BALL_M
            normals[i * 3] = nx
            normals[i * 3 + 1] = ny
            normals[i * 3 + 2] = nz
        }
        val ball = DenseCloud(positions, colors, normals)
        assertNull(RoomBox.of(ball, 0f))
        assertNull(runBlocking { RoomShellBuilder.build(ball, null, 0f) })
        // Nor is a cloud without normals.
        assertNull(RoomBox.of(DenseCloud(positions, colors), 0f))
    }

    @Test
    fun `holes are filled from the nearest seen texel`() {
        val seen = BooleanArray(25).also { it[0] = true; it[24] = true }
        val nearest = TexelFill.nearest(seen, 5, 5)
        assertEquals(0, nearest[1])
        assertEquals(24, nearest[23])
        assertEquals(0, nearest[5])
        assertNotNull(nearest)
    }

    private fun texel(face: ShellFace, x: Float, y: Float): Int {
        val z = RerunSyntheticRoom.MIN.z
        val s = (x - face.origin[0]) * face.u[0] + (y - face.origin[1]) * face.u[1] + (z - face.origin[2]) * face.u[2]
        val t = y - face.origin[1]
        val i = (s / face.texelU).toInt().coerceIn(0, face.columns - 1)
        val j = (t / face.texelV).toInt().coerceIn(0, face.rows - 1)
        return face.argb[(face.rows - 1 - j) * face.columns + i]
    }

    private fun luma(c: Int) = 0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)

    private companion object {
        val cloud: DenseCloud by lazy {
            DenseFusion().apply { TexturedSyntheticRoom.frames().forEach { add(DepthBackProjection.project(it)) } }
                .cloud(minViews = DenseFusion.MIN_VIEWS)
        }
        val shell: RoomShell by lazy {
            val photos = ModelPhotos(
                TexturedSyntheticRoom.PHOTO_LENS,
                TexturedSyntheticRoom.photos().map { shot -> ModelPhoto(shot.pose) { shot } },
            )
            checkNotNull(runBlocking { RoomShellBuilder.build(cloud, photos, RerunSyntheticRoom.MIN.y) }) { "no shell" }
        }
        const val TOLERANCE_M = 0.03f
        const val MAX_YAW_SIN = 0.02f
        const val PRINT_X0 = 0.2f
        const val PRINT_X1 = 1.4f
        const val PRINT_Y0 = 1.1f
        const val PRINT_Y1 = 1.9f
        const val FRAME_M = 0.03f
        const val CHECK_M = 0.05f
        const val MAX_LUMA_ERROR = 45f
        const val MIN_CHECKS = 100
        const val MIN_RIGHT_SHARE = 0.85f
        const val MIN_SEEN = 0.5f
        const val MIN_OBJECT_POINTS = 500
        const val BALL_M = 0.3f
        const val GOLDEN = 2.399963
        const val GREY = 0xFF808080.toInt()
    }
}
