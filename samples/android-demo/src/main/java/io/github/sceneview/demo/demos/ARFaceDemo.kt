package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.frontCameraConfig
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Direction
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberFillLightNode
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance
import kotlinx.coroutines.delay

/**
 * Augmented face mesh tracking demo.
 *
 * Configures the AR session with the front camera and [Config.AugmentedFaceMode.MESH3D] to
 * detect face meshes. When a face is detected, an [AugmentedFaceNode] renders a translucent,
 * **lit** mesh overlay on the user's face — semi-transparent so the real face stays visible
 * underneath the fitted topology, and shaded by a fixed key + fill rig so the topology reads as
 * a 3D surface instead of a flat sticker (#3576).
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

    // Frame-received sentinel — flips true on the first onSessionUpdated call so the
    // "slow front camera" hint knows the camera feed is live.
    var cameraActive by remember { mutableStateOf(false) }
    // #3341: non-null once ARCore has ruled this device out. `cameraActive` never flips
    // then, so the init scrim below has to read the verdict or it covers the SDK's own
    // explanation card forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    // Session resumed — set by onSessionResumed. The slow-camera hint is anchored on
    // this (not the composition) so its grace window starts when ARCore actually
    // resumes the session.
    var sessionResumed by remember { mutableStateOf(false) }
    // Advisory (non-latching): the front camera has not delivered a frame within the
    // grace window. NOT a verdict — the front camera is simply slower to open than the
    // back camera on some devices (e.g. Pixel 9, #1612). Cleared as soon as a frame arrives.
    var slowCameraWarning by remember { mutableStateOf(false) }
    // Genuine ARCore session failure (permission denied, ARCore unavailable) — wired to
    // the real onSessionFailed callback so it gets a distinct, honest message.
    var sessionFailed by remember { mutableStateOf(false) }

    // Lit translucent overlay for the face mesh (#3576).
    //
    // ARCore force-disables light estimation on a front-camera session (see the front-camera
    // guard in `ARSession.configure`), so there is no estimate to react to — but that is an
    // argument for a *deterministic* rig, not for no shading at all. The previous unlit flat
    // colour painted the whole face one uniform blue: no highlight, no falloff, no silhouette,
    // so nothing on screen said "this is a mesh fitted to your face" rather than "a blue filter".
    //
    // A lit PBR instance shades the mesh with the key + fill rig installed below. Roughness is
    // low enough for a visible specular sweep across the cheekbones and the bridge of the nose —
    // that highlight is the depth cue the flat colour lacked — and the alpha keeps the real face
    // readable underneath, which is the entire point of a face-mesh demo.
    val faceMaterial = rememberMaterialInstance(
        materialLoader,
        SceneViewColors.FaceMeshOverlay,
        metallic = 0.0f,
        roughness = 0.35f,
    )

    // Slow-front-camera hint. Anchored on the session RESUME (not composition) and
    // non-latching: it resets on every (re)launch and only trips if, a full
    // SLOW_FRONT_CAMERA_HINT_MS after the session resumed, no camera frame has arrived.
    // The window is deliberately longer than the shared 8 s ARCameraInitScrim timeout so
    // the front camera — slower to open than the back camera — gets real grace before we
    // hint. It never claims the camera is unavailable; a genuine failure is surfaced
    // separately via onSessionFailed → sessionFailed.
    LaunchedEffect(sessionResumed, cameraActive) {
        slowCameraWarning = false
        if (sessionResumed && !cameraActive) {
            delay(SLOW_FRONT_CAMERA_HINT_MS)
            if (!cameraActive) slowCameraWarning = true
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_face_title),
        onBack = onBack,
        // Status banner hosted by the scaffold slot (#2779) — the one container that
        // knows where the Settings FAB is and applies the system-bar inset.
        bottomOverlay = {
            // Always on, as before: this banner is the demo's only running commentary,
            // so it has no hidden state. The AnimatedVisibility is kept so the
            // enter/exit spec is unchanged.
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                // Tone is derived from the same branch that picks the sentence:
                // a front camera that could not start is broken until the user acts
                // outside the demo; tracking and "still starting" are normal states;
                // "point the camera at a face" is a physical instruction.
                val (statusText, statusTone) = when {
                    sessionFailed ->
                        // Genuine ARCore session failure — a real, distinct error state
                        // (permission denied, ARCore unavailable), not a slow start.
                        stringResource(R.string.demo_ar_face_status_camera_failed) to
                            DemoStatusTone.Blocked
                    faceCount > 0 ->
                        stringResource(R.string.demo_ar_face_status_tracking, faceCount) to
                            DemoStatusTone.Progress
                    slowCameraWarning ->
                        // Advisory only: the front camera is slow to open on some devices.
                        // Progress (not blocked) — this is not a verdict.
                        stringResource(R.string.demo_ar_face_status_starting) to
                            DemoStatusTone.Progress
                    else ->
                        stringResource(R.string.demo_ar_face_status_aim) to
                            DemoStatusTone.Guidance
                }
                DemoStatusBanner(text = statusText, tone = statusTone)
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                // Selfie lighting rig (#3576). A front-camera session never tracks the device
                // pose — ARCore documents `Camera.getDisplayOrientedPose()` as always identity —
                // so the world frame is pinned to the phone: -Z points straight out of the screen
                // at the user's face, +Y is up. That makes a FIXED direction a perfectly stable,
                // camera-anchored light, which is exactly what a portrait wants and what the SDK
                // defaults do NOT give: `DefaultLightNode` points straight down (0, -1, 0), the
                // classic overhead angle that buries the eyes, the nose base and the mouth in
                // shadow — the "lighting is not good at all" report of #3576.
                //
                // Key light: slightly above and in front, aimed back into the face. Fill: from
                // the other side and a little below, at ~40% intensity, to open the shadow side
                // without flattening the relief. Intensities stay in the same lux ballpark as the
                // AR defaults (10 000 / 3 000) so the mesh sits at the same exposure as every
                // other AR demo.
                mainLightNode = rememberMainLightNode(engine) {
                    lightDirection = Direction(x = 0.0f, y = -0.35f, z = -1.0f)
                    intensity = 9_000.0f
                },
                fillLightNode = rememberFillLightNode(engine) {
                    lightDirection = Direction(x = -0.6f, y = 0.25f, z = -1.0f)
                    intensity = 3_500.0f
                },
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
                // (see the front-camera guard in `ARSession.configure`), so the
                // `ARDefaultCameraNode` defaults (f/12, 1/200 s, ISO 200 ≈ EV 11.6)
                // plus the fixed key/fill rig declared above give a correctly exposed
                // selfie preview on Pixel 9 without any per-demo exposure override. If the preview ever shows up over-bright again on
                // a new device, fix via a per-facing AE strategy in `arsceneview/`
                // (e.g. front-camera-specific `ARDefaultCameraNode`), not a per-demo
                // linear-gain hack.
                sessionConfiguration = { _: Session, config: Config ->
                    config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                    config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                },
                onSessionResumed = { _: Session ->
                    // Anchor the slow-camera grace window on the real session resume,
                    // not on the composition (which fires before ARCore is ready).
                    sessionResumed = true
                },
                onSessionFailed = { error: Exception ->
                    // A genuine session failure (permission denied, ARCore unavailable) —
                    // distinct from a merely slow front-camera start.
                    android.util.Log.e("ARFaceDemo", "AR session failed", error)
                    sessionFailed = true
                },
                onARCoreAvailability = { arCoreAvailability = it },
                onSessionUpdated = { session: Session, _: Frame ->
                    // Mark camera as active on the first frame so the slow-camera hint
                    // knows the front camera opened successfully.
                    if (!cameraActive) cameraActive = true
                    detectedFaces = session.getAllTrackables(AugmentedFace::class.java)
                        .filter { it.trackingState == TrackingState.TRACKING }
                    faceCount = detectedFaces.size
                }
            ) {
                detectedFaces.forEach { face ->
                    AugmentedFaceNode(
                        augmentedFace = face,
                        meshMaterialInstance = faceMaterial,
                        // The material is lit again (#3576), so the per-vertex tangent
                        // quaternions PBR samples have to be rebuilt on every frame the mesh
                        // deforms. `computeTangents = false` is the unlit-only shortcut (#878)
                        // and would leave the shading undefined here.
                        computeTangents = true,
                        onTrackingStateChanged = { state ->
                            // Face tracking state changed
                        }
                    )
                }
            }

            // Cover the still-black AR viewport until the front camera delivers its first
            // frame. The advisory slow-camera hint does NOT lift it — only a real frame or a
            // genuine session failure does — so a slow start no longer flashes a black
            // viewport. Its own defensive timeout is widened to the grace window so the
            // spinner, not a black viewport, covers a slow front-camera start (#2484).
            ARCameraInitScrim(
                initializing = !cameraActive && !sessionFailed,
                arCoreAvailability = arCoreAvailability,
                timeoutMillis = SLOW_FRONT_CAMERA_HINT_MS,
            )
        }
    }
}

/**
 * Grace window before the demo shows the advisory "still starting the front camera" hint.
 *
 * Deliberately longer than the shared 8 s [ARCameraInitScrim] defensive timeout
 * (`AR_CAMERA_INIT_SCRIM_TIMEOUT_MS`): the front (selfie) camera is slower to open than the
 * back camera on many devices (e.g. Pixel 9, #1612), so it is given extra grace before we
 * hint — and even then the hint is advisory, never a verdict of "camera unavailable".
 */
private const val SLOW_FRONT_CAMERA_HINT_MS = 12_000L
