package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.common.Axes3DNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import java.util.Locale

/**
 * AR pose placement demo.
 *
 * Places objects at specific [Pose] coordinates in AR space using [PoseNode].
 * Unlike [AnchorNode], a PoseNode is not persisted and tracks the given pose directly.
 * Sliders let the user adjust x, y, z offsets in real time.
 */
@Composable
fun ARPoseDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val cameraNode = rememberARCameraNode(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Real bundled GLB instead of the old placeholder cube/sphere (#1618). The Khronos
    // Lantern is a hanging-light object — a model that intuitively "sits at a precise
    // point in space", which is exactly the concept this pose-placement demo teaches.
    // It is PBR-textured (metal + emissive) so it reads as a finished asset under
    // ARCore's ENVIRONMENTAL_HDR estimation. `rememberModelInstance` loads on the main
    // thread and returns null while the GLB decodes — handled below.
    val lanternInstance = rememberModelInstance(modelLoader, "models/khronos_lantern.glb")

    // Sliders hold the user-tweakable *offset* from the pose captured in front of
    // the camera on first tracked frame. Keeping the offset small and centred means
    // the lantern starts where the user is already pointing — the previous version
    // placed it at ARCore world origin (wherever the phone was at session-start),
    // which is usually off-screen once the user moves.
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var z by remember { mutableFloatStateOf(0f) }
    var isTracking by remember { mutableStateOf(false) }
    // Cover the jet-black ARSceneView surface until ARCore delivers its first camera
    // frame, so the ~1–3 s warm-up on entry doesn't read as a frozen screen (#2484).
    var cameraReady by remember { mutableStateOf(false) }
    // Base pose: 1 m in front of the camera at the moment the demo gained tracking.
    // Captured once per session to keep the lantern anchored in world space (so sliders
    // nudge it relative to that anchor, not to the moving camera).
    var basePose by remember { mutableStateOf<Pose?>(null) }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_pose_title),
        onBack = onBack,
        controls = {
            Text("Position Controls", style = MaterialTheme.typography.labelLarge)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // X slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("X", modifier = Modifier.alignByBaseline())
                    Slider(
                        value = x,
                        onValueChange = { x = it },
                        valueRange = -1f..1f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "%.2f".format(Locale.US, x),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alignByBaseline()
                    )
                }

                // Y slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Y", modifier = Modifier.alignByBaseline())
                    Slider(
                        value = y,
                        onValueChange = { y = it },
                        valueRange = -0.5f..0.5f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "%.2f".format(Locale.US, y),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alignByBaseline()
                    )
                }

                // Z slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Z", modifier = Modifier.alignByBaseline())
                    Slider(
                        value = z,
                        onValueChange = { z = it },
                        valueRange = -0.5f..0.5f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "%.2f".format(Locale.US, z),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.alignByBaseline()
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                cameraNode = cameraNode,
                playbackDataset = arPlaybackDataset,
                planeRenderer = true,
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                },
                onSessionUpdated = { _, frame: Frame ->
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (isTracking && basePose == null) {
                        // Compute a pose 1 m in front of the camera, without tilt —
                        // keep the camera's yaw (facing direction) but zero-out
                        // pitch/roll so the base pose is level and the lantern stays
                        // upright on the horizon rather than matching the phone's
                        // exact orientation.
                        val cameraPose = frame.camera.pose
                        val translation = cameraPose.transformPoint(floatArrayOf(0f, 0f, -1f))
                        basePose = Pose(translation, floatArrayOf(0f, 0f, 0f, 1f))
                    }
                }
            ) {
                val base = basePose
                if (isTracking && base != null) {
                    // Cache the placed Pose across recompositions — without remember,
                    // each slider drag allocates a new Pose + two FloatArray instances
                    // per frame.
                    val objectPose = remember(base, x, y, z) {
                        val t = base.translation
                        Pose(
                            floatArrayOf(t[0] + x, t[1] + y, t[2] + z),
                            floatArrayOf(0f, 0f, 0f, 1f),
                        )
                    }
                    // Blender-style XYZ axes anchored at the base pose so the user
                    // always sees where the pose's origin sits in the world. Sliders
                    // nudge the lantern relative to this anchor — the gizmo makes that
                    // mapping visible (red = X, green = Y, blue = Z).
                    PoseNode(pose = base) {
                        Axes3DNode(
                            materialLoader = materialLoader,
                            length = 0.4f,
                            thickness = 0.005f,
                        )
                    }
                    // The lantern — a real bundled GLB placed at the slider-driven pose.
                    // `scaleToUnits = 0.3f` fits it to a 0.3 m cube so it reads clearly
                    // at 1 m from the camera. `rememberModelInstance` returns null while
                    // the GLB is still decoding, so guard with `?.let`.
                    lanternInstance?.let { instance ->
                        PoseNode(pose = objectPose) {
                            ModelNode(
                                modelInstance = instance,
                                scaleToUnits = 0.3f,
                                // The lantern sits centred on the slider-driven pose. A previous
                                // `centerOrigin = Position(0, -1, 0)` (bottom-align) here was a
                                // silent no-op — the composable discarded it (fixed library-side) —
                                // so it's removed to keep the lantern centred on the pose exactly
                                // as shipped, which is the natural read for a 6DoF pose marker.
                            )
                        }
                    }
                }
            }

            // Live X/Y/Z coordinate readout (#1618) — rendered as a screen-anchored
            // Compose overlay rather than a world-space TextNode floating above the
            // lantern. The world-space label followed the lantern off the right screen
            // edge as soon as the X slider offset the pose sideways, clipping the numbers
            // (#2727). A screen-space pill keeps the readout fully on-screen and readable
            // for any slider value, matching the sibling AR demos' status-pill idiom
            // (e.g. ARDepthVisualizationDemo). Shown only once the pose is placed so the
            // numbers appear alongside the lantern they describe.
            if (isTracking && basePose != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    color = Color(0xCC161B22),  // SceneView SurfaceDim
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text(
                        text = "X %.2f   Y %.2f   Z %.2f".format(Locale.US, x, y, z),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // Cover the still-black AR viewport until the first camera frame (#2484).
            ARCameraInitScrim(initializing = !cameraReady)
        }
    }
}
