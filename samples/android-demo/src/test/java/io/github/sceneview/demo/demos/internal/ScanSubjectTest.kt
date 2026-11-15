package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** [ScanSubject]: the object a scan went round is boxed; a scan that went round none gets no box. */
class ScanSubjectTest {
    /** A floor of [FLOOR_M] a side with a crate standing at ([CRATE_X], [CRATE_Z]), sampled finer than a voxel. */
    private val cloud: DenseCloud by lazy {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        fun rect(origin: Vec3, u: Vec3, v: Vec3, normal: Vec3) {
            val nu = (u.length() / STEP).toInt()
            val nv = (v.length() / STEP).toInt()
            for (a in 0..nu) for (b in 0..nv) {
                val p = origin + u * (a.toFloat() / nu) + v * (b.toFloat() / nv)
                positions += listOf(p.x, p.y, p.z)
                normals += listOf(normal.x, normal.y, normal.z)
            }
        }
        rect(Vec3(-FLOOR_M / 2, 0f, -FLOOR_M / 2), Vec3(FLOOR_M, 0f, 0f), Vec3(0f, 0f, FLOOR_M), Vec3.Up)
        val corner = Vec3(CRATE_X - LENGTH / 2, 0f, CRATE_Z - DEPTH / 2)
        val up = Vec3(0f, HEIGHT, 0f)
        rect(corner + up, Vec3(LENGTH, 0f, 0f), Vec3(0f, 0f, DEPTH), Vec3.Up)
        rect(corner, Vec3(LENGTH, 0f, 0f), up, Vec3(0f, 0f, -1f))
        rect(corner + Vec3(0f, 0f, DEPTH), Vec3(LENGTH, 0f, 0f), up, Vec3(0f, 0f, 1f))
        rect(corner, Vec3(0f, 0f, DEPTH), up, Vec3(-1f, 0f, 0f))
        rect(corner + Vec3(LENGTH, 0f, 0f), Vec3(0f, 0f, DEPTH), up, Vec3(1f, 0f, 0f))
        DenseCloud(positions.toFloatArray(), IntArray(positions.size / 3), normals.toFloatArray())
    }

    /** [count] looks from a circle [radius] round ([x], [z]) at eye height, each at the point [height] over its centre. */
    private fun round(x: Float, z: Float, height: Float, radius: Float = 1.2f, count: Int = ScanSubject.AIMS) = List(count) { i ->
        val angle = 2.0 * PI * i / count
        val eye = Vec3(x + radius * cos(angle).toFloat(), EYE_M, z + radius * sin(angle).toFloat())
        ScanSubject.Aim(eye, (Vec3(x, height, z) - eye).normalized())
    }

    @Test
    fun `the crate the phone went round is the scan's object, with its three sides`() {
        val box = ScanSubject.of(cloud, VOXEL, round(CRATE_X, CRATE_Z, HEIGHT / 2))
        assertNotNull("no object for a crate looked at all the way round", box)
        assertEquals(LENGTH, maxOf(box!!.length, box.depth), 2 * VOXEL)
        assertEquals(DEPTH, minOf(box.length, box.depth), 2 * VOXEL)
        assertEquals(HEIGHT, box.height, 2 * VOXEL)
        assertEquals(CRATE_X, box.centerX, 2 * VOXEL)
        assertEquals(CRATE_Z, box.centerZ, 2 * VOXEL)
    }

    @Test
    fun `the result is framed on the object and a little of the floor round it`() {
        val box = ScanBox(centerX = 1f, centerZ = 2f, baseY = 0.1f, yaw = 0f, length = 0.8f, depth = 0.4f, height = 0.6f)
        val frame = ScanSubject.frame(box)
        val m = ScanSubject.FRAME_MARGIN_M
        val expected = floatArrayOf(0.6f - m, 0.1f, 1.8f - m, 1.4f + m, 0.7f + m, 2.2f + m)
        expected.forEachIndexed { i, e -> assertEquals("bound $i", e, frame.local[i], 1e-4f) }
        assertEquals(0f, frame.yawDegrees, 0f)
    }

    @Test
    fun `a box standing askew is framed square to its own sides, not in one as wide as its diagonal`() {
        val box = ScanBox(centerX = 1f, centerZ = 2f, baseY = 0f, yaw = (PI / 4).toFloat(), length = 0.8f, depth = 0.4f, height = 0.6f)
        val frame = ScanSubject.frame(box)
        val m = ScanSubject.FRAME_MARGIN_M
        assertEquals(45f, frame.yawDegrees, 1e-3f)
        assertEquals(0.8f + 2 * m, frame.local[3] - frame.local[0], 1e-4f)
        assertEquals(0.4f + 2 * m, frame.local[5] - frame.local[2], 1e-4f)
        val (x, z) = frame.worldCentre
        assertEquals(1f, x, 1e-4f)
        assertEquals(2f, z, 1e-4f)
    }

    @Test
    fun `it opens on the side of the box nearest the one the scan opens on`() {
        val box = ScanBox(1f, 2f, 0f, yaw = Math.toRadians(30.0).toFloat(), length = 0.8f, depth = 0.4f, height = 0.6f)
        // A scan opened on its 270 side in a frame turned 20 degrees: 250 in the world, 280 in the box's own.
        val scan = ScanResultFrame(20f, floatArrayOf(-1f, 0f, -1f, 1f, 1f, 1f), 270f, -1f)
        val frame = ScanSubject.frame(box, scan)
        assertEquals(270f, frame.frontDegrees, 0f)
        assertEquals(-1f, frame.turn, 0f)
    }

    @Test
    fun `a scan that looked at the floor all the way has no object`() {
        // Round a bare spot of the floor, the crate never in the middle of the picture.
        assertNull(ScanSubject.of(cloud, VOXEL, round(-1f, -1f, 0f, radius = 0.5f)))
    }

    @Test
    fun `a few looks at the crate among many elsewhere are not its scan`() {
        val few = round(CRATE_X, CRATE_Z, HEIGHT / 2).take(ScanSubject.AIMS / 4)
        val elsewhere = round(-1f, -1f, 0f, radius = 0.5f).drop(ScanSubject.AIMS / 4)
        assertNull(ScanSubject.of(cloud, VOXEL, few + elsewhere))
    }

    @Test
    fun `no pose kept, or no point, is no object`() {
        assertNull(ScanSubject.of(cloud, VOXEL, emptyList()))
        assertNull(ScanSubject.of(DenseCloud(FloatArray(0), IntArray(0)), VOXEL, round(CRATE_X, CRATE_Z, HEIGHT / 2)))
        assertEquals(0, ScanSubject.aims(ArDebugTrace()).size)
    }

    private companion object {
        const val VOXEL = DenseFusion.VOXEL_M
        const val STEP = 0.012f
        const val FLOOR_M = 4f
        const val CRATE_X = 0.5f
        const val CRATE_Z = 0.3f
        const val LENGTH = 0.8f
        const val DEPTH = 0.5f
        const val HEIGHT = 0.6f
        const val EYE_M = 1.4f
    }
}
