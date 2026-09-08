package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the camera maths behind the rebuilt `camera-gestures` demo (#3500).
 *
 * The screen's whole claim is that the camera is *continuous*: a drag, a coast, a preset flight and
 * the on-screen readout are four views of one spherical pose. That only holds if the conversions
 * agree, so the properties worth pinning are the round-trips and the invariants, not sampled
 * numbers:
 *
 * - [CameraRig.eye] and [CameraRig.poseOf] are inverses, or the HUD prints one camera while the
 *   renderer draws another.
 * - A flight takes the **shortest arc**. The demo this replaced rebuilt its manipulator to change
 *   framing, so every change teleported; going the long way round a subject reads as the same bug
 *   even when it lands on the right frame.
 * - The clamps are expressed in framing units (elevation degrees, multiples of the auto-fit
 *   distance) and actually bind, because a camera that can sink under the floor or cross the pole
 *   shows the user a broken scene and blames their finger.
 * - The coast decays **per second**, not per frame, or a flick spins twice as long on a 120 Hz
 *   panel as on a 60 Hz one.
 */
class CameraRigTest {

    private val tolerance = 1e-3f

    // ── Spherical ↔ world round-trip ──────────────────────────────────────────

    @Test
    fun `eye and poseOf are inverses`() {
        val pose = OrbitPose(
            target = Position(0.2f, 0.4f, -0.1f),
            azimuthDegrees = 37f,
            elevationDegrees = 21f,
            distance = 2.4f,
        )
        val roundTripped = CameraRig.poseOf(CameraRig.eye(pose), pose.target)

        assertEquals(pose.azimuthDegrees, roundTripped.azimuthDegrees, tolerance)
        assertEquals(pose.elevationDegrees, roundTripped.elevationDegrees, tolerance)
        assertEquals(pose.distance, roundTripped.distance, tolerance)
    }

    @Test
    fun `zero azimuth puts the camera on positive Z`() {
        // The convention the presets are authored against: "Front" is azimuth 0, and the demo's
        // subjects face +Z. If this flips, every preset silently looks at the back of the models.
        val eye = CameraRig.eye(OrbitPose(azimuthDegrees = 0f, elevationDegrees = 0f, distance = 3f))

        assertEquals(0f, eye.x, tolerance)
        assertEquals(0f, eye.y, tolerance)
        assertEquals(3f, eye.z, tolerance)
    }

    @Test
    fun `positive elevation lifts the camera above the target`() {
        val eye = CameraRig.eye(OrbitPose(elevationDegrees = 90f, distance = 2f))

        assertEquals(2f, eye.y, tolerance)
    }

    @Test
    fun `poseOf survives a degenerate eye on the target`() {
        // Guards the frame where a fit distance has not been computed yet: `NaN` reaching the
        // look-at matrix blanks the viewport for the rest of the session.
        val pose = CameraRig.poseOf(Position(1f, 1f, 1f), Position(1f, 1f, 1f))

        assertTrue(pose.distance.isFinite())
        assertTrue(pose.distance > 0f)
    }

    // ── Angles ────────────────────────────────────────────────────────────────

    @Test
    fun `normalizeDegrees wraps a multi-turn turntable into what the HUD prints`() {
        assertEquals(0f, CameraRig.normalizeDegrees(1080f), tolerance)
        assertEquals(-10f, CameraRig.normalizeDegrees(350f), tolerance)
        assertEquals(180f, CameraRig.normalizeDegrees(180f), tolerance)
        assertEquals(0f, CameraRig.normalizeDegrees(Float.NaN), tolerance)
    }

    @Test
    fun `shortestDelta crosses the seam instead of going the long way round`() {
        assertEquals(20f, CameraRig.shortestDelta(from = 350f, to = 10f), tolerance)
        assertEquals(-20f, CameraRig.shortestDelta(from = 10f, to = 350f), tolerance)
    }

    // ── Flights ───────────────────────────────────────────────────────────────

    @Test
    fun `a flight across the seam never travels more than half a turn`() {
        val from = OrbitPose(azimuthDegrees = 350f, distance = 2f)
        val to = OrbitPose(azimuthDegrees = 10f, distance = 2f)

        var previous = from.azimuthDegrees
        var travelled = 0f
        for (step in 1..20) {
            val azimuth = CameraRig.lerp(from, to, step / 20f).azimuthDegrees
            travelled += abs(azimuth - previous)
            previous = azimuth
        }

        assertEquals(20f, travelled, 0.1f)
    }

    @Test
    fun `a dolly covers equal ratios per unit time, not equal metres`() {
        // Geometric interpolation: the midpoint of a 4 m → 1 m flight is the geometric mean (2 m),
        // not the arithmetic one (2.5 m). A linear lerp appears to accelerate violently at the end,
        // because the last half-metre changes the image far more than the first.
        val midpoint = CameraRig.lerp(
            from = OrbitPose(distance = 4f),
            to = OrbitPose(distance = 1f),
            fraction = 0.5f,
        )

        assertEquals(2f, midpoint.distance, tolerance)
    }

    @Test
    fun `lerp endpoints are exact and the fraction is clamped`() {
        val from = OrbitPose(azimuthDegrees = 10f, elevationDegrees = 5f, distance = 3f)
        val to = OrbitPose(
            target = Position(1f, 2f, 3f),
            azimuthDegrees = 70f,
            elevationDegrees = 40f,
            distance = 0.8f,
        )

        assertEquals(from.distance, CameraRig.lerp(from, to, -1f).distance, tolerance)
        assertEquals(to.distance, CameraRig.lerp(from, to, 2f).distance, tolerance)
        assertEquals(to.azimuthDegrees, CameraRig.lerp(from, to, 1f).azimuthDegrees, tolerance)
        assertEquals(to.target.y, CameraRig.lerp(from, to, 1f).target.y, tolerance)
    }

    @Test
    fun `poseFor reaches an absolute preset angle by the shortest arc from where the camera is`() {
        // Tapping "Front" from behind the subject swings round the near side: the returned azimuth
        // is 350 + 10 = 360, which is Front, reached the short way.
        val pose = CameraRig.poseFor(
            view = CameraView.Front,
            focusTarget = Position(0f, 0f, 0f),
            fitDistance = 2f,
            awayFrom = 350f,
        )

        assertEquals(360f, pose.azimuthDegrees, tolerance)
        assertEquals(0f, CameraRig.normalizeDegrees(pose.azimuthDegrees), tolerance)
        assertTrue(abs(pose.azimuthDegrees - 350f) <= 180f)
    }

    @Test
    fun `every preset scales its distance off the focus's own fit`() {
        // Presets are angles and a multiple, never absolute eye positions — that is what lets the
        // same five chips frame the whole stage and any single subject.
        CameraView.entries.forEach { view ->
            val near = CameraRig.poseFor(view, Position(0f, 0f, 0f), fitDistance = 1f)
            val far = CameraRig.poseFor(view, Position(0f, 0f, 0f), fitDistance = 4f)

            assertEquals(view.distanceScale, near.distance, tolerance)
            assertEquals(4f * view.distanceScale, far.distance, tolerance)
        }
    }

    @Test
    fun `no preset is clamped away by the demo's own framing limits`() {
        // A preset the clamp rewrites is a chip that lies: it says "Top" and lands somewhere else.
        CameraView.entries.forEach { view ->
            val pose = CameraRig.poseFor(view, CameraRig.SCENE_TARGET, fitDistance = 2f)
            val clamped = CameraRig.clamp(pose, fitDistance = 2f)

            assertEquals(view.name, pose.elevationDegrees, clamped.elevationDegrees, tolerance)
            assertEquals(view.name, pose.distance, clamped.distance, tolerance)
        }
    }

    // ── Clamps ────────────────────────────────────────────────────────────────

    @Test
    fun `elevation is held short of the pole and above the floor`() {
        val overhead = CameraRig.clamp(OrbitPose(elevationDegrees = 300f, distance = 2f), 2f)
        val underground = CameraRig.clamp(OrbitPose(elevationDegrees = -90f, distance = 2f), 2f)

        assertEquals(CameraRig.MAX_ELEVATION_DEGREES, overhead.elevationDegrees, tolerance)
        assertEquals(CameraRig.MIN_ELEVATION_DEGREES, underground.elevationDegrees, tolerance)
        assertTrue(CameraRig.MAX_ELEVATION_DEGREES < 90f)
    }

    @Test
    fun `distance is clamped to a band around the focus's fit, so zoom follows the focus`() {
        val closeUp = CameraRig.clamp(OrbitPose(distance = 0.001f), fitDistance = 2f)
        val faraway = CameraRig.clamp(OrbitPose(distance = 500f), fitDistance = 2f)

        assertEquals(2f * CameraRig.MIN_DISTANCE_SCALE, closeUp.distance, tolerance)
        assertEquals(2f * CameraRig.MAX_DISTANCE_SCALE, faraway.distance, tolerance)
    }

    @Test
    fun `a pan cannot drag the pivot off the stage`() {
        val runaway = CameraRig.clamp(
            OrbitPose(target = Position(99f, 99f, -99f), distance = 2f),
            fitDistance = 2f,
        )

        assertEquals(CameraRig.MAX_PAN_RADIUS, runaway.target.x, tolerance)
        assertEquals(-CameraRig.MAX_PAN_RADIUS, runaway.target.z, tolerance)
        assertEquals(CameraRig.MAX_TARGET_Y, runaway.target.y, tolerance)
    }

    @Test
    fun `clamp survives a fit distance that has not been computed yet`() {
        val clamped = CameraRig.clamp(OrbitPose(distance = 2f), fitDistance = Float.NaN)

        assertTrue(clamped.distance.isFinite())
        assertTrue(clamped.distance > 0f)
    }

    // ── Focus framing ─────────────────────────────────────────────────────────

    @Test
    fun `focus aims at the middle of a subject's height, not the spot it stands on`() {
        RigSubject.entries.forEach { subject ->
            val target = CameraRig.focusTarget(subject)

            assertEquals(subject.name, subject.groundX, target.x, tolerance)
            assertEquals(subject.name, subject.groundZ, target.z, tolerance)
            assertTrue(subject.name, target.y > 0f)
            assertTrue(subject.name, target.y < subject.extent)
        }
    }

    @Test
    fun `the three subjects stand apart and inside the stage extent`() {
        // The composition is what makes orbit and pan distinguishable at a glance; overlapping
        // subjects would read as one blob and the per-subject focus would pick the wrong one.
        RigSubject.entries.forEach { a ->
            RigSubject.entries.forEach { b ->
                if (a != b) {
                    val gap = abs(a.groundX - b.groundX) + abs(a.groundZ - b.groundZ)
                    assertTrue("${a.name} vs ${b.name}", gap > (a.extent + b.extent) * 0.5f)
                }
            }
            assertTrue(a.name, abs(a.groundX) <= CameraRig.SCENE_EXTENT_X)
            assertTrue(a.name, abs(a.groundZ) <= CameraRig.SCENE_EXTENT_Z)
        }
    }

    @Test
    fun `every subject has its own resting yaw`() {
        // All three facing the same way reads as stock on a shelf rather than a composition.
        val yaws = RigSubject.entries.map { it.yawDegrees }

        assertEquals(yaws.size, yaws.toSet().size)
    }

    // ── Pan projection ────────────────────────────────────────────────────────

    @Test
    fun `a pan pixel is worth more world at distance, so the scene tracks the fingers`() {
        val near = CameraRig.worldPerPixel(1f, verticalFovDegrees = 40.0, viewportHeightPx = 2400)
        val far = CameraRig.worldPerPixel(4f, verticalFovDegrees = 40.0, viewportHeightPx = 2400)

        assertEquals(4f, far / near, 1e-2f)
    }

    @Test
    fun `worldPerPixel refuses a viewport it has not been given yet`() {
        assertEquals(0f, CameraRig.worldPerPixel(2f, 40.0, viewportHeightPx = 0), tolerance)
        assertEquals(0f, CameraRig.worldPerPixel(0f, 40.0, viewportHeightPx = 2400), tolerance)
    }

    // ── Inertia ───────────────────────────────────────────────────────────────

    @Test
    fun `the coast decays per second, so it feels the same at 60 and 120 Hz`() {
        // One second of coasting at 60 Hz and at 120 Hz must leave the same velocity — a per-frame
        // multiplier would spin twice as long on the faster panel.
        fun coast(steps: Int): Float {
            var velocity = 300f
            repeat(steps) { velocity = CameraRig.decayInertia(velocity, 1f / steps) }
            return velocity
        }

        assertEquals(coast(60), coast(120), 0.5f)
        assertEquals(300f * CameraRig.INERTIA_DECAY_PER_SECOND, coast(60), 0.5f)
    }

    @Test
    fun `a coast parks instead of crawling forever`() {
        var velocity = 400f
        repeat(600) { velocity = CameraRig.decayInertia(velocity, 1f / 60f) }

        assertEquals(0f, velocity, 0f)
    }

    @Test
    fun `decayInertia rejects a stalled frame and a non-finite velocity`() {
        assertEquals(0f, CameraRig.decayInertia(300f, deltaSeconds = 0f), 0f)
        assertEquals(0f, CameraRig.decayInertia(Float.NaN, deltaSeconds = 1f / 60f), 0f)
    }

    // ── Chips and copy ────────────────────────────────────────────────────────

    @Test
    fun `the view chips stay one word each, because five of them share a phone width`() {
        CameraView.entries.forEach { view ->
            assertTrue(view.label, view.label.isNotBlank())
            assertEquals(view.label, 1, view.label.trim().split(" ").size)
        }
        assertEquals(5, CameraView.entries.size)
    }

    @Test
    fun `every named view frames the subject from a distinct angle`() {
        val angles = CameraView.entries.map { it.azimuthDegrees to it.elevationDegrees }

        assertEquals(angles.size, angles.toSet().size)
        assertNotEquals(CameraView.Hero.distanceScale, CameraView.Close.distanceScale)
    }

    @Test
    fun `the sensitivity slider brackets its own neutral position`() {
        assertTrue(CameraRig.MIN_SENSITIVITY < CameraRig.DEFAULT_SENSITIVITY)
        assertTrue(CameraRig.DEFAULT_SENSITIVITY < CameraRig.MAX_SENSITIVITY)
    }
}
