package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * [ScanResultFraming]: the scan result's view, square to the walls, from outside, on the model's
 * own box (Scan redesign, PR 1).
 */
class ScanResultFramingTest {

    /** Flat positions and normals, built in the turned frame `(a, y, b)` and turned into the world. */
    private class Cloud(private val yawDegrees: Float) {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        private val c = cos(Math.toRadians(yawDegrees.toDouble())).toFloat()
        private val s = sin(Math.toRadians(yawDegrees.toDouble())).toFloat()

        fun add(a: Float, y: Float, b: Float, na: Float, ny: Float, nb: Float) {
            positions += listOf(a * c - b * s, y, a * s + b * c)
            normals += listOf(na * c - nb * s, ny, na * s + nb * c)
        }

        /** A wall at constant [at] on axis a ([onA]) or b, spanning the other axis over [from]..[to]. */
        fun wall(onA: Boolean, at: Float, from: Float, to: Float, height: Float, inward: Float, share: Float = 1f) {
            var u = from
            while (u <= to * share + from * (1f - share) + 1e-4f) {
                var y = 0f
                while (y <= height + 1e-4f) {
                    if (onA) add(at, y, u, inward, 0f, 0f) else add(u, y, at, 0f, 0f, inward)
                    y += STEP
                }
                u += STEP
            }
        }

        fun floor(halfA: Float, halfB: Float) {
            var a = -halfA
            while (a <= halfA + 1e-4f) {
                var b = -halfB
                while (b <= halfB + 1e-4f) {
                    add(a, 0f, b, 0f, 1f, 0f)
                    b += STEP
                }
                a += STEP
            }
        }

        fun frame(): ScanResultFrame? =
            ScanResultFraming.of(positions.toFloatArray(), normals.toFloatArray())
    }

    /** A 4 m by 3 m room, 2.5 m high, its walls facing in, turned [yaw] in the world. */
    private fun room(yaw: Float, nearShare: Float = 1f, leftShare: Float = 1f) = Cloud(yaw).apply {
        floor(HALF_A, HALF_B)
        wall(onA = false, at = -HALF_B, from = -HALF_A, to = HALF_A, height = HEIGHT, inward = 1f)
        wall(onA = false, at = HALF_B, from = -HALF_A, to = HALF_A, height = HEIGHT, inward = -1f, share = nearShare)
        wall(onA = true, at = -HALF_A, from = -HALF_B, to = HALF_B, height = HEIGHT, inward = 1f, share = leftShare)
        wall(onA = true, at = HALF_A, from = -HALF_B, to = HALF_B, height = HEIGHT, inward = -1f)
    }

    @Test
    fun `a room turned in the world is read square to its walls`() {
        val frame = req(room(yaw = 20f).frame())
        assertEquals(20f, frame.yawDegrees, 0.5f)
        // Its box is the room's, not the world box around it turned 20°.
        assertEquals(-HALF_A, frame.local[0], 0.05f)
        assertEquals(0f, frame.local[1], 0.05f)
        assertEquals(-HALF_B, frame.local[2], 0.05f)
        assertEquals(HALF_A, frame.local[3], 0.05f)
        assertEquals(HEIGHT, frame.local[4], 0.1f)
        assertEquals(HALF_B, frame.local[5], 0.05f)
    }

    @Test
    fun `walls a quarter turn apart say the same way`() {
        // A room turned 70° runs the same way as one turned -20°: its walls are square to both.
        val frame = req(room(yaw = 70f).frame())
        assertEquals(-20f, frame.yawDegrees, 0.5f)
        assertEquals(HALF_B, frame.local[3], 0.05f)
        assertEquals(HALF_A, frame.local[5], 0.05f)
    }

    @Test
    fun `pieces seen through a window do not shrink the room`() {
        val cloud = room(yaw = 0f)
        repeat(cloud.positions.size / 3 / 200) { cloud.add(9f, 1f, 9f, 1f, 0f, 0f) }
        val frame = req(cloud.frame())
        assertEquals(HALF_A, frame.local[3], 0.05f)
        assertEquals(HALF_B, frame.local[5], 0.05f)
    }

    @Test
    fun `the view opens on the room's fullest wall, from outside the missing one`() {
        // The near wall (+b) half scanned: the eye stands on its side, the full wall at the back.
        val frame = req(room(yaw = 0f, nearShare = 0.4f).frame())
        assertEquals(0f, frame.frontDegrees, 0f)
        // The left wall (-a) half scanned too: the three-quarter turns towards it, the full one at the back.
        val turned = req(room(yaw = 0f, nearShare = 0.4f, leftShare = 0.4f).frame())
        assertEquals(-1f, turned.turn, 0f)
    }

    @Test
    fun `an object opens on the side it was scanned from`() {
        // A 30 cm box, its faces out, its +a side scanned in full and the others in part.
        val cloud = Cloud(yawDegrees = 10f).apply {
            wall(onA = true, at = 0.15f, from = -0.15f, to = 0.15f, height = 0.3f, inward = 1f)
            wall(onA = true, at = -0.15f, from = -0.15f, to = 0.15f, height = 0.3f, inward = -1f, share = 0.3f)
            wall(onA = false, at = 0.15f, from = -0.15f, to = 0.15f, height = 0.3f, inward = 1f, share = 0.6f)
            wall(onA = false, at = -0.15f, from = -0.15f, to = 0.15f, height = 0.3f, inward = -1f, share = 0.3f)
        }
        val frame = req(cloud.frame())
        assertEquals(90f, frame.frontDegrees, 0f)
        // Towards +b, the better of its two neighbours.
        assertEquals(-1f, frame.turn, 0f)
    }

    @Test
    fun `a long object scanned along both its long sides opens on the end it was scanned from`() {
        // A bench 60 cm along a, 20 cm across b: both long sides scanned in full, the +a end too,
        // the -a end never. The long sides hold the most surface, but each faces away as much as
        // the other faces in: only the +a end says where the scan was taken.
        val cloud = Cloud(yawDegrees = 0f).apply {
            wall(onA = false, at = 0.1f, from = -0.3f, to = 0.3f, height = 0.3f, inward = 1f)
            wall(onA = false, at = -0.1f, from = -0.3f, to = 0.3f, height = 0.3f, inward = -1f)
            wall(onA = true, at = 0.3f, from = -0.1f, to = 0.1f, height = 0.3f, inward = 1f)
        }
        val frame = req(cloud.frame())
        assertEquals(90f, frame.frontDegrees, 0f)
    }

    @Test
    fun `an object is framed on its own box, not widened to a room`() {
        val box = ScanResultFrame(0f, floatArrayOf(-0.15f, 0f, -0.15f, 0.15f, 0.3f, 0.15f), 0f, 1f)
        val pose = box.pose(ScanResultFraming.OBJECT_ELEVATION, ScanResultFraming.THREE_QUARTER, FOV, ASPECT, FramingInsets.None)
        // A 30 cm box fills a tall phone's width from about a metre; the 0.9 m room minimum would push it past 3 m.
        assertTrue("distance ${pose.distance}", pose.distance < 1.5f)
        // The box the old framing widened it to: 0.9 m across, 0.5 m high.
        val roomSized = ArDebugFraming.fit(
            floatArrayOf(-0.45f, -0.1f, -0.45f, 0.45f, 0.4f, 0.45f),
            ScanResultFraming.THREE_QUARTER,
            ScanResultFraming.OBJECT_ELEVATION,
            FOV,
            ASPECT,
        )
        assertTrue(pose.distance < roomSized.distance / 2f)
    }

    @Test
    fun `the world pose shows the turned box the same picture`() {
        val frame = req(room(yaw = 25f, nearShare = 0.4f).frame())
        val local = ArDebugFraming.fit(frame.local, frame.frontDegrees + frame.turn * 28f, 40f, FOV, ASPECT)
        val world = frame.pose(40f, 28f, FOV, ASPECT, FramingInsets.None)
        assertEquals(local.distance, world.distance, 1e-4f)
        assertEquals(local.elevationDegrees, world.elevationDegrees, 1e-4f)
        // The eye, turned with the room, is where the world pose puts it.
        val c = cos(Math.toRadians(25.0)).toFloat()
        val s = sin(Math.toRadians(25.0)).toFloat()
        val eye = CameraRig.eye(local)
        val worldEye = CameraRig.eye(world)
        assertEquals(eye.x * c - eye.z * s, worldEye.x, 1e-3f)
        assertEquals(eye.y, worldEye.y, 1e-3f)
        assertEquals(eye.x * s + eye.z * c, worldEye.z, 1e-3f)
    }

    @Test
    fun `the gestures keep the eye out of the room and above its floor`() {
        val frame = req(room(yaw = 30f).frame())
        val home = frame.pose(40f, 28f, FOV, ASPECT, FramingInsets.None)
        val limits = frame.limits(home)
        assertEquals(home.distance * ArDebugFraming.MAX_ZOOM_OUT, limits.maxDistance, 1e-3f)
        assertEquals(frame.local[1], req(limits.floorY), 0f)
        val bounds = req(limits.bounds)
        // The room's corners, turned into the world, are all inside the gestures' box.
        for (a in listOf(-HALF_A, HALF_A)) {
            for (b in listOf(-HALF_B, HALF_B)) {
                val x = a * cos(Math.toRadians(30.0)).toFloat() - b * sin(Math.toRadians(30.0)).toFloat()
                val z = a * sin(Math.toRadians(30.0)).toFloat() + b * cos(Math.toRadians(30.0)).toFloat()
                assertTrue(x >= bounds[0] - 0.06f && x <= bounds[3] + 0.06f)
                assertTrue(z >= bounds[2] - 0.06f && z <= bounds[5] + 0.06f)
            }
        }
    }

    @Test
    fun `a model's glb is read to the same frame as its vertices`() {
        val cloud = room(yaw = 15f, nearShare = 0.5f)
        val positions = cloud.positions.toFloatArray()
        val normals = cloud.normals.toFloatArray()
        val n = positions.size / 3
        val mesh = RerunMesh(positions, normals, IntArray(n) { 0xFF808080.toInt() }, intArrayOf(0, 1, 2))
        val glb = RerunMeshGlb.write(mesh)
        val read = req(ScanResultFraming.read(glb))
        val direct = req(ScanResultFraming.of(positions, normals))
        assertEquals(direct.yawDegrees, read.yawDegrees, 1e-4f)
        assertEquals(direct.frontDegrees, read.frontDegrees, 0f)
        for (i in 0 until 6) assertEquals(direct.local[i], read.local[i], 1e-4f)
        // Sampled, it still finds the walls.
        val sampled = req(ScanResultFraming.read(glb, maxSamples = n / 7))
        assertEquals(15f, sampled.yawDegrees, 1f)
        assertEquals(HALF_A, sampled.local[3], 0.1f)
    }

    @Test
    fun `anything else is not read, and a model without walls stays unturned`() {
        assertNull(ScanResultFraming.read(ByteArray(64)))
        assertNull(ScanResultFraming.of(FloatArray(0), FloatArray(0)))
        val flat = Cloud(0f).apply { floor(1f, 1f) }
        assertEquals(0f, req(flat.frame()).yawDegrees, 0f)
        val unturned = ScanResultFrame.unturned(floatArrayOf(-1f, 0f, -2f, 1f, 2f, 2f))
        assertEquals(0f, unturned.yawDegrees, 0f)
        assertEquals(-2f, unturned.worldBounds[2], 1e-5f)
    }

    @Test
    fun `an object opens lower than a room, at a three-quarter, a room from above, square on`() {
        assertEquals(ScanResultFraming.OBJECT_ELEVATION, ScanResultFraming.elevation(ScanScope.Object), 0f)
        assertEquals(ScanResultFraming.ROOM_ELEVATION, ScanResultFraming.elevation(ScanScope.Room), 0f)
        assertEquals(ScanResultFraming.ROOM_ELEVATION, ScanResultFraming.elevation(null), 0f)
        assertTrue(ScanResultFraming.ROOM_ELEVATION in 35f..40f)
        assertTrue(ScanResultFraming.OBJECT_ELEVATION < ScanResultFraming.ROOM_ELEVATION)
        assertEquals(ScanResultFraming.THREE_QUARTER, ScanResultFraming.offset(ScanScope.Object), 0f)
        assertEquals(0f, ScanResultFraming.offset(ScanScope.Room), 0f)
    }

    @Test
    fun `a scan with no walls to read is turned along its footprint's long side`() {
        // A garden path, 6 m by 1 m, turned 20° in the world: nothing stands up to say its way.
        val path = Cloud(20f).apply { floor(3f, 0.5f) }
        assertTrue(ScanResultFraming.wallCoherence(path.normals.toFloatArray()) < ScanResultFraming.MIN_WALL_COHERENCE)
        val frame = req(path.frame())
        assertEquals(20f, frame.yawDegrees, 0.5f)
        assertEquals(6f, frame.local[3] - frame.local[0], 0.3f)
        assertEquals(1f, frame.local[5] - frame.local[2], 0.1f)
        // A room's walls still say it, whatever its footprint.
        assertTrue(ScanResultFraming.wallCoherence(room(15f).normals.toFloatArray()) > 0.9f)
    }

    @Test
    fun `a long room opens on the side it fills a tall screen most from, square on`() {
        // 2 m wide, 8 m long, cut at 1.2 m, its walls saying the long side: on a tall screen
        // the long side across is a sliver, seen down its length it fills the width.
        val long = ScanResultFrame(0f, floatArrayOf(-1f, 0f, -4f, 1f, 1.2f, 4f), 90f, 1f)
        val zone = FramingInsets(left = 0.04f, top = 0.14f, right = 0.04f, bottom = 0.22f)
        val area = RerunResultView.area(zone)
        val elevation = ScanResultFraming.ROOM_ELEVATION
        val turned = long.widestSide(elevation, FOV, ASPECT, area)
        assertTrue("down its length: ${turned.frontDegrees}", turned.frontDegrees == 0f || turned.frontDegrees == 180f)
        val across = long.pose(elevation, 0f, FOV, ASPECT, area)
        val down = turned.pose(elevation, 0f, FOV, ASPECT, area)
        assertTrue("closer: ${down.distance} vs ${across.distance}", down.distance < across.distance * ScanResultFrame.SIDE_GAIN)
        // Square on: the eye straight on the side, so the box stands straight on the screen.
        assertEquals(0f, down.azimuthDegrees % 90f, 1e-3f)
        // A room near square keeps the front its walls give.
        val square = ScanResultFrame(0f, floatArrayOf(-2f, 0f, -1.8f, 2f, 1.2f, 1.8f), 0f, 1f)
        assertTrue(square.widestSide(elevation, FOV, ASPECT, area) === square)
    }

    private fun <T : Any> req(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        const val STEP = 0.05f
        const val HALF_A = 2f
        const val HALF_B = 1.5f
        const val HEIGHT = 2.5f
        const val FOV = 45.0
        const val ASPECT = 0.45f
    }
}
