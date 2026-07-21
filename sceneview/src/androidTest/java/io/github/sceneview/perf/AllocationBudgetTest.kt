package io.github.sceneview.perf

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.collision.MeshCollider
import io.github.sceneview.collision.Ray
import io.github.sceneview.collision.Vector3
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.copyColumnsInto
import io.github.sceneview.math.slerp
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Allocation-budget harness (#2761) — resurrects the instrumented allocation-counting
 * methodology of the #2317 profiling pass as a **committed** suite, so the hot-path wins
 * of umbrella #2263 can no longer regress silently.
 *
 * ## Methodology (#2317)
 *
 * Allocations are counted with [Debug.getThreadAllocCount] between
 * [Debug.startAllocCounting]/[Debug.stopAllocCounting], over [ITERATIONS] iterations after
 * [WARMUP] warmed iterations (JIT steady state). The counter is **per-thread**, so JIT
 * compilation and GC activity on background threads never pollute the measurement — which
 * is why #2317 reproduced its numbers byte-identical across two independent runs. No GPU,
 * no rendering, no readback: this suite is deterministic and runs fully on the SwiftShader
 * CI emulator (unlike the render tests, it needs no `gpuReadback` opt-in).
 *
 * [canary_plusOneAllocationIsMeasured] doubles as a *permanent* mutation test: it proves a
 * single extra allocation per call is measured as ~1.0, i.e. every `+ BUDGET_EPSILON`
 * ceiling below would catch an accidental `+1 alloc/call` regression. If the deprecated
 * counting API ever becomes a no-op on a future API level, the canary fails loudly instead
 * of letting the budget tests pass vacuously on a dead instrument.
 *
 * ## Budgets (allocs/call ceilings — #2317 measured values)
 *
 * | Path | Budget | Provenance |
 * |---|---|---|
 * | `slerp` pre-decomposed TRS (#2289) | ≤ 7 | 72 → 7.0 measured in #2317 |
 * | `Mat4.copyColumnsInto` (#2282) | 0 | 1 → 0.0 measured in #2317 |
 * | Ray↔mesh per-triangle (#2286) | ≤ 3/triangle | 2 defensive `Vector3` copies eliminated; the parallel-miss path allocates exactly edge1 + edge2 + h |
 *
 * ## Legitimate-refactor path
 *
 * A budget is a **ceiling**, not a pin — an optimization that lowers a count stays green.
 * If a deliberate refactor legitimately raises one: run this suite on an emulator, read the
 * `AllocBudget` logcat lines (the measured value is also in every failure message), update
 * the budget constant here with a comment linking the PR that justifies the new cost, and
 * say so in the PR body. Never widen a budget to silence a red you can't explain.
 */
@RunWith(AndroidJUnit4::class)
class AllocationBudgetTest {

    private companion object {
        const val TAG = "AllocBudget"
        const val WARMUP = 10_000
        const val ITERATIONS = 10_000

        /** Tight enough that a +1 alloc/call regression (canary-proven measurable) is red. */
        const val BUDGET_EPSILON = 0.01

        const val SLERP_TRS_BUDGET = 7.0
        const val COPY_COLUMNS_BUDGET = 0.0
        const val RAY_TRIANGLE_MISS_BUDGET = 3.0
    }

    /** Blackhole: results are stored so ART cannot elide the measured calls. */
    @Volatile
    private var sink: Any? = null

    private inline fun measureAllocsPerCall(
        warmup: Int = WARMUP,
        iterations: Int = ITERATIONS,
        block: () -> Unit,
    ): Double {
        repeat(warmup) { block() }
        @Suppress("DEPRECATION")
        Debug.startAllocCounting()
        @Suppress("DEPRECATION")
        val before = Debug.getThreadAllocCount()
        repeat(iterations) { block() }
        @Suppress("DEPRECATION")
        val after = Debug.getThreadAllocCount()
        @Suppress("DEPRECATION")
        Debug.stopAllocCounting()
        return (after - before).toDouble() / iterations
    }

    private fun assertBudget(name: String, measured: Double, budget: Double) {
        Log.i(TAG, "$name: measured=$measured allocs/call (budget ≤ $budget)")
        assertTrue(
            "$name regressed: measured $measured allocs/call exceeds the budget of $budget " +
                "(methodology + refactor path: see the class KDoc and #2761/#2317)",
            measured <= budget + BUDGET_EPSILON,
        )
    }

    /**
     * Permanent mutation test + instrument self-check: exactly one allocation per call must
     * be measured as ~1.0. Proves (a) the deprecated counting API still works on this API
     * level, and (b) the budgets below are sensitive to a +1 alloc/call regression.
     */
    @Test
    fun canary_plusOneAllocationIsMeasured() {
        val measured = measureAllocsPerCall { sink = Any() }
        Log.i(TAG, "canary: measured=$measured allocs/call (expected ~1.0)")
        assertTrue(
            "Allocation counting is broken on this API level (measured $measured for a " +
                "1-alloc/call workload) — the budget tests would pass vacuously; fix the " +
                "instrument before trusting this suite",
            measured >= 0.9,
        )
        assertTrue(
            "Allocation counting is too noisy (measured $measured for a 1-alloc/call " +
                "workload) — budgets cannot be asserted reliably",
            measured <= 1.5,
        )
    }

    /** #2289 — smooth-transform slerp, pre-decomposed TRS overload (72 → 7 allocs/call). */
    @Test
    fun slerpTrs_staysWithinBudget() {
        val startPosition = Position(0f, 0f, 0f)
        val startQuaternion = Quaternion(0f, 0f, 0f, 1f)
        val startScale = Scale(1f, 1f, 1f)
        val endPosition = Position(1f, 2f, 3f)
        // 90° around Y: dot with identity = cos(45°) ≈ 0.707 < 0.9995 → the generic
        // (non-near-parallel) slerp path, the one the smooth-transform loop runs.
        val endQuaternion = Quaternion(0f, 0.7071f, 0f, 0.7071f)
        val endScale = Scale(2f, 2f, 2f)

        val measured = measureAllocsPerCall {
            sink = slerp(
                startPosition = startPosition,
                startQuaternion = startQuaternion,
                startScale = startScale,
                endPosition = endPosition,
                endQuaternion = endQuaternion,
                endScale = endScale,
                deltaSeconds = 1.0 / 60.0,
                speed = 5f,
            )
        }
        assertBudget("slerp(TRS)", measured, SLERP_TRS_BUDGET)
    }

    /** #2282 — `Mat4.copyColumnsInto` fires thousands of times/sec via `TransformManager.setTransform`. */
    @Test
    fun mat4CopyColumnsInto_isZeroAlloc() {
        val matrix = Mat4.identity()
        val scratch = FloatArray(16)

        val measured = measureAllocsPerCall {
            sink = matrix.copyColumnsInto(scratch)
        }
        assertBudget("Mat4.copyColumnsInto", measured, COPY_COLUMNS_BUDGET)
    }

    /**
     * #2286 — Ray↔mesh read path. The by-ref fix removed 2 defensive `Vector3` copies
     * *per triangle*; on the parallel-miss path exactly edge1 + edge2 + h remain, so the
     * per-triangle ceiling is 3 and a defensive-copy regression (+2/triangle) is red.
     * Driven through the public [MeshCollider.rayMeshIntersection] entry point so loop
     * regressions (e.g. reintroducing `withIndex()` boxing) are caught too.
     */
    @Test
    fun rayMeshIntersection_perTriangleStaysWithinBudget() {
        val triangleCount = 1_000
        // All triangles in the z=2 plane; ray along +x is parallel to that plane, so every
        // triangle takes the deterministic parallel-miss early-out.
        val triangles = List(triangleCount) { i ->
            val offset = i * 0.001f
            MeshCollider.Triangle(
                Vector3(offset, 0f, 2f),
                Vector3(offset + 1f, 0f, 2f),
                Vector3(offset, 1f, 2f),
            )
        }
        val ray = Ray(Vector3(0f, 0f, 0f), Vector3(1f, 0f, 0f))

        // 1 000 triangles/call: scale the loop down to keep the run short — the measured
        // window still spans ~1.5M counted allocation events.
        val measuredPerCall = measureAllocsPerCall(warmup = 100, iterations = 500) {
            sink = MeshCollider.rayMeshIntersection(ray, triangles)
        }
        val measuredPerTriangle = measuredPerCall / triangleCount
        assertBudget("rayMeshIntersection (per triangle)", measuredPerTriangle, RAY_TRIANGLE_MISS_BUDGET)
    }
}
