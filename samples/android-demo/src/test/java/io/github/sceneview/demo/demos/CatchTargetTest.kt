package io.github.sceneview.demo.demos

import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.tan

/**
 * Pure-JVM tests for the tap-to-catch layer of the `ar-orbital` demo (#3341):
 * [projectTarget] (screen pixels for a world point), [nearestCatchTarget] (which
 * flyer a tap caught), [orbitAngleRad] and [releaseAngleOffset] (freeze on catch,
 * resume without a jump on release).
 *
 * These live on the JVM because the emulator can never run an AR session (#2754),
 * so the only place this arithmetic can be checked before it reaches a physical
 * Pixel is here. The camera convention matches [OffscreenTargetProjectionTest]:
 * origin, looking down -Z, +Y up, view matrix identity so `viewProjection ==
 * projection`; 90° vertical FOV and 1:1 aspect, meaning the visible half-extent
 * at `z = -1` is exactly 1.
 */
class CatchTargetTest {

    private val cameraAtOrigin = Position(0f, 0f, 0f)

    private val projection: Transform =
        standardPerspective(fovYDegrees = 90f, aspect = 1f, near = 0.05f, far = 30f)

    private val viewportWidth = 1080f
    private val viewportHeight = 2400f

    // ── projectTarget ────────────────────────────────────────────────────────

    @Test
    fun `a target dead ahead projects to the centre of the viewport`() {
        val projected = projectTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(0f, 0f, -5f),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        assertTrue("a target dead ahead must be on-screen", projected.onScreen)
        assertEquals(viewportWidth / 2f, projected.screenX, 0.5f)
        assertEquals(viewportHeight / 2f, projected.screenY, 0.5f)
        assertEquals(5f, projected.distanceMeters, 0.001f)
    }

    @Test
    fun `world up maps to the top half of the screen, not the bottom`() {
        // Compose's Y axis points down; NDC's points up. Getting this inversion
        // wrong would put every hitbox mirrored across the horizon, which reads
        // on-device exactly like "you never manage to catch one".
        val projected = projectTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(0f, 2f, -5f),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        assertTrue(projected.onScreen)
        assertTrue(
            "a target above eye level must project above the mid-line, got ${projected.screenY}",
            projected.screenY < viewportHeight / 2f,
        )
    }

    @Test
    fun `a target behind the camera is never on-screen`() {
        val projected = projectTarget(
            viewProjection = projection,
            cameraPosition = cameraAtOrigin,
            targetWorld = Position(0f, 0f, 5f),
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
        assertFalse("a target behind the camera must never read as on-screen", projected.onScreen)
        assertNull(
            "and it must not be catchable either",
            nearestCatchTarget(
                projected = mapOf(0 to projected),
                tapX = projected.screenX,
                tapY = projected.screenY,
                radiusPx = 200f,
                caught = emptySet(),
            ),
        )
    }

    @Test
    fun `asOffscreenTarget returns an indicator only when the target is off-screen`() {
        val onScreen = projectTarget(
            projection, cameraAtOrigin, Position(0f, 0f, -5f), viewportWidth, viewportHeight,
        )
        val offScreen = projectTarget(
            projection, cameraAtOrigin, Position(40f, 0f, -5f), viewportWidth, viewportHeight,
        )
        assertNull("on-screen targets get a model, not an edge arrow", onScreen.asOffscreenTarget())
        assertTrue("off-screen targets get an edge arrow", offScreen.asOffscreenTarget() != null)
    }

    // ── nearestCatchTarget ───────────────────────────────────────────────────

    private fun at(x: Float, y: Float, onScreen: Boolean = true) = ProjectedTarget(
        screenX = x,
        screenY = y,
        onScreen = onScreen,
        angleRad = 0f,
        distanceMeters = 1.5f,
    )

    @Test
    fun `a tap inside the radius catches that flyer`() {
        val hit = nearestCatchTarget(
            projected = mapOf(0 to at(500f, 500f)),
            tapX = 520f,
            tapY = 530f,
            radiusPx = 100f,
            caught = emptySet(),
        )
        assertEquals(0, hit)
    }

    @Test
    fun `a tap outside the radius catches nothing`() {
        val hit = nearestCatchTarget(
            projected = mapOf(0 to at(500f, 500f)),
            tapX = 900f,
            tapY = 500f,
            radiusPx = 100f,
            caught = emptySet(),
        )
        assertNull("a tap well away from every flyer must miss", hit)
    }

    @Test
    fun `when two flyers overlap the nearest one wins`() {
        val hit = nearestCatchTarget(
            projected = mapOf(0 to at(500f, 500f), 1 to at(560f, 500f)),
            tapX = 550f,
            tapY = 500f,
            radiusPx = 200f,
            caught = emptySet(),
        )
        assertEquals("the closer centre must win, not the lower index", 1, hit)
    }

    @Test
    fun `an already-caught flyer is not catchable again`() {
        val hit = nearestCatchTarget(
            projected = mapOf(0 to at(500f, 500f), 1 to at(700f, 500f)),
            tapX = 505f,
            tapY = 500f,
            radiusPx = 250f,
            caught = setOf(0),
        )
        assertEquals("the caught flyer is skipped, the next one in range wins", 1, hit)
    }

    @Test
    fun `an off-screen flyer is not catchable`() {
        // Its projected pixels are meaningless once it leaves the frustum — the
        // arrow overlay is what points at it. Catching thin air behind the phone
        // would be worse than missing.
        val hit = nearestCatchTarget(
            projected = mapOf(0 to at(500f, 500f, onScreen = false)),
            tapX = 500f,
            tapY = 500f,
            radiusPx = 100f,
            caught = emptySet(),
        )
        assertNull("an off-screen flyer must not be catchable", hit)
    }

    @Test
    fun `a tap exactly on the radius boundary still catches`() {
        val hit = nearestCatchTarget(
            projected = mapOf(0 to at(500f, 500f)),
            tapX = 600f,
            tapY = 500f,
            radiusPx = 100f,
            caught = emptySet(),
        )
        assertEquals("the hit disc is inclusive at its edge", 0, hit)
    }

    // ── orbitAngleRad / releaseAngleOffset ───────────────────────────────────

    @Test
    fun `orbit angle is normalised into zero to two pi`() {
        val angle = orbitAngleRad(
            initialAngleRad = 0.1f,
            orbitSpeed = 0.18f,
            angleOffsetRad = 0f,
            seconds = 10_000f,
        )
        assertTrue("angle must stay in [0, 2π), got $angle", angle >= 0f && angle < TWO_PI)
    }

    @Test
    fun `a negative offset still yields a positive angle`() {
        val angle = orbitAngleRad(
            initialAngleRad = 0f,
            orbitSpeed = 0.1f,
            angleOffsetRad = -1f,
            seconds = 0f,
        )
        assertTrue("angle must never be negative, got $angle", angle >= 0f)
        assertEquals(TWO_PI - 1f, angle, 1e-3f)
    }

    @Test
    fun `releasing a caught flyer resumes from where it was frozen`() {
        // The whole point of the release path: a flyer caught at t=3s and let go
        // at t=30s must continue from its frozen angle, not teleport to wherever
        // the un-offset orbit would have carried it.
        val initial = 0.7f
        val speed = 0.13f
        val catchTime = 3f
        val releaseTime = 30f

        val frozen = orbitAngleRad(initial, speed, angleOffsetRad = 0f, seconds = catchTime)
        val offset = releaseAngleOffset(
            initialAngleRad = initial,
            orbitSpeed = speed,
            seconds = releaseTime,
            frozenAngleRad = frozen,
        )
        val resumed = orbitAngleRad(initial, speed, angleOffsetRad = offset, seconds = releaseTime)

        assertEquals("release must be continuous with the frozen pose", frozen, resumed, 1e-3f)
    }

    @Test
    fun `a released flyer keeps moving at its own speed`() {
        val initial = 0f
        val speed = 0.16f
        val frozen = orbitAngleRad(initial, speed, angleOffsetRad = 0f, seconds = 5f)
        val offset = releaseAngleOffset(initial, speed, seconds = 20f, frozenAngleRad = frozen)

        val justAfter = orbitAngleRad(initial, speed, offset, seconds = 21f)
        val expected = normalise(frozen + speed * 1f)
        assertEquals("one second after release the flyer has advanced by one second of orbit", expected, justAfter, 1e-3f)
    }

    @Test
    fun `the release offset is itself normalised`() {
        val offset = releaseAngleOffset(
            initialAngleRad = 0.4f,
            orbitSpeed = 0.18f,
            seconds = 9_000f,
            frozenAngleRad = 1.2f,
        )
        assertTrue("offset must stay in [0, 2π), got $offset", offset >= 0f && offset < TWO_PI)
    }

    private fun normalise(angle: Float): Float {
        val raw = angle % TWO_PI
        return if (raw < 0f) raw + TWO_PI else raw
    }

    /** See [OffscreenTargetProjectionTest.standardPerspective] — same convention. */
    private fun standardPerspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): Mat4 {
        val f = 1f / tan(Math.toRadians(fovYDegrees / 2.0)).toFloat()
        return Mat4(
            Float4(f / aspect, 0f, 0f, 0f),
            Float4(0f, f, 0f, 0f),
            Float4(0f, 0f, (far + near) / (near - far), -1f),
            Float4(0f, 0f, (2f * far * near) / (near - far), 0f),
        )
    }

    private companion object {
        const val TWO_PI = 2f * PI.toFloat()
    }
}
