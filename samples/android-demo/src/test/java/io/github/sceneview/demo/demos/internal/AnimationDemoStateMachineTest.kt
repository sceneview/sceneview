package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.DemoMath.CameraShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM state-machine tests for `AnimationDemo`'s cinematic camera.
 *
 * `AnimationDemo` drives a `LifecyclePausingLaunchedEffect` that animates the
 * camera through one of five scripted shots. The keyframe choreography of each
 * shot — yaw sweep, orbit radius, FOV range, the cinematic hold beats — is
 * extracted into [DemoMath.cameraModeScript] so it can be exercised here
 * without firing up Compose, the Choreographer, or Filament.
 *
 * These tests pin the *visible* behaviour of every camera shot: a refactor that
 * drifts a tween duration, drops a hold beat, or breaks the dolly-zoom FOV/radius
 * opposition fails here at commit time instead of shipping silently.
 *
 * Closes part of [#880](https://github.com/sceneview/sceneview/issues/880) — the
 * non-AR demo state-machine regression suite.
 */
class AnimationDemoStateMachineTest {

    private val eps = 0.001f

    // ── shot coverage ───────────────────────────────────────────────────────

    @Test
    fun `every camera shot has a script (FREE is intentionally empty)`() {
        for (shot in CameraShot.entries) {
            val script = DemoMath.cameraModeScript(shot)
            if (shot == CameraShot.FREE) {
                assertTrue("FREE shot must be user-driven (no scripted steps)", script.isEmpty())
            } else {
                assertTrue("$shot must have at least one scripted step", script.isNotEmpty())
            }
        }
    }

    @Test
    fun `there are exactly five camera shots`() {
        // Pins the picker chip count in AnimationDemo's controls panel.
        assertEquals(5, CameraShot.entries.size)
    }

    // ── HERO ────────────────────────────────────────────────────────────────

    @Test
    fun `HERO sweeps yaw a full revolution and holds at the front-3-4 angle`() {
        val script = DemoMath.cameraModeScript(CameraShot.HERO)
        // Yaw must reset to 0 then climb monotonically to a full 360° turn.
        val yawTargets = script.mapNotNull { it.yaw }
        assertEquals("HERO must reset yaw to 0", 0f, yawTargets.first(), eps)
        assertEquals("HERO must complete a full revolution", 360f, yawTargets.last(), eps)
        for (i in 1 until yawTargets.size) {
            assertTrue(
                "HERO yaw must be non-decreasing: ${yawTargets[i - 1]} → ${yawTargets[i]}",
                yawTargets[i] >= yawTargets[i - 1],
            )
        }
        // The cinematic beat: a 2 s hold at the front-3/4 (45°) angle.
        val frontThreeQuarter = script.single { it.yaw == 45f }
        assertEquals("HERO must hold 2 s at the 45° beat", 2_000, frontThreeQuarter.holdMillis)
    }

    @Test
    fun `HERO orbits slightly outside the base radius at eyes-level height`() {
        val enter = DemoMath.cameraModeScript(CameraShot.HERO).first()
        assertEquals(DemoMath.BASE_RADIUS + 0.2f, enter.radius!!, eps)
        // 0.55 m sits ~10 cm above the chest target — eyes-level heroic framing.
        assertEquals(0.55f, enter.yHeight!!, eps)
    }

    // ── REVEAL ──────────────────────────────────────────────────────────────

    @Test
    fun `REVEAL pulls the camera back from a close-up to a wide shot`() {
        val script = DemoMath.cameraModeScript(CameraShot.REVEAL)
        val radii = script.mapNotNull { it.radius }
        // The dolly-out IS the shot: radius must strictly increase close-up → wide.
        assertEquals("REVEAL must start at a 1.5 m close-up", 1.5f, radii.first(), eps)
        assertTrue("REVEAL must pull back to a wide shot", radii.last() > radii.first())
        // The camera rises as it pulls back so the rooftop floor stays visible.
        val heights = script.mapNotNull { it.yHeight }
        assertTrue("REVEAL camera must rise during the pull-back", heights.last() > heights.first())
        // REVEAL never orbits — yaw is pinned off-axis at 15°.
        val yaws = script.mapNotNull { it.yaw }
        assertEquals("REVEAL holds a single off-axis yaw", listOf(15f), yaws)
    }

    // ── VERTIGO ─────────────────────────────────────────────────────────────

    @Test
    fun `VERTIGO moves radius and FOV in opposition (dolly-zoom)`() {
        val script = DemoMath.cameraModeScript(CameraShot.VERTIGO)
        val vIn = script.single { it.label == "vertigo-in" }
        val vOut = script.single { it.label == "vertigo-out" }
        // Vertigo IN: camera dollies AWAY (radius grows) while the lens NARROWS.
        assertEquals(5.0f, vIn.radius!!, eps)
        assertEquals(25f, vIn.fov!!, eps)
        // Vertigo OUT reverses both: radius shrinks back, FOV widens back.
        assertEquals(2.0f, vOut.radius!!, eps)
        assertEquals(60f, vOut.fov!!, eps)
        // The opposition is the whole point — radius up ⇔ FOV down.
        assertTrue("dolly-zoom: radius and FOV must move opposite", vIn.radius!! > vOut.radius!!)
        assertTrue("dolly-zoom: radius and FOV must move opposite", vIn.fov!! < vOut.fov!!)
    }

    @Test
    fun `VERTIGO FOV range stays inside Filament's valid projection bounds`() {
        // setProjection clamps silently to 0 < fov < 180; an out-of-range keyframe
        // would be a silent no-op. Pin every FOV target to the sane cinema range.
        for (step in DemoMath.cameraModeScript(CameraShot.VERTIGO)) {
            step.fov?.let { fov ->
                assertTrue("VERTIGO fov $fov must be > 0", fov > 0f)
                assertTrue("VERTIGO fov $fov must be < 180", fov < 180f)
            }
        }
    }

    // ── TRACKING ────────────────────────────────────────────────────────────

    @Test
    fun `TRACKING is a single timed lateral sweep`() {
        val script = DemoMath.cameraModeScript(CameraShot.TRACKING)
        assertEquals("TRACKING is one continuous sweep beat", 1, script.size)
        val sweep = script.single()
        assertTrue("TRACKING sweep must take real time", sweep.durationMillis > 0)
        // TRACKING drives an absolute eye position, bypassing the spherical
        // (yaw, radius, yHeight) path — so those fields stay null.
        assertEquals(null, sweep.yaw)
        assertEquals(null, sweep.radius)
        assertEquals(null, sweep.yHeight)
    }

    // ── FREE ────────────────────────────────────────────────────────────────

    @Test
    fun `FREE shot hands all control to the user`() {
        assertTrue(DemoMath.cameraModeScript(CameraShot.FREE).isEmpty())
    }

    // ── timing invariants shared across all scripted shots ──────────────────

    @Test
    fun `every tween has a non-negative duration and hold`() {
        for (shot in CameraShot.entries) {
            for (step in DemoMath.cameraModeScript(shot)) {
                assertTrue(
                    "$shot/${step.label} duration must be >= 0",
                    step.durationMillis >= 0,
                )
                assertTrue(
                    "$shot/${step.label} hold must be >= 0",
                    step.holdMillis >= 0,
                )
            }
        }
    }

    @Test
    fun `instant snaps never carry a hold beat`() {
        // A 0 ms step is a snapTo — pairing it with a hold would be a dead delay.
        for (shot in CameraShot.entries) {
            for (step in DemoMath.cameraModeScript(shot)) {
                if (step.durationMillis == 0) {
                    assertEquals(
                        "$shot/${step.label}: instant snap must not hold",
                        0, step.holdMillis,
                    )
                }
            }
        }
    }

    // ── slider ranges (controls-panel state) ────────────────────────────────

    @Test
    fun `playback speed slider range is a quarter-speed to triple-speed window`() {
        val range = DemoMath.ANIMATION_SPEED_RANGE
        assertEquals(0.25f, range.start, eps)
        assertEquals(3f, range.endInclusive, eps)
        // 1.0x (the default) must sit inside the range.
        assertTrue("default 1.0x speed must be selectable", 1f in range)
    }

    @Test
    fun `IBL intensity slider spans pitch-black to the balanced default`() {
        val range = DemoMath.IBL_INTENSITY_RANGE
        assertEquals(0f, range.start, eps)
        // 10_000 lux is SceneView's balanced IBL default and the AnimationDemo
        // default — it must be the slider's upper bound, not out of range (#1468).
        assertEquals(10_000f, range.endInclusive, eps)
        assertTrue("balanced 10k-lux default must be selectable", 10_000f in range)
        assertFalse("a negative IBL intensity must be unreachable", -1f in range)
    }
}
