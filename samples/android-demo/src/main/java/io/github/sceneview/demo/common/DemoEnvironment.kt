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
        MODEL_DEMO_HDR,
        createSkybox = false,
    )
    // Neutral fallback while the HDR is still decoding — avoids a black flash.
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    return hdrEnvironment ?: fallbackEnvironment
}

/** The single HDRI [rememberModelDemoEnvironment] lights every model demo with. */
const val MODEL_DEMO_HDR: String = "environments/studio_2k.hdr"

/**
 * The **one** HDRI the material showcases light with.
 *
 * ## Why it is a named constant, and why it is not `studio_2k` (#2874)
 *
 * The `materials` demo has to produce the same frame on every cold launch: it is
 * a store-screenshot candidate, a Maestro subject and a listing-diff input, and
 * all three break when the lighting can vary run to run. Naming the HDRI here —
 * one constant, shared by every section of the demo — makes "which environment
 * does this demo use?" answerable by reading one line instead of auditing each
 * section's own `rememberHDREnvironment` call.
 *
 * The sections used to draw the **`studio_2k`** skybox, and #2874 reported that
 * as "the demo picks a different HDRI on each launch — a Christmas-tree room, a
 * window with plants". It never picked anything: decoding the asset shows
 * `studio_2k.hdr` is a **domestic living-room interior** (sofa, lamps, decorated
 * tree, windows) despite the `neutral / studio / product` tags it carries in
 * `assets/catalog.json`. See [rememberMaterialsShowcaseEnvironment] for why the
 * skybox is no longer drawn at all.
 *
 * `studio_warm_2k.hdr` is the actual photo studio of the two — a dark surround,
 * a seamless white sweep and a few big softboxes — so as an IBL it gives
 * clearcoat, transmission and sheen the broad, directional highlights they need
 * to read as materials rather than as flat colour.
 *
 * Demos whose *subject* is the environment
 * ([io.github.sceneview.demo.demos.LightingLabDemo]) pick their own HDRI on
 * purpose and must not use this.
 */
const val MATERIALS_SHOWCASE_HDR: String = "environments/studio_warm_2k.hdr"

/**
 * Image-based lighting for the material showcases — [MATERIALS_SHOWCASE_HDR],
 * IBL only, **no skybox**.
 *
 * ## Why no skybox (#2874) — measured, not assumed
 *
 * Drawing the skybox is what made the demo's *backdrop* unreproducible, and
 * switching HDRIs does not fix it. Two cold launches of the `materials` demo,
 * same build, same device, captured with a drawn `studio_warm` skybox came back
 * with the same subject in front of two completely different backgrounds: the
 * studio's dark surround in one, its bright white sweep in the other. The cause
 * is not the asset — it is that the hero camera orbits 360° every 18 s, so the
 * capture sees whatever part of the environment sphere the orbit happens to be
 * facing. Any photographic HDRI has that property; the fix is to stop painting
 * it behind the subject.
 *
 * With IBL only, the subject sits on the demo's own flat surface background —
 * identical at every yaw — while the *materials themselves* still read the
 * environment: clearcoat highlights, sheen falloff and transmission all sample
 * the same studio IBL as before. What is lost is a photo behind the model; what
 * is gained is a backdrop that two captures agree on. The camera is NOT part of
 * that guarantee: the section's idle orbit is time-driven and starts when the
 * model finishes loading, so two captures still differ in yaw unless the app is
 * launched with `--ez qa_mode true`, which pins it.
 *
 * Falls back to the neutral default while the HDR decodes, so the first frames
 * never flash black.
 */
@Composable
fun rememberMaterialsShowcaseEnvironment(environmentLoader: EnvironmentLoader): Environment {
    val hdrEnvironment = rememberHDREnvironment(
        environmentLoader,
        MATERIALS_SHOWCASE_HDR,
        createSkybox = false,
    )
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    return hdrEnvironment ?: fallbackEnvironment
}
