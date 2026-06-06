package io.github.sceneview.demo.demos

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val HELMET_ASSET = "models/khronos_damaged_helmet.glb"

/** Origin the PiP camera always looks at. */
private val ORIGIN = Position(0f, 0f, 0f)

// Orbit preset tuning. The camera circles the model in the horizontal plane at
// a slight elevation; one full sweep takes ORBIT_PERIOD_NANOS.
private const val ORBIT_RADIUS = 1.8f
private const val ORBIT_HEIGHT = 0.6f
private const val ORBIT_PERIOD_NANOS = 12_000_000_000L

/**
 * Secondary camera (picture-in-picture) demo.
 *
 * Two [SceneView]s share the same engine/loaders and render the helmet
 * simultaneously:
 *  - the main view uses the default orbital camera (user-interactive),
 *  - the small PiP overlay binds a dedicated [rememberCameraNode] and is
 *    repositioned by [LaunchedEffect] when the user picks a chip.
 *
 * The chips drive the PiP camera in two distinct ways, both proving the
 * per-instance `cameraNode` binding is genuinely independent of the main view:
 *
 *  - **Fixed angles** (Top / Side / Front / Corner) park the PiP at a static
 *    eye position, so the inset shows the helmet from an angle the user has
 *    not orbited the main view to.
 *  - **Orbit** runs an [LaunchedEffect] frame loop that continuously sweeps
 *    `pipCameraNode.position` around the model. The PiP keeps flying around
 *    the helmet on its own *while the user drags the main view* — the most
 *    direct demonstration of why you would reach for a second `cameraNode`:
 *    one scene, two cameras moving fully independently.
 *
 * Key correctness invariants — both ship-blockers if missed:
 *
 *  1. Each view gets its OWN [ModelInstance]. [ModelNode]'s wrapper entity is
 *     `modelInstance.root`, so a single instance attached to two scenes would
 *     be destroyed twice on dispose (SIGABRT) and its child light / camera
 *     nodes reparented to whichever ModelNode was built last. We get two
 *     distinct instances from one `createInstancedModel(count = 2)` call —
 *     the GLB is parsed ONCE and the two instances share the asset's mesh /
 *     material / texture GPU resources, while each carries its own root
 *     entity hierarchy, so the two ModelNodes destroy distinct roots and
 *     never double-free. (See [rememberInstancedHelmet].)
 *
 *  2. The PiP SceneView passes `cameraManipulator = null`. Without it, the
 *     SceneView frame loop (`SceneView.kt`) writes
 *     `cameraNode.transform = manipulator.getTransform()` every frame,
 *     clobbering whatever the LaunchedEffect just set on `pipCameraNode`.
 *     The chips would fire and the PiP would visually freeze at the
 *     manipulator's home position.
 *
 * Both views also share a single hoisted [rememberEnvironment] so the neutral
 * IBL / skybox is built once rather than once per SceneView.
 *
 * The PiP uses [SurfaceType.TextureSurface] so it composites correctly over
 * the main [SurfaceType.Surface] view. Placed top-start so it never collides
 * with the top-end `AssetSourceChip` or bottom-end settings FAB used by
 * [DemoScaffold]. `cameraPreset` is [rememberSaveable] so configuration
 * changes (rotation, dark-mode toggle) preserve the user's selection.
 */
@Composable
fun SecondaryCameraDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)

    // One environment shared by both SceneViews — the neutral IBL is otherwise
    // loaded + built twice (each SceneView defaults to its own rememberEnvironment).
    val environment = rememberEnvironment(environmentLoader)

    // One GLB parse, two resource-sharing instances — see invariant #1.
    val instances = rememberInstancedHelmet(modelLoader, count = 2)
    val mainInstance = instances.getOrNull(0)
    val pipInstance = instances.getOrNull(1)

    var cameraPreset by rememberSaveable { mutableStateOf(CameraPreset.TOP) }

    val pipCameraNode = rememberCameraNode(engine)
    // Positions the PiP camera. A fixed preset (Top / Side / Front / Corner)
    // parks the camera once; the Orbit preset runs a frame loop that keeps
    // sweeping the camera around the model — independently of the user's
    // main-view orbit, which is the whole point of a dedicated `cameraNode`.
    LaunchedEffect(cameraPreset) {
        cameraPreset.eye?.let { eye ->
            pipCameraNode.position = eye
            pipCameraNode.lookAt(ORIGIN)
            return@LaunchedEffect
        }
        // Orbit: one full sweep every ORBIT_PERIOD_NANOS, driven by the display
        // clock so the speed is frame-rate independent (matches OrbitalARDemo).
        var startNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (startNanos == 0L) startNanos = nanos
                val phase = (nanos - startNanos) % ORBIT_PERIOD_NANOS
                val angle = phase.toFloat() / ORBIT_PERIOD_NANOS * (2f * PI.toFloat())
                pipCameraNode.position = Position(
                    x = sin(angle) * ORBIT_RADIUS,
                    y = ORBIT_HEIGHT,
                    z = cos(angle) * ORBIT_RADIUS,
                )
                pipCameraNode.lookAt(ORIGIN)
            }
        }
    }

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_secondary_camera_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            // Mark the section label as a heading so TalkBack users can
            // navigate to it and understand the chip row that follows.
            Text(
                stringResource(R.string.demo_secondary_camera_chip_section),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() }
            )
            val selectedStateDescription =
                stringResource(R.string.demo_secondary_camera_chip_selected)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CameraPreset.entries.forEach { preset ->
                    val selected = cameraPreset == preset
                    FilterChip(
                        selected = selected,
                        onClick = { cameraPreset = preset },
                        label = { Text(stringResource(preset.labelRes)) },
                        // FilterChip exposes a selected toggle to TalkBack, but
                        // the default state announcement ("on"/"off") is vague
                        // for a camera-angle picker — spell out "selected".
                        modifier = Modifier.semantics {
                            if (selected) stateDescription = selectedStateDescription
                        }
                    )
                }
            }
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            environment = environment,
            onFrame = firstFrame.onFrame,
        ) {
            mainInstance?.let { instance ->
                ModelNode(
                    modelInstance = instance,
                    scaleToUnits = 0.5f,
                    centerOrigin = Position(0f, 0f, 0f),
                )
            }
        }

        val pipDescription = stringResource(
            R.string.demo_secondary_camera_pip_cd,
            stringResource(cameraPreset.labelRes)
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .size(160.dp, 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    2.dp,
                    MaterialTheme.colorScheme.outline,
                    RoundedCornerShape(12.dp)
                )
                // The PiP renders into a TextureView that TalkBack cannot
                // introspect — describe it explicitly, and mark it a polite
                // live region so the angle change is announced when the user
                // picks a different chip.
                .semantics {
                    contentDescription = pipDescription
                    liveRegion = LiveRegionMode.Polite
                }
        ) {
            SceneView(
                modifier = Modifier.fillMaxSize(),
                surfaceType = SurfaceType.TextureSurface,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = environment,
                cameraNode = pipCameraNode,
                cameraManipulator = null,
            ) {
                pipInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 0.5f,
                        centerOrigin = Position(0f, 0f, 0f),
                    )
                }
            }
        }
    }
}

/**
 * Loads the helmet GLB once and returns [count] resource-sharing
 * [ModelInstance]s via `ModelLoader.createInstancedModel`.
 *
 * Mirrors the threading contract of `rememberModelInstance`: the asset bytes
 * are read on [Dispatchers.IO], then `createInstancedModel` (a `@MainThread`
 * Filament call) runs back on the composition's main dispatcher inside
 * [produceState]. Returns an empty list while loading.
 */
@Composable
private fun rememberInstancedHelmet(
    modelLoader: ModelLoader,
    count: Int,
): List<ModelInstance> {
    val context = LocalContext.current
    return produceState(emptyList(), modelLoader, count) {
        val buffer = withContext(Dispatchers.IO) {
            runCatching { context.assets.readBuffer(HELMET_ASSET) }.getOrNull()
        } ?: return@produceState
        value = runCatching { modelLoader.createInstancedModel(buffer, count) }
            .getOrNull()
            .orEmpty()
    }.value
}

/**
 * A PiP camera framing.
 *
 * [eye] is the static eye position for a fixed angle. It is `null` for [ORBIT],
 * which has no fixed position — the [LaunchedEffect] animates it every frame.
 */
private enum class CameraPreset(@StringRes val labelRes: Int, val eye: Position?) {
    // Y=1.8 with X=0.01 to avoid a gimbal singularity in lookAt's up-vector
    // resolution when the camera sits exactly above the origin.
    TOP(R.string.demo_secondary_camera_chip_top, Position(0.01f, 1.8f, 0f)),
    SIDE(R.string.demo_secondary_camera_chip_side, Position(1.8f, 0.2f, 0f)),
    FRONT(R.string.demo_secondary_camera_chip_front, Position(0f, 0.2f, 1.8f)),
    CORNER(R.string.demo_secondary_camera_chip_corner, Position(1.3f, 0.9f, 1.3f)),

    // No fixed eye — the demo's LaunchedEffect sweeps the PiP camera around the
    // model on its own, regardless of how the user orbits the main view.
    ORBIT(R.string.demo_secondary_camera_chip_orbit, null),
}
