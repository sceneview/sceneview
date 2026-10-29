package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.ScanPick
import io.github.sceneview.demo.demos.internal.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The geometry of recognition with synthetic detections: the upright box fitted round points, one
 * object seen from several keyframes fused into one, and a detection lifted onto what a ray picks.
 */
class SceneFusionTest {
    // ── The upright box ──────────────────────────────────────────────────────

    @Test
    fun `a turned slab of points gets its own box back`() {
        val yaw = (30.0 * PI / 180).toFloat()
        val points = slab(centerX = 0.4f, centerZ = -0.6f, yaw = yaw, length = 1.2f, depth = 0.6f, height = 0.8f)
        val box = FootprintFit.fit(points.cloud, points.all)!!
        val (long, short) = listOf(box.length, box.depth).sortedDescending()
        assertEquals(1.2f, long, STEP)
        assertEquals(0.6f, short, STEP)
        assertEquals(0.8f, box.height, STEP)
        assertEquals(0.4f, box.centerX, STEP)
        assertEquals(-0.6f, box.centerZ, STEP)
        assertEquals(0f, box.baseY, STEP)
        // The long side along the slab's own: yaw, or the same line turned half a turn.
        val along = if (box.length >= box.depth) box.yaw else box.yaw + (PI / 2).toFloat()
        assertTrue("turned by ${along * 180 / PI}°", abs(sin(along - yaw)) < 0.05f)
    }

    @Test
    fun `a box fitted with a floor under it reaches down to that floor`() {
        val points = slab(0f, 0f, 0f, 0.5f, 0.5f, 0.4f, baseY = 0.3f)
        val box = FootprintFit.fit(points.cloud, points.all, baseY = 0f)!!
        assertEquals(0f, box.baseY, 1e-6f)
        assertEquals(0.7f, box.height, STEP)
    }

    @Test
    fun `a chair a metre or two off the origin on a diagonal is boxed square, not turned`() {
        // The session's origin is wherever the phone started, so furniture 1–2 m away along a
        // diagonal is the common case: the box must not turn with where the chair stands.
        val diagonal = listOf(1f to -1f, -1f to 1f, 1.2f to 1.2f, -1.2f to -1.2f, 1.4f to 1.4f, -1.4f to -1.4f)
        val fits = diagonal.map { (x, z) ->
            val points = slab(x, z, yaw = 0f, length = 0.5f, depth = 0.5f, height = 0.9f)
            val box = FootprintFit.fit(points.cloud, points.all)!!
            val truth = ScanBox(x, z, 0f, 0f, 0.5f, 0.5f, 0.9f)
            Triple(x to z, quarterTurnOff(box.yaw), SceneFusion.footprintIou(box, truth))
        }
        val report = fits.joinToString("; ") { (at, turned, iou) ->
            "(%.1f, %.1f): turned %.1f°, IoU %.2f".format(at.first, at.second, turned, iou)
        }
        println("diagonal chairs: $report")
        assertTrue(report, fits.all { (_, turned, iou) -> turned <= MAX_TURN_DEG && iou >= MIN_SQUARE_IOU })
    }

    @Test
    fun `footprints overlap as their floor areas do`() {
        val a = TruthDetector.box(0f, 0f, 1f, 1f, 1f)
        assertEquals(1f, SceneFusion.footprintIou(a, a), 0.02f)
        assertEquals(0f, SceneFusion.footprintIou(a, TruthDetector.box(2f, 2f, 3f, 3f, 1f)), 0f)
        assertEquals(1f / 3f, SceneFusion.footprintIou(a, TruthDetector.box(0.5f, 0f, 1.5f, 1f, 1f)), 0.03f)
        assertTrue(SceneFusion.inside(a, 0.5f, 0.5f))
        assertTrue(!SceneFusion.inside(a, 1.5f, 0.5f))
    }

    // ── Several views, one object ────────────────────────────────────────────

    @Test
    fun `a chair seen from three keyframes and a table from two are two objects`() {
        val room = Room()
        val chair = room.add(slab(-1f, 0f, 0.2f, 0.5f, 0.5f, 0.9f))
        val table = room.add(slab(1f, 0.5f, 0f, 1.2f, 0.8f, 0.75f))
        val cloud = room.cloud()
        val observations = listOf(
            // Each view grows a slightly different region of the same chair; one names it a sofa.
            room.observe(chair, "chair", 0.8f, view = 0, keep = 0..90),
            room.observe(chair, "chair", 0.7f, view = 1, keep = 10..100),
            room.observe(chair, "couch", 0.5f, view = 2, keep = 0..80),
            room.observe(table, "dining table", 0.6f, view = 1, keep = 0..100),
            room.observe(table, "dining table", 0.5f, view = 3, keep = 20..100),
        )
        val items = SceneFusion.fuse(observations, cloud)
        assertEquals(listOf(SceneLabel.Chair, SceneLabel.Table), items.map { it.label }.sortedBy { it.ordinal })
        val fusedChair = items.single { it.label == SceneLabel.Chair }
        assertEquals(3, fusedChair.views)
        assertEquals(0.5f, maxOf(fusedChair.box.length, fusedChair.box.depth), 3 * STEP)
        assertEquals(0.9f, fusedChair.box.height, 2 * STEP)
        assertEquals(-1f, fusedChair.box.centerX, 2 * STEP)
        assertEquals(2, items.single { it.label == SceneLabel.Table }.views)
        // Three views are surer than two; both under the views' mean score.
        assertTrue(fusedChair.confidence > items.single { it.label == SceneLabel.Table }.confidence)
        assertTrue(fusedChair.confidence < 0.67f)
    }

    @Test
    fun `one faint view is not enough, one sure view is`() {
        val room = Room()
        val plant = room.add(slab(0f, 0f, 0f, 0.3f, 0.3f, 0.6f))
        val cloud = room.cloud()
        assertTrue(SceneFusion.fuse(listOf(room.observe(plant, "potted plant", 0.4f, view = 0)), cloud).isEmpty())
        val sure = SceneFusion.fuse(listOf(room.observe(plant, "potted plant", 0.9f, view = 0)), cloud)
        assertEquals(SceneLabel.Plant, sure.single().label)
        assertEquals(1, sure.single().views)
    }

    @Test
    fun `two names on one footprint make one object, named by the surer votes`() {
        val room = Room()
        val low = room.add(slab(0f, 0f, 0f, 1f, 1f, 0.45f))
        val cloud = room.cloud()
        // Two disjoint halves of one bed's surfels, each seen twice under two names: one footprint.
        val items = SceneFusion.fuse(
            listOf(
                room.observe(low, "bed", 0.9f, view = 0, keep = 0..49),
                room.observe(low, "bed", 0.9f, view = 1, keep = 0..49),
                room.observe(low, "couch", 0.5f, view = 2, keep = 50..100),
                room.observe(low, "couch", 0.5f, view = 3, keep = 50..100),
            ),
            cloud,
        )
        assertEquals(listOf(SceneLabel.Bed), items.map { it.label })
    }

    // ── From a detection to the cloud ────────────────────────────────────────

    @Test
    fun `a camera projects back what its rays go through`() {
        val camera = SceneCamera(DebugPose(0.3f, 1.4f, 0.2f, 0.1f, 0.3f, 0f, 0.95f).unit(), 0.64f, 0.36f)
        for ((fx, fy) in listOf(0.5f to 0.5f, 0.1f to 0.9f, 0.8f to 0.2f)) {
            val p = camera.eye + camera.ray(fx, fy) * 2.5f
            val back = camera.project(p)!!
            assertEquals(fx, back[0], 1e-4f)
            assertEquals(fy, back[1], 1e-4f)
        }
        assertNull("behind the camera", camera.project(camera.eye - camera.ray(0.5f, 0.5f)))
    }

    @Test
    fun `a detection is lifted onto the object whose outline matches it, not onto the wall behind`() {
        val pose = DebugPose(0f, 1f, 2f, 0f, 0f, 0f, 1f) // looking down -z
        val camera = SceneCamera(pose, 0.64f, 0.36f)
        val chairBox = TruthDetector.box(-0.25f, -0.25f, 0.25f, 0.25f, 0.9f)
        val rect = camera.rectOf(chairBox)!!
        val detection = Detection2D("chair", 0.8f, rect[0], rect[1], rect[2], rect[3])
        val wall = ScanBox(0f, -2f, 0f, 0f, 4f, 0.1f, 2.6f)
        // The middle ray goes past the chair to the wall; the others hit the chair.
        var calls = 0
        val lifted = SceneLift.lift(detection, view = 7, camera = camera) { _, _ ->
            calls++
            if (calls == 1) ScanPick.Found(wall, intArrayOf(1, 2, 3)) else ScanPick.Found(chairBox, intArrayOf(4, 5, 6))
        }
        assertNotNull(lifted)
        assertEquals(SceneLabel.Chair, lifted!!.label)
        assertEquals(7, lifted.view)
        assertTrue(lifted.surfels.contentEquals(intArrayOf(4, 5, 6)))
        assertTrue("IoU ${lifted.iou}", lifted.iou > 0.95f)
        // Nothing the rays find matches: no observation.
        assertNull(SceneLift.lift(detection, 0, camera) { _, _ -> ScanPick.Found(wall, intArrayOf(1)) })
        assertNull(SceneLift.lift(Detection2D("person", 0.9f, 0.4f, 0.4f, 0.6f, 0.6f), 0, camera) { _, _ -> ScanPick.Empty })
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private class Slab(val cloud: DenseCloud, val all: IntArray)

    /** Points every [STEP] over the five visible faces of an upright box, as a cloud of its own. */
    @Suppress("LongParameterList")
    private fun slab(
        centerX: Float, centerZ: Float, yaw: Float, length: Float, depth: Float, height: Float, baseY: Float = 0f,
    ): Slab {
        val points = slabPoints(centerX, centerZ, yaw, length, depth, height, baseY)
        val positions = FloatArray(points.size * 3)
        points.forEachIndexed { i, p ->
            positions[i * 3] = p.x
            positions[i * 3 + 1] = p.y
            positions[i * 3 + 2] = p.z
        }
        return Slab(DenseCloud(positions, IntArray(points.size) { -1 }), IntArray(points.size) { it })
    }

    @Suppress("LongParameterList")
    private fun slabPoints(cx: Float, cz: Float, yaw: Float, length: Float, depth: Float, height: Float, baseY: Float): List<Vec3> {
        val c = cos(yaw)
        val s = sin(yaw)
        fun world(u: Float, v: Float, y: Float) = Vec3(cx + u * c - v * s, baseY + y, cz + u * s + v * c)
        val out = ArrayList<Vec3>()
        fun range(size: Float) = (0..(size / STEP).toInt()).map { it * STEP - size / 2 }
        val ys = (0..(height / STEP).toInt()).map { it * STEP }
        for (u in range(length)) for (v in range(depth)) out += world(u, v, height)
        for (y in ys) {
            for (u in range(length)) {
                out += world(u, -depth / 2, y)
                out += world(u, depth / 2, y)
            }
            for (v in range(depth)) {
                out += world(-length / 2, v, y)
                out += world(length / 2, v, y)
            }
        }
        return out
    }

    /** Several slabs in one cloud, each remembered by the range of its surfels. */
    private class Room {
        private val parts = ArrayList<Slab>()
        private val offsets = ArrayList<Int>()
        private var count = 0

        fun add(slab: Slab): Int {
            parts += slab
            offsets += count
            count += slab.cloud.count
            return parts.size - 1
        }

        fun cloud(): DenseCloud {
            val positions = FloatArray(count * 3)
            parts.forEachIndexed { i, part -> part.cloud.positions.copyInto(positions, offsets[i] * 3) }
            return DenseCloud(positions, IntArray(count) { -1 })
        }

        /**
         * What one keyframe made of part [index]: the surfels in the [keep] percent range of it,
         * their fitted box, named [name] by the detector with [score].
         */
        fun observe(index: Int, name: String, score: Float, view: Int, keep: IntRange = 0..100): SceneObservation {
            val part = parts[index]
            val n = part.cloud.count
            val local = (n * keep.first / 100 until minOf(n, n * keep.last / 100)).toList().toIntArray()
            val box = FootprintFit.fit(part.cloud, local)!!
            return SceneObservation(SceneLabel.ofDetector(name)!!, score, view, box, IntArray(local.size) { offsets[index] + local[it] }, 0.8f)
        }
    }

    private fun DebugPose.unit(): DebugPose {
        val n = kotlin.math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw)
        return DebugPose(x, y, z, qx / n, qy / n, qz / n, qw / n)
    }

    /** How far [yaw] is from the world's axes, in degrees, a quarter turn being the same square. */
    private fun quarterTurnOff(yaw: Float): Float {
        val deg = (yaw * 180 / PI).toFloat().mod(90f)
        return minOf(deg, 90f - deg)
    }

    private companion object {
        const val STEP = 0.02f

        /** A chair's box may turn this much off its sides, one degree past the fit's angle step… */
        const val MAX_TURN_DEG = 2f

        /** …and must cover this much of the chair's footprint (a 0.5 m square 2° off still covers 0.97). */
        const val MIN_SQUARE_IOU = 0.85f
    }
}
