package io.github.sceneview.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.MaterialInstance
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setMetallic
import io.github.sceneview.material.setReflectance
import io.github.sceneview.material.setRoughness

/**
 * Creates a colored PBR [MaterialInstance] tied to the composition's
 * lifecycle. Equivalent to `materialLoader.createColorInstance(color)` but
 * automatically calls `destroyMaterialInstance(...)` in a
 * [DisposableEffect] `onDispose` when the composition leaves or any of the
 * keys change.
 *
 * Why this exists
 * ===============
 * Filament's `MaterialLoader.createColorInstance(...)` allocates a JNI
 * `MaterialInstance` handle (plus a small uniform buffer). Each handle
 * leaks GPU + JNI memory if it's never destroyed. Across the 11 demos that
 * pre-#937 allocated colored instances with no disposal, a single
 * navigation cycle (home → demo list → demo → home) leaked ~26 handles.
 *
 * Mirroring the `rememberModelInstance` / `rememberEngine` /
 * `rememberMaterialLoader` pattern, this helper inverts the contract: the
 * composable owns the resource lifecycle, not the demo.
 *
 * Usage
 * =====
 * ```kotlin
 * val sphereMaterial = rememberMaterialInstance(materialLoader,
 *     SceneViewColors.Primary)
 * ```
 *
 * `materialLoader` and the `key` are observed — a change to either swaps
 * the instance (destroying the old one) so re-keyable previews work
 * without leaks.
 *
 * @param materialLoader the [MaterialLoader] to allocate against. Usually
 *   obtained from `rememberMaterialLoader(engine)`.
 * @param color the PBR colour applied to the instance.
 * @param metallic 0..1, default 0.4 — same default as
 *   `MaterialLoader.createColorInstance`.
 * @param roughness 0..1, default 0.4 — same default.
 * @param reflectance 0..1, default 0.5 — same default.
 */
@Composable
fun rememberMaterialInstance(
    materialLoader: MaterialLoader,
    color: Color,
    metallic: Float = 0.4f,
    roughness: Float = 0.4f,
    reflectance: Float = 0.5f,
): MaterialInstance {
    // CRITICAL: do NOT include `metallic / roughness / reflectance` in the
    // `remember(...)` key. Slider-driven demos (Geometry, Lighting,
    // CustomMesh, …) tweak these at 60 Hz; if they were keys, every
    // slider tick would destroy + recreate the MaterialInstance, hammering
    // Filament's main-thread JNI surface. Allocate once on `color` change
    // (color rarely toggles) and push parameter updates via setMetallic /
    // setRoughness / setReflectance in a LaunchedEffect at the call site.
    // The defaults below seed the initial allocation; subsequent updates
    // are the caller's responsibility. Caught by an independent #937 review.
    val instance = remember(materialLoader, color) {
        materialLoader.createColorInstance(color, metallic, roughness, reflectance)
    }
    // Reflect dynamic PBR updates without churning the instance.
    DisposableEffect(instance, metallic, roughness, reflectance) {
        instance.setMetallic(metallic)
        instance.setRoughness(roughness)
        instance.setReflectance(reflectance)
        onDispose { /* parameter setters are no-op on destroy; instance cleanup is the
                       outer onDispose below */ }
    }
    DisposableEffect(instance) {
        onDispose { materialLoader.destroyMaterialInstance(instance) }
    }
    return instance
}

/**
 * Unlit variant of [rememberMaterialInstance]. Same disposal contract,
 * different shader path — see `MaterialLoader.createUnlitColorInstance`.
 *
 * Picks `unlit` materials for overlays / UI billboards where you DON'T
 * want the colour to react to scene lighting (e.g. plane visualisation,
 * debug grids, AR placement reticles).
 */
@Composable
fun rememberUnlitMaterialInstance(
    materialLoader: MaterialLoader,
    color: Color,
): MaterialInstance {
    val instance = remember(materialLoader, color) {
        materialLoader.createUnlitColorInstance(color)
    }
    DisposableEffect(instance) {
        onDispose { materialLoader.destroyMaterialInstance(instance) }
    }
    return instance
}

/**
 * Occlusion variant of [rememberMaterialInstance] — invisible, depth-writing.
 *
 * Same disposal contract as [rememberMaterialInstance] (#1776). Wears
 * `MaterialLoader.createOcclusionInstance()` on any node that should
 * **occlude** virtual content behind it without painting any colour itself —
 * the SceneView equivalent of RealityKit's `OcclusionMaterial` and Sceneform
 * legacy's `MaterialFactory.makeOcclusionMaterial(...)`.
 *
 * No `color` key — the material has nothing to tint, and skipping the
 * remember key means the instance is allocated exactly once per composition.
 *
 * For AR scenes that need to occlude virtual content against the **live
 * depth camera** rather than a static mesh, use
 * [`ARCameraStream.isDepthOcclusionEnabled`][io.github.sceneview.ar.camera.ARCameraStream]
 * — that path samples ARCore's per-pixel depth image.
 */
@Composable
fun rememberOcclusionMaterialInstance(
    materialLoader: MaterialLoader,
): MaterialInstance {
    val instance = remember(materialLoader) {
        materialLoader.createOcclusionInstance()
    }
    DisposableEffect(instance) {
        onDispose { materialLoader.destroyMaterialInstance(instance) }
    }
    return instance
}
