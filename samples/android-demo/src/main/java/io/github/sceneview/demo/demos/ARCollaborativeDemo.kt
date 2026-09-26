@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sceneview.SceneView
import io.github.sceneview.ar.collaborative.CollaborativeSession
import io.github.sceneview.ar.collaborative.LoopbackCollaborativeTransport
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberModelDemoEnvironment
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import kotlinx.coroutines.delay

/** How long the "Synced with Bob" pulse stays visible after each state change. */
private const val SYNC_PULSE_HOLD_MILLIS = 1_200L

/** Two local peers render the state actually received through the collaboration transport. */
@Composable
fun ARCollaborativeDemo(onBack: () -> Unit) {
    val hub = remember { LoopbackCollaborativeTransport.LoopbackHub() }
    val alice = remember { CollaborativeSession(hub.join("alice"), displayName = "Alice") }
    val bob = remember { CollaborativeSession(hub.join("bob"), displayName = "Bob") }
    var placement by remember { mutableIntStateOf(0) }
    var learnMore by remember { mutableStateOf(false) }
    DisposableEffect(alice, bob) {
        alice.start()
        bob.start()
        onDispose { alice.stop(); bob.stop() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Collaborative AR") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back_button))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md),
        ) {
            Text("Place once. See it together.", style = SceneViewTokens.Type.title)
            Text(
                "Two simulated viewers on this device share the same objects. " +
                    "No second phone needed.",
                style = SceneViewTokens.Type.body,
            )
            Button(
                onClick = {
                    placement++
                    alice.placeNode(
                        nodeKey = "shared-object",
                        modelKey = if (placement % 2 == 1) "cube" else "sphere",
                        translation = floatArrayOf(0f, 0f, 0f),
                        quaternion = floatArrayOf(0f, 0f, 0f, 1f),
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = SceneViewTokens.Layout.touchTarget),
            ) { Text(if (placement == 0) "Place a cube" else "Change shared object") }
            SessionPane("Alice", alice, alternateViewpoint = false)
            SessionPane("Bob", bob, alternateViewpoint = true)
            // A brief pulse rather than a static line, so the sync is something that
            // visibly *happens* each time Bob's state changes, not just a fact that is
            // true (#3833 — the previous static text was easy to miss).
            var syncPulseVisible by remember { mutableStateOf(false) }
            LaunchedEffect(bob.placedNodes) {
                if (bob.placedNodes.isNotEmpty()) {
                    syncPulseVisible = true
                    delay(SYNC_PULSE_HOLD_MILLIS)
                    syncPulseVisible = false
                }
            }
            AnimatedVisibility(
                visible = syncPulseVisible,
                enter = fadeIn(SceneViewTokens.Motion.fade()),
                exit = fadeOut(SceneViewTokens.Motion.fade()),
            ) {
                Text("Synced with Bob", style = SceneViewTokens.Type.body, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { learnMore = !learnMore }) { Text(if (learnMore) "Hide details" else "Learn more") }
            if (learnMore) {
                Text(
                    "This preview passes object changes between two local sessions. " +
                        "A multi-device app also needs a shared spatial anchor and a network " +
                        "connection so everyone sees objects in the same place.",
                    style = SceneViewTokens.Type.body,
                )
            }
        }
    }
}

@Composable
private fun SessionPane(title: String, session: CollaborativeSession, alternateViewpoint: Boolean) {
    val engine = rememberEngine()
    val materials = rememberMaterialLoader(engine)
    // SceneView's *default* environment is the neutral_ibl.ktx paired with a solid black
    // skybox — a metallic material has nothing to reflect and renders solid black (#2110,
    // see rememberModelDemoEnvironment's kdoc). That is exactly what #3833 reported here:
    // "two black viewports, nothing works" was this pane never having a lit environment,
    // not a missing camera feed (this is a non-AR SceneView, by design — see the
    // "No second phone needed" copy above).
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberModelDemoEnvironment(environmentLoader)
    // Bob's pane renders from a different pose than Alice's, so the two panes visibly
    // read as two independent viewers of the same shared object rather than two copies
    // of the same screenshot.
    val cameraNode = rememberCameraNode(engine) {
        if (alternateViewpoint) {
            position = Position(1.4f, 1.0f, 1.8f)
        } else {
            position = Position(0f, 0.4f, 2.2f)
        }
        lookAt(Position(0f, 0f, 0f))
    }
    val accent = MaterialTheme.colorScheme.primary
    val material = remember(materials, accent) {
        materials.createColorInstance(accent, metallic = 0.25f, roughness = 0.3f)
    }
    Card(Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.padding(SceneViewTokens.Space.md), style = SceneViewTokens.Type.card)
        Box(Modifier.fillMaxWidth().height(SceneViewTokens.Space.x4l + SceneViewTokens.Space.x3l)) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                surfaceType = io.github.sceneview.SurfaceType.TextureSurface,
                engine = engine,
                materialLoader = materials,
                environmentLoader = environmentLoader,
                environment = environment,
                cameraNode = cameraNode,
            ) {
                session.placedNodes.forEach { node ->
                    key(node.nodeKey, node.modelKey) {
                        val position = Position(node.translation[0], node.translation[1], node.translation[2])
                        if (node.modelKey == "sphere") {
                            SphereNode(radius = 0.45f, position = position, materialInstance = material)
                        } else {
                            CubeNode(size = Size(0.8f), position = position, materialInstance = material)
                        }
                    }
                }
            }
        }
    }
}
