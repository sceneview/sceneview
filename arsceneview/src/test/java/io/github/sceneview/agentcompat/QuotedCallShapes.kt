// SceneView 2.x call shapes quoted from agent answers whose full code was not recorded — they must
// keep compiling (issue #5216).
//
// Source: the 2026-11-23 measurement of 130 agent answers. For Gemini (web) only excerpts were
// kept, not the code, so no Gemini answer can be a verbatim fixture like Answer1..Answer5. What
// the excerpts and the prose did quote is checked here, each expression exactly as quoted. The
// surrounding functions and their parameters (`engine`, `modelLoader`, `anchor`, `frame`, `path`,
// `w`, `h`, `d`) are added, so that the quotes have something to compile against.
// Compile-only: nothing here runs.
package io.github.sceneview.agentcompat

import com.google.android.filament.Engine
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.arcore.getUpdatedPlanes
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.ModelNode

/** Gemini, question 2 (arsceneview 2.2.1): the placed model and its anchor, as quoted. */
fun geminiP2Shapes(engine: Engine, modelLoader: ModelLoader, anchor: Anchor, frame: Frame, path: String) {
    ModelNode(modelInstance = modelLoader.createModelInstance(path), scaleToUnits = null).apply { isEditable = true; editableScaleRange = 1.0f..1.0f }
    AnchorNode(engine, anchor)
    frame.getUpdatedPlanes()
}

/**
 * Claude Opus with web search, question 8 ("does the sofa fit"): a box of the sofa's size when
 * there is no sofa model, as quoted. Gemini's answer to the same question draws the same box
 * with `CubeNode` and `rememberMaterialLoader` (no call recorded); its green/red material is
 * `materialLoader.createColorInstance(color)`, which Answer4 exercises.
 */
fun opusWebP8Shapes(engine: Engine, w: Float, h: Float, d: Float) {
    CubeNode(engine, size = Float3(w, h, d), center = Position(y = h / 2))
}
