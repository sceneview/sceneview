package io.github.sceneview.demo

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * [ContinuousCameraManipulator] is what stands between a demo's camera sources and `SceneView`:
 * whatever the sources do — a swap, a rebuild, a `snapTo` — the pose drawn must not step.
 */
class ContinuousCameraManipulatorTest {

    /** A camera on a circle of [radius] around the origin, turning at [degreesPerSecond]. */
    private class Turntable(
        var yawDegrees: Float,
        var radius: Float = 2f,
        var height: Float = 0.5f,
        val degreesPerSecond: Float = 0f,
    ) : CameraGestureDetector.CameraManipulator {
        var viewport: Pair<Int, Int>? = null
        var grabs = 0

        override fun setViewport(width: Int, height: Int) {
            viewport = width to height
        }

        override fun getTransform(): Transform {
            val yaw = Math.toRadians(yawDegrees.toDouble())
            val eye = Position((radius * sin(yaw)).toFloat(), height, (radius * cos(yaw)).toFloat())
            return lookAt(eye, Position(0f, 0f, 0f), Float3(0f, 1f, 0f))
        }

        override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
            grabs++
        }

        override fun grabUpdate(x: Int, y: Int) = Unit
        override fun grabEnd() = Unit
        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) = Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) {
            yawDegrees += degreesPerSecond * deltaTime
        }
    }

    private var nanos = 1_000_000_000L
    private var eased = true

    private fun manipulator(blendMillis: Long = 700L) = ContinuousCameraManipulator(
        blendMillis = blendMillis,
        eased = { eased },
        nanoTime = { nanos },
    )

    /** One 60 fps frame: the clock, the source's own update, then the pose `SceneView` draws. */
    private fun ContinuousCameraManipulator.frame(): Transform {
        nanos += FRAME_NANOS
        update(FRAME_SECONDS)
        return getTransform()
    }

    private fun distance(a: Transform, b: Transform) = length(a.position - b.position)

    private fun maxElementDelta(a: Transform, b: Transform): Float =
        a.toFloatArray().zip(b.toFloatArray()).maxOf { (x, y) -> abs(x - y) }

    // ── Pass-through ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the first source is shown as it is`() {
        val source = Turntable(yawDegrees = 30f)
        val camera = manipulator().apply { drive(source) }

        assertEquals(0f, maxElementDelta(source.getTransform(), camera.frame()), 0f)
        assertFalse(camera.isEasing)
    }

    @Test
    fun `gestures and the viewport reach the current source, a new one included`() {
        val first = Turntable(yawDegrees = 0f)
        val second = Turntable(yawDegrees = 90f)
        val camera = manipulator().apply { drive(first) }
        camera.setViewport(1080, 2400)
        camera.drive(second)
        camera.grabBegin(10, 10, strafe = false)

        assertEquals(1080 to 2400, first.viewport)
        assertEquals(1080 to 2400, second.viewport)
        assertEquals(0, first.grabs)
        assertEquals(1, second.grabs)
    }

    // ── A new source ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a new source is not a cut - the pose carries on from the one on screen`() {
        val first = Turntable(yawDegrees = 0f)
        val second = Turntable(yawDegrees = 150f, radius = 3.5f, height = 1.2f)
        val camera = manipulator().apply { drive(first) }
        val before = camera.frame()

        camera.drive(second)
        val after = camera.frame()

        assertTrue(camera.isEasing)
        assertTrue("stepped ${distance(before, after)} m", distance(before, after) < 0.01f)
        assertTrue("turned", maxElementDelta(before, after) < 0.01f)
    }

    @Test
    fun `no frame of the ease steps more than a camera move would`() {
        val first = Turntable(yawDegrees = 0f)
        val second = Turntable(yawDegrees = 150f, radius = 3.5f, height = 1.2f)
        val camera = manipulator().apply { drive(first) }
        var previous = camera.frame()
        camera.drive(second)

        var worst = 0f
        var frames = 0
        while (frames == 0 || camera.isEasing) {
            val next = camera.frame()
            worst = maxOf(worst, distance(previous, next))
            previous = next
            frames++
            assertTrue("the ease never ends", frames < 600)
        }

        // 150° at 2–3.5 m is a ~6 m arc; spread over the ease, a frame is a few centimetres of it.
        assertTrue("worst step $worst m", worst < 0.25f)
        assertEquals(0f, maxElementDelta(second.getTransform(), previous), 1e-5f)
    }

    @Test
    fun `the ease goes round the subject, not through it`() {
        val first = Turntable(yawDegrees = 0f)
        val second = Turntable(yawDegrees = 180f)
        val camera = manipulator().apply { drive(first) }
        camera.frame()
        camera.drive(second)

        var closest = Float.MAX_VALUE
        var frames = 0
        do {
            closest = minOf(closest, length(camera.frame().position))
            frames++
        } while (camera.isEasing && frames < 600)

        assertTrue("came within $closest m of the pivot", closest > 1.9f)
    }

    @Test
    fun `a source that already stands on the pose on screen adds no ease`() {
        val first = Turntable(yawDegrees = 40f)
        val seeded = Turntable(yawDegrees = 40f)
        val camera = manipulator().apply { drive(first) }
        camera.frame()

        camera.drive(seeded)
        camera.frame()

        assertFalse(camera.isEasing)
    }

    @Test
    fun `a change nobody can see is taken at once`() {
        val first = Turntable(yawDegrees = 0f)
        val second = Turntable(yawDegrees = 120f, radius = 4f)
        val camera = manipulator().apply { drive(first) }
        camera.frame()

        camera.drive(second, cut = true)

        assertEquals(0f, maxElementDelta(second.getTransform(), camera.frame()), 0f)
        assertFalse(camera.isEasing)
    }

    @Test
    fun `QA mode keeps every change instantaneous`() {
        eased = false
        val first = Turntable(yawDegrees = 0f)
        val second = Turntable(yawDegrees = 120f)
        val camera = manipulator().apply { drive(first) }
        camera.frame()

        camera.drive(second)

        assertEquals(0f, maxElementDelta(second.getTransform(), camera.frame()), 0f)
    }

    // ── A script that snaps its own pose ─────────────────────────────────────────────────────────

    @Test
    fun `a snap announced with easeNextCut is eased without changing source`() {
        val script = Turntable(yawDegrees = 0f)
        val camera = manipulator().apply { drive(script) }
        val before = camera.frame()

        camera.easeNextCut()
        script.yawDegrees = 170f
        script.radius = 1.5f
        val after = camera.frame()

        assertTrue("stepped ${distance(before, after)} m", distance(before, after) < 0.01f)
        assertTrue(camera.isEasing)
    }

    @Test
    fun `a snap that only lands a frame after easeNextCut is still eased`() {
        val script = Turntable(yawDegrees = 0f)
        val camera = manipulator().apply { drive(script) }
        camera.frame()

        // The script announces its cut and then suspends: `Animatable.snapTo` waits on the mutex
        // an animation of the outgoing mode still holds, so a frame goes by with the pose it had.
        camera.easeNextCut()
        val before = camera.frame()
        assertFalse("nothing has moved yet", camera.isEasing)

        script.yawDegrees = 170f
        script.radius = 1.5f
        val after = camera.frame()

        assertTrue("the snap was drawn as a cut", camera.isEasing)
        assertTrue("stepped ${distance(before, after)} m", distance(before, after) < 0.01f)
    }

    @Test
    fun `an announced cut nothing came of is dropped, not spent on a later change`() {
        val script = Turntable(yawDegrees = 0f)
        val camera = manipulator().apply { drive(script) }
        camera.frame()

        // Announced, then abandoned — the branch that would have snapped returned early.
        camera.easeNextCut()
        repeat(CUT_ARM_FRAMES) { camera.frame() }

        // Long afterwards, an unrelated move: it is the source's own, not a cut to protect.
        script.yawDegrees = 170f
        val drawn = camera.frame()

        assertFalse(camera.isEasing)
        assertEquals(0f, maxElementDelta(script.getTransform(), drawn), 1e-5f)
    }

    // ── Velocity ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the speed going in is carried instead of stopping dead`() {
        val turning = Turntable(yawDegrees = 0f, degreesPerSecond = 60f)
        val still = Turntable(yawDegrees = 200f)
        val camera = manipulator().apply { drive(turning) }
        var previous = camera.frame()
        var cruise = 0f
        repeat(10) {
            val next = camera.frame()
            cruise = distance(previous, next)
            previous = next
        }

        camera.drive(still)
        val first = camera.frame()
        val second = camera.frame()

        val carried = distance(first, second)
        assertTrue("cruise $cruise m/frame, then $carried", carried > cruise * 0.6f)
        assertTrue("cruise $cruise m/frame, then $carried", carried < cruise * 1.4f)
    }

    @Test
    fun `a moving source is not given the old speed on top of its own`() {
        val first = Turntable(yawDegrees = 0f, degreesPerSecond = 60f)
        val second = Turntable(yawDegrees = 0f, degreesPerSecond = 60f, radius = 2.6f)
        val camera = manipulator().apply { drive(first) }
        var previous = camera.frame()
        repeat(10) { previous = camera.frame() }
        val cruise = Math.toRadians(60.0).toFloat() * FRAME_SECONDS * 2f

        camera.drive(second)
        var worst = 0f
        repeat(30) {
            val next = camera.frame()
            worst = maxOf(worst, distance(previous, next))
            previous = next
        }

        // Same turn rate either side of the change: the only extra motion is the 0.6 m dolly.
        assertTrue("worst step $worst m against a cruise of $cruise", worst < cruise * 2.2f)
    }

    // ── Frames that never came ───────────────────────────────────────────────────────────────────

    @Test
    fun `a freeze does not eat the ease`() {
        val first = Turntable(yawDegrees = 0f)
        val second = Turntable(yawDegrees = 150f, radius = 3.5f)
        val camera = manipulator().apply { drive(first) }
        val before = camera.frame()
        camera.drive(second)
        camera.frame()

        // A model decodes on the main thread: the next frame arrives three seconds later.
        nanos += 3_000_000_000L
        val after = camera.frame()

        assertTrue(camera.isEasing)
        assertTrue("stepped ${distance(before, after)} m", distance(before, after) < 0.15f)
    }

    @Test
    fun `a source that moved on during a freeze is eased back into, not cut to`() {
        val script = Turntable(yawDegrees = 0f)
        val camera = manipulator().apply { drive(script) }
        val before = camera.frame()

        nanos += 2_000_000_000L
        script.yawDegrees = 120f
        val after = camera.frame()

        assertTrue(camera.isEasing)
        assertTrue("stepped ${distance(before, after)} m", distance(before, after) < 0.15f)
    }

    @Test
    fun `a source one frame stale shows its move a frame after the freeze - still no cut`() {
        val script = Turntable(yawDegrees = 0f)
        val camera = manipulator().apply { drive(script) }
        camera.frame()

        // The script is animated from another frame callback: on the first frame after the
        // freeze it still shows the old pose, and only on the next one how far it went.
        nanos += 4_000_000_000L
        val before = camera.frame()
        assertFalse(camera.isEasing)
        script.yawDegrees = 43f
        val after = camera.frame()

        assertTrue(camera.isEasing)
        assertTrue("stepped ${distance(before, after)} m", distance(before, after) < 0.15f)
    }

    @Test
    fun `long after a freeze a moving source is left alone`() {
        val turning = Turntable(yawDegrees = 0f, degreesPerSecond = 60f)
        val camera = manipulator().apply { drive(turning) }
        camera.frame()
        nanos += 4_000_000_000L
        repeat(ContinuousCameraManipulator.SETTLING_FRAMES + 1) { camera.frame() }
        while (camera.isEasing) camera.frame()

        turning.yawDegrees += 10f
        val drawn = camera.frame()
        assertEquals(0f, maxElementDelta(turning.getTransform(), drawn), 1e-5f)
        assertFalse(camera.isEasing)
    }

    @Test
    fun `behind a loading cover every change is taken at once, and the reveal adds no move`() {
        val first = Turntable(yawDegrees = 0f)
        val second = Turntable(yawDegrees = 120f, radius = 4f)
        val camera = manipulator().apply { drive(first) }
        camera.frame()

        camera.contentShown = false
        camera.drive(second)
        assertEquals(0f, maxElementDelta(second.getTransform(), camera.frame()), 0f)

        nanos += 2_000_000_000L
        camera.contentShown = true
        assertEquals(0f, maxElementDelta(second.getTransform(), camera.frame()), 0f)
        assertFalse(camera.isEasing)
    }

    // ── Robustness ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pose on the pivot's vertical never yields a broken transform`() {
        val first = Turntable(yawDegrees = 0f)
        val overhead = Turntable(yawDegrees = 0f, radius = 1e-3f, height = 3f)
        val camera = manipulator().apply { drive(first) }
        camera.frame()
        camera.drive(overhead)

        repeat(90) {
            assertTrue(camera.frame().toFloatArray().all { it.isFinite() })
        }
    }

    private companion object {
        const val FRAME_SECONDS = 1f / 60f
        const val FRAME_NANOS = 16_666_667L

        /** Comfortably past [ContinuousCameraManipulator.CUT_ARM_SECONDS] at 60 fps. */
        val CUT_ARM_FRAMES =
            (ContinuousCameraManipulator.CUT_ARM_SECONDS / FRAME_SECONDS).toInt() + 5
    }
}
