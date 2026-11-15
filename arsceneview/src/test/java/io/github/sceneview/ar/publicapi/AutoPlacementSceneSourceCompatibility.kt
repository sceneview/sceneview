@file:Suppress("unused")

package io.github.sceneview.ar.publicapi

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.ARCameraPermissionState
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.ar.AutoPlacementResult
import io.github.sceneview.ar.AutoPlacementScene
import io.github.sceneview.ar.AutoPlacementState
import io.github.sceneview.ar.PlacementSurface
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader

// Compile-only consumer fixtures: executing these would require Filament and an ARCore session.
// testDebugUnitTest compiles this file, so an API change that rebinds a callback fails the build.

@Composable
private fun legacyTypedTrackingCallback(
    engine: Engine,
    models: ModelLoader,
    materials: MaterialLoader,
    state: AutoPlacementState,
    tracking: (TrackingFailureReason?) -> Unit,
) {
    AutoPlacementScene(
        true, Modifier, state, PlacementSurface.SURFACE, engine, models, materials,
        true, true, PaddingValues(0.dp), null, null, tracking,
    ) { _ -> }
}

@Composable
private fun legacyInferredTrackingCallback(
    engine: Engine,
    models: ModelLoader,
    materials: MaterialLoader,
    state: AutoPlacementState,
    tracking: (TrackingFailureReason?) -> Unit,
) {
    AutoPlacementScene(
        true, Modifier, state, PlacementSurface.SURFACE, engine, models, materials,
        true, true, PaddingValues(0.dp), null, null,
        { reason ->
            // Pin inferred lambda typing: it must never become a camera-permission observer.
            val trackingReason: TrackingFailureReason? = reason
            tracking(trackingReason)
        },
    ) { _ -> }
}

@Composable
private fun legacyAllPositionalCallbacksAndContent(
    engine: Engine,
    models: ModelLoader,
    materials: MaterialLoader,
    state: AutoPlacementState,
    tracking: (TrackingFailureReason?) -> Unit,
    failed: (Exception) -> Unit,
    placed: (AutoPlacementResult) -> Unit,
    content: @Composable ARSceneScope.(AutoPlacementResult) -> Unit,
) {
    AutoPlacementScene(
        true, Modifier, state, PlacementSurface.SURFACE, engine, models, materials,
        true, true, PaddingValues(0.dp), null, null, tracking, failed, placed, content,
    )
}

@Composable
private fun permissionObserverWithTrailingContent(
    engine: Engine,
    models: ModelLoader,
    materials: MaterialLoader,
    permission: (ARCameraPermissionState?) -> Unit,
    tracking: (TrackingFailureReason?) -> Unit,
) {
    AutoPlacementScene(
        assetReady = true,
        engine = engine,
        modelLoader = models,
        materialLoader = materials,
        onTrackingFailureChanged = tracking,
        onCameraPermissionStateChanged = permission,
    ) { _ -> }
}

@Composable
private fun permissionObserverAfterLegacyPositionalCallbacks(
    engine: Engine,
    models: ModelLoader,
    materials: MaterialLoader,
    state: AutoPlacementState,
    tracking: (TrackingFailureReason?) -> Unit,
    failed: (Exception) -> Unit,
    placed: (AutoPlacementResult) -> Unit,
    permission: (ARCameraPermissionState?) -> Unit,
) {
    AutoPlacementScene(
        true, Modifier, state, PlacementSurface.SURFACE, engine, models, materials,
        true, true, PaddingValues(0.dp), null, null, tracking, failed, placed, permission,
    ) { _ -> }
}
