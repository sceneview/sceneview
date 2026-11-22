// SceneView 2.x code that an AI coding agent writes from memory — it must keep compiling.
//
// Source: the 2026-11-23 measurement of 130 agent answers, Claude Opus without tools, question 9:
// "Je veux afficher un modèle 3D animé (.glb) dans mon app Android en Compose, avec de la lumière
// et une ombre au sol. Comment faire ?" ("show an animated .glb in my Compose app, with light and
// a ground shadow"). It recommended `io.github.sceneview:sceneview:2.3.0` and wrote the code below
// (`PlaneNode` as the shadow-receiving floor is the most frequent shape for this question). The
// 4.x shims are in Compat2x.kt; issue #5216.
//
// The code is verbatim. Additions, none of them SceneView API:
// - the `package` line and every import: the answer gave none;
// - the answer's animation snippet ("Contrôler les animations") is top-level statements around a
//   `modelInstance = ...` placeholder: it is wrapped in `AnimationControl(modelInstance)` below,
//   the placeholder becoming the parameter. Its two commented alternatives (`playAnimation(0)`,
//   `stopAnimation(0)`) are kept as comments and also written out once, so they are checked too.
// The deprecation warnings it raises are left on purpose: they are what the shims tell the agent.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x shim broke.
package io.github.sceneview.agentcompat

// Added: the answer gave no imports.
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.PlaneNode
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes

@Composable
fun ModelViewer() {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    Scene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,

        // Caméra et gestes (rotation, zoom)
        cameraNode = rememberCameraNode(engine) {
            position = Position(x = 0f, y = 1f, z = 3f)
            lookAt(Position(0f, 0.4f, 0f))
        },
        cameraManipulator = rememberCameraManipulator(),

        // Éclairage d'ambiance (IBL) à partir d'un HDR
        environment = rememberEnvironment(environmentLoader) {
            environmentLoader.createHDREnvironment("environments/studio.hdr")!!
        },

        // Lumière principale directionnelle (soleil), qui projette les ombres
        mainLightNode = rememberMainLightNode(engine) {
            intensity = 100_000f
            isShadowCaster = true
        },

        childNodes = rememberNodes {
            // Le modèle animé
            add(
                ModelNode(
                    modelInstance = modelLoader.createModelInstance("models/personnage.glb"),
                    scaleToUnits = 1f,          // normalise la taille à 1 m
                    centerOrigin = Position(y = -1f), // pose les pieds sur y = 0
                    autoAnimate = true           // joue les animations du glTF
                ).apply {
                    isShadowCaster = true
                }
            )

            // Le sol, qui reçoit l'ombre
            add(
                PlaneNode(
                    engine = engine,
                    size = Size(4f, 4f),
                    materialInstance = materialLoader.createColorInstance(Color.LightGray)
                ).apply {
                    isShadowReceiver = true
                }
            )
        }
    )
}

// Added wrapper: the answer's snippet, verbatim inside, `...` replaced by the parameter.
fun AnimationControl(modelInstance: ModelInstance) {
    val modelNode = ModelNode(modelInstance = modelInstance, autoAnimate = false)
    modelNode.playAnimation(animationName = "Walk", loop = true)
    // ou par index : modelNode.playAnimation(0)
    // modelNode.stopAnimation(0)
    modelNode.playAnimation(0)
    modelNode.stopAnimation(0)
}
