package io.github.sceneview.compose

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import dev.romainguy.kotlin.math.Float3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `CameraState` is pure `commonMain` Kotlin with no renderer behind it, so its clamping
 * and its `Saver` round-trip are directly testable — and they are exactly the kind of
 * invariant that breaks silently: a camera that rolls at the pole or a rotation that
 * quietly re-enables gestures looks like a rendering glitch, not a state bug.
 */
class CameraStateTest {

    private fun state(
        target: Float3 = Float3(0f, 0f, 0f),
        distance: Float = 4f,
        azimuth: Float = 0f,
        elevation: Float = 0f,
    ) = CameraState(target, distance, azimuth, elevation)

    // ── Elevation clamping ──────────────────────────────────────────────────

    @Test
    fun elevation_is_clamped_short_of_both_poles() {
        // At exactly ±90° the up vector and the view direction become colinear and the
        // orbit basis is undefined, so the clamp must stop strictly short of them.
        assertEquals(89.9f, state(elevation = 90f).elevation)
        assertEquals(89.9f, state(elevation = 1_000f).elevation)
        assertEquals(-89.9f, state(elevation = -90f).elevation)
        assertEquals(-89.9f, state(elevation = -1_000f).elevation)
    }

    @Test
    fun elevation_clamps_on_write_not_only_on_construction() {
        // The gesture path writes the property; a clamp that only ran in the
        // constructor would let a drag walk the camera straight through the pole.
        val camera = state()
        camera.elevation = 90f
        assertEquals(89.9f, camera.elevation)
        camera.elevation = -90f
        assertEquals(-89.9f, camera.elevation)
    }

    @Test
    fun elevation_inside_the_range_is_untouched() {
        assertEquals(45f, state(elevation = 45f).elevation)
        assertEquals(-89.8f, state(elevation = -89.8f).elevation)
    }

    // ── Distance clamping ───────────────────────────────────────────────────

    @Test
    fun distance_stays_strictly_positive() {
        // Zero or negative distance puts the eye on (or behind) the target, which is a
        // degenerate view matrix rather than a very close-up one.
        assertTrue(state(distance = 0f).distance > 0f)
        assertTrue(state(distance = -5f).distance > 0f)
        assertEquals(0.01f, state(distance = 0f).distance)
    }

    @Test
    fun distance_clamps_on_write() {
        val camera = state()
        camera.distance = -1f
        assertEquals(0.01f, camera.distance)
    }

    // ── Saver round-trip ────────────────────────────────────────────────────

    private object AlwaysSave : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    // Bound to the interface type explicitly: `CameraState.Saver` (the companion
    // property) and `Saver` (the type) otherwise collide during resolution.
    private val saver: Saver<CameraState, List<Float>> get() = CameraState.Saver

    private fun roundTrip(original: CameraState): CameraState {
        // `save` is a member of Saver whose EXTENSION receiver is SaverScope
        // (`save(SaverScope, Original)` on the JVM), so the Saver goes in `with` and
        // the scope becomes the dot-receiver — not the other way round.
        val saved = with(saver) { AlwaysSave.save(original) }
        return assertNotNull(saver.restore(assertNotNull(saved)))
    }

    @Test
    fun saver_round_trip_preserves_every_property() {
        val original = state(
            target = Float3(1f, 2f, 3f),
            distance = 7.5f,
            azimuth = 33f,
            elevation = 12f,
        )

        val restored = roundTrip(original)

        assertEquals(original.target, restored.target)
        assertEquals(original.distance, restored.distance)
        assertEquals(original.azimuth, restored.azimuth)
        assertEquals(original.elevation, restored.elevation)
    }

    @Test
    fun saver_round_trip_preserves_gesturesEnabled_when_disabled() {
        // The one that actually bites: restoring a state that silently re-enabled
        // gestures after a rotation is worse than not restoring at all, because the
        // screen becomes interactive again without the app ever asking for it.
        val original = state().apply { gesturesEnabled = false }

        assertEquals(false, roundTrip(original).gesturesEnabled)
    }

    @Test
    fun saver_round_trip_preserves_gesturesEnabled_when_enabled() {
        val original = state().apply { gesturesEnabled = true }

        assertEquals(true, roundTrip(original).gesturesEnabled)
    }

    @Test
    fun saver_restores_a_clamped_state_unchanged() {
        // A saved state is already clamped, so restoring must be a fixed point — a
        // second clamp pass that shifted the value would drift the camera a little on
        // every configuration change.
        val original = state(distance = -3f, elevation = 500f)

        val restored = roundTrip(original)

        assertEquals(original.distance, restored.distance)
        assertEquals(original.elevation, restored.elevation)
    }
}
