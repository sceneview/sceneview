package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.ar.AutoPlacementModel
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.ar.rememberAutoPlacementState
import io.github.sceneview.demo.common.LocalMotionSensorSilent
import io.github.sceneview.demo.common.CatalogueProductPill
import io.github.sceneview.demo.common.CatalogueProducts
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoChromeBottomWidth
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.common.placement.coachStackSlot
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.LocalDemoChromeTopInset
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberCatalogueProduct
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader

/**
 * The additive automatic-placement convenience API, placing a real product at its real size:
 * the KitchenAid stand mixer from the catalogue (#4560), on a table or on the floor.
 */
@Composable
fun PlacementSceneDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val product = rememberCatalogueProduct(modelLoader, CatalogueProducts.KITCHENAID_STAND_MIXER)
    val instance = product.instance
    val state = rememberAutoPlacementState()
    // AutoPlacementScene draws the coaching card itself (coaching = true, the default); this
    // only tells the demo when to keep its own pill quiet, from the same tracking reason.
    var cameraDenied by remember { mutableStateOf(false) }
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    val guidance = rememberArGuidanceState(state, trackingFailureReason = trackingFailure)
    val recoveryCardShown = autoPlacementRecoveryCardShown(cameraDenied, availability, state.phase)
    val playback = rememberArPlaybackDataset()
    ARHapticFeedback(state)
    DemoScaffold(
        monitorArMotionSensors = true,
        title = stringResource(R.string.demo_placement_scene_title),
        onBack = onBack,
        // The SDK explains coaching and recovery; leave the corner beside the dock free.
        bottomOverlay = if (guidance.isCoaching || recoveryCardShown) null else {
            {
                // Until the product is in, its pill names it and narrates the download.
                CatalogueProductPill(product)
                val message = when {
                    instance == null -> if (product.needsPill) null else R.string.ar_place_loading_model
                    state.phase == PlacementPhase.SCANNING -> R.string.ar_place_move_slowly
                    state.phase == PlacementPhase.NO_SURFACE -> R.string.ar_place_no_surface_title
                    state.phase == PlacementPhase.TRACKING_LOST -> R.string.ar_place_tracking_paused
                    state.phase == PlacementPhase.RECOVERING -> R.string.ar_place_finding_placement
                    state.phase == PlacementPhase.RECOVERY_FAILED -> R.string.ar_place_recovery_failed_title
                    else -> null
                }
                DemoStatusBanner(text = message?.let { stringResource(it) }, tone = DemoStatusTone.Guidance)
            }
        },
        controls = {
            Text(stringResource(R.string.ar_place_real_size))
            TextButton(onClick = { state.resetPlacement(SystemClock.uptimeMillis()) }) {
                Text(stringResource(R.string.ar_dock_reset_label))
            }
            if (state.phase == PlacementPhase.NO_SURFACE) {
                TextButton(onClick = { state.keepScanning(SystemClock.uptimeMillis()) }) {
                    Text(stringResource(R.string.ar_place_keep_scanning))
                }
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            AutoPlacementScene(
                coaching = !LocalMotionSensorSilent.current,
                assetReady = instance != null,
                modifier = Modifier.fillMaxSize(),
                state = state,
                engine = engine,
                modelLoader = modelLoader,
                // Recovery cards clear the title; coaching keeps its full band above the dock.
                coachingContentPadding = autoPlacementGuidancePadding(
                    bandWidth = maxWidth,
                    recoveryCardShown = recoveryCardShown,
                ),
                playbackDataset = playback,
                onARCoreAvailability = { availability = it },
                onCameraPermissionStateChanged = { cameraDenied = it != null },
                onTrackingFailureChanged = { trackingFailure = it },
            ) { placement ->
                instance?.let {
                    // Real size, not the API's 0.3 m preview: a mixer reads as a mixer next to the
                    // user's own kitchen counter.
                    AutoPlacementModel(placement, state, it, scaleToUnits = product.realSizeMetres)
                }
            }
        }
    }
}

/** Gives recovery cards the free corner; animated coaching keeps the wider band above the dock. */
@Composable
internal fun autoPlacementGuidancePadding(bandWidth: Dp, recoveryCardShown: Boolean): PaddingValues {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing
    val safeWidth = (bandWidth - with(density) {
        (safeInsets.getLeft(density, direction) + safeInsets.getRight(density, direction)).toDp()
    }).coerceAtLeast(0.dp)
    val gutter = SceneViewTokens.Space.md
    val bottom = LocalDemoChromeBottomInset.current
    val slot = coachStackSlot(
        compactHeight = isDemoCompactHeight(),
        cardShown = recoveryCardShown,
        bandWidth = safeWidth,
        chromeWidth = LocalDemoChromeBottomWidth.current,
        chromeBottom = bottom,
        gutter = gutter,
        minWidth = SceneViewTokens.ArOverlay.sdkCardActionInsets +
            SceneViewTokens.ArOverlay.actionLabelMinWidth * LocalDensity.current.fontScale - gutter * 2,
        maxWidth = SceneViewTokens.ArOverlay.maxWidth,
    )
    return PaddingValues(
        start = if (slot.besideChrome) safeWidth - slot.width - gutter * 2 else 0.dp,
        // The coaching row cannot scroll: reserving the title as well as the dock clips its
        // instructions in landscape with large text. Only recovery cards need this top inset.
        top = if (recoveryCardShown) LocalDemoChromeTopInset.current else 0.dp,
        bottom = if (slot.besideChrome) 0.dp else bottom,
    )
}

/** The SDK can explain a refused camera before a session exists. */
internal fun autoPlacementRecoveryCardShown(
    cameraDenied: Boolean,
    availability: ARCoreAvailability?,
    phase: PlacementPhase,
): Boolean = cameraDenied || availability != null || phase == PlacementPhase.CAMERA_ERROR
