package io.github.sceneview.demo.demos

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.MeshClassification
import io.github.sceneview.ar.node.toMeshClassification
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance

private const val TAG = "ARSceneMeshDemo"

private const val NO_GEOMETRY_HINT_DELAY_MS = 15_000L

/**
 * Default overlay opacity for the scene mesh visualization — semi-transparent so the camera
 * feed and the real-world structure remain visible through the coloured mesh.
 */
private const val MESH_OVERLAY_ALPHA = 0.55f

/**
 * AR demo — Scene Mesh: wraps ARCore's Streetscape Geometry API using [SceneMeshNode] to render
 * classified real-world meshes. Each [StreetscapeGeometry] is colour-coded by its
 * [MeshClassification] ([MeshClassification.TERRAIN] = green, [MeshClassification.BUILDING] = blue).
 *
 * This demo is the Android-side analogue of ARKit's `ARMeshAnchor` Scene Reconstruction:
 * - **ARCore (this demo)**: classification is per geometry — TERRAIN or BUILDING — delivered by
 *   the Geospatial Streetscape API (outdoor, requires Street View coverage and a Cloud API key).
 * - **ARKit**: classification is per face — FLOOR, WALL, CEILING, TABLE, SEAT, WINDOW, DOOR —
 *   delivered by Scene Reconstruction (indoor and outdoor, requires LiDAR).
 *
 * SceneView's [MeshClassification] enum covers both vocabularies so the same color-map or
 * physics-layer code runs on both platforms without change.
 *
 * **Requirements:**
 * - ARCore Geospatial API enabled in Google Cloud Console
 * - Device supports ARCore Geospatial API
 * - Outdoor environment with Google Street View coverage
 * - CAMERA + ACCESS_FINE_LOCATION permissions
 */
@Composable
fun ARSceneMeshDemo(onBack: () -> Unit) {
    val context = LocalContext.current

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var fineLocationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionsResolved by remember { mutableStateOf(cameraGranted && fineLocationGranted) }
    var permissionDeniedReason by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        fineLocationGranted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] ?: fineLocationGranted
        permissionDeniedReason = when {
            !cameraGranted -> "Camera permission denied — AR cannot run"
            !fineLocationGranted ->
                "Location permission denied — Scene Mesh needs ACCESS_FINE_LOCATION"
            else -> null
        }
        permissionsResolved = true
    }

    LaunchedEffect(Unit) {
        if (cameraGranted && fineLocationGranted) {
            permissionsResolved = true
            return@LaunchedEffect
        }
        val toRequest = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (!fineLocationGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(toRequest.toTypedArray())
    }

    if (!permissionsResolved || !cameraGranted || !fineLocationGranted) {
        DemoScaffold(title = stringResource(R.string.demo_ar_scene_mesh_title), onBack = onBack) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = permissionDeniedReason ?: "Requesting permissions…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    if (permissionDeniedReason != null) {
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = {
                                val toRequest = buildList {
                                    if (!cameraGranted) add(Manifest.permission.CAMERA)
                                    if (!fineLocationGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                if (toRequest.isNotEmpty()) {
                                    permissionDeniedReason = null
                                    permissionsResolved = false
                                    permissionLauncher.launch(toRequest.toTypedArray())
                                }
                            }) { Text("Retry") }
                            Button(onClick = {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null)
                                ).apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK }
                                context.startActivity(intent)
                            }) { Text("Open Settings") }
                        }
                    }
                }
            }
        }
        return
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Per-classification material instances — colour coded so TERRAIN and BUILDING read
    // at a glance from the mesh overlay.
    val terrainMaterial = rememberMaterialInstance(
        materialLoader,
        color = Color(0x8D4CAF50.toInt()),  // semi-transparent green
        metallic = 0.0f,
        roughness = 1.0f,
        reflectance = 0.0f,
    )
    val buildingMaterial = rememberMaterialInstance(
        materialLoader,
        color = Color(0x8D2196F3.toInt()),  // semi-transparent blue
        metallic = 0.0f,
        roughness = 1.0f,
        reflectance = 0.0f,
    )

    val geometries = remember { mutableStateListOf<StreetscapeGeometry>() }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var geometryCount by remember { mutableStateOf(0) }
    var noGeometryGuidance by remember { mutableStateOf(false) }
    var geospatialUnavailable by remember { mutableStateOf<String?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }

    val hasArcoreApiKey = remember {
        runCatching {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            !ai.metaData?.getString("com.google.android.ar.API_KEY").isNullOrBlank()
        }.getOrDefault(false)
    }

    LaunchedEffect(isTracking, geometryCount, geospatialUnavailable, sessionError, hasArcoreApiKey) {
        noGeometryGuidance = false
        if (isTracking &&
            geometryCount == 0 &&
            geospatialUnavailable == null &&
            sessionError == null &&
            hasArcoreApiKey
        ) {
            kotlinx.coroutines.delay(NO_GEOMETRY_HINT_DELAY_MS)
            noGeometryGuidance = true
        }
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_scene_mesh_title),
        onBack = onBack,
        controls = if (DemoSettings.qaMode) {
            { ForceTrackingFailureMenu() }
        } else {
            null
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionConfiguration = { session: Session, config: Config ->
                    val geospatialOk = runCatching {
                        session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
                    }.getOrElse { error ->
                        Log.w(TAG, "isGeospatialModeSupported threw", error)
                        false
                    }
                    if (!geospatialOk) {
                        geospatialUnavailable = "Geospatial API not available on this device"
                        Log.w(TAG, "Geospatial mode unsupported — running plain AR session")
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                        return@ARSceneView
                    }
                    runCatching {
                        config.geospatialMode = Config.GeospatialMode.ENABLED
                        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }.onFailure { error ->
                        Log.w(TAG, "Geospatial config failed — falling back", error)
                        geospatialUnavailable = "Geospatial config failed: ${error.message}"
                        config.geospatialMode = Config.GeospatialMode.DISABLED
                        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }
                },
                onSessionFailed = { exception ->
                    Log.e(TAG, "AR session failed", exception)
                    sessionError = exception.message ?: exception.javaClass.simpleName
                },
                onSessionUpdated = { _: Session, frame: Frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    frame.getUpdatedTrackables(StreetscapeGeometry::class.java).forEach { geo ->
                        if (geo.trackingState == TrackingState.TRACKING) {
                            if (geometries.none { it == geo }) geometries.add(geo)
                        }
                    }
                    geometries.removeAll { it.trackingState == TrackingState.STOPPED }
                    geometryCount = geometries.size
                },
                onTrackingFailureChanged = { reason -> trackingFailureReason = reason }
            ) {
                geometries.forEach { geo ->
                    // Colour-code by classification so TERRAIN and BUILDING are visually distinct.
                    val material = when (geo.type.toMeshClassification()) {
                        MeshClassification.TERRAIN -> terrainMaterial
                        MeshClassification.BUILDING -> buildingMaterial
                        else -> buildingMaterial
                    }
                    SceneMeshNode(
                        streetscapeGeometry = geo,
                        meshMaterialInstance = material,
                    )
                }
            }

            // Classification legend — only visible once geometry is rendering.
            if (geometryCount > 0) {
                MeshClassificationLegend(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 72.dp)
                )
            }

            // Status overlay.
            val effectiveReason = ForcedTrackingFailure.override ?: trackingFailureReason
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val statusText = when {
                    sessionError != null -> "AR session error: $sessionError"
                    !hasArcoreApiKey ->
                        "ARCore Cloud API key not configured — see samples/android-demo/ARCORE_CLOUD_SETUP.md"
                    geospatialUnavailable != null ->
                        "${geospatialUnavailable!!} — needs outdoor area with Street View coverage + Cloud API key"
                    ForcedTrackingFailure.override != null ->
                        trackingFailureMessage(effectiveReason) ?: "Scanning…"
                    geometryCount > 0 -> "Rendering $geometryCount mesh(es)"
                    !isTracking -> trackingFailureMessage(effectiveReason)
                        ?: "Scanning environment…"
                    noGeometryGuidance -> stringResource(R.string.demo_ar_scene_mesh_no_geometry_hint)
                    else -> "Looking for scene mesh…"
                }
                Text(
                    text = statusText,
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

/** Small overlay legend mapping the two ARCore surface types to their overlay colour. */
@Composable
private fun MeshClassificationLegend(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = "Scene Mesh",
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.height(4.dp))
            LegendRow(
                color = Color(0xFF4CAF50),
                label = "Terrain"
            )
            LegendRow(
                color = Color(0xFF2196F3),
                label = "Building"
            )
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
