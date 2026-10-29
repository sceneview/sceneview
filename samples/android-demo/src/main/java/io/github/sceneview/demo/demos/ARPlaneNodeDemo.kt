package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.rememberDetectedPlanes
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ArAvailabilityStage
import io.github.sceneview.demo.common.arSessionFailedUnexplained
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.demos.internal.rememberArMeshTintInstance
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.math.Size
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader

/**
 * AR demo — Plane lifecycle: spawn one marker [CubeNode][io.github.sceneview.node.CubeNode] per
 * detected ARCore [Plane], wired through the `PlaneNode` composable and the
 * [rememberDetectedPlanes] lifecycle helper (#1774).
 *
 * Showcases the SceneView equivalent of AR Foundation's `ARPlaneManager`:
 *
 *  - [rememberDetectedPlanes] returns the live `State<List<Plane>>` of tracked planes and fires
 *    `onAdded` / `onUpdated` / `onRemoved` — the parity of `ARPlaneManager.planesChanged`. The
 *    demo wires `onAdded` to bump a "total ever detected" counter so the user sees the lifecycle
 *    even after a plane is subsumed.
 *  - Each plane in the list is paired with a `PlaneNode(plane)` composable whose `content` block
 *    declares a small cube. The cube rides the plane's centre pose as ARCore refines its extents.
 *
 * Planes are otherwise opaque inside `PlaneRenderer`; this demo is the supported way to *react*
 * to plane detection from declarative Compose code rather than hand-rolling a
 * `frame.getUpdatedTrackables(Plane::class.java)` loop.
 *
 * Top-center pill shows the live tracked-plane count plus the total ever detected.
 *
 * Closes [#1774](https://github.com/sceneview/sceneview/issues/1774).
 */
@Composable
fun ARPlaneNodeDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // ARCore session, captured on first `onSessionCreated` — `rememberDetectedPlanes` reads
    // `session.getAllTrackables(Plane::class.java)` off it each Compose frame.
    var arSession by remember { mutableStateOf<Session?>(null) }

    // "Total ever detected" — incremented by the onAdded lifecycle callback so the count keeps
    // climbing even when ARCore subsumes a smaller plane into a larger coplanar one.
    var totalDetected by remember { mutableIntStateOf(0) }

    // #3341: non-null once ARCore has ruled this device out. The flag the scanning
    // banner waits on never flips then, so that banner has to read the verdict or
    // it promises a scan under the SDK's "AR unavailable" card, forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }

    // #4622: an ARCore session that failed. The SDK's "Couldn't start AR" card, with its
    // "Try again", carries a session that could not be created (drawn on the stage ground by
    // ArAvailabilityStage); the scaffold's card only takes a failure the SDK does not explain.
    var arSessionFailed by remember { mutableStateOf(false) }

    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    var isTracking by remember { mutableStateOf(false) }
    // The SDK coaching card, driven by tracking and the first plane; the QA menu's forced
    // reason goes through it too. While it speaks the scanning banner steps aside.
    val failure = ForcedTrackingFailure.override ?: trackingFailureReason
    val guidance = rememberArGuidanceState(
        cameraReady = cameraReady,
        isTracking = isTracking && failure == null,
        surfaceFound = totalDetected > 0,
        trackingFailureReason = failure,
    )

    // Marker cube material — allocated once so recompositions never leak a fresh
    // MaterialInstance. `ar-mesh-ground`, tinting the camera image (#4623): the plane is the
    // ground ARCore found, and the colour holds over a white floor as over a dark one.
    val markerMaterial = rememberArMeshTintInstance(materialLoader, SceneViewTokens.ArOverlay.meshGround)

    DemoScaffold(
        // The SDK's "Couldn't start AR" card keeps its in-place "Try again" (#4622); the
        // scaffold's card, which has none, is only for a failure the SDK does not explain.
        arSessionFailed = arSessionFailedUnexplained(arSessionFailed, arCoreAvailability),
        // #3341: the SDK's "AR unavailable" card speaks alone while ARCore rules the device out.
        arOverlaysEnabled = arCoreAvailability == null,
        title = stringResource(R.string.demo_ar_plane_node_title),
        onBack = onBack,
        // The on-screen pill already explains the demo; the only sheet content is the
        // dev-only ForceTrackingFailureMenu, gated on qaMode so end users see no empty FAB.
        controls = if (DemoSettings.qaMode) {
            { ForceTrackingFailureMenu() }
        } else {
            null
        },
        // Live tracked-plane count + total ever detected, hosted by the scaffold's top
        // slot (#3237) so it shares one inset frame with the asset-source chip beside it.
        topOverlay = {
            // Chrome over the camera feed: theme-independent glass tokens (DESIGN.md, Glass Chrome
            // over Media), the same readout as AR Pose, never a copied black literal.
            val readoutShape = MaterialTheme.shapes.large
            Surface(
                modifier = Modifier.overMediaEdge(readoutShape),
                color = SceneViewTokens.Glass.scrim,
                contentColor = SceneViewTokens.Glass.onGlass,
                shape = readoutShape,
            ) {
                val tracked = arSession?.getAllTrackables(Plane::class.java)?.count {
                    it.trackingState == com.google.ar.core.TrackingState.TRACKING
                } ?: 0
                Column(
                    modifier = Modifier.padding(
                        horizontal = SceneViewTokens.Space.md,
                        vertical = SceneViewTokens.Space.sm,
                    ),
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.demo_ar_plane_node_tracked, tracked, tracked),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.demo_ar_plane_node_detected,
                            totalDetected,
                            totalDetected,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = SceneViewTokens.Glass.onGlassMuted,
                    )
                }
            }
        },
        // Scanning / tracking-failure banner, hosted by the scaffold slot (#2779) so it
        // is laid out against the Settings FAB instead of blindly under it. This demo's
        // `controls` is itself gated on qaMode, and the slot resolves the FAB reserve
        // from that same condition — no duplicated gate to drift.
        bottomOverlay = {
            // Never leave the user staring at a black screen (#1617). Visible until at
            // least one plane is tracked.
            val noPlanesYet = totalDetected == 0
            AnimatedVisibility(
                // #3341: on a device ARCore has ruled out, the flag this banner waits on
                // never flips, so the banner would promise a scan under the SDK's "AR
                // unavailable" card. Drop it and let the card carry reason and retry.
                visible = noPlanesYet && arCoreAvailability == null && !arSessionFailed &&
                    !guidance.isCoaching,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                // A reported tracking failure asks the user to move the device —
                // guidance. Plain scanning is a normal transient state.
                val trackingLost = trackingFailureReason != null
                DemoStatusBanner(
                    text = if (trackingLost) {
                        "Move the device slowly to scan a surface…"
                    } else {
                        stringResource(R.string.ar_status_scanning)
                    },
                    tone = if (trackingLost) {
                        DemoStatusTone.Guidance
                    } else {
                        DemoStatusTone.Progress
                    },
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                lifecycle = rememberHostLifecycle(),
                onSessionFailure = { arSessionFailed = true },
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = true,
                onSessionCreated = { session -> arSession = session },
                onARCoreAvailability = {
                    arCoreAvailability = it
                    // Back to null is a retry starting: a retry that works must not then land on
                    // the scaffold's failure card.
                    if (it == null) arSessionFailed = false
                },
                arCoreAvailabilityOverlay = { ArAvailabilityStage(it) },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
                onSessionUpdated = { _, frame ->
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                },
            ) {
                // ARPlaneManager parity: observe the live set of detected planes and react to
                // the lifecycle. `onAdded` bumps the running total; the returned `State` drives
                // the per-plane PlaneNode list below.
                val planes by rememberDetectedPlanes(
                    session = arSession,
                    onAdded = { added -> totalDetected += added.size },
                )

                planes.forEach { plane ->
                    // One PlaneNode per detected plane. The node follows the plane's centre
                    // pose every frame; its `content` block declares a small marker cube that
                    // rides the plane as ARCore refines it.
                    PlaneNode(plane = plane) {
                        CubeNode(
                            size = Size(0.08f, 0.08f, 0.08f),
                            materialInstance = markerMaterial,
                        )
                    }
                }
            }
            // #3341: silent while the SDK's "AR unavailable" card carries the reason.
            ARCoachingOverlay(
                cue = if (arCoreAvailability == null) guidance.cue else ArGuidanceCue.NONE,
                surface = guidance.surface,
                hint = guidance.hint,
                scanLingering = guidance.scanLingering,
                contentPadding = PaddingValues(bottom = LocalDemoChromeBottomInset.current),
            )
        }
    }
}
