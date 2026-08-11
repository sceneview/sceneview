// Render-loop helpers for the `SceneView` composable. They live beside it rather than inside
// SceneView.kt because that file sits at detekt's `allowedFunctionsPerFile` ceiling (25) — one more
// declaration there fails the Lint leg, and raising the ceiling to fit a helper would relax the
// rule for every file in the module.
package io.github.sceneview

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Suspends until [isRendering] reads `true`, then returns. Returns immediately — without
 * allocating — when it already reads `true`, which is the per-frame hot path.
 *
 * This is the `isRendering = false` half of [SceneView]'s render loop, extracted so the parking
 * *semantics* are unit-testable without a Filament engine or a device (`SceneIsRenderingTest`).
 *
 * The distinction it pins is the whole point of #3108: pausing must **park** the coroutine, not
 * poll it. A `while (!value) delay(16)` loop stops the GPU work but keeps the CPU waking 60x a
 * second forever on a scene that never changes, which on the reporting devices is a large part of
 * what makes an idle 3D screen run hot. Reading through a [State] and suspending on
 * [snapshotFlow] means an idle scene schedules nothing at all until a recomposition writes the
 * flag back to `true`.
 */
internal suspend fun awaitRenderingEnabled(isRendering: State<Boolean>) {
    if (isRendering.value) return
    snapshotFlow { isRendering.value }.first { it }
}
