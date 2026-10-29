package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoachingOverlay
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.ArGuidanceCue
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.ar.rememberHostLifecycle
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ArAvailabilityStage
import io.github.sceneview.demo.common.arSessionFailedUnexplained
import io.github.sceneview.demo.common.CatalogueProductPill
import io.github.sceneview.demo.common.CatalogueProducts
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.placement.PivotedModelNode
import io.github.sceneview.demo.demos.internal.SurfaceShowcaseBanner
import io.github.sceneview.demo.demos.internal.SurfaceShowcaseState
import io.github.sceneview.demo.demos.internal.surfaceShowcaseBanner
import io.github.sceneview.demo.common.rememberCatalogueProduct
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener

/**
 * AR showcase demo — **Surfaces** (#3507, #4307): what `ARSceneView` draws on the surfaces
 * the phone finds, and what happens to them once something stands on one.
 *
 * There is one plane renderer in the SDK and every AR screen gets it by default; this screen
 * only adds what makes it readable. Plane finding runs in `HORIZONTAL_AND_VERTICAL`, so the
 * three treatments are on screen at once — round dots on a floor or a table, upright dashes
 * on a wall, hollow rings on a ceiling — and the status sentence says what to do next:
 *
 * 1. **Scanning** — marks grow over each surface the phone finds.
 * 2. **Found** — tap the floor to place the sofa.
 * 3. **Placed** — the surfaces fade out (`planeRenderer = false`), the sofa does **not**
 *    freeze: drag it along the floor, twist it, pinch it, or tap somewhere else to move it
 *    there. The surfaces come back for the length of a drag. The dock keeps **Surfaces**
 *    (show them again) and **Reset** (pick the sofa up, scan again) on screen throughout.
 *
 * The rules of step 3 live in [SurfaceShowcaseState], which is pure and unit-tested.
 */
@Composable
fun ARSurfacesDemo(onBack: () -> Unit) {
    var arSessionFailed by remember { mutableStateOf(false) }
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch.
    val arPlaybackDataset = rememberArPlaybackDataset()
    // A real product at its real size, the IKEA NOCKEBY from the catalogue (#4560, #4614),
    // fetched from the HD pack: the floor reads at the scale of the room it is in.
    val sofa = rememberCatalogueProduct(modelLoader, CatalogueProducts.IKEA_NOCKEBY_SOFA)

    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var planeDetected by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var isTracking by remember { mutableStateOf(false) }
    var showcase by remember { mutableStateOf(SurfaceShowcaseState()) }
    // The anchor the sofa was first placed on. It only keys the node: a drag or a tap
    // elsewhere gives the node a new anchor and detaches this one, and the node's current
    // anchor is never written back here — that would rebuild the node, whose teardown
    // detaches the very anchor it was just given.
    var placedAnchor by remember { mutableStateOf<Anchor?>(null) }
    // Read by the tap handler only: plain holders, so a new frame never recomposes.
    val latestFrame = remember { FrameHolder() }
    val sofaAnchorNode = remember { AnchorNodeHolder() }

    // #3341: non-null once ARCore has ruled this device out. The flag the scanning
    // banner waits on never flips then, so that banner has to read the verdict or
    // it promises a scan under the SDK's "AR unavailable" card, forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    // The SDK coaching card: getting ready, the phone sweep until a surface comes up, "Surface
    // found", and the reason with its fix when tracking struggles. While it speaks the
    // banner steps aside — one voice at a time.
    val guidance = rememberArGuidanceState(
        cameraReady = cameraReady,
        isTracking = isTracking,
        surfaceFound = planeDetected,
        trackingFailureReason = trackingFailureReason,
    )

    DemoScaffold(
        // The SDK's "Couldn't start AR" card keeps its in-place "Try again" (#4622); the
        // scaffold's card, which has none, is only for a failure the SDK does not explain.
        arSessionFailed = arSessionFailedUnexplained(arSessionFailed, arCoreAvailability),
        arOverlaysEnabled = arCoreAvailability == null,
        title = stringResource(R.string.demo_ar_surfaces_title),
        onBack = onBack,
        // A tap on the scene places the sofa; it must never also hide the dock.
        chromeToggleOnTap = false,
        // Both actions stay in the dock before and after the sofa is placed (#4307): the
        // surfaces fading must not take the controls with them. Reset is the same glyph and
        // the same words as AR Placement's — one action, one icon across the app.
        dock = listOf(
            DockItem(
                icon = Icons.Filled.Grain,
                label = stringResource(R.string.ar_surfaces_dock_surfaces_label),
                caption = stringResource(R.string.ar_surfaces_dock_surfaces_caption),
                onClick = { showcase = showcase.onSurfacesToggled() },
                selected = showcase.surfacesShown,
            ),
            DockItem(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.ar_dock_reset_label),
                caption = stringResource(R.string.ar_dock_reset_caption),
                onClick = {
                    // Leaving the composition destroys the node, which detaches whichever
                    // anchor it holds by now.
                    placedAnchor = null
                    sofaAnchorNode.node = null
                    showcase = showcase.onRemoved()
                },
                enabled = showcase.removeEnabled,
            ),
        ),
        bottomOverlay = {
            val banner = surfaceShowcaseBanner(
                arUnavailable = arCoreAvailability != null,
                coaching = guidance.isCoaching,
                trackingLost = trackingFailureReason != null,
                surfaceFound = planeDetected,
                placed = showcase.placed,
            )
            // Until the sofa is in, its pill names it and narrates the download.
            if (arCoreAvailability == null) CatalogueProductPill(sofa)
            DemoStatusBanner(
                text = when (banner) {
                    null -> null
                    SurfaceShowcaseBanner.SCANNING -> stringResource(R.string.ar_surfaces_status_scanning)
                    // Surface found but the sofa not in yet: its pill says why a tap waits.
                    SurfaceShowcaseBanner.FOUND ->
                        sofa.instance?.let { stringResource(R.string.ar_surfaces_status_found) }
                    SurfaceShowcaseBanner.PLACED -> stringResource(R.string.ar_surfaces_status_placed)
                    SurfaceShowcaseBanner.MOVE_PHONE -> stringResource(R.string.ar_surfaces_status_move)
                },
                tone = if (banner == SurfaceShowcaseBanner.MOVE_PHONE) {
                    DemoStatusTone.Guidance
                } else {
                    DemoStatusTone.Progress
                },
                // A Progress pill spins unless it is handed an icon. Only the scan spins:
                // once a surface is found the phone is waiting for the user, not working.
                icon = when (banner) {
                    SurfaceShowcaseBanner.FOUND -> Icons.Filled.TouchApp
                    SurfaceShowcaseBanner.PLACED -> Icons.Filled.OpenWith
                    else -> null
                },
            )
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
                // Fading the surfaces only stops drawing them: detection, hit tests and
                // the gestures on the sofa keep working.
                planeRenderer = showcase.planesVisible,
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                },
                onARCoreAvailability = {
                    arCoreAvailability = it
                    // Back to null is a retry starting: a retry that works must not then land on
                    // the scaffold's failure card.
                    if (it == null) arSessionFailed = false
                },
                arCoreAvailabilityOverlay = { ArAvailabilityStage(it) },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason },
                onSessionUpdated = { _, frame ->
                    latestFrame.frame = frame
                    cameraReady = true
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    if (!planeDetected) {
                        planeDetected = frame.getUpdatedTrackables(Plane::class.java)
                            .any { it.trackingState == TrackingState.TRACKING }
                    }
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event: MotionEvent, node ->
                        // A tap on the sofa itself is not a request to move it.
                        // Nothing to place until the sofa is in.
                        val canPlace = node == null && sofa.instance != null
                        val anchor = if (canPlace) latestFrame.frame?.floorHit(event)?.createAnchor() else null
                        if (anchor != null) {
                            val placedNode = sofaAnchorNode.node
                            if (placedNode != null) {
                                // The setter detaches the anchor the sofa was standing on.
                                placedNode.anchor = anchor
                                showcase = showcase.onRePlaced()
                            } else {
                                placedAnchor?.detach()
                                placedAnchor = anchor
                                showcase = showcase.onPlaced()
                            }
                        }
                    },
                    onMoveBegin = { _, _, node -> if (node != null) showcase = showcase.onMoveBegin() },
                    onMoveEnd = { _, _, _ -> showcase = showcase.onMoveEnd() },
                ),
            ) {
                placedAnchor?.let { anchor ->
                    AnchorNode(
                        anchor = anchor,
                        apply = {
                            // Without this the node ignores every gesture its children
                            // forward to it, and the sofa is nailed to where it was placed.
                            isEditable = true
                            // The sofa lands where it was placed from: on a floor or a table,
                            // inside what the phone has actually seen of it.
                            moveHitTest = { frame, event -> frame.floorHit(event) }
                            sofaAnchorNode.node = this
                        },
                    ) {
                        sofa.instance?.let {
                            // Drag goes up to the anchor, the twist stops at the pivot, the
                            // pinch stays on the model (#3735).
                            PivotedModelNode(
                                modelInstance = it,
                                assetRotation = Rotation(),
                                scaleToUnits = sofa.realSizeMetres,
                                groundBase = true,
                            )
                        }
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

/** Latest AR frame, for the tap handler. Not Compose state: it changes every frame. */
private class FrameHolder {
    var frame: Frame? = null
}

/** The sofa's anchor node, for the tap handler that re-places it. Not Compose state. */
private class AnchorNodeHolder {
    var node: AnchorNode? = null
}

/** Where [event] hits a tracked floor or table inside its polygon, or `null`. */
private fun Frame.floorHit(event: MotionEvent): HitResult? {
    if (camera.trackingState != TrackingState.TRACKING) return null
    return hitTest(event).firstOrNull { result ->
        val plane = result.trackable
        plane is Plane &&
            plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
            plane.trackingState == TrackingState.TRACKING &&
            plane.isPoseInPolygon(result.hitPose) &&
            result.distance <= MAX_PLACE_DISTANCE_METERS
    }
}

private const val MAX_PLACE_DISTANCE_METERS = 5.0f
