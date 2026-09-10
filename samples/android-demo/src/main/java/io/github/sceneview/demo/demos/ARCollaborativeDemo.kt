@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.demos

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
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader

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
            Text("Two simulated viewers on this device share the same objects. No second phone needed.", style = SceneViewTokens.Type.body)
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
            SessionPane("Alice", alice)
            SessionPane("Bob", bob)
            if (bob.placedNodes.isNotEmpty()) {
                Text("Shared with Bob", style = SceneViewTokens.Type.body, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { learnMore = !learnMore }) { Text(if (learnMore) "Hide details" else "Learn more") }
            if (learnMore) {
                Text("This preview passes object changes between two local sessions. A multi-device app also needs a shared spatial anchor and a network connection so everyone sees objects in the same place.", style = SceneViewTokens.Type.body)
            }
        }
    }
}

@Composable
private fun SessionPane(title: String, session: CollaborativeSession) {
    val engine = rememberEngine()
    val materials = rememberMaterialLoader(engine)
    val accent = MaterialTheme.colorScheme.primary
    val material = remember(materials, accent) { materials.createColorInstance(accent, metallic = 0.25f, roughness = 0.3f) }
    Card(Modifier.fillMaxWidth()) {
        Text(title, modifier = Modifier.padding(SceneViewTokens.Space.md), style = SceneViewTokens.Type.card)
        Box(Modifier.fillMaxWidth().height(SceneViewTokens.Space.x4l + SceneViewTokens.Space.x3l)) {
            SceneView(Modifier.fillMaxSize(), surfaceType = io.github.sceneview.SurfaceType.TextureSurface, engine = engine, materialLoader = materials) {
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
