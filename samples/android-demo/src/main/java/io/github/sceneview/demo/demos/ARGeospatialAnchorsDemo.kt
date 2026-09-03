package io.github.sceneview.demo.demos

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.RooftopAnchorState
import com.google.ar.core.Anchor.TerrainAnchorState
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.RooftopAnchorNode
import io.github.sceneview.ar.node.TerrainAnchorNode
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.CloudServiceStatus
import io.github.sceneview.demo.common.CloudServiceStatusBanner
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.common.message
import io.github.sceneview.demo.common.rememberHasArcoreApiKey
import io.github.sceneview.demo.common.rememberIsNetworkAvailable
import io.github.sceneview.demo.common.toCloudServiceStatus
import io.github.sceneview.demo.demos.internal.friendlyArSessionError
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import java.util.Locale

private const val TAG_TERRAIN = "ARGeospatialAnchorsDemo/Terrain"
private const val TAG_ROOFTOP = "ARGeospatialAnchorsDemo/Rooftop"

/**
 * Unified "Geospatial Anchors" demo — consolidates the retired `ar-terrain` and
 * `ar-rooftop` demos behind a single segmented-button toggle (#2239).
 *
 * - **Terrain** — [io.github.sceneview.ar.node.TerrainAnchorNode] resolves a
 *   lat/lng against Google's global outdoor terrain altitude, so a model stays
 *   glued to the ground with no local plane detection. (Formerly `ar-terrain`.)
 * - **Rooftop** — [io.github.sceneview.ar.node.RooftopAnchorNode] resolves the
 *   same lat/lng against the *building* at that point, falling back to terrain
 *   where Google has no building data. (Formerly `ar-rooftop`.)
 *
 * **Why these two are one card.** They were 303 identical lines out of ~485: the
 * same permission gate, the same `Config.GeospatialMode.ENABLED` session, the
 * same `Earth` pose readout, the same Cloud-API-key banner, the same
 * `SceneActionBar`. The whole difference is which altitude reference the resolve
 * call names — `altitudeAboveTerrain` versus `altitudeAboveRooftop` — and the
 * `Anchor.*AnchorState` enum that comes back. That is a toggle, not a second
 * screen, and shipping it as two cards taught the reader that Geospatial has two
 * unrelated APIs when it has one with two references.
 *
 * The two `Anchor.TerrainAnchorState` and `Anchor.RooftopAnchorState` enums are
 * genuinely distinct ARCore types with different members (rooftop has no
 * `TASK_IN_PROGRESS`), so each mode keeps its **own** section, its own state
 * list and its own label/colour mapping rather than being forced through a
 * lowest-common-denominator wrapper — the merge is structural, and no
 * per-mode behaviour was rewritten. Each mode also keeps its own `ARSceneView`,
 * so switching modes tears the inactive session down completely (the invariant
 * the #2239 Batch-1 review pinned).
 *
 * Old deep links route through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES]; `ar-rooftop`
 * pre-selects mode 1 through
 * [io.github.sceneview.demo.DeepLinkRouter.ALIAS_INITIAL_TAB].
 */
@Composable
fun ARGeospatialAnchorsDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(GeospatialAnchorMode.entries, GeospatialAnchorMode.Terrain))
    }
    when (mode) {
        GeospatialAnchorMode.Terrain -> TerrainSection(onBack, mode) { mode = it }
        GeospatialAnchorMode.Rooftop -> RooftopSection(onBack, mode) { mode = it }
    }
}

/**
 * Declaration order is the segmented-button order and
 * [io.github.sceneview.demo.DeepLinkRouter.ALIAS_INITIAL_TAB] indexes into it
 * (`ar-rooftop` = 1). Append, never reorder.
 */
private enum class GeospatialAnchorMode(val label: String) {
    Terrain("Terrain"),
    Rooftop("Rooftop"),
}

@Composable
private fun ModeSelector(
    current: GeospatialAnchorMode,
    onModeChange: (GeospatialAnchorMode) -> Unit,
) {
    val modes = GeospatialAnchorMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(m.label) },
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

// ─── Terrain section ─────────────────────────────────────────────────────────
// Formerly ARTerrainAnchorDemo (`ar-terrain`).

@Composable
private fun TerrainSection(
    onBack: () -> Unit,
    mode: GeospatialAnchorMode,
    onModeChange: (GeospatialAnchorMode) -> Unit,
) {
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
                "Location permission denied — Terrain anchors need ACCESS_FINE_LOCATION"
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
        DemoScaffold(title = stringResource(R.string.demo_ar_geospatial_anchors_title), onBack = onBack) {
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

    val hasArcoreApiKey = rememberHasArcoreApiKey()

    var arSession by remember { mutableStateOf<Session?>(null) }
    // Flipped true on the first onSessionUpdated — i.e. once ARCore has opened the
    // camera and delivered a frame. Until then the ARSceneView surface is bare black,
    // so we cover it with ARCameraInitScrim instead of leaving a frozen-looking screen.
    var cameraReady by remember { mutableStateOf(false) }
    // #3341: non-null once ARCore has ruled this device out. `cameraReady` never flips
    // then, so the init scrim below has to read the verdict or it covers the SDK's own
    // explanation card forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var earthTracking by remember { mutableStateOf(false) }
    // Tracked alongside `earthTracking` because `Earth.resolveAnchorOnTerrainAsync`
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
    // Preemptive network check (#3262): Geospatial silently returns no data with no
    // network, which otherwise reads identically to a rejected API key.
    val isNetworkAvailable = rememberIsNetworkAvailable()

    // The one shared "why can't this demo work right now" answer (#3262): a missing
    // or rejected key, an exhausted Cloud quota, no network, or Geospatial not yet
    // localised (no VPS lock, so no usable lat/lng to resolve against).
    val cloudStatus: CloudServiceStatus = when {
        !hasArcoreApiKey -> CloudServiceStatus.ApiKeyMissing
        !isNetworkAvailable -> CloudServiceStatus.NoNetwork
        else -> earthState.toCloudServiceStatus("Geospatial")
            ?: if (isTracking && geospatialUnavailable == null && !earthTracking) {
                CloudServiceStatus.EarthLocalizing
            } else {
                CloudServiceStatus.Available
            }
    }

    val placedAnchors = remember { mutableStateListOf<PlacedTerrainAnchor>() }
    var nextId by remember { mutableStateOf(0) }

    val foxInstance = rememberModelInstance(modelLoader, "models/khronos_fox.glb")

    // Resolve a terrain anchor at the current camera lat/lng. Hoisted so the
    // on-screen SceneActionBar can invoke it — "Drop here" is the demo's
    // primary action (the banner says tap "Drop here"), so it lives on-screen,
    // not in the Settings sheet (#1964).
    val onDrop = onDrop@{
        val session = arSession ?: return@onDrop
        val lat = cameraLat ?: return@onDrop
        val lng = cameraLng ?: return@onDrop
        val id = nextId++
        placedAnchors.add(
            PlacedTerrainAnchor(
                id = id,
                latitude = lat,
                longitude = lng,
                state = TerrainAnchorState.TASK_IN_PROGRESS,
                anchor = null
            )
        )
        // Identity EUS quaternion so the model is upright with X+ east, Y+ up,
        // Z+ south — that is, oriented north-facing and ground-flat. ARCore takes
        // the four floats x/y/z/w of the quaternion.
        runCatching {
            TerrainAnchorNode.resolve(
                engine = engine,
                session = session,
                latitude = lat,
                longitude = lng,
                altitudeAboveTerrain = 0.0,
                // Identity EUS quaternion (0,0,0,1): X+ east, Y+ up, Z+ south.
                // Default no-arg constructor of dev.romainguy.kotlin.math.Quaternion
                // is the identity rotation.
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
            Log.w(TAG_TERRAIN, "Terrain anchor resolve threw", error)
            val idx = placedAnchors.indexOfFirst { it.id == id }
            if (idx >= 0) {
                placedAnchors[idx] = placedAnchors[idx].copy(
                    state = TerrainAnchorState.ERROR_INTERNAL
                )
            }
        }
        Unit
    }
    // Clear All is also a primary action (resets the demo) — moved on-screen.
    val onClearAll = {
        placedAnchors.forEach { it.anchor?.let { a -> runCatching { a.detach() } } }
        placedAnchors.clear()
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_geospatial_anchors_title),
        onBack = onBack,
        // "Drop here" / "Clear All" are the demo's primary actions and live
        // on-screen via SceneActionBar (#1964). The sheet keeps only the demo
        // explainer, the live Geospatial pose readout, the placed-anchors list
        // and the states legend — all genuinely informational / secondary.
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                text = "Terrain anchors snap to Google's outdoor terrain data. Works outdoors " +
                    "anywhere on Earth — no local plane detection needed. Stand outside, tap " +
                    "Drop, walk away. The model stays glued to the ground at that GPS coord.",
                style = MaterialTheme.typography.bodyMedium
            )

            // Live camera Geospatial pose readout, refreshed each frame from
            // Earth.cameraGeospatialPose. This is the position used as the lat/lng
            // for the next "Drop" — surfacing it makes it obvious why a tap on
            // the button might fail (no VPS lock = no usable lat/lng).
            Text(
                text = when {
                    // Mirrors the shared `bottomOverlay` banner below (#3262) so the
                    // sheet never disagrees with the main AR view about why Drop is
                    // disabled.
                    cloudStatus.isUnavailable -> cloudStatus.message()
                    geospatialUnavailable != null -> geospatialUnavailable!!
                    // Friendly, complete sentence from friendlyArSessionError (#2349).
                    sessionError != null -> sessionError!!
                    !isTracking -> "Waiting for camera tracking…"
                    !earthTracking -> "Waiting for VPS lock (go outside, look around)…"
                    cameraLat != null && cameraLng != null -> {
                        val lat = "%.6f".format(Locale.US, cameraLat)
                        val lng = "%.6f".format(Locale.US, cameraLng)
                        val alt = cameraAlt?.let { "%.1f m".format(Locale.US, it) } ?: "?"
                        val acc = horizontalAccuracy?.let { "%.1f m".format(Locale.US, it) } ?: "?"
                        "Camera: $lat, $lng • alt $alt • ±$acc"
                    }
                    else -> "Resolving Geospatial pose…"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

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

            if (placedAnchors.isNotEmpty()) {
                Text(
                    text = "Placed terrain anchors:",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    placedAnchors.forEach { placed ->
                        Text(
                            text = "#${placed.id}: ${"%.5f".format(Locale.US, placed.latitude)}, " +
                                "${"%.5f".format(Locale.US, placed.longitude)} — ${placed.state.label()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = placed.state.color()
                        )
                    }
                }
            }
        },
        // Status banner + primary actions both live in the scaffold's bottom slot —
        // a bottom-aligned Column, so they stack instead of sharing the same strip
        // of pixels above the system bars (#2779). Before this, the first-launch
        // "no ARCore Cloud API key" banner ran under both the "Drop here" button
        // and the Settings FAB.
        bottomOverlay = {
            when {
                // friendlyArSessionError already yields a complete, honest sentence (#2349).
                sessionError != null ->
                    DemoStatusBanner(sessionError!!, tone = DemoStatusTone.Blocked)
                // The one shared "Cloud service unavailable" banner (#3262): missing or
                // rejected key, exhausted quota, no network, or no VPS lock yet. Lives
                // in the main AR view, never only in Settings.
                cloudStatus.isUnavailable -> CloudServiceStatusBanner(cloudStatus)
                geospatialUnavailable != null ->
                    DemoStatusBanner(
                        "${geospatialUnavailable!!} — needs outdoor area with VPS coverage + Cloud API key",
                        tone = DemoStatusTone.Blocked,
                    )
                // The SDK's availability overlay already fills the viewport with the
                // reason and the retry; a second, now false, "Initializing camera…"
                // underneath it only contradicts it (#3341).
                arCoreAvailability != null -> Unit
                !isTracking -> DemoStatusBanner("Initializing camera…", tone = DemoStatusTone.Progress)
                else ->
                    DemoStatusBanner(
                        "Ready — point at the ground and tap \"Drop here\"",
                        tone = DemoStatusTone.Guidance,
                    )
            }

            // Primary actions on-screen (#1964) — the banner tells the user to
            // tap "Drop here", so it (and the Clear All reset) must be on-screen
            // buttons. Disabled for the whole `cloudStatus.isUnavailable` window,
            // not just a missing key (#3262) — no VPS lock and no network both
            // mean the resolve call cannot succeed either.
            SceneActionBar(
                SceneAction(
                    label = "Drop here",
                    onClick = onDrop,
                    enabled = !cloudStatus.isUnavailable &&
                        earthState == Earth.EarthState.ENABLED &&
                        cameraLat != null,
                ),
                *(if (placedAnchors.isNotEmpty()) {
                    arrayOf(SceneAction("Clear All", onClick = onClearAll))
                } else {
                    emptyArray()
                }),
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
                        Log.w(TAG_TERRAIN, "isGeospatialModeSupported threw", error)
                        false
                    }
                    if (!geospatialOk) {
                        geospatialUnavailable = "Geospatial API not available on this device"
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                        return@ARSceneView
                    }
                    runCatching {
                        config.geospatialMode = Config.GeospatialMode.ENABLED
                        // Terrain anchors don't depend on plane detection, so disable it
                        // to save tracking budget on outdoor scenes.
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }.onFailure { error ->
                        Log.w(TAG_TERRAIN, "Geospatial config failed", error)
                        geospatialUnavailable = "Geospatial config failed: ${error.message}"
                        config.geospatialMode = Config.GeospatialMode.DISABLED
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }
                },
                onSessionCreated = { session ->
                    arSession = session
                },
                onSessionFailed = { exception ->
                    Log.e(TAG_TERRAIN, "AR session failed", exception)
                    // Map to friendly copy (#2349) — never surface the raw
                    // "FatalException" class name to the user.
                    sessionError = friendlyArSessionError(exception)
                },
                onARCoreAvailability = { arCoreAvailability = it },
                onSessionUpdated = { session: Session, frame: Frame ->
                    cameraReady = true
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
                            foxInstance?.let { instance ->
                                ModelNode(
                                    modelInstance = instance,
                                    scaleToUnits = 0.5f,
                                )
                            }
                        }
                    }
                }
            }

            // Cover the still-black ARSceneView surface until ARCore delivers its
            // first camera frame, so the entry doesn't read as a frozen screen (#1473).
            ARCameraInitScrim(
                initializing = !cameraReady && sessionError == null,
                arCoreAvailability = arCoreAvailability,
            )
        }
    }
}

private data class PlacedTerrainAnchor(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val state: TerrainAnchorState,
    val anchor: Anchor?
)

private fun TerrainAnchorState.label(): String = when (this) {
    TerrainAnchorState.NONE -> "Idle"
    TerrainAnchorState.TASK_IN_PROGRESS -> "Resolving…"
    TerrainAnchorState.SUCCESS -> "Anchored"
    TerrainAnchorState.ERROR_INTERNAL -> "Error: internal"
    TerrainAnchorState.ERROR_NOT_AUTHORIZED -> "Error: API key not authorized"
    TerrainAnchorState.ERROR_UNSUPPORTED_LOCATION -> "Error: no terrain data here"
}

@Composable
private fun TerrainAnchorState.color() = when (this) {
    TerrainAnchorState.SUCCESS -> MaterialTheme.colorScheme.primary
    TerrainAnchorState.TASK_IN_PROGRESS, TerrainAnchorState.NONE ->
        MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

// ─── Rooftop section ─────────────────────────────────────────────────────────
// Formerly ARRooftopAnchorDemo (`ar-rooftop`).

@Composable
private fun RooftopSection(
    onBack: () -> Unit,
    mode: GeospatialAnchorMode,
    onModeChange: (GeospatialAnchorMode) -> Unit,
) {
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
        DemoScaffold(title = stringResource(R.string.demo_ar_geospatial_anchors_title), onBack = onBack) {
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

    val hasArcoreApiKey = rememberHasArcoreApiKey()

    var arSession by remember { mutableStateOf<Session?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    // #3341: non-null once ARCore has ruled this device out. `isTracking` never flips
    // then, so every "waiting for the camera" line below has to read the verdict or it
    // contradicts the SDK's own explanation card forever.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
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
    // Preemptive network check (#3262): Geospatial silently returns no data with no
    // network, which otherwise reads identically to a rejected API key.
    val isNetworkAvailable = rememberIsNetworkAvailable()

    // The one shared "why can't this demo work right now" answer (#3262): a missing
    // or rejected key, an exhausted Cloud quota, no network, or Geospatial not yet
    // localised (no VPS lock, so no usable lat/lng to resolve against).
    val cloudStatus: CloudServiceStatus = when {
        !hasArcoreApiKey -> CloudServiceStatus.ApiKeyMissing
        !isNetworkAvailable -> CloudServiceStatus.NoNetwork
        else -> earthState.toCloudServiceStatus("Geospatial")
            ?: if (isTracking && geospatialUnavailable == null && !earthTracking) {
                CloudServiceStatus.EarthLocalizing
            } else {
                CloudServiceStatus.Available
            }
    }

    val placedAnchors = remember { mutableStateListOf<PlacedRooftopAnchor>() }
    var nextId by remember { mutableStateOf(0) }

    val labelInstance = rememberModelInstance(modelLoader, "models/khronos_lantern.glb")

    // Resolve a rooftop anchor at the current camera lat/lng. Hoisted so the
    // on-screen SceneActionBar can invoke it — "Drop on roof" is the demo's
    // primary action (the banner says tap "Drop on roof"), so it lives
    // on-screen, not in the Settings sheet (#1964).
    val onDrop = onDrop@{
        val session = arSession ?: return@onDrop
        val lat = cameraLat ?: return@onDrop
        val lng = cameraLng ?: return@onDrop
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
            Log.w(TAG_ROOFTOP, "Rooftop anchor resolve threw", error)
            val idx = placedAnchors.indexOfFirst { it.id == id }
            if (idx >= 0) {
                placedAnchors[idx] = placedAnchors[idx].copy(
                    state = RooftopAnchorState.ERROR_INTERNAL
                )
            }
        }
        Unit
    }
    // Clear All is also a primary action (resets the demo) — moved on-screen.
    val onClearAll = {
        placedAnchors.forEach { it.anchor?.let { a -> runCatching { a.detach() } } }
        placedAnchors.clear()
    }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_geospatial_anchors_title),
        onBack = onBack,
        // "Drop on roof" / "Clear All" are the demo's primary actions and live
        // on-screen via SceneActionBar (#1964). The sheet keeps only the demo
        // explainer, the live Geospatial pose readout, the placed-anchors list
        // and the states legend — all genuinely informational / secondary.
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                text = "Rooftop anchors snap to building tops. Useful for floating labels above " +
                    "stores, drone landing signage, or property markers in real-estate apps. " +
                    "Currently works only where Google has detailed building data (urban areas).",
                style = MaterialTheme.typography.bodyMedium
            )

            Text(
                text = when {
                    // Mirrors the shared `bottomOverlay` banner below (#3262) so the
                    // sheet never disagrees with the main AR view about why Drop is
                    // disabled.
                    cloudStatus.isUnavailable -> cloudStatus.message()
                    geospatialUnavailable != null -> geospatialUnavailable!!
                    // Friendly, complete sentence from friendlyArSessionError (#2349).
                    sessionError != null -> sessionError!!
                    // ARCore will never start here, so "waiting" is a lie — the SDK
                    // overlay in the AR view carries the real explanation (#3341).
                    arCoreAvailability != null -> "AR is unavailable on this device"
                    !isTracking -> "Waiting for camera tracking…"
                    !earthTracking -> "Waiting for VPS lock (go outside, look around)…"
                    cameraLat != null && cameraLng != null -> {
                        val lat = "%.6f".format(Locale.US, cameraLat)
                        val lng = "%.6f".format(Locale.US, cameraLng)
                        val alt = cameraAlt?.let { "%.1f m".format(Locale.US, it) } ?: "?"
                        val acc = horizontalAccuracy?.let { "%.1f m".format(Locale.US, it) } ?: "?"
                        "Camera: $lat, $lng • alt $alt • ±$acc"
                    }
                    else -> "Resolving Geospatial pose…"
                },
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

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

            if (placedAnchors.isNotEmpty()) {
                Text(
                    text = "Placed rooftop anchors:",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    placedAnchors.forEach { placed ->
                        Text(
                            text = "#${placed.id}: ${"%.5f".format(Locale.US, placed.latitude)}, " +
                                "${"%.5f".format(Locale.US, placed.longitude)} — ${placed.state.label()}",
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
        },
        // Status banner + primary actions both live in the scaffold's bottom slot —
        // a bottom-aligned Column, so they stack instead of sharing the same strip
        // of pixels above the system bars (#2779). Before this, the first-launch
        // "no ARCore Cloud API key" banner ran under both the "Drop on roof" button
        // and the Settings FAB.
        bottomOverlay = {
            when {
                // friendlyArSessionError already yields a complete, honest sentence (#2349).
                sessionError != null ->
                    DemoStatusBanner(sessionError!!, tone = DemoStatusTone.Blocked)
                // The one shared "Cloud service unavailable" banner (#3262): missing or
                // rejected key, exhausted quota, no network, or no VPS lock yet. Lives
                // in the main AR view, never only in Settings.
                cloudStatus.isUnavailable -> CloudServiceStatusBanner(cloudStatus)
                geospatialUnavailable != null ->
                    DemoStatusBanner(
                        "${geospatialUnavailable!!} — needs urban area with building data + Cloud API key",
                        tone = DemoStatusTone.Blocked,
                    )
                // The SDK's availability overlay already fills the viewport with the
                // reason and the retry; a second, now false, "Initializing camera…"
                // underneath it only contradicts it (#3341).
                arCoreAvailability != null -> Unit
                !isTracking -> DemoStatusBanner("Initializing camera…", tone = DemoStatusTone.Progress)
                else ->
                    DemoStatusBanner(
                        "Ready — point at a building and tap \"Drop on roof\"",
                        tone = DemoStatusTone.Guidance,
                    )
            }

            // Primary actions on-screen (#1964) — the banner tells the user to
            // tap "Drop on roof", so it (and the Clear All reset) must be
            // on-screen buttons. Disabled for the whole `cloudStatus.isUnavailable`
            // window, not just a missing key (#3262) — no VPS lock and no network
            // both mean the resolve call cannot succeed either.
            SceneActionBar(
                SceneAction(
                    label = "Drop on roof",
                    onClick = onDrop,
                    enabled = !cloudStatus.isUnavailable &&
                        earthState == Earth.EarthState.ENABLED &&
                        cameraLat != null,
                ),
                *(if (placedAnchors.isNotEmpty()) {
                    arrayOf(SceneAction("Clear All", onClick = onClearAll))
                } else {
                    emptyArray()
                }),
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
                        Log.w(TAG_ROOFTOP, "isGeospatialModeSupported threw", error)
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
                        Log.w(TAG_ROOFTOP, "Geospatial config failed", error)
                        geospatialUnavailable = "Geospatial config failed: ${error.message}"
                        config.geospatialMode = Config.GeospatialMode.DISABLED
                        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                    }
                },
                onSessionCreated = { session ->
                    arSession = session
                },
                onSessionFailed = { exception ->
                    Log.e(TAG_ROOFTOP, "AR session failed", exception)
                    // Map to friendly copy (#2349) — never surface the raw
                    // "FatalException" class name to the user.
                    sessionError = friendlyArSessionError(exception)
                },
                onARCoreAvailability = { arCoreAvailability = it },
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
                                )
                            }
                        }
                    }
                }
            }
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
