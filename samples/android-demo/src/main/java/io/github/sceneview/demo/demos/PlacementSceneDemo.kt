package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
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
    var availability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    val guidance = rememberArGuidanceState(state, trackingFailureReason = trackingFailure)
    val recoveryCardShown = availability != null || state.phase == PlacementPhase.CAMERA_ERROR
    val playback = rememberArPlaybackDataset()
    ARHapticFeedback(state)
    DemoScaffold(
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
                assetReady = instance != null,
                modifier = Modifier.fillMaxSize(),
                state = state,
                engine = engine,
                modelLoader = modelLoader,
                // SDK coaching, permission and failure cards keep clear of the title and mode selector.
                coachingContentPadding = autoPlacementGuidancePadding(
                    bandWidth = maxWidth,
                    recoveryCardShown = recoveryCardShown,
                ),
                playbackDataset = playback,
                onARCoreAvailability = { availability = it },
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
    val gutter = SceneViewTokens.Space.md
    val bottom = LocalDemoChromeBottomInset.current
    val slot = coachStackSlot(
        compactHeight = isDemoCompactHeight(),
        cardShown = recoveryCardShown,
        bandWidth = bandWidth,
        chromeWidth = LocalDemoChromeBottomWidth.current,
        chromeBottom = bottom,
        gutter = gutter,
        minWidth = SceneViewTokens.ArOverlay.sdkCardActionInsets +
            SceneViewTokens.ArOverlay.actionLabelMinWidth * LocalDensity.current.fontScale - gutter * 2,
        maxWidth = SceneViewTokens.ArOverlay.maxWidth,
    )
    return PaddingValues(
        start = if (slot.besideChrome) bandWidth - slot.width - gutter * 2 else 0.dp,
        top = LocalDemoChromeTopInset.current,
        bottom = if (slot.besideChrome) 0.dp else bottom,
    )
}
