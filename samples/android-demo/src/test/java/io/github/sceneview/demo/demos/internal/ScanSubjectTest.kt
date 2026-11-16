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
    /** A scan drawn by hand: each patch sampled finer than a voxel, with its normal. */
    private class Drawn {
        private val positions = ArrayList<Float>()
        private val normals = ArrayList<Float>()

        fun rect(origin: Vec3, u: Vec3, v: Vec3, normal: Vec3) {
            val nu = (u.length() / STEP).toInt()
            val nv = (v.length() / STEP).toInt()
            for (a in 0..nu) for (b in 0..nv) {
                val p = origin + u * (a.toFloat() / nu) + v * (b.toFloat() / nv)
                positions += listOf(p.x, p.y, p.z)
                normals += listOf(normal.x, normal.y, normal.z)
            }
        }

        fun floor() = rect(Vec3(-FLOOR_M / 2, 0f, -FLOOR_M / 2), Vec3(FLOOR_M, 0f, 0f), Vec3(0f, 0f, FLOOR_M), Vec3.Up)

        /** The top and the four sides of a block [length] by [depth] round ([x], [z]), from [y] up [height]. */
        fun block(x: Float, z: Float, length: Float, depth: Float, height: Float, y: Float = 0f) {
            val corner = Vec3(x - length / 2, y, z - depth / 2)
            val up = Vec3(0f, height, 0f)
            rect(corner + up, Vec3(length, 0f, 0f), Vec3(0f, 0f, depth), Vec3.Up)
            rect(corner, Vec3(length, 0f, 0f), up, Vec3(0f, 0f, -1f))
            rect(corner + Vec3(0f, 0f, depth), Vec3(length, 0f, 0f), up, Vec3(0f, 0f, 1f))
            rect(corner, Vec3(0f, 0f, depth), up, Vec3(-1f, 0f, 0f))
            rect(corner + Vec3(length, 0f, 0f), Vec3(0f, 0f, depth), up, Vec3(1f, 0f, 0f))
        }

        fun cloud() = DenseCloud(positions.toFloatArray(), IntArray(positions.size / 3), normals.toFloatArray())
    }

    /** A floor of [FLOOR_M] a side with a crate standing at ([CRATE_X], [CRATE_Z]). */
    private val cloud: DenseCloud by lazy {
        Drawn().apply {
            floor()
            block(CRATE_X, CRATE_Z, LENGTH, DEPTH, HEIGHT)
        }.cloud()
    }

    /** [count] looks from a circle [radius] round ([x], [z]) at eye height, each at the point [height] over its centre. */
    private fun round(x: Float, z: Float, height: Float, radius: Float = 1.2f, count: Int = ScanSubject.AIMS, eyeM: Float = EYE_M) = List(count) { i ->
        val angle = 2.0 * PI * i / count
        val eye = Vec3(x + radius * cos(angle).toFloat(), eyeM, z + radius * sin(angle).toFloat())
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
    fun `a stool the scan saw the seat and the feet of is boxed from the floor up`() {
        val stool = Drawn().apply {
            floor()
            // Four feet, and a seat on legs too thin for a scan to hold.
            for (dx in listOf(-FOOT_OUT, FOOT_OUT)) for (dz in listOf(-FOOT_OUT, FOOT_OUT)) {
                block(CRATE_X + dx, CRATE_Z + dz, FOOT, FOOT, FOOT_HEIGHT)
            }
            block(CRATE_X, CRATE_Z, SEAT, SEAT, SEAT_THICK, y = SEAT_TOP - SEAT_THICK)
            rect(Vec3(CRATE_X - SEAT / 2, SEAT_TOP - SEAT_THICK, CRATE_Z - SEAT / 2), Vec3(SEAT, 0f, 0f), Vec3(0f, 0f, SEAT), Vec3(0f, -1f, 0f))
        }.cloud()
        val box = ScanSubject.of(stool, VOXEL, round(CRATE_X, CRATE_Z, SEAT_TOP))
        assertNotNull("no object for a stool looked at all the way round", box)
        assertEquals("it stands on the floor, not where its seat hangs", 0f, box!!.baseY, VOXEL)
        assertEquals(SEAT_TOP, box.height, VOXEL)
        assertEquals(SEAT, box.length, 2 * VOXEL)
        assertEquals(SEAT, box.depth, 2 * VOXEL)
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
    fun `a pair of shoes on a floor drawn as two sheets is boxed, too small and too low for a tap`() {
        val shoes = Drawn().apply {
            // A phone's depth draws the floor as a sheet a centimetre or two thick.
            floor()
            rect(Vec3(-FLOOR_M / 2, 0.015f, -FLOOR_M / 2), Vec3(FLOOR_M, 0f, 0f), Vec3(0f, 0f, FLOOR_M), Vec3.Up)
            block(CRATE_X, CRATE_Z, SHOES_LENGTH, SHOES_WIDTH, SHOES_HEIGHT)
        }.cloud()
        // Under a finger it is nothing: smaller than any piece of furniture.
        val objects = ScanObjects.of(shoes, VOXEL)
        val eye = Vec3(CRATE_X + 0.25f, 0.4f, CRATE_Z)
        assertEquals(ScanPick.Empty, objects.pick(eye, (Vec3(CRATE_X, SHOES_HEIGHT / 2, CRATE_Z) - eye).normalized()))

        // Gone round at arm's length, it is the scan's object.
        val box = ScanSubject.of(shoes, VOXEL, round(CRATE_X, CRATE_Z, SHOES_HEIGHT / 2, radius = 0.25f, eyeM = 0.4f))
        assertNotNull("no object for shoes looked at all the way round", box)
        assertEquals(SHOES_LENGTH, box!!.length, 2.5f * VOXEL)
        assertEquals(SHOES_WIDTH, box.depth, 2.5f * VOXEL)
        assertEquals(SHOES_HEIGHT, box.height, 2 * VOXEL)
        assertEquals(CRATE_X, box.centerX, 2 * VOXEL)
        assertEquals(CRATE_Z, box.centerZ, 2 * VOXEL)
    }

    /** A floor as a phone's depth draws it: a sheet a centimetre or two thick. */
    private fun Drawn.roughFloor() {
        floor()
        rect(Vec3(-FLOOR_M / 2, 0.015f, -FLOOR_M / 2), Vec3(FLOOR_M, 0f, 0f), Vec3(0f, 0f, FLOOR_M), Vec3.Up)
    }

    @Test
    fun `the toes of a pair of shoes, lower than their heels, are in its box`() {
        val shoes = Drawn().apply {
            roughFloor()
            val heels = SHOES_LENGTH - TOES
            block(CRATE_X - TOES / 2, CRATE_Z, heels, SHOES_WIDTH, SHOES_HEIGHT)
            block(CRATE_X + heels / 2, CRATE_Z, TOES, SHOES_WIDTH, TOES_HEIGHT)
        }.cloud()
        val box = ScanSubject.of(shoes, VOXEL, round(CRATE_X, CRATE_Z, SHOES_HEIGHT / 2, radius = 0.25f, eyeM = 0.4f))
        assertNotNull("no object for shoes looked at all the way round", box)
        assertEquals("heels and toes", SHOES_LENGTH, box!!.length, 2.5f * VOXEL)
        assertEquals(SHOES_WIDTH, box.depth, 2.5f * VOXEL)
    }

    @Test
    fun `a mat a pair of shoes stands on is not in its box, however far it stands out`() {
        for (mat in listOf(SMALL_MAT, MAT)) {
            val shoes = Drawn().apply {
                roughFloor()
                block(CRATE_X, CRATE_Z, mat, mat * 0.8f, TOES_HEIGHT)
                block(CRATE_X, CRATE_Z, SHOES_LENGTH, SHOES_WIDTH, SHOES_HEIGHT, y = TOES_HEIGHT)
            }.cloud()
            val box = ScanSubject.of(shoes, VOXEL, round(CRATE_X, CRATE_Z, SHOES_HEIGHT / 2, radius = 0.25f, eyeM = 0.4f))
            assertNotNull("no object for shoes on a mat of $mat m looked at all the way round", box)
            assertEquals("the shoes, not a mat of $mat m", SHOES_LENGTH, box!!.length, 2.5f * VOXEL)
            assertEquals(SHOES_WIDTH, box.depth, 2.5f * VOXEL)
        }
    }

    @Test
    fun `shoes whose looks meet at the floor's own height are boxed`() {
        val shoes = Drawn().apply {
            roughFloor()
            block(CRATE_X, CRATE_Z, SHOES_LENGTH, SHOES_WIDTH, SHOES_HEIGHT)
        }.cloud()
        val box = ScanSubject.of(shoes, VOXEL, round(CRATE_X, CRATE_Z, -0.02f, radius = 0.25f, eyeM = 0.4f))
        assertNotNull("no object for shoes whose looks meet at the floor", box)
        assertEquals(SHOES_LENGTH, box!!.length, 2.5f * VOXEL)
    }

    @Test
    fun `a box on a table is measured from the table's top, not as the table`() {
        val scene = Drawn().apply {
            floor()
            block(CRATE_X, CRATE_Z, 1.2f, 0.8f, TABLE_TOP)
            block(CRATE_X + 0.1f, CRATE_Z, SMALL, SMALL, SMALL_HEIGHT, y = TABLE_TOP)
        }.cloud()
        val box = ScanSubject.of(scene, VOXEL, round(CRATE_X + 0.1f, CRATE_Z, TABLE_TOP + SMALL_HEIGHT / 2, radius = 0.3f, eyeM = 1.1f))
        assertNotNull("no object for a box on a table looked at all the way round", box)
        assertEquals(SMALL, box!!.length, 2.5f * VOXEL)
        assertEquals(SMALL, box.depth, 2.5f * VOXEL)
        assertEquals("from the table's top", TABLE_TOP, box.baseY, VOXEL)
        assertEquals(SMALL_HEIGHT, box.height, 2 * VOXEL)
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
        const val SEAT = 0.5f
        const val SEAT_TOP = 0.48f
        const val SEAT_THICK = 0.08f
        const val FOOT = 0.06f
        const val FOOT_HEIGHT = 0.1f
        const val FOOT_OUT = 0.18f
        const val SHOES_LENGTH = 0.28f
        const val SHOES_WIDTH = 0.2f
        const val SHOES_HEIGHT = 0.11f
        const val TOES = 0.08f
        const val TOES_HEIGHT = 0.055f
        const val MAT = 0.6f
        const val SMALL_MAT = 0.44f
        const val TABLE_TOP = 0.75f
        const val SMALL = 0.12f
        const val SMALL_HEIGHT = 0.25f
    }
}
