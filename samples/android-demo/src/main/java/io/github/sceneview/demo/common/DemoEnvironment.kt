@file:OptIn(io.github.sceneview.ExperimentalSceneViewApi::class)

package io.github.sceneview.demo.common

import androidx.compose.runtime.Composable
import io.github.sceneview.environment.Environment
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.rememberEnvironment

/**
 * Shared image-based-lighting (IBL) environment for every **non-AR** demo that
 * displays a loaded glTF model (#2110).
 *
 * ## Why this exists
 *
 * A glTF model with metallic / smooth PBR materials needs an IBL environment to
 * reflect. SceneView's *default* environment ([rememberEnvironment]) is the
 * lightweight `neutral_ibl.ktx` paired with a **solid black skybox** — so a
 * metallic surface has nothing bright to reflect and renders **solid black**.
 * That is the exact symptom reported in #2110 for the Damaged Helmet across
 * several demos.
 *
 * This helper returns a proper studio HDR environment instead: the same
 * `studio_2k.hdr` IBL the multi-model "park" scene uses, which lights metallic
 * PBR materials correctly. `createSkybox = false` keeps the model floating on
 * the demo's own surface background (no sky drawn) — the demos look exactly as
 * before, just **correctly lit** rather than black.
 *
 * ## Usage
 *
 * ```kotlin
 * val environmentLoader = rememberEnvironmentLoader(engine)
 * val environment = rememberModelDemoEnvironment(environmentLoader)
 * SceneView(
 *     engine = engine,
 *     environmentLoader = environmentLoader,
 *     environment = environment,
 * ) { /* ModelNode(...) */ }
 * ```
 *
 * The HDR decodes asynchronously; until it is ready this falls back to the
 * default neutral environment so the first frames never flash black either.
 *
 * Demos whose *subject* is the environment ([io.github.sceneview.demo.demos.LightingLabDemo]'s
 * Sky / Environment / Reflections tabs) manage their own environment and must
 * NOT use this helper. AR demos use ARCore light estimation, not IBL, and
 * are likewise out of scope.
 */
@Composable
fun rememberModelDemoEnvironment(environmentLoader: EnvironmentLoader): Environment {
    // Studio HDR — bundled in `assets/environments/`, already used by the
    // multi-model demo where the metallic lantern renders correctly. IBL only
    // (no skybox) so the model keeps floating on the demo's surface background.
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        "environments/studio_2k.hdr",
        createSkybox = false,
    )
    // Neutral fallback while the HDR is still decoding — avoids a black flash.
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    return hdrEnvironment ?: fallbackEnvironment
}
