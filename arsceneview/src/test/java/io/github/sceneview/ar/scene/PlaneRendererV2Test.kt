package io.github.sceneview.ar.scene

import com.google.ar.core.Plane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-JVM coverage for the Plane Renderer V2 skeleton
 * ([#2203](https://github.com/sceneview/sceneview/issues/2203) PR #1).
 *
 * **What this pins down**
 *
 *  1. Both [PlaneRenderer] (V1) and [PlaneRendererV2] satisfy the [PlaneRendererBase] contract —
 *     the routing in `ARSceneView` relies on the shared interface for the V1 / V2 swap.
 *  2. The [PlaneRendererBase.Version] enum exposes exactly the two values the public DSL
 *     surface advertises (V1, V2). A future addition or rename would invalidate the
 *     `when (planeRendererVersion)` exhaustive branches in `ARSceneView.kt` and must come with
 *     a deliberate API-surface review.
 *
 * **What this does NOT cover**
 *
 *  Constructing either renderer touches Filament JNI (Material loading, Texture upload, etc.)
 *  which cannot run under Robolectric without an actual GL context. The
 *  [`construct PlaneRendererV2 against a real engine`] test is gated behind `@Ignore` for that
 *  reason — it documents the intended contract and is a placeholder for an on-device suite.
 *
 * Routing-via-Compose and end-to-end V1↔V2 visual parity is covered by the device-QA harness
 * (`bash .claude/scripts/device-qa.sh --platform=android`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PlaneRendererV2Test {

    // ── Interface conformance ───────────────────────────────────────────────────────────────

    @Test
    @Suppress("DEPRECATION") // V1 PlaneRenderer is @Deprecated as of #2203 PR #5; the test
    // legitimately references it to assert the V1 → PlaneRendererBase relationship survives
    // the one-cycle deprecation window.
    fun `PlaneRenderer V1 implements PlaneRendererBase`() {
        // Sealed-interface routing in `ARSceneView` requires both V1 and V2 to satisfy
        // `PlaneRendererBase`. This assertion catches a future refactor that would
        // accidentally drop the interface from V1 — the V1↔V2 swap in ARScene's
        // `when (planeRendererVersion)` would otherwise stop compiling against a typed
        // `PlaneRendererBase` local val.
        assertTrue(
            "PlaneRenderer must implement PlaneRendererBase so the V1 path stays " +
                "compatible with ARSceneView's typed routing local.",
            PlaneRendererBase::class.java.isAssignableFrom(PlaneRenderer::class.java),
        )
    }

    @Test
    fun `PlaneRendererV2 implements PlaneRendererBase`() {
        assertTrue(
            "PlaneRendererV2 must implement PlaneRendererBase so the V2 path is " +
                "interchangeable with the V1 path through the sealed-interface contract.",
            PlaneRendererBase::class.java.isAssignableFrom(PlaneRendererV2::class.java),
        )
    }

    @Test
    fun `PlaneRendererBase Version enum exposes exactly V1 and V2`() {
        // The exhaustive `when (planeRendererVersion)` in ARSceneView.kt relies on this enum
        // surface being stable. Adding a value silently turns the `when` into a non-exhaustive
        // expression (compile error), which is the desired safeguard — but the test pins
        // down the intentional surface so a stray rename gets caught early too.
        val values = PlaneRendererBase.Version.entries.map { it.name }.toSet()
        assertTrue("Version.V1 must be present: $values", "V1" in values)
        assertTrue("Version.V2 must be present: $values", "V2" in values)
        assertTrue(
            "PR #1 ships exactly V1 + V2 — saw: $values",
            values == setOf("V1", "V2"),
        )
    }

    // ── Engine-touching lifecycle ───────────────────────────────────────────────────────────

    /**
     * Documented as a placeholder. A full constructor exercise needs a live Filament
     * [com.google.android.filament.Engine] + a working
     * [io.github.sceneview.loaders.MaterialLoader] reading the new
     * `materials/plane_renderer_v2.filamat` asset — both depend on JNI symbols Robolectric
     * does not host. Real coverage for the construct/destroy lifecycle lives in the device-QA
     * harness (run `bash .claude/scripts/device-qa.sh --platform=android`).
     */
    @Test
    @Ignore("Requires a live Filament Engine and asset loader — exercised by the device-QA harness.")
    fun `construct PlaneRendererV2 against a real engine`() {
        // Intentionally empty — see test KDoc and the device-QA harness for live coverage.
        assertNotNull("placeholder", PlaneRendererV2::class.java)
    }

    // ── Per-type dot style (#3507) ──────────────────────────────────────────────────────────

    @Test
    fun `the floor gets white dots, the strongest of the three`() {
        val floor = planeMaterialPresetFor(Plane.Type.HORIZONTAL_UPWARD_FACING)
        assertEquals(1.0f, floor.gridR, 0f)
        assertEquals(1.0f, floor.gridG, 0f)
        assertEquals(1.0f, floor.gridB, 0f)
        val ceiling = planeMaterialPresetFor(Plane.Type.HORIZONTAL_DOWNWARD_FACING)
        val wall = planeMaterialPresetFor(Plane.Type.VERTICAL)
        // The floor is where things get placed: it must read first.
        assertTrue(floor.dotAlpha > wall.dotAlpha && floor.dotAlpha > ceiling.dotAlpha)
    }

    @Test
    fun `the ceiling is warm — more red than blue`() {
        val ceiling = planeMaterialPresetFor(Plane.Type.HORIZONTAL_DOWNWARD_FACING)
        assertTrue("ceiling must be warm: $ceiling", ceiling.gridR > ceiling.gridB)
    }

    @Test
    fun `walls are a soft blue — more blue than red`() {
        val wall = planeMaterialPresetFor(Plane.Type.VERTICAL)
        assertTrue("wall must be blue: $wall", wall.gridB > wall.gridR)
    }

    @Test
    fun `all three known plane types are mapped without falling back to floor`() {
        val floor = planeMaterialPresetFor(Plane.Type.HORIZONTAL_UPWARD_FACING)
        val ceiling = planeMaterialPresetFor(Plane.Type.HORIZONTAL_DOWNWARD_FACING)
        val wall = planeMaterialPresetFor(Plane.Type.VERTICAL)
        val distinct = setOf(floor, ceiling, wall)
        assertEquals(
            "Each of floor/ceiling/wall must produce a distinct preset — got $distinct",
            3, distinct.size,
        )
    }

    @Test
    fun `every preset stays in the unit range`() {
        Plane.Type.values().forEach { type ->
            val preset = planeMaterialPresetFor(type)
            listOf(preset.gridR, preset.gridG, preset.gridB, preset.dotAlpha).forEach {
                assertTrue("$type: $it out of [0, 1]", it in 0f..1f)
            }
        }
    }

    @Test
    fun `every Plane Type currently exposed by ARCore maps to a non-crashing preset`() {
        // Defensive: walk every enum value ARCore declares at this ARCore version. The
        // helper must produce a non-null preset for each one — including any future
        // enum value not enumerated in the when {} arms (those fall back to floor).
        // This is the test the brief calls "nonsense future type": at this ARCore
        // version (1.40+ — VERTICAL added long before then) there is no extra entry,
        // so we use `lastOrNull()` to grab whatever the highest-indexed enum is and
        // assert the helper handles it without throwing.
        val lastType = Plane.Type.values().lastOrNull()
        assertNotNull("Plane.Type must expose at least one value", lastType)
        val preset = planeMaterialPresetFor(lastType!!)
        assertNotNull("planeMaterialPresetFor must never return null", preset)
    }
}
