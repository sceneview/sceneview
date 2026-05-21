package io.github.sceneview.sample.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import io.github.sceneview.sample.common.update.InAppUpdateManager
import io.github.sceneview.sample.common.update.UpdateBanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.animation.Transition.animateRotation
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNode
import io.github.sceneview.sample.SceneviewTheme
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

internal data class ModelEntry(val label: String, val assetPath: String, val scale: Float)

// Models bundled in src/main/assets/models — names must match exactly.
// Kept in sync with samples/android-demo bundled catalog.
// `internal` so TvModelListTest (testDebugUnitTest source set) can assert
// every assetPath resolves to a bundled file in src/main/assets/.
internal val models = listOf(
    ModelEntry("Damaged Helmet", "models/khronos_damaged_helmet.glb", 1.0f),
    ModelEntry("Toy Car", "models/khronos_toy_car.glb", 1.0f),
    ModelEntry("Sheen Chair", "models/khronos_sheen_chair.glb", 1.0f),
    ModelEntry("Velvet Sofa", "models/khronos_glam_velvet_sofa.glb", 1.0f),
    ModelEntry("Lantern", "models/khronos_lantern.glb", 1.0f),
    ModelEntry("Iridescent Dish", "models/khronos_iridescent_dish.glb", 1.0f),
    // `animated_dragon.glb` dropped in #1152 Stage 3 — 8 MB GLB removed from
    // the shared bundle. `Soldier` (below) covers the animated character role.
    ModelEntry("Duck", "models/khronos_duck.glb", 1.0f),
    ModelEntry("Fox", "models/khronos_fox.glb", 1.0f),
    ModelEntry("Toon Cat", "models/toon_cat.glb", 1.0f),
    ModelEntry("Shiba", "models/shiba.glb", 1.0f),
    ModelEntry("Soldier", "models/threejs_soldier.glb", 1.0f),
    ModelEntry("Nike Air Jordan", "models/nike_air_jordan.glb", 1.0f),
)

/**
 * Android TV Model Viewer — SceneView TV sample.
 *
 * Demonstrates 3D model viewing on Android TV with D-pad controls:
 * - D-pad Left/Right: rotate model
 * - D-pad Up/Down: zoom in/out
 * - Select (center): cycle models
 * - Play/Pause: toggle auto-rotation
 */
class TvModelViewerActivity : ComponentActivity() {

    // Exposed (internal) so [TvModelViewerScreen] can overlay the [UpdateBanner]
    // on top of the SceneView surface. Same pattern as android-demo.
    internal lateinit var updateManager: InAppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateManager = InAppUpdateManager(this)

        setContent {
            SceneviewTheme {
                TvModelViewerScreen(updateManager = updateManager)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-pick up a download already finished in a previous foreground
        // before issuing a fresh check — handles the "user backgrounded the
        // app mid-install" case without double-prompting.
        updateManager.checkForStalledUpdate()
        updateManager.checkForUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateManager.destroy()
    }
}

@Composable
private fun TvModelViewerScreen(updateManager: InAppUpdateManager? = null) {
    var selectedIndex by remember { mutableIntStateOf(0) }
    val selectedModel = models[selectedIndex]

    var manualRotationY by remember { mutableFloatStateOf(0f) }
    var cameraDistance by remember { mutableFloatStateOf(2.0f) }
    var autoRotate by remember { mutableStateOf(true) }

    val focusRequester = remember { FocusRequester() }

    // Android TV is entirely D-pad driven with no touch. The root Box owns the
    // whole control scheme via onKeyEvent, but onKeyEvent only fires when its
    // node holds focus — so the Box must be focusable() AND must actually grab
    // focus on first composition, or every D-pad key is inert from launch.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    // D-pad Left/Right: rotate
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        manualRotationY -= 15f; true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        manualRotationY += 15f; true
                    }
                    // D-pad Up/Down: zoom
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        cameraDistance = (cameraDistance - 0.3f).coerceAtLeast(0.5f); true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        cameraDistance = (cameraDistance + 0.3f).coerceAtMost(10f); true
                    }
                    // Select: cycle models
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        selectedIndex = (selectedIndex + 1) % models.size; true
                    }
                    // Play/Pause: toggle auto-rotation
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        autoRotate = !autoRotate; true
                    }
                    else -> false
                }
            }
    ) {
        val engine = rememberEngine()
        val modelLoader = rememberModelLoader(engine)
        val environmentLoader = rememberEnvironmentLoader(engine)

        val centerNode = rememberNode(engine)

        val cameraNode = rememberCameraNode(engine) {
            position = Position(y = 0f, z = cameraDistance)
            lookAt(centerNode)
            centerNode.addChildNode(this)
        }

        val cameraTransition = rememberInfiniteTransition(label = "CameraTransition")
        val autoRotation by cameraTransition.animateRotation(
            initialValue = Rotation(y = 0f),
            targetValue = Rotation(y = 360f),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 10.seconds.toInt(DurationUnit.MILLISECONDS))
            )
        )

        val modelInstance = rememberModelInstance(modelLoader, selectedModel.assetPath)
        val environment = rememberEnvironment(environmentLoader) {
            environmentLoader.createHDREnvironment("environments/studio_2k.hdr")
                ?: environmentLoader.createHDREnvironment("environments/rooftop_night_2k.hdr")!!
        }

        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            cameraNode = cameraNode,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = cameraNode.worldPosition,
                targetPosition = centerNode.worldPosition
            ),
            environment = environment,
            onFrame = {
                val rotation = if (autoRotate) {
                    Rotation(y = autoRotation.y + manualRotationY)
                } else {
                    Rotation(y = manualRotationY)
                }
                centerNode.rotation = rotation
                cameraNode.position = Position(y = 0f, z = cameraDistance)
                cameraNode.lookAt(centerNode)
            }
        ) {
            modelInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = selectedModel.scale,
                    autoAnimate = true,
                    animationLoop = true
                )
            }
        }

        // TV overlay — model name and controls hint
        TvOverlay(
            modelName = selectedModel.label,
            autoRotate = autoRotate,
            modifier = Modifier.align(Alignment.BottomStart)
        )

        // Play in-app update banner — overlays the top of the screen during
        // DOWNLOADING / READY_TO_INSTALL only (no-op otherwise). On TV the
        // Restart CTA must grab D-pad focus the moment the banner appears, so
        // we hand UpdateBanner a FocusRequester it auto-requests on the
        // READY_TO_INSTALL transition. FLEXIBLE is the supported Leanback flow.
        val restartFocusRequester = remember { FocusRequester() }
        updateManager?.let { mgr ->
            UpdateBanner(
                updateManager = mgr,
                modifier = Modifier.align(Alignment.TopCenter),
                restartFocusRequester = restartFocusRequester
            )
        }
    }
}

@Composable
private fun TvOverlay(
    modelName: String,
    autoRotate: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(32.dp)
            .background(
                color = Color.Black.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.medium
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = modelName,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White
        )
        Text(
            text = buildString {
                appendLine("D-pad: Rotate & Zoom")
                appendLine("Select: Next model")
                append("Play/Pause: Auto-rotate ${if (autoRotate) "ON" else "OFF"}")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}
