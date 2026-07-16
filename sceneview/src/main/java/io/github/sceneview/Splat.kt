// Compose entry point for 3D Gaussian Splatting (#2646). Lives in its own file — not
// SceneView.kt, which is at detekt's TooManyFunctions file cap — same package, so imports are
// unaffected (the SurfaceMirroring.kt precedent, #2626).
package io.github.sceneview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.sceneview.core.splat.SplatCloud

/**
 * Creates and remembers a [SplatCloud] — the in-memory 3D Gaussian Splatting data model consumed
 * by [SceneScope.SplatNode] (#2646).
 *
 * [creator] runs once (and again whenever any of [keys] changes) — build or decode the cloud
 * there. Construction is pure CPU (no Filament calls), so a cheap synthetic cloud can be built
 * inline; keep heavy decoding out of composition by hoisting it behind your own state.
 *
 * ```kotlin
 * val cloud = rememberSplatCloud {
 *     SplatCloud(count = n, positions = ..., scales = ..., rotations = ..., colors = ..., opacities = ...)
 * }
 * SceneView { SplatNode(splatCloud = cloud) }
 * ```
 *
 * File-based overloads (`.ply` / `.spz` via `SplatParser`) arrive with the #2646 loader
 * workstream (P1a).
 *
 * @param keys    When any key changes, [creator] runs again and produces a new cloud.
 * @param creator Builds the [SplatCloud].
 */
@Composable
fun rememberSplatCloud(vararg keys: Any?, creator: () -> SplatCloud): SplatCloud =
    remember(keys = keys) { creator() }
