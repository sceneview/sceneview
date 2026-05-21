package io.github.sceneview.demo.demos

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.RooftopAnchorState
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.RooftopAnchorNode
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.math.Position
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

private const val TAG = "ARRooftopAnchorDemo"

/**
 * Rooftop Anchor demo (Geospatial API).
 *
 * Showcases [RooftopAnchorNode] — a Geospatial anchor that snaps a model to the rooftop of the
 * building at a given lat/lng (falls back to terrain if there's no building). Useful for floating
 * labels above stores, drone-landing signage, or property markers in real-estate apps.
 *
 * Currently works only where Google has detailed building data (urban areas with VPS coverage).
 *
 * Requires:
 * - ARCore Geospatial API enabled in Google Cloud Console (com.google.android.ar.API_KEY meta-data)
 * - Device supports ARCore Geospatial API
 * - Outdoor environment with VPS / Street View coverage
 * - CAMERA + ACCESS_FINE_LOCATION permissions
 */
@Composable
fun ARRooftopAnchorDemo(onBack: () -> Unit) {
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
    var permissionsResolved by remember {
        mutableStateOf(cameraGranted && fineLocationGranted)
    }
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
                "Location permission denied — Rooftop anchors need ACCESS_FINE_LOCATION"
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
        DemoScaffold(title = stringResource(R.string.demo_ar_rooftop_title), onBack = onBack) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = permissionDeniedReason ?: "Requesting permissions…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
        }
        return
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val hasArcoreApiKey = remember {
        runCatching {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            !ai.metaData?.getString("com.google.android.ar.API_KEY").isNullOrBlank()
        }.getOrDefault(false)
    }

    var arSession by remember { mutableStateOf<Session?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var earthTracking by remember { mutableStateOf(false) }
    // Tracked alongside `earthTracking` because `Earth.resolveAnchorOnRooftopAsync`
    // throws IllegalStateException if `earth.earthState != EarthState.ENABLED`. The
    // `runCatching` below would swallow it silently — gating the button on the
    // explicit state lets us tell the user *why* the drop is unavailable.
    var earthState by remember { mutableStateOf<Earth.EarthState?>(null) }
    var cameraLat by remember { mutableStateOf<Double?>(null) }
    var cameraLng by remember { mutableStateOf<Double?>(null) }
    var cameraAlt by remember { mutableStateOf<Double?>(null) }
    var horizontalAccuracy by remember { mutableStateOf<Double?>(null) }
    var geospatialUnavailable by remember { mutableStateOf<String?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }

    val placedAnchors = remember { mutableStateListOf<PlacedRooftopAnchor>() }
    var nextId by remember { mutableStateOf(0) }

    val labelInstance = rememberModelInstance(modelLoader, "models/khronos_lantern.glb")

    DemoScaffold(
        title = stringResource(R.string.demo_ar_rooftop_title),
        onBack = onBack,
        controls = {
            Text(
                text = "Rooftop anchors snap to building tops. Useful for floating labels above " +
                    "stores, drone landing signage, or property markers in real-estate apps. " +
                    "Currently works only where Google has detailed building data (urban areas).",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = when {
                    !hasArcoreApiKey ->
                        "ARCore Cloud API key missing — set ARCORE_API_KEY in local.properties"
                    geospatialUnavailable != null -> geospatialUnavailable!!
                    sessionError != null -> "Session error: $sessionError"
                    !isTracking -> "Waiting for camera tracking…"
                    !earthTracking -> "Waiting for VPS lock (go outside, look around)…"
                    cameraLat != null && cameraLng != null -> {
                        val lat = "%.6f".format(cameraLat)
                        val lng = "%.6f".format(cameraLng)
                        val alt = cameraAlt?.let { "%.1f m".format(it) } ?: "?"
                        val acc = horizontalAccuracy?.let { "%.1f m".format(it) } ?: "?"
                        "Camera: $lat, $lng • alt $alt • ±$acc"
                    }
                    else -> "Resolving Geospatial pose…"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

            Button(
                onClick = {
                    val session = arSession ?: return@Button
                    val lat = cameraLat ?: return@Button
                    val lng = cameraLng ?: return@Button
                    val id = nextId++
                    placedAnchors.add(
                        PlacedRooftopAnchor(
                            id = id,
                            latitude = lat,
                            longitude = lng,
                            // ARCore RooftopAnchorState has no TASK_IN_PROGRESS — use NONE as
                            // the in-flight placeholder, then update on the resolve callback.
                            state = RooftopAnchorState.NONE,
                            anchor = null
                        )
                    )
                    runCatching {
                        RooftopAnchorNode.resolve(
                            engine = engine,
                            session = session,
                            latitude = lat,
                            longitude = lng,
                            altitudeAboveRooftop = 1.5,
                            // Identity EUS quaternion (X+ east, Y+ up, Z+ south).
                            eusQuaternion = Quaternion()
                        ) { state, node ->
                            val idx = placedAnchors.indexOfFirst { it.id == id }
                            if (idx < 0) return@resolve
                            placedAnchors[idx] = placedAnchors[idx].copy(
                                state = state,
                                anchor = node?.anchor
                            )
                        }
                    }.onFailure { error ->
                        Log.w(TAG, "Rooftop anchor resolve threw", error)
                        val idx = placedAnchors.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            placedAnchors[idx] = placedAnchors[idx].copy(
                                state = RooftopAnchorState.ERROR_INTERNAL
                            )
                        }
                    }
                },
                enabled = hasArcoreApiKey &&
                    earthTracking &&
                    earthState == Earth.EarthState.ENABLED &&
                    cameraLat != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                Text("Drop on roof")
            }

            // Surface the EarthState when it is something other than ENABLED — the
            // resolve API throws IllegalStateException in that case, but the button
            // is disabled silently so the user otherwise has no idea what's wrong.
            if (earthState != null && earthState != Earth.EarthState.ENABLED) {
                Text(
                    text = "Earth not ready (state: $earthState)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            OutlinedButton(
                onClick = {
                    placedAnchors.forEach { it.anchor?.let { a -> runCatching { a.detach() } } }
                    placedAnchors.clear()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Clear All")
            }

            if (placedAnchors.isNotEmpty()) {
                Text(
                    text = "Placed rooftop anchors:",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    placedAnchors.forEach { placed ->
                        Text(
                            text = "#${placed.id}: ${"%.5f".format(placed.latitude)}, " +
                                "${"%.5f".format(placed.longitude)} — ${placed.state.label()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = placed.state.color()
                        )
                    }
                }
            }

            // Quick legend so the demo is self-explanatory: each error state explains
            // exactly what failed, since "ERROR_UNSUPPORTED_LOCATION" sounds vague otherwise.
            Text(
                text = "States: SUCCESS = anchored to roof/terrain. " +
                    "ERROR_NOT_AUTHORIZED = API key not whitelisted. " +
                    "ERROR_UNSUPPORTED_LOCATION = no building/terrain data here. " +
                    "ERROR_INTERNAL = transient ARCore failure.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
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
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                        return@ARSceneView
                    }
                    runCatching {
                        config.geospatialMode = Config.GeospatialMode.ENABLED
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }.onFailure { error ->
                        Log.w(TAG, "Geospatial config failed", error)
                        geospatialUnavailable = "Geospatial config failed: ${error.message}"
                        config.geospatialMode = Config.GeospatialMode.DISABLED
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }
                },
                onSessionCreated = { session ->
                    arSession = session
                },
                onSessionFailed = { exception ->
                    Log.e(TAG, "AR session failed", exception)
                    sessionError = exception.message ?: exception.javaClass.simpleName
                },
                onSessionUpdated = { session: Session, frame: Frame ->
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    val earth = session.earth
                    earthTracking = earth?.trackingState == TrackingState.TRACKING
                    earthState = earth?.earthState
                    if (earthTracking) {
                        val pose = earth?.cameraGeospatialPose
                        cameraLat = pose?.latitude
                        cameraLng = pose?.longitude
                        cameraAlt = pose?.altitude
                        horizontalAccuracy = pose?.horizontalAccuracy
                    }
                }
            ) {
                placedAnchors.forEach { placed ->
                    val anchor = placed.anchor ?: return@forEach
                    key(placed.id) {
                        AnchorNode(anchor = anchor) {
                            labelInstance?.let { instance ->
                                ModelNode(
                                    modelInstance = instance,
                                    scaleToUnits = 0.7f,
                                    centerOrigin = Position(0.0f, 0.0f, 0.0f)
                                )
                            }
                        }
                    }
                }
            }

            val statusText = when {
                sessionError != null -> "AR session error: $sessionError"
                !hasArcoreApiKey ->
                    "ARCore Cloud API key not configured — see samples/android-demo/" +
                        "STREETSCAPE_SETUP.md"
                geospatialUnavailable != null ->
                    "${geospatialUnavailable!!} — needs urban area with building data + " +
                        "Cloud API key"
                !isTracking -> "Initializing camera…"
                !earthTracking -> "Waiting for VPS lock — go outside in an urban area"
                else -> "Ready — point at a building and tap \"Drop on roof\""
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(
                        color = if (!hasArcoreApiKey || sessionError != null ||
                            geospatialUnavailable != null) {
                            MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        },
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

private data class PlacedRooftopAnchor(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val state: RooftopAnchorState,
    val anchor: Anchor?
)

private fun RooftopAnchorState.label(): String = when (this) {
    RooftopAnchorState.NONE -> "Resolving…"
    RooftopAnchorState.SUCCESS -> "Anchored on rooftop"
    RooftopAnchorState.ERROR_INTERNAL -> "Error: internal"
    RooftopAnchorState.ERROR_NOT_AUTHORIZED -> "Error: API key not authorized"
    RooftopAnchorState.ERROR_UNSUPPORTED_LOCATION ->
        "Error: no building/terrain data here"
}

@Composable
private fun RooftopAnchorState.color() = when (this) {
    RooftopAnchorState.SUCCESS -> MaterialTheme.colorScheme.primary
    RooftopAnchorState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}
