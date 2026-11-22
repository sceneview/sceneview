// SceneView 2.x code that an AI coding agent writes from memory — it must keep compiling.
//
// Source: the 2026-11-23 measurement of 130 agent answers, Claude Opus without tools, run 3 of
// question 5: "Ajoute de la 3D et de l'AR à mon app Android : je veux qu'on puisse voir un objet
// en 3D puis le poser chez soi en réalité augmentée. Quelle bibliothèque, et quel code ?" ("add
// 3D and AR to my Android app: view an object in 3D, then place it at home in AR"). It
// recommended `sceneview:2.3.0` + `arsceneview:2.3.0` and wrote the two screens below. The 4.x
// shims are in Compat2x.kt and ar/ARCompat2x.kt; issue #5216.
//
// `rememberArAvailable()` and `createAnchorNode(...)` are not SceneView API: the answer defines
// both itself, on top of ARCore's `ArCoreApk` and SceneView's `AnchorNode`.
//
// The code of sections 3 and 4 of the answer is verbatim, its imports included. Additions, none
// of them SceneView API:
// - the `package` line; the two sections' imports merged into one list;
// - the Compose and ARCore imports the answer left out (layout, material3, runtime, `ArCoreApk`);
// - section 5 (a `NavHost` snippet, not inside a function, needing navigation-compose) is left
//   out: it calls no SceneView API.
// The deprecation warnings it raises are left on purpose: they are what the shims tell the agent.
// Compile-only: nothing here runs. If this file stops compiling, a 2.x shim broke.
package io.github.sceneview.agentcompat

// Added: Compose and ARCore imports missing from the answer.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.ar.core.ArCoreApk
// Verbatim from the answer (sections 3 and 4).
import io.github.sceneview.*
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.isValid
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.loaders.ModelLoader
import com.google.android.filament.Engine

@Composable
fun ModelViewerScreen(modelPath: String = "models/objet.glb", onOpenAR: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    val centerNode = rememberNode(engine)
    val cameraNode = rememberCameraNode(engine) {
        position = Position(y = 0.2f, z = 1.5f)
        lookAt(centerNode)
        centerNode.addChildNode(this)
    }

    Box(Modifier.fillMaxSize()) {
        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = cameraNode.worldPosition,
                targetPosition = centerNode.worldPosition
            ),
            childNodes = listOf(
                centerNode,
                rememberNode {
                    ModelNode(
                        modelInstance = modelLoader.createModelInstance(modelPath),
                        scaleToUnits = 0.5f,   // ramène le modèle à 50 cm pour l'affichage
                        centerOrigin = Position(y = 0f)
                    )
                }
            ),
            environment = environmentLoader.createHDREnvironment("environments/studio.hdr")!!
        )

        if (rememberArAvailable()) {
            Button(
                onClick = onOpenAR,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)
            ) { Text("Voir chez moi") }
        }
    }
}

@Composable
fun rememberArAvailable(): Boolean {
    val context = LocalContext.current
    return remember {
        ArCoreApk.getInstance().checkAvailability(context).isSupported
    }
}

@Composable
fun ARPlacementScreen(modelPath: String = "models/objet.glb") {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)
    val childNodes = rememberNodes()

    var frame by remember { mutableStateOf<Frame?>(null) }
    var showPlanes by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        ARScene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            modelLoader = modelLoader,
            collisionSystem = collisionSystem,
            childNodes = childNodes,
            cameraNode = rememberARCameraNode(engine),
            planeRenderer = showPlanes,
            sessionConfiguration = { session, config ->
                config.depthMode =
                    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                        Config.DepthMode.AUTOMATIC          // occlusion par les vrais objets
                    else Config.DepthMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onSessionUpdated = { _, updatedFrame -> frame = updatedFrame },
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { motionEvent, node ->
                    if (node != null) return@rememberOnGestureListener // tap sur l'objet existant
                    frame?.hitTest(motionEvent.x, motionEvent.y)
                        ?.firstOrNull { it.isValid(depthPoint = false, point = false) }
                        ?.createAnchorOrNull()
                        ?.let { anchor ->
                            childNodes.clear()                 // un seul objet à la fois
                            childNodes += createAnchorNode(engine, modelLoader, anchor, modelPath)
                            showPlanes = false
                        }
                }
            )
        )

        Text(
            text = if (showPlanes) "Bougez le téléphone puis touchez le sol"
                   else "Pincez pour redimensionner, glissez pour déplacer",
            modifier = Modifier.align(Alignment.TopCenter).padding(32.dp),
            color = Color.White
        )
    }
}

private fun createAnchorNode(
    engine: Engine,
    modelLoader: ModelLoader,
    anchor: Anchor,
    modelPath: String
): AnchorNode = AnchorNode(engine = engine, anchor = anchor).apply {
    addChildNode(
        ModelNode(
            modelInstance = modelLoader.createModelInstance(modelPath),
            // Taille réelle : ne mets PAS scaleToUnits si ton .glb est déjà en mètres
        ).apply {
            isEditable = true          // déplacer, tourner et redimensionner au doigt
            editableScaleRange = 0.2f..3f
        }
    )
}
