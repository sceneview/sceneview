package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `2D in 3D` demo's framing and its billboard solution (#3424).
 *
 * The demo it replaced hid the same arithmetic in magic literals inside the composable, where
 * nothing could check it and where a wrong sign shows up only as a card facing backwards on a
 * device. Everything here runs in pure JVM: no Filament engine, no Android framework.
 */
class CalloutLayoutTest {

    private val eps = 1e-3f

    // ── Placement ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a callout sits on the spread circle at its own bearing`() {
        val callout = Callout("t", "T", "b", angleDegrees = 0f, height = 0.1f)
        val position = CalloutLayout.localPosition(callout, spread = 0.5f)

        // Bearing 0 means "between the model and the camera's home", i.e. straight down +Z.
        assertEquals(0f, position.x, eps)
        assertEquals(0.1f, position.y, eps)
        assertEquals(0.5f, position.z, eps)
    }

    @Test
    fun `bearing grows counter-clockwise seen from above`() {
        val callout = Callout("t", "T", "b", angleDegrees = 90f, height = 0f)
        val position = CalloutLayout.localPosition(callout, spread = 0.5f)

        // +90° is +X — the same convention `billboardYawDegrees` measures headings in.
        assertEquals(0.5f, position.x, eps)
        assertEquals(0f, position.z, eps)
    }

    @Test
    fun `spread is clamped, so a wild slider cannot put a card inside the model`() {
        val callout = CalloutLayout.CALLOUTS.first()

        val tooClose = CalloutLayout.localPosition(callout, spread = 0f)
        assertEquals(CalloutLayout.MIN_SPREAD, radius(tooClose), eps)

        val tooFar = CalloutLayout.localPosition(callout, spread = 99f)
        assertEquals(CalloutLayout.MAX_SPREAD, radius(tooFar), eps)
    }

    @Test
    fun `the turntable carries a card round without changing its radius or height`() {
        val callout = CalloutLayout.CALLOUTS.first()
        val spread = CalloutLayout.DEFAULT_SPREAD

        val home = CalloutLayout.worldPosition(callout, spread, turntableYawDegrees = 0f)
        listOf(37f, 128f, 270f, 359f).forEach { yaw ->
            val spun = CalloutLayout.worldPosition(callout, spread, yaw)
            assertEquals("radius at $yaw°", radius(home), radius(spun), eps)
            assertEquals("height at $yaw°", home.y, spun.y, eps)
        }
    }

    @Test
    fun `a quarter turn of the turntable moves a card a quarter of the way round`() {
        val callout = Callout("t", "T", "b", angleDegrees = 0f, height = 0f)
        val spun = CalloutLayout.worldPosition(callout, spread = 0.5f, turntableYawDegrees = 90f)

        // Started on +Z, ends on +X.
        assertEquals(0.5f, spun.x, eps)
        assertEquals(0f, spun.z, eps)
    }

    // ── Billboarding ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a card in front of a camera on the Z axis needs no rotation`() {
        val callout = Callout("t", "T", "b", angleDegrees = 0f, height = 0f)

        val yaw = CalloutLayout.billboardYawDegrees(
            callout = callout,
            spread = 0.5f,
            turntableYawDegrees = 0f,
            cameraWorldPosition = Position(0f, 0f, 3f),
        )

        assertEquals(0f, yaw, eps)
    }

    @Test
    fun `the local yaw undoes the turntable, so the card faces the same way in world space`() {
        val callout = CalloutLayout.CALLOUTS[1]
        val spread = CalloutLayout.DEFAULT_SPREAD
        val camera = CalloutLayout.cameraHomePosition()

        listOf(0f, 45f, 137f, 265f, 359f).forEach { turntableYaw ->
            val localYaw = CalloutLayout.billboardYawDegrees(callout, spread, turntableYaw, camera)
            // World heading = parent yaw + local yaw. It must point from the card at the camera.
            val card = CalloutLayout.worldPosition(callout, spread, turntableYaw)
            val expected = CalloutLayout.normalizeDegrees(
                Math.toDegrees(
                    kotlin.math.atan2(
                        (camera.x - card.x).toDouble(),
                        (camera.z - card.z).toDouble(),
                    )
                ).toFloat()
            )
            assertEquals(
                "world heading at turntable $turntableYaw°",
                0f,
                CalloutLayout.normalizeDegrees(turntableYaw + localYaw - expected),
                1e-2f,
            )
        }
    }

    @Test
    fun `billboarding actually differs from the fixed orientation`() {
        // Guards against a regression where both branches returned the same angle and the dock
        // toggle silently did nothing — the exact defect the demo this replaced shipped with.
        val callout = CalloutLayout.CALLOUTS[1]
        val camera = CalloutLayout.cameraHomePosition()

        val billboarded = CalloutLayout.billboardYawDegrees(
            callout = callout,
            spread = CalloutLayout.DEFAULT_SPREAD,
            turntableYawDegrees = 0f,
            cameraWorldPosition = camera,
        )
        val fixed = CalloutLayout.fixedYawDegrees(callout)

        assertTrue(
            "billboard $billboarded° vs fixed $fixed° should differ by more than a degree",
            abs(CalloutLayout.normalizeDegrees(billboarded - fixed)) > 1f,
        )
    }

    @Test
    fun `a camera sitting on the card yields no rotation instead of NaN`() {
        val position = CalloutLayout.CONTROL_CARD_POSITION

        val yaw = CalloutLayout.billboardYawDegrees(
            cardWorldPosition = position,
            cameraWorldPosition = position,
        )

        assertEquals(0f, yaw, 0f)
        assertTrue(yaw.isFinite())
    }

    @Test
    fun `the control card faces the camera without any parent to undo`() {
        val yaw = CalloutLayout.billboardYawDegrees(
            cardWorldPosition = CalloutLayout.CONTROL_CARD_POSITION,
            cameraWorldPosition = CalloutLayout.cameraHomePosition(),
        )

        // The card and the camera home both sit on the +Z axis, so it needs no turn at all.
        assertEquals(0f, yaw, eps)
    }

    // ── Angle wrapping ───────────────────────────────────────────────────────────────────

    @Test
    fun `normalizeDegrees wraps into the half-open turn`() {
        assertEquals(0f, CalloutLayout.normalizeDegrees(0f), 0f)
        assertEquals(0f, CalloutLayout.normalizeDegrees(360f), eps)
        assertEquals(0f, CalloutLayout.normalizeDegrees(-720f), eps)
        assertEquals(180f, CalloutLayout.normalizeDegrees(180f), eps)
        assertEquals(180f, CalloutLayout.normalizeDegrees(-180f), eps)
        assertEquals(-90f, CalloutLayout.normalizeDegrees(270f), eps)
        assertEquals(10f, CalloutLayout.normalizeDegrees(730f), eps)
    }

    @Test
    fun `normalizeDegrees never returns negative zero`() {
        // `-0f == 0f` is true, so only the raw bits catch this — and "-0.0°" in a readout is the
        // kind of thing that gets reported as a bug.
        assertEquals(0, CalloutLayout.normalizeDegrees(-0f).toRawBits())
        assertEquals(0, CalloutLayout.normalizeDegrees(-360f).toRawBits())
    }

    // ── Turntable ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the turntable advances at the declared rate`() {
        val oneSecond = 1_000_000_000L
        assertEquals(
            CalloutLayout.SPIN_DEGREES_PER_SECOND,
            CalloutLayout.nextTurntableYaw(previousDegrees = 0f, deltaNanos = oneSecond),
            eps,
        )
    }

    @Test
    fun `the turntable wraps instead of growing without bound`() {
        val hour = 3_600_000_000_000L
        val yaw = CalloutLayout.nextTurntableYaw(previousDegrees = 350f, deltaNanos = hour)

        assertTrue("yaw was $yaw", yaw >= 0f && yaw < 360f)
    }

    @Test
    fun `a non-advancing frame leaves the turntable where it was`() {
        assertEquals(42f, CalloutLayout.nextTurntableYaw(42f, deltaNanos = 0L), 0f)
        assertEquals(42f, CalloutLayout.nextTurntableYaw(42f, deltaNanos = -5L), 0f)
    }

    // ── Camera framing ───────────────────────────────────────────────────────────────────

    @Test
    fun `the camera home vector's length is the orbit distance`() {
        // `rememberCameraManipulator` reads the LENGTH of orbitHomePosition as the distance
        // (#2930), so this is the property the framing actually depends on.
        val home = CalloutLayout.cameraHomePosition()
        val offset = hypot(home.x, hypot(home.y - CalloutLayout.TARGET_Y, home.z))

        assertEquals(CalloutLayout.CAMERA_DISTANCE, offset, eps)
    }

    @Test
    fun `an explicit camera_distance is honoured, and the look-down angle survives it`() {
        val near = CalloutLayout.cameraHomePosition(1.2f)
        val far = CalloutLayout.cameraHomePosition(4f)

        assertEquals(
            1.2f,
            hypot(near.x, hypot(near.y - CalloutLayout.TARGET_Y, near.z)),
            eps,
        )
        // Same pitch at both distances: height / distance is constant.
        assertEquals(
            (near.y - CalloutLayout.TARGET_Y) / 1.2f,
            (far.y - CalloutLayout.TARGET_Y) / 4f,
            eps,
        )
    }

    @Test
    fun `a zero camera_distance is clamped instead of collapsing the scene`() {
        val degenerate = CalloutLayout.cameraHomePosition(0f)

        assertTrue(degenerate.z > 0f)
        assertTrue(degenerate.y.isFinite())
    }

    // ── The scene's own invariants ───────────────────────────────────────────────────────

    @Test
    fun `every callout is distinct, described, and clear of the model`() {
        val callouts = CalloutLayout.CALLOUTS
        assertEquals(callouts.size, callouts.map { it.id }.toSet().size)

        callouts.forEach { callout ->
            assertTrue("blank title on ${callout.id}", callout.title.isNotBlank())
            assertTrue("blank body on ${callout.id}", callout.body.isNotBlank())
            // The default radius has to clear the model's own half-extent, or a card spawns
            // inside the helmet and the demo opens on a defect.
            assertTrue(
                "${callout.id} starts inside the model",
                CalloutLayout.DEFAULT_SPREAD > CalloutLayout.MODEL_SIZE_METERS / 2f,
            )
        }
    }

    @Test
    fun `no two callouts land on top of each other at the default spread`() {
        val positions = CalloutLayout.CALLOUTS.map {
            CalloutLayout.localPosition(it, CalloutLayout.DEFAULT_SPREAD)
        }
        positions.forEachIndexed { i, a ->
            positions.drop(i + 1).forEach { b ->
                val separation = hypot(a.x - b.x, hypot(a.y - b.y, a.z - b.z))
                assertTrue("cards $a and $b are $separation m apart", separation > 0.2f)
            }
        }
    }

    @Test
    fun `the control card is in front of the model and below it, out of the callouts' way`() {
        val control = CalloutLayout.CONTROL_CARD_POSITION

        assertTrue("control card must be toward the camera", control.z > 0f)
        assertTrue("control card must sit below the model", control.y < 0f)
        CalloutLayout.CALLOUTS.forEach { callout ->
            val card = CalloutLayout.localPosition(callout, CalloutLayout.DEFAULT_SPREAD)
            val separation = hypot(control.x - card.x, hypot(control.y - card.y, control.z - card.z))
            assertTrue("${callout.id} overlaps the control card", separation > 0.2f)
        }
    }

    @Test
    fun `the card scale range brackets its default`() {
        assertTrue(CalloutLayout.MIN_CARD_SCALE < CalloutLayout.DEFAULT_CARD_SCALE)
        assertTrue(CalloutLayout.DEFAULT_CARD_SCALE < CalloutLayout.MAX_CARD_SCALE)
        assertTrue(CalloutLayout.MIN_SPREAD < CalloutLayout.DEFAULT_SPREAD)
        assertTrue(CalloutLayout.DEFAULT_SPREAD < CalloutLayout.MAX_SPREAD)
    }

    private fun radius(position: Position) = hypot(position.x, position.z)
}
