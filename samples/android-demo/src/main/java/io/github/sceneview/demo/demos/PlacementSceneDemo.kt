package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARHapticFeedback
import io.github.sceneview.ar.AutoPlacementModel
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.PlacementMath
import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.ar.PlacementSource
import io.github.sceneview.ar.rememberArGuidanceState
import io.github.sceneview.ar.rememberAutoPlacementState
import io.github.sceneview.demo.common.CatalogueProductPill
import io.github.sceneview.demo.common.CatalogueProducts
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.rememberCatalogueProduct
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader

/**
 * The automatic-placement API, placing a real product at its real size: the KitchenAid stand
 * mixer from the catalogue (#4560), on a table or on the floor.
 *
 * Everything here is the default behaviour (#4858): the mixer lands on the first surface, or on
 * the estimated floor after 600 ms without one, with a contact shadow and the flat placement
 * ring. One finger drags it, two fingers turn and pinch it (both carry on when flicked), a
 * double tap toggles real size. The demo only adds the placement distance for the mixer's size
 * and a Recenter button.
 */
@Composable
fun PlacementSceneDemo(onBack: () -> Unit) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val product = rememberCatalogueProduct(modelLoader, CatalogueProducts.KITCHENAID_STAND_MIXER)
    val instance = product.instance
    val state = rememberAutoPlacementState()
    // Stand back far enough to see the whole product at its real size.
    LaunchedEffect(product.realSizeMetres) {
        product.realSizeMetres?.let { state.placementDistance = PlacementMath.placementDistanceFor(it) }
    }
    // AutoPlacementScene draws the coaching card itself (coaching = true, the default); this
    // only tells the demo when to keep its own pill quiet, from the same tracking reason.
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    val guidance = rememberArGuidanceState(state, trackingFailureReason = trackingFailure)
    val playback = rememberArPlaybackDataset()
    ARHapticFeedback(state)
    DemoScaffold(
        title = stringResource(R.string.demo_placement_scene_title),
        onBack = onBack,
        bottomOverlay = {
            // Until the product is in, its pill names it and narrates the download.
            CatalogueProductPill(product)
            val message = when {
                instance == null -> if (product.needsPill) null else R.string.ar_place_loading_model
                guidance.isCoaching -> null
                state.phase == PlacementPhase.SCANNING -> R.string.ar_place_move_slowly
                state.phase == PlacementPhase.NO_SURFACE -> R.string.ar_place_no_surface_title
                state.phase == PlacementPhase.TRACKING_LOST -> R.string.ar_place_tracking_paused
                state.phase == PlacementPhase.RECOVERING -> R.string.ar_place_finding_placement
                state.phase == PlacementPhase.RECOVERY_FAILED -> R.string.ar_place_recovery_failed_title
                state.phase == PlacementPhase.CAMERA_ERROR -> R.string.ar_place_camera_error_title
                state.placementSource == PlacementSource.ESTIMATED && !state.isSettled ->
                    R.string.placement_scene_estimated_floor
                else -> null
            }
            DemoStatusBanner(text = message?.let { stringResource(it) }, tone = DemoStatusTone.Guidance)
        },
        controls = {
            Text(stringResource(R.string.ar_place_real_size))
            if (state.phase == PlacementPhase.PLACED || state.phase == PlacementPhase.ADJUSTING) {
                TextButton(onClick = { state.recenter() }) {
                    Text(stringResource(R.string.placement_scene_recenter))
                }
            }
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
        AutoPlacementScene(
            assetReady = instance != null,
            modifier = Modifier.fillMaxSize(),
            state = state,
            engine = engine,
            modelLoader = modelLoader,
            // The card and its "Surface found" pill stay above the dock.
            coachingContentPadding = PaddingValues(bottom = LocalDemoChromeBottomInset.current),
            playbackDataset = playback,
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
