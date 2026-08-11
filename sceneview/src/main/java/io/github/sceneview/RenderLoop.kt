// Render-loop helpers for the `SceneView` composable. They live beside it rather than inside
// SceneView.kt because that file sits at detekt's `allowedFunctionsPerFile` ceiling (25) — one more
// declaration there fails the Lint leg, and raising the ceiling to fit a helper would relax the
// rule for every file in the module.
package io.github.sceneview

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Suspends until [shouldRender] reads `true`, then returns. Returns immediately — without
 * allocating — when it already reads `true`, which is the per-frame hot path.
 *
 * This is the paused half of [SceneView]'s render loop, extracted so the parking *semantics* are
 * unit-testable without a Filament engine or a device (`SceneIsRenderingTest`).
 *
 * The distinction it pins is the whole point of #3108: pausing must **park** the coroutine, not
 * poll it. A `while (!value) delay(16)` loop stops the GPU work but keeps the CPU waking 60x a
 * second forever on a scene that never changes, which on the reporting devices is a large part of
 * what makes an idle 3D screen run hot. Reading through a [State] and suspending on
 * [snapshotFlow] means an idle scene schedules nothing at all until a snapshot apply writes the
 * flag back to `true`.
 *
 * [shouldRender] is deliberately wider than the caller's `isRendering` parameter: the loop must
 * also wake when the current surface is still owed a frame, because a newly created swap chain
 * holds no pixels and a park would otherwise leave it blank indefinitely (#3109). Pass a
 * [State] that already folds both conditions together — this function only knows "may I sleep?".
 */
internal suspend fun awaitRenderingEnabled(shouldRender: State<Boolean>) {
    if (shouldRender.value) return
    snapshotFlow { shouldRender.value }.first { it }
}
