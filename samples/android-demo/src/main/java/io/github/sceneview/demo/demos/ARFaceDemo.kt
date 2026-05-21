package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.frontCameraConfig
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberUnlitMaterialInstance

/**
 * Augmented face mesh tracking demo.
 *
 * Configures the AR session with the front camera and [Config.AugmentedFaceMode.MESH3D] to
 * detect face meshes. When a face is detected, an [AugmentedFaceNode] renders a translucent
 * unlit-blue mesh overlay on the user's face — alpha 0.4 so the user's actual facial features
 * remain visible underneath the fitted topology.
 *
 * Augmented Faces needs **two** session settings, not one: `sessionFeatures` must include
 * [Session.Feature.FRONT_CAMERA] *and* `sessionCameraConfig` must select a FRONT-facing
 * [com.google.ar.core.CameraConfig] via `::frontCameraConfig`. The feature flag alone leaves
 * the session on the default BACK camera and the mesh never tracks (#1436).
 *
 * Requires a device with a front-facing camera and ARCore face mesh support.
 */
@Composable
fun ARFaceDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    var detectedFaces by remember { mutableStateOf<List<AugmentedFace>>(emptyList()) }
    var faceCount by remember { mutableStateOf(0) }

    // Unlit translucent overlay for the face mesh. ARCore disables `ENVIRONMENTAL_HDR`
    // light estimation when the front camera is in use, so any lit material falls
    // back to whatever neutral IBL is in scope — which historically rendered the
    // mesh near-invisible even after the tangent-quaternion fix in `35e5990d`.
    //
    // `createUnlitColorInstance` ships the mesh's flat colour in a single fragment
    // pass (no PBR shading, no IBL dependency), so the overlay reads as a clean
    // SceneView-blue tone regardless of front-camera lighting. No fill light needed —
    // the previous explicit 100 000 lux directional was a workaround for the lit
    // material that's now obsolete.
    //
    // [SceneViewColors.PrimaryOverlay] (alpha 0.4) routes through `transparent_unlit_colored.mat`
    // which is `doubleSided: true` — pairs with `culling(false)` on the face mesh.
    // We use a translucent overlay (not opaque blue) so the user can SEE their face
    // through the fitted topology — that's the entire point of a face-mesh demo.
    val faceMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.PrimaryOverlay)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_face_title),
        onBack = onBack
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionFeatures = setOf(Session.Feature.FRONT_CAMERA),
                // CRITICAL for Augmented Faces (#1436). `sessionFeatures = FRONT_CAMERA`
                // only makes the front camera *eligible* — it does NOT switch the camera.
                // ARSceneView's default `sessionCameraConfig` is `highestResolutionCameraConfig`,
                // which is hardwired to `FacingDirection.BACK`. So without this override the
                // session runs the BACK camera, `AugmentedFaceMode.MESH3D` yields zero
                // trackables, and the face mesh never appears — exactly the "j'ai jamais vu
                // marcher ça" symptom. `frontCameraConfig` selects a FRONT-facing CameraConfig
                // so ARCore actually opens the selfie camera and the face mesh can track.
                sessionCameraConfig = ::frontCameraConfig,
                // No `cameraExposure` override — issue #1179. The previous
                // `cameraExposure = -1.5f` rendered the entire scene fully black on
                // Pixel 9 v4.3.0 production.
                //
                // Root cause: `cameraExposure` calls Filament's single-arg
                // `CameraComponent.setExposure(Float)`, which is an **absolute exposure
                // scaling** (1.0 = ISO 100, EV 0), NOT a signed EV stop bias as the
                // KDoc misleadingly hints. A negative scaling clamps the framebuffer to
                // zero ⇒ full black. The original intent ("dial the over-bright selfie
                // preview down by ~1.5 EV") needs the 3-arg `setExposure(aperture,
                // shutter, ISO)` form on a fresh `ARCameraNode`, not the linear-gain
                // overload. Filed as #1101 (closed, partial) → re-tracking in the AR
                // exposure realignment after #1067 + #1088.
                //
                // ARSession force-DISABLES light estimation for front-camera sessions
                // (see ArSession.kt:77-81), so the new `ARDefaultCameraNode` defaults
                // (f/12, 1/200 s, ISO 200 ≈ EV 11.6) + 10k+3k lux main/fill lights
                // give a correctly exposed selfie preview on Pixel 9 without any
                // per-demo override. If the preview ever shows up over-bright again on
                // a new device, fix via a per-facing AE strategy in `arsceneview/`
                // (e.g. front-camera-specific `ARDefaultCameraNode`), not a per-demo
                // linear-gain hack.
                sessionConfiguration = { _: Session, config: Config ->
                    config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                    config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                },
                onSessionUpdated = { session: Session, _: Frame ->
                    detectedFaces = session.getAllTrackables(AugmentedFace::class.java)
                        .filter { it.trackingState == TrackingState.TRACKING }
                    faceCount = detectedFaces.size
                }
            ) {
                // Unlit material — no fill light needed. The previous 100 000-lux
                // directional was a workaround for the lit-PBR material's IBL
                // dependency; the unlit path renders the mesh's flat colour
                // straight to the framebuffer regardless of scene lighting.
                detectedFaces.forEach { face ->
                    AugmentedFaceNode(
                        augmentedFace = face,
                        meshMaterialInstance = faceMaterial,
                        // Unlit material → no PBR sampling of TANGENTS, so skip the
                        // per-frame Mikkelsen compute + JNI upload (#878).
                        computeTangents = false,
                        onTrackingStateChanged = { state ->
                            // Face tracking state changed
                        }
                    )
                }
            }

            // Status overlay
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Text(
                    text = if (faceCount > 0) {
                        "Tracking $faceCount face(s)"
                    } else {
                        "Point the front camera at a face"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(bottom = 32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }
}
