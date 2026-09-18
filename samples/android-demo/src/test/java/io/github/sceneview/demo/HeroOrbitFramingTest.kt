package io.github.sceneview.demo

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The arithmetic under [HeroOrbitCameraManipulator]'s hand-back (#3642): a camera pose read as an
 * orbit, the user's share of it kept as an offset, and that offset eased away.
 */
class HeroOrbitFramingTest {

    private fun assertPositionEquals(expected: Position, actual: Position, tolerance: Float = 1e-4f) {
        assertEquals("x", expected.x, actual.x, tolerance)
        assertEquals("y", expected.y, actual.y, tolerance)
        assertEquals("z", expected.z, actual.z, tolerance)
    }

    private val authored = authoredOrbitFraming(
        yawDegrees = 30f,
        radius = 2f,
        height = 0.5f,
        target = Position(0.1f, 0.2f, -0.3f),
    )

    /** Round the other side, higher, closer, and panned off the authored pivot. */
    private val user = orbitFramingOf(
        eye = Position(-1.0f, 1.5f, 0.6f),
        pivot = Position(0.4f, 0.3f, -0.1f),
    )

    // ── A pose as an orbit ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the authored framing puts the eye where the manipulator always has`() {
        val yaw = Math.toRadians(30.0).toFloat()
        val legacyEye = Position(sin(yaw) * 2f + 0.1f, 0.2f + 0.5f, cos(yaw) * 2f - 0.3f)

        assertPositionEquals(legacyEye, authored.eye())
    }

    @Test
    fun `reading an eye as an orbit and back gives the eye`() {
        val eye = Position(-1.0f, 1.5f, 0.6f)
        val pivot = Position(0.4f, 0.3f, -0.1f)

        assertPositionEquals(eye, orbitFramingOf(eye, pivot).eye())
    }

    @Test
    fun `the look point lies on the view ray, at the asked depth`() {
        val point = lookPoint(Position(1f, 2f, 3f), forward = Position(0f, 0f, -4f), distance = 2.5f)

        assertPositionEquals(Position(1f, 2f, 0.5f), point)
    }

    @Test
    fun `a view direction that is not one leaves the look point on the eye`() {
        val eye = Position(1f, 2f, 3f)

        assertPositionEquals(eye, lookPoint(eye, forward = Position(0f, 0f, 0f), distance = 2f))
        assertPositionEquals(eye, lookPoint(eye, forward = Position(0f, 0f, -1f), distance = Float.NaN))
    }

    // ── The user's share of it ───────────────────────────────────────────────────────────────────

    @Test
    fun `the whole offset is the user's pose, none of it is the authored one`() {
        val offset = orbitFramingOffset(user, authored)

        assertPositionEquals(user.eye(), authored.offsetBy(offset, 1f).eye())
        assertPositionEquals(user.pivot, authored.offsetBy(offset, 1f).pivot)
        assertPositionEquals(authored.eye(), authored.offsetBy(offset, 0f).eye())
        assertPositionEquals(authored.pivot, authored.offsetBy(offset, 0f).pivot)
    }

    @Test
    fun `the offset rides the orbit - the yaw goes on turning underneath it`() {
        val offset = orbitFramingOffset(user, authored)
        val later = authored.copy(yawDegrees = authored.yawDegrees + 45f)

        val carried = later.offsetBy(offset, 1f)

        assertEquals(user.yawDegrees + 45f, carried.yawDegrees, 1e-3f)
        assertEquals(user.elevation, carried.elevation, 1e-5f)
        assertEquals(user.distance, carried.distance, 1e-4f)
    }

    @Test
    fun `a re-fitted radius keeps the user's zoom as a ratio`() {
        val offset = orbitFramingOffset(user, authored)
        val refitted = authored.copy(distance = authored.distance * 2f)

        assertEquals(user.distance * 2f, refitted.offsetBy(offset, 1f).distance, 1e-4f)
    }

    @Test
    fun `the yaw offset takes the short way round`() {
        val offset = orbitFramingOffset(
            user = authored.copy(yawDegrees = 170f),
            authored = authored.copy(yawDegrees = -170f),
        )

        assertEquals("20 degrees back through 180, not 340 forward", -20f, offset.yawDegrees, 1e-4f)
    }

    @Test
    fun `angles fold into the half-open turn around zero`() {
        assertEquals(-170f, wrapDegrees(190f), 1e-4f)
        assertEquals(170f, wrapDegrees(-190f), 1e-4f)
        assertEquals(180f, wrapDegrees(180f), 1e-4f)
        assertEquals(180f, wrapDegrees(-180f), 1e-4f)
        assertEquals(0f, wrapDegrees(720f), 1e-4f)
        assertEquals(0f, wrapDegrees(Float.NaN), 0f)
    }

    @Test
    fun `an offset can never carry the eye over a pole`() {
        val offset = orbitFramingOffset(
            user = authored.copy(elevation = Math.toRadians(80.0).toFloat()),
            authored = authored.copy(elevation = 0f),
        )
        // The demo re-tilts its authored path after the hand-back: 40 + 80 degrees is past the top.
        val tilted = authored.copy(elevation = Math.toRadians(40.0).toFloat())

        val elevation = tilted.offsetBy(offset, 1f).elevation

        assertTrue("$elevation", elevation < Math.toRadians(90.0).toFloat())
    }

    // ── Easing it away ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the ease leaves with the whole offset and arrives with none, without turning back`() {
        assertEquals(1f, resumeBlendWeight(0f, 1.2f), 0f)
        assertEquals(0.5f, resumeBlendWeight(0.6f, 1.2f), 1e-5f)
        assertEquals(0f, resumeBlendWeight(1.2f, 1.2f), 0f)
        assertEquals(0f, resumeBlendWeight(9f, 1.2f), 0f)

        val samples = (0..24).map { resumeBlendWeight(it * 0.05f, 1.2f) }
        assertTrue("$samples", samples.zipWithNext().all { (a, b) -> b <= a })
    }

    @Test
    fun `an ease that lasts no time is already over`() {
        assertEquals(0f, resumeBlendWeight(0f, 0f), 0f)
        assertEquals(0f, resumeBlendWeight(Float.NaN, 1.2f), 0f)
    }

    @Test
    fun `a carried framing is held at full weight until it is eased, then lands and empties`() {
        var now = 5_000_000_000L
        val carried = CarriedFraming { now }
        val offset = orbitFramingOffset(user, authored)

        carried.hold(offset)
        now += 3_600_000_000_000L
        assertPositionEquals(user.eye(), checkNotNull(carried.over { authored }).eye())
        assertFalse(carried.isEasing)

        carried.easeBack(millis = 1_000L)
        assertTrue(carried.isEasing)
        assertPositionEquals(user.eye(), checkNotNull(carried.over { authored }).eye())
        now += 500_000_000L
        val halfWay = checkNotNull(carried.over { authored })
        assertEquals((user.elevation + authored.elevation) / 2f, halfWay.elevation, 1e-4f)

        now += 500_000_000L
        assertNull("landed: the caller is back on its own authored formula", carried.over { authored })
        assertTrue(carried.isEmpty)
    }

    @Test
    fun `nothing carried - nothing to ease, and the authored framing is not even asked for`() {
        val carried = CarriedFraming { 1L }

        carried.easeBack(millis = 1_000L)

        assertFalse(carried.isEasing)
        assertNull(carried.over { error("the authored framing is only read when there is an offset") })
    }

    @Test
    fun `a long way home takes longer, a short one keeps the length it was given`() {
        val near = OrbitFramingOffset(Position(0f, 0f, 0f), yawDegrees = 8f, elevation = 0f, distanceScale = 1.1f)
        val far = OrbitFramingOffset(Position(0f, 0f, 0f), yawDegrees = -75f, elevation = 0f, distanceScale = 1f)
        val zoomed = OrbitFramingOffset(Position(0f, 0f, 0f), yawDegrees = 0f, elevation = 0f, distanceScale = 0.2f)
        val opposite = OrbitFramingOffset(Position(0f, 0f, 0f), yawDegrees = 180f, elevation = 0f, distanceScale = 1f)

        assertEquals(1_200L, resumeBlendMillisFor(near, 1_200L))
        // 75° under a 45°/s peak: 1.5 × 75 / 45 = 2.5 s.
        assertEquals(2_500.0, resumeBlendMillisFor(far, 1_200L).toDouble(), 2.0)
        assertTrue(resumeBlendMillisFor(zoomed, 1_200L) > 2_500L)
        assertEquals(3_600L, resumeBlendMillisFor(opposite, 1_200L))
        assertEquals(0L, resumeBlendMillisFor(far, 0L))
    }

    @Test
    fun `a paced ease back is still on its way when the asked length has passed`() {
        var now = 0L
        val carried = CarriedFraming { now }
        carried.hold(OrbitFramingOffset(Position(0f, 0f, 0f), yawDegrees = 75f, elevation = 0f, distanceScale = 1f))

        carried.easeBack(millis = 1_200L, paced = true)
        now += 1_250_000_000L

        // Half of 2.5 s in: half of the turn is left, where the unpaced ease had already landed.
        assertEquals(authored.yawDegrees + 37.5f, checkNotNull(carried.over { authored }).yawDegrees, 0.5f)
    }

    // ── The turntable's clock ────────────────────────────────────────────────────────────────────

    @Test
    fun `the turntable eases in from rest instead of starting at full speed`() {
        val spin = OrbitSpin()
        val goal = OrbitSpin.degreesPerSecond(fullTurnMillis = 20_000)
        assertEquals(18f, goal, 1e-4f)

        spin.advance(deltaSeconds = FRAME, goalDegreesPerSecond = goal)

        // One frame in, the orbit has barely moved: the old looping tween was at 18°/s here.
        assertTrue(spin.degreesPerSecond < goal * 0.05f)
        repeat(240) { spin.advance(FRAME, goal) }
        assertEquals(goal, spin.degreesPerSecond, 0.01f)
    }

    @Test
    fun `pausing coasts to a stop and keeps the angle`() {
        val spin = OrbitSpin()
        repeat(240) { spin.advance(FRAME, 18f) }
        val yawAtPause = spin.yawDegrees

        spin.advance(FRAME, goalDegreesPerSecond = 0f)

        // Still moving the frame after the switch flips: no velocity cut…
        assertTrue(spin.degreesPerSecond > 18f * 0.9f)
        repeat(240) { spin.advance(FRAME, 0f) }
        // …then at rest, a few degrees further on — never back at 0° (#3640).
        assertEquals(0f, spin.degreesPerSecond, 0.01f)
        assertTrue(spin.yawDegrees > yawAtPause)
        assertTrue(spin.yawDegrees - yawAtPause < 18f * OrbitSpin.DEFAULT_SPIN_EASE_SECONDS * 1.1f)
    }

    @Test
    fun `the speed is continuous whatever the frame pacing`() {
        val smooth = OrbitSpin()
        val janky = OrbitSpin()
        repeat(60) { smooth.advance(FRAME, 18f) }
        repeat(6) { janky.advance(FRAME * 10f, 18f) }

        // Same clock time, same place: a dropped frame carries its own motion, not the next one's.
        assertEquals(smooth.yawDegrees, janky.yawDegrees, 0.05f)
        assertEquals(smooth.degreesPerSecond, janky.degreesPerSecond, 0.01f)
    }

    @Test
    fun `a frame that lasted the whole background stay does not leap the orbit`() {
        val spin = OrbitSpin()
        repeat(240) { spin.advance(FRAME, 18f) }
        val before = spin.yawDegrees

        spin.advance(deltaSeconds = 90f, goalDegreesPerSecond = 18f)

        assertEquals(before + 18f * OrbitSpin.MAX_STEP_SECONDS, spin.yawDegrees, 0.01f)
    }

    @Test
    fun `the yaw wraps and reset puts it back at rest`() {
        val spin = OrbitSpin(easeSeconds = 0f)
        repeat(25) { spin.advance(1f / 4f, 360f) }
        assertTrue(spin.yawDegrees in 0f..360f)

        spin.reset()

        assertEquals(0f, spin.yawDegrees, 0f)
        assertEquals(0f, spin.degreesPerSecond, 0f)
    }

    private companion object {
        const val FRAME = 1f / 60f
    }
}
