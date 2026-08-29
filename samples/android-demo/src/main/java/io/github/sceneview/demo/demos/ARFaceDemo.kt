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
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import kotlinx.coroutines.delay

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
                // (see the front-camera guard in `ARSession.configure`), so the new
                // `ARDefaultCameraNode` defaults
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
