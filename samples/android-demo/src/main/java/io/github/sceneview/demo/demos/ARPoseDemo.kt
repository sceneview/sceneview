package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.common.Axes3DNode
import io.github.sceneview.math.Size
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance

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
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Sliders hold the user-tweakable *offset* from the pose captured in front of
    // the camera on first tracked frame. Keeping the offset small and centred means
    // the cubes start where the user is already pointing — the previous version
    // placed them at ARCore world origin (wherever the phone was at session-start),
    // which is usually off-screen once the user moves.
    var x by remember { mutableFloatStateOf(0f) }
    var y by remember { mutableFloatStateOf(0f) }
    var z by remember { mutableFloatStateOf(0f) }
    var isTracking by remember { mutableStateOf(false) }
    // Base pose: 1 m in front of the camera at the moment the demo gained tracking.
    // Captured once per session to keep the cubes anchored in world space (so sliders
    // nudge them relative to that anchor, not to the moving camera).
    var basePose by remember { mutableStateOf<Pose?>(null) }

    // Cube and sphere materials — distinct on-brand colours plus PBR settings tuned
    // to read clearly under ARCore's `ENVIRONMENTAL_HDR` light estimation.
    //
    // The previous version went all the way to roughness=0.85 to avoid a Pixel 9 IBL
    // blowout on the original metallic=0.5/roughness=0.3 setup, but that swung the
    // dial too far the other way — the sphere lost all visible diffuse falloff and
    // specular highlight on Pixel 9 (issue #1200) and read as a flat 2D disc next to
    // a barely-shaded cube. Re-tuned to roughness=0.55 / reflectance=0.2: matte
    // enough that the IBL can't blow out the highlight, but glossy enough that the
    // sphere gradient (lit cap → shadowed underside) is unmistakable. Also adds an
    // explicit dim DirectionalLight in the scene below so even ARCore's most
    // featureless estimations (uniform low-light interior) still produce shading.
    //
    // Two materials so cube and sphere read as distinct objects, not a single white
    // blob — matches the same brand-ramp split used elsewhere in the demos.
    val cubeMaterial = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = SceneViewColors.Accent,
        metallic = 0.0f,
        roughness = 0.55f,
        reflectance = 0.2f,
    )
    val sphereMaterial = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = SceneViewColors.Primary,
        metallic = 0.0f,
        roughness = 0.55f,
        reflectance = 0.2f,
    )

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
                        text = "%.2f".format(x),
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
                        text = "%.2f".format(y),
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
                        text = "%.2f".format(z),
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
                playbackDataset = arPlaybackDataset,
                planeRenderer = true,
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                },
                onSessionUpdated = { _, frame: Frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (isTracking && basePose == null) {
                        // Compute a pose 1 m in front of the camera, without tilt —
                        // keep the camera's yaw (facing direction) but zero-out
                        // pitch/roll so the base pose is level and the cubes stay
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
                    // Cache Pose objects across recompositions — without remember,
                    // each slider drag allocates two new Pose + four FloatArray
                    // instances per frame.
                    val cubePose = remember(base, x, y, z) {
                        val t = base.translation
                        Pose(
                            floatArrayOf(t[0] + x, t[1] + y, t[2] + z),
                            floatArrayOf(0f, 0f, 0f, 1f),
                        )
                    }
                    val spherePose = remember(base, x, y, z) {
                        val t = base.translation
                        Pose(
                            floatArrayOf(t[0] + x + 0.3f, t[1] + y, t[2] + z),
                            floatArrayOf(0f, 0f, 0f, 1f),
                        )
                    }
                    // Blender-style XYZ axes anchored at the base pose so the user
                    // always sees where the pose's origin sits in the world. Sliders
                    // nudge the cube/sphere relative to this anchor — the gizmo
                    // makes that mapping visible (red = X, green = Y, blue = Z).
                    PoseNode(pose = base) {
                        Axes3DNode(
                            materialLoader = materialLoader,
                            length = 0.4f,
                            thickness = 0.005f,
                        )
                    }
                    // Cube is 0.2 m / sphere is 0.1 m — big enough to read clearly
                    // at 1 m from the camera on a phone screen. The previous 0.1 m
                    // cube was visible only as a couple of pixels at default FOV.
                    PoseNode(pose = cubePose) {
                        CubeNode(
                            size = Size(0.2f),
                            materialInstance = cubeMaterial,
                        )
                    }
                    PoseNode(pose = spherePose) {
                        SphereNode(
                            radius = 0.1f,
                            materialInstance = sphereMaterial,
                        )
                    }
                }
            }
        }
    }
}
