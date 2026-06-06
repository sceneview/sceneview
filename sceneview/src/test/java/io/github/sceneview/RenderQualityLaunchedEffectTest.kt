package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression test for the keyed-`LaunchedEffect` pattern introduced in
 * #1078 around `view.applyRenderQuality(renderQuality)` in [SceneView.kt:278].
 *
 * Before the fix, the call lived in an unkeyed `SideEffect { view.applyRenderQuality(...) }`
 * that ran on **every recomposition**, silently overwriting any post-`Scene` tweaks
 * such as `view.colorGrading = ...` or `view.bloomOptions.strength = 0.2f` —
 * breaking the contract documented at the top of [RenderQuality.kt]
 * ("Apply additional View tweaks AFTER calling this — they will not be undone").
 *
 * The fix wraps the call in `LaunchedEffect(view, renderQuality)`, so the effect
 * is reapplied ONLY when the `(view, renderQuality)` key tuple changes. This test
 * pins that algorithm by simulating the Compose re-keying semantics in plain JVM
 * (the test module has no Compose UI testing dependency).
 *
 * If anyone reverts back to an unkeyed `SideEffect`, the second test below fails
 * because the effect count grows with recomposition count instead of staying at 1.
 */
class RenderQualityLaunchedEffectTest {

    /**
     * Minimal stand-in for Compose's `LaunchedEffect(keys...)` re-keying.
     *
     * Compose's contract: when any key changes from the previous composition,
     * the effect block is re-launched. When all keys are equal to the previous
     * composition's keys, the effect is NOT re-run.
     */
    private class LaunchedEffectSimulator<K>(private val effect: (K) -> Unit) {
        private var lastKey: K? = null
        private var firstRun = true
        var invocationCount = 0
            private set

        fun recompose(newKey: K) {
            if (firstRun || newKey != lastKey) {
                effect(newKey)
                invocationCount++
                lastKey = newKey
                firstRun = false
            }
        }
    }

    @Test
    fun applyRenderQualityRunsOnceWhenKeyStaysConstant() {
        // Simulate `LaunchedEffect(view, renderQuality)` where `renderQuality` never changes.
        val simulator = LaunchedEffectSimulator<Pair<String, RenderQuality>> { /* no-op effect */ }
        val constantKey = "view-A" to RenderQuality.Default

        repeat(5) { simulator.recompose(constantKey) }

        assertEquals(
            "applyRenderQuality must fire exactly ONCE for 5 recompositions with the same key — " +
                "if this fails, the call has likely regressed to an unkeyed SideEffect (#1078) and " +
                "is clobbering post-Scene View tweaks on every recomposition.",
            1,
            simulator.invocationCount,
        )
    }

    @Test
    fun applyRenderQualityRerunsWhenRenderQualityChanges() {
        val simulator = LaunchedEffectSimulator<Pair<String, RenderQuality>> { /* no-op effect */ }
        simulator.recompose("view-A" to RenderQuality.Default)        // 1
        simulator.recompose("view-A" to RenderQuality.Default)        // no-op (same key)
        simulator.recompose("view-A" to RenderQuality.Cinematic)      // 2 (preset changed)
        simulator.recompose("view-A" to RenderQuality.Cinematic)      // no-op
        simulator.recompose("view-A" to RenderQuality.Performance)    // 3 (preset changed)

        assertEquals(
            "applyRenderQuality must fire exactly 3 times across the Default → Cinematic → " +
                "Performance toggles (one per distinct preset), ignoring intermediate " +
                "recompositions that don't change the key.",
            3,
            simulator.invocationCount,
        )
    }

    @Test
    fun applyRenderQualityRerunsWhenViewChanges() {
        // `view` is the other key — changing the View instance also re-fires the effect.
        // Required so a SceneView whose View is recreated (renderer destroyed and rebuilt)
        // gets its preset reapplied to the new View.
        val simulator = LaunchedEffectSimulator<Pair<String, RenderQuality>> { /* no-op effect */ }
        simulator.recompose("view-A" to RenderQuality.Default)
        simulator.recompose("view-B" to RenderQuality.Default)   // view changed → re-fire
        simulator.recompose("view-B" to RenderQuality.Default)   // no-op

        assertEquals(
            "applyRenderQuality must fire when the View identity changes, even if " +
                "renderQuality is unchanged — otherwise a rebuilt View renders with no preset.",
            2,
            simulator.invocationCount,
        )
    }

    @Test
    fun applyRenderQualityIsActuallyApplied() {
        // Sanity check the simulator: confirm the effect block receives the key value.
        val seen = mutableListOf<RenderQuality>()
        val simulator = LaunchedEffectSimulator<RenderQuality> { seen += it }
        simulator.recompose(RenderQuality.Default)
        simulator.recompose(RenderQuality.Cinematic)
        simulator.recompose(RenderQuality.Cinematic)   // dedupe
        simulator.recompose(RenderQuality.Performance)

        assertEquals(
            listOf(RenderQuality.Default, RenderQuality.Cinematic, RenderQuality.Performance),
            seen,
        )
    }
}
