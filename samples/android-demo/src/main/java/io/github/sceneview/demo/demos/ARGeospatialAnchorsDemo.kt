package io.github.sceneview.demo.demos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Terrain
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.Future
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.VpsAvailability
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.NodeScope
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.awaitVpsAvailability
import io.github.sceneview.ar.rememberARCameraStream
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.CloudServiceStatus
import io.github.sceneview.demo.common.CloudServiceStatusBanner
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.GeospatialStatusPanel
import io.github.sceneview.demo.common.QaCameraBackdrop
import io.github.sceneview.demo.common.qaCameraBackdropEnabled
import io.github.sceneview.demo.common.qaCameraBackdropSurfaceType
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.common.rememberHasArcoreApiKey
import io.github.sceneview.demo.common.rememberIsNetworkAvailable
import io.github.sceneview.demo.common.rememberQaCameraBackdropActive
import io.github.sceneview.demo.common.toCloudServiceStatus
import io.github.sceneview.demo.demos.internal.CloudRequestGeneration
import io.github.sceneview.demo.demos.internal.DropFeedback
import io.github.sceneview.demo.demos.internal.DropOutcome
import io.github.sceneview.demo.demos.internal.GeospatialAnchorMode
import io.github.sceneview.demo.demos.internal.GeospatialFrame
import io.github.sceneview.demo.demos.internal.GeospatialLocalizationTracker
import io.github.sceneview.demo.demos.internal.GeospatialOverlay
import io.github.sceneview.demo.demos.internal.GeospatialScenario
import io.github.sceneview.demo.demos.internal.PROVISIONAL_PIN_DROP_M
import io.github.sceneview.demo.demos.internal.ROOFTOP_DROP_DISTANCE_M
import io.github.sceneview.demo.demos.internal.TERRAIN_DROP_DISTANCE_M
import io.github.sceneview.demo.demos.internal.VpsCoverage
import io.github.sceneview.demo.demos.internal.dropOutcomeOf
import io.github.sceneview.demo.demos.internal.dropPoseAhead
import io.github.sceneview.demo.demos.internal.earthErrorMessage
import io.github.sceneview.demo.demos.internal.frame
import io.github.sceneview.demo.demos.internal.friendlyArSessionError
import io.github.sceneview.demo.demos.internal.geospatialScenarioOf
import io.github.sceneview.demo.demos.internal.message
import io.github.sceneview.demo.demos.internal.overlay
import io.github.sceneview.demo.demos.internal.primaryAction
import io.github.sceneview.demo.demos.internal.statusCard
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlin.math.sqrt
import io.github.sceneview.ar.node.RooftopAnchorNode as RooftopAnchorNodeImpl
import io.github.sceneview.ar.node.TerrainAnchorNode as TerrainAnchorNodeImpl

private const val TAG = "ARGeospatialAnchorsDemo"

/**
 * "Geospatial Anchors" — place content by latitude and longitude, with Google's Earth-scale
 * localization instead of a local plane. One screen, two anchor references (#2239):
 *
 * - **Terrain** — [TerrainAnchorNodeImpl.resolve] glues the anchor to Google's outdoor
 *   terrain at that spot. (Formerly `ar-terrain`.)
 * - **Rooftop** — [RooftopAnchorNodeImpl.resolve] puts it on the building at that spot,
 *   falling back to terrain where Google has no building. (Formerly `ar-rooftop`.)
 *
 * ## How the screen works (#3832)
 *
 * Every decision lives in `internal/GeospatialFlow.kt` as pure, unit-tested Kotlin — the
 * emulator cannot run ARCore (#2754). This composable only reads ARCore and draws:
 *
 * 1. **One loader.** `ARCameraInitScrim` narrates the camera start; from the first frame on,
 *    the bottom of the screen is the [GeospatialStatusPanel] and nothing else
 *    ([GeospatialFrame.overlay]).
 * 2. **A persistent status card**: localization state, a three-segment accuracy meter,
 *    "±2.4 m position · ±6° heading", Street View (VPS) coverage from
 *    [awaitVpsAvailability], and a hint when accuracy is low — the readout Google Maps
 *    Live View and ARCore's `hello_geo` sample keep on screen. It used to live only in the
 *    settings sheet.
 * 3. **One big Drop button**, live as soon as Earth has a fix.
 * 4. **Every drop is visible.** The anchor lands [TERRAIN_DROP_DISTANCE_M] ahead of the
 *    camera (not under the phone), facing it ([dropPoseAhead]); a pin marks it while it
 *    resolves, then a model with a pin above it replaces the pin. A failed drop keeps its
 *    pin, in the error colour, and the card says why. Each anchor gets its own model
 *    instance — one shared instance could only ever be drawn once.
 *
 * ## Resolve and clean-up
 *
 * `resolve()` returns an ARCore future that bills until it completes; Clear and leaving
 * the screen cancel it. Its callback is posted to the main thread and dropped (its node
 * destroyed) once the request generation has moved on. A resolved node is handed to the
 * `TerrainAnchorNode(node = …)` / `RooftopAnchorNode(node = …)` composables, which own it
 * from then on and destroy it — detaching its anchor — when it leaves composition.
 *
 * Switching mode re-keys the whole section, so the previous session is torn down (the
 * #2239 invariant). Old deep links route through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES]; `ar-rooftop` pre-selects
 * mode 1 through [io.github.sceneview.demo.DeepLinkRouter.ALIAS_INITIAL_TAB].
 *
 * QA: `--ez qa_mode true --es qa_state <scenario>` renders a [GeospatialScenario] over the
 * QA camera backdrop, with no AR session, so every state of the card can be captured.
 */
@Composable
fun ARGeospatialAnchorsDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(GeospatialAnchorMode.entries, GeospatialAnchorMode.Terrain))
    }
    val forced = remember {
        geospatialScenarioOf(DemoSettings.qaDemoState)?.takeIf { qaStateOverridesAllowed() }
    }
    if (forced != null) {
        ForcedGeospatialScreen(onBack, forced)
        return
    }
    if (!rememberGeospatialPermissions(onBack)) return
    key(mode) {
        GeospatialSection(onBack = onBack, mode = mode, onModeChange = { mode = it })
    }
}

/**
 * Camera and precise location, asked together once. Returns `true` when both are granted;
 * until then it draws the permission screen itself.
 */
@Composable
private fun rememberGeospatialPermissions(onBack: () -> Unit): Boolean {
    val context = LocalContext.current
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var locationGranted by remember { mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var asked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: locationGranted
        asked = true
    }
    LaunchedEffect(Unit) {
        val missing = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (!locationGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
    }
    if (cameraGranted && locationGranted) return true

    DemoScaffold(title = stringResource(R.string.demo_ar_geospatial_anchors_title), onBack = onBack) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = when {
                    !asked -> "Requesting permissions…"
                    !cameraGranted -> "Allow camera access to use AR."
                    else -> "Allow precise location: Geospatial anchors are placed by " +
                        "latitude and longitude."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(horizontal = SceneViewTokens.Space.xl)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(SceneViewTokens.Radius.md),
                    )
                    .padding(horizontal = SceneViewTokens.Space.lg, vertical = SceneViewTokens.Space.md),
            )
        }
    }
    return false
}

/** Earth's camera pose, rounded so an unchanged reading does not recompose the screen. */
private data class GeospatialFix(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val horizontalAccuracyM: Double,
    val yawAccuracyDeg: Double,
)

/** One drop, from the tap to its resolved (or failed) anchor. */
@Stable
private class GeospatialDrop(val id: Int, val mode: GeospatialAnchorMode) {
    var latitude: Double? = null
    var longitude: Double? = null

    /** Local anchor at the drop point: the pin shown while resolving, and after a failure. */
    var provisional by mutableStateOf<Anchor?>(null)
    var outcome by mutableStateOf(DropOutcome.Resolving)
    var terrainNode by mutableStateOf<TerrainAnchorNodeImpl?>(null)
    var rooftopNode by mutableStateOf<RooftopAnchorNodeImpl?>(null)
    var horizontalDistanceM by mutableStateOf<Double?>(null)
    var heightDeltaM by mutableStateOf<Double?>(null)

    /** The in-flight resolve, cancelled on Clear and on dispose so it stops billing. */
    var future: Future? = null

    val resolvedAnchor: Anchor? get() = terrainNode?.anchor ?: rooftopNode?.anchor

    fun feedback() = DropFeedback(mode, outcome, horizontalDistanceM, heightDeltaM)

    /**
     * Cancels the resolve and detaches both anchors. The node composables detach again
     * when they leave composition; detaching twice is harmless, and doing it here also
     * covers a node resolved in the same frame as Clear, before it was ever composed.
     */
    fun release() {
        future?.let { runCatching { it.cancel() } }
        future = null
        provisional?.let { runCatching { it.detach() } }
        resolvedAnchor?.let { runCatching { it.detach() } }
    }
}

/** Plain holder for the latest camera pose: written per frame, read on tap, never drawn. */
private class CameraPoseHolder {
    var pose: Pose? = null
}

@Composable
private fun GeospatialSection(
    onBack: () -> Unit,
    mode: GeospatialAnchorMode,
    onModeChange: (GeospatialAnchorMode) -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replays a recorded ARCore dataset for the device-QA harness (#1576); null otherwise.
    val arPlaybackDataset = rememberArPlaybackDataset()
    val cameraStream = rememberARCameraStream(materialLoader)
    val hasArcoreApiKey = rememberHasArcoreApiKey()
    // Geospatial returns nothing without a network, which otherwise reads exactly like a
    // rejected key (#3262).
    val isNetworkAvailable = rememberIsNetworkAvailable()

    var arSession by remember { mutableStateOf<Session?>(null) }
    var cameraReady by remember { mutableStateOf(false) }
    // Whether ARCameraInitScrim's card is the one saying "Starting camera…" (#3825).
    var cameraScrimNarrating by remember { mutableStateOf(true) }
    // #3341: non-null once ARCore has ruled this device out; the SDK's card explains it.
    var arCoreAvailability by remember { mutableStateOf<ARCoreAvailability?>(null) }
    var isTracking by remember { mutableStateOf(false) }
    var earthState by remember { mutableStateOf<Earth.EarthState?>(null) }
    var fix by remember { mutableStateOf<GeospatialFix?>(null) }
    var tracker by remember { mutableStateOf(GeospatialLocalizationTracker()) }
    var vps by remember { mutableStateOf(VpsCoverage.Unknown) }
    var geospatialUnavailable by remember { mutableStateOf<String?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }
    val qaBackdrop = rememberQaCameraBackdropActive(cameraReady)

    val cameraPose = remember { CameraPoseHolder() }
    val drops = remember { mutableStateListOf<GeospatialDrop>() }
    var nextId by remember { mutableStateOf(0) }
    val requestGeneration = remember { CloudRequestGeneration() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Street View (VPS) coverage where the user stands, re-asked every ~100 m. The helper
    // cancels its ARCore future if this effect is cancelled.
    val latestFix by rememberUpdatedState(fix)
    val vpsCell = fix?.let { (it.latitude * 1_000).roundToInt() to (it.longitude * 1_000).roundToInt() }
    LaunchedEffect(arSession, vpsCell) {
        val session = arSession ?: return@LaunchedEffect
        val here = latestFix ?: return@LaunchedEffect
        if (vps == VpsCoverage.Unknown) vps = VpsCoverage.Checking
        vps = try {
            when (session.awaitVpsAvailability(here.latitude, here.longitude)) {
                VpsAvailability.AVAILABLE -> VpsCoverage.Available
                VpsAvailability.UNAVAILABLE -> VpsCoverage.Unavailable
                else -> VpsCoverage.Error
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "VPS availability check failed", e)
            VpsCoverage.Error
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Late callbacks now destroy their node instead of adding it; the node
            // composables release everything already on screen.
            requestGeneration.invalidate()
            drops.forEach { drop -> drop.future?.let { runCatching { it.cancel() } } }
        }
    }

    val cloudStatus: CloudServiceStatus = when {
        !hasArcoreApiKey -> CloudServiceStatus.ApiKeyMissing
        !isNetworkAvailable -> CloudServiceStatus.NoNetwork
        else -> earthState.toCloudServiceStatus("Geospatial") ?: CloudServiceStatus.Available
    }
    val earthError = earthErrorMessage(earthState?.name)
    val frame = GeospatialFrame(
        mode = mode,
        blocked = sessionError != null || cloudStatus.isUnavailable ||
            geospatialUnavailable != null || earthError != null,
        arUnavailable = arCoreAvailability != null,
        cameraReady = cameraReady,
        scrimNarrating = cameraScrimNarrating,
        cameraTracking = isTracking,
        localization = tracker.phase,
        hasFix = fix != null,
        horizontalAccuracyM = fix?.horizontalAccuracyM,
        yawAccuracyDeg = fix?.yawAccuracyDeg,
        vps = vps,
        lastDrop = drops.lastOrNull()?.feedback(),
    )

    fun settle(
        drop: GeospatialDrop,
        generation: Int,
        outcome: DropOutcome,
        terrain: TerrainAnchorNodeImpl?,
        rooftop: RooftopAnchorNodeImpl?,
    ) {
        if (!requestGeneration.accepts(generation) || drop !in drops) {
            terrain?.destroy()
            rooftop?.destroy()
            return
        }
        drop.future = null
        drop.outcome = outcome
        if (outcome == DropOutcome.Anchored) {
            drop.terrainNode = terrain
            drop.rooftopNode = rooftop
            // The pin's job is done; its composable detaches the local anchor.
            drop.provisional = null
        } else {
            terrain?.destroy()
            rooftop?.destroy()
        }
    }

    val onDrop: () -> Unit = onDrop@{
        val session = arSession ?: return@onDrop
        val earth = session.earth ?: return@onDrop
        val camera = cameraPose.pose ?: return@onDrop
        if (earth.trackingState != TrackingState.TRACKING) return@onDrop
        val target = dropPoseAhead(
            cameraX = camera.tx(),
            cameraY = camera.ty(),
            cameraZ = camera.tz(),
            cameraZAxis = camera.zAxis,
            cameraYAxis = camera.yAxis,
            distance = mode.dropDistance(),
            below = PROVISIONAL_PIN_DROP_M,
        )
        val pose = Pose(
            floatArrayOf(target.x, target.y, target.z),
            floatArrayOf(target.qx, target.qy, target.qz, target.qw),
        )
        val drop = GeospatialDrop(id = nextId++, mode = mode)
        drops.add(drop)
        val generation = requestGeneration.current
        try {
            drop.provisional = session.createAnchor(pose)
            // The same Earth estimate turns the local pose into latitude/longitude and an
            // east-up-south rotation, so the anchor resolves where the pin is, facing
            // the user.
            val geo = earth.getGeospatialPose(pose)
            drop.latitude = geo.latitude
            drop.longitude = geo.longitude
            val q = geo.eastUpSouthQuaternion
            val eus = Quaternion(q[0], q[1], q[2], q[3])
            drop.future = when (mode) {
                GeospatialAnchorMode.Terrain -> TerrainAnchorNodeImpl.resolve(
                    engine = engine,
                    session = session,
                    latitude = geo.latitude,
                    longitude = geo.longitude,
                    altitudeAboveTerrain = 0.0,
                    eusQuaternion = eus,
                ) { state, node ->
                    // ARCore may complete on its own thread: serialize with Compose.
                    mainHandler.post {
                        settle(drop, generation, dropOutcomeOf(state.name, node != null), node, null)
                    }
                }
                GeospatialAnchorMode.Rooftop -> RooftopAnchorNodeImpl.resolve(
                    engine = engine,
                    session = session,
                    latitude = geo.latitude,
                    longitude = geo.longitude,
                    altitudeAboveRooftop = 0.0,
                    eusQuaternion = eus,
                ) { state, node ->
                    mainHandler.post {
                        settle(drop, generation, dropOutcomeOf(state.name, node != null), null, node)
                    }
                }
            }
            if (drop.future == null) drop.outcome = DropOutcome.Failed
        } catch (e: Exception) {
            Log.w(TAG, "Geospatial drop failed", e)
            drop.outcome = DropOutcome.Failed
        }
    }

    val onClear: () -> Unit = {
        requestGeneration.invalidate()
        drops.forEach { it.release() }
        drops.clear()
    }

    val pinMaterials = rememberPinMaterials(materialLoader)
    val terrainModel = "models/khronos_fox.glb"
    val rooftopModel = "models/khronos_lantern.glb"

    GeospatialScreen(
        onBack = onBack,
        frame = frame,
        dropCount = drops.size,
        onModeChange = onModeChange,
        onDrop = onDrop,
        onClear = onClear,
        blocker = {
            when {
                // friendlyArSessionError already yields a complete sentence (#2349).
                sessionError != null -> DemoStatusBanner(sessionError!!, tone = DemoStatusTone.Blocked)
                // The shared Cloud-service banner (#3262): key missing or rejected, quota,
                // no network.
                cloudStatus.isUnavailable -> CloudServiceStatusBanner(cloudStatus)
                geospatialUnavailable != null ->
                    DemoStatusBanner(geospatialUnavailable!!, tone = DemoStatusTone.Blocked)
                earthError != null -> DemoStatusBanner(earthError, tone = DemoStatusTone.Blocked)
            }
        },
        controls = {
            GeospatialExplainer(mode)
            Text(
                text = fix?.let {
                    String.format(
                        Locale.US,
                        "Camera: %.6f, %.6f · altitude %.1f m · ±%.1f m · ±%.0f°",
                        it.latitude, it.longitude, it.altitude, it.horizontalAccuracyM, it.yawAccuracyDeg,
                    )
                } ?: "Camera: no location yet",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = SceneViewTokens.Space.sm),
            )
            if (drops.isNotEmpty()) {
                Text(
                    text = "Anchors",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = SceneViewTokens.Space.md),
                )
                drops.forEach { drop ->
                    val where = drop.latitude?.let { lat ->
                        String.format(Locale.US, "%.5f, %.5f", lat, drop.longitude ?: 0.0)
                    } ?: "no location"
                    Text(
                        text = "#${drop.id + 1} · $where · ${drop.feedback().message()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (drop.outcome.isFailure()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = SceneViewTokens.Space.xs),
                    )
                }
            }
        },
    ) {
        if (qaBackdrop) QaCameraBackdrop(seed = "ar-geospatial-anchors")
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            isOpaque = !qaCameraBackdropEnabled(),
            surfaceType = qaCameraBackdropSurfaceType(),
            cameraStream = if (qaBackdrop) null else cameraStream,
            playbackDataset = arPlaybackDataset,
            planeRenderer = false,
            sessionConfiguration = { session: Session, config: Config ->
                val supported = runCatching {
                    session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
                }.getOrElse { error ->
                    Log.w(TAG, "isGeospatialModeSupported threw", error)
                    false
                }
                // Geospatial needs no plane detection; it only costs tracking budget.
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                if (!supported) {
                    geospatialUnavailable = "This phone doesn't support Geospatial anchors."
                    return@ARSceneView
                }
                runCatching { config.geospatialMode = Config.GeospatialMode.ENABLED }
                    .onFailure { error ->
                        Log.w(TAG, "Geospatial config failed", error)
                        geospatialUnavailable = "Geospatial couldn't start on this phone."
                        config.geospatialMode = Config.GeospatialMode.DISABLED
                    }
            },
            onSessionCreated = { session -> arSession = session },
            onSessionFailed = { exception ->
                Log.e(TAG, "AR session failed", exception)
                sessionError = friendlyArSessionError(exception)
            },
            onARCoreAvailability = { arCoreAvailability = it },
            onSessionUpdated = { session: Session, arFrame: Frame ->
                cameraReady = true
                val camera = arFrame.camera
                isTracking = camera.trackingState == TrackingState.TRACKING
                cameraPose.pose = camera.displayOrientedPose
                val earth = session.earth
                earthState = earth?.earthState
                val earthTracking = earth?.trackingState == TrackingState.TRACKING
                fix = if (earthTracking) {
                    earth?.cameraGeospatialPose?.let { geo ->
                        GeospatialFix(
                            latitude = geo.latitude.roundTo(1e-6),
                            longitude = geo.longitude.roundTo(1e-6),
                            altitude = geo.altitude.roundTo(0.1),
                            horizontalAccuracyM = geo.horizontalAccuracy.roundTo(0.1),
                            yawAccuracyDeg = geo.orientationYawAccuracy.roundTo(1.0),
                        )
                    }
                } else {
                    null
                }
                tracker = tracker.update(
                    earthTracking = earthTracking,
                    horizontalAccuracyM = fix?.horizontalAccuracyM,
                    yawAccuracyDeg = fix?.yawAccuracyDeg,
                    nowMillis = SystemClock.uptimeMillis(),
                )
                // Where the latest anchor really is, so the card can say "4 m away" or
                // "9 m below you at street level" instead of leaving the user searching.
                val last = drops.lastOrNull()
                val anchor = last?.resolvedAnchor
                if (last != null && anchor != null && anchor.trackingState == TrackingState.TRACKING) {
                    val a = anchor.pose
                    val c = camera.pose
                    val dx = (a.tx() - c.tx()).toDouble()
                    val dz = (a.tz() - c.tz()).toDouble()
                    last.horizontalDistanceM = sqrt(dx * dx + dz * dz).roundTo(1.0)
                    last.heightDeltaM = (a.ty() - c.ty()).toDouble().roundTo(1.0)
                }
            },
        ) {
            drops.forEach { drop ->
                key(drop.id) {
                    val pin = when (drop.outcome) {
                        DropOutcome.Resolving -> pinMaterials.resolving
                        DropOutcome.Anchored -> pinMaterials.anchored
                        else -> pinMaterials.failed
                    }
                    val scale = drop.mode.markerScale()
                    drop.provisional?.let { anchor ->
                        AnchorNode(anchor = anchor) {
                            Node(scale = Scale(scale)) { GeospatialPin(pin) }
                        }
                    }
                    drop.terrainNode?.let { node ->
                        TerrainAnchorNode(node = node) {
                            GeospatialMarker(rememberModelInstance(modelLoader, terrainModel), pin, scale)
                        }
                    }
                    drop.rooftopNode?.let { node ->
                        RooftopAnchorNode(node = node) {
                            GeospatialMarker(rememberModelInstance(modelLoader, rooftopModel), pin, scale)
                        }
                    }
                }
            }
        }

        // Covers the still-black surface until the first camera frame (#1473) — the one
        // loader of the camera start (#3825).
        ARCameraInitScrim(
            initializing = !cameraReady && sessionError == null,
            arCoreAvailability = arCoreAvailability,
            onNarratingChange = { cameraScrimNarrating = it },
        )
    }
}

/**
 * A QA-forced [GeospatialScenario] (`--ez qa_mode true --es qa_state <name>`): the real
 * screen chrome and status card over the QA camera backdrop, with no AR session, so the
 * emulator — which cannot run ARCore (#2754) — can capture every state.
 */
@Composable
private fun ForcedGeospatialScreen(onBack: () -> Unit, scenario: GeospatialScenario) {
    val frame = remember(scenario) { scenario.frame() }
    GeospatialScreen(
        onBack = onBack,
        frame = frame,
        dropCount = if (frame.lastDrop != null) 1 else 0,
        onModeChange = {},
        onDrop = {},
        onClear = {},
        blocker = {},
        controls = { GeospatialExplainer(frame.mode) },
    ) {
        QaCameraBackdrop(seed = "ar-geospatial-anchors")
    }
}

/**
 * The chrome both the live and the QA-forced screen share: scaffold, dock, bottom card.
 * Internal so `GeospatialScreenSnapshotTest` photographs this exact screen.
 */
@Composable
internal fun GeospatialScreen(
    onBack: () -> Unit,
    frame: GeospatialFrame,
    dropCount: Int,
    onModeChange: (GeospatialAnchorMode) -> Unit,
    onDrop: () -> Unit,
    onClear: () -> Unit,
    blocker: @Composable DemoBottomOverlayScope.() -> Unit,
    controls: @Composable ColumnScope.() -> Unit,
    scene: @Composable BoxScope.() -> Unit,
) {
    DemoScaffold(
        title = stringResource(R.string.demo_ar_geospatial_anchors_title),
        onBack = onBack,
        controls = controls,
        onReset = onClear,
        // The two anchor references are the screen's two modes, so they are the dock —
        // the same idiom as Cloud Anchors' Host / Resolve — and Clear sits beside them.
        // The Drop button itself is in the card, where the status that enables it is.
        dock = listOf(
            DockItem(
                icon = Icons.Rounded.Terrain,
                label = "Terrain anchors",
                caption = "Terrain",
                onClick = { onModeChange(GeospatialAnchorMode.Terrain) },
                selected = frame.mode == GeospatialAnchorMode.Terrain,
            ),
            DockItem(
                icon = Icons.Rounded.Apartment,
                label = "Rooftop anchors",
                caption = "Rooftop",
                onClick = { onModeChange(GeospatialAnchorMode.Rooftop) },
                selected = frame.mode == GeospatialAnchorMode.Rooftop,
            ),
            DockItem(
                icon = Icons.Rounded.DeleteSweep,
                label = "Clear all anchors",
                caption = "Clear",
                onClick = onClear,
                enabled = dropCount > 0,
            ),
        ),
        bottomOverlay = {
            when (frame.overlay()) {
                GeospatialOverlay.Silent -> Unit
                GeospatialOverlay.Blocker -> blocker()
                // Only when the scrim's own card timed out on a stuck camera start.
                GeospatialOverlay.StartingCamera -> DemoStatusBanner(
                    stringResource(R.string.ar_starting_camera),
                    tone = DemoStatusTone.Progress,
                )
                GeospatialOverlay.Status -> GeospatialStatusPanel(
                    card = frame.statusCard(),
                    action = frame.primaryAction(),
                    onDrop = onDrop,
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) { scene() }
    }
}

@Composable
private fun GeospatialExplainer(mode: GeospatialAnchorMode) {
    Text(
        text = when (mode) {
            GeospatialAnchorMode.Terrain ->
                "Terrain anchors sit on Google's outdoor ground model, placed by latitude and " +
                    "longitude — no plane detection. Stand outside, drop one, walk away: it " +
                    "stays at that spot."
            GeospatialAnchorMode.Rooftop ->
                "Rooftop anchors sit on the building at that latitude and longitude, or on the " +
                    "ground where Google has no building. Point across the street at a " +
                    "building, then drop."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Pin colours: the coaching overlay's three accents, flat so they read in any light. */
private class PinMaterials(
    val resolving: MaterialInstance,
    val anchored: MaterialInstance,
    val failed: MaterialInstance,
)

@Composable
private fun rememberPinMaterials(
    materialLoader: io.github.sceneview.loaders.MaterialLoader,
): PinMaterials {
    val resolving = rememberUnlitMaterialInstance(materialLoader, SceneViewTokens.ArOverlay.accentProgress)
    val anchored = rememberUnlitMaterialInstance(materialLoader, SceneViewTokens.ArOverlay.accentSuccess)
    val failed = rememberUnlitMaterialInstance(materialLoader, SceneViewTokens.ArOverlay.accentBlocked)
    return remember(resolving, anchored, failed) { PinMaterials(resolving, anchored, failed) }
}

/** Height of the pin's head above its anchor, in metres before [markerScale]. */
private const val PIN_HEIGHT_M = 1.3f
private const val PIN_HEAD_RADIUS_M = 0.12f
private const val PIN_TIP_RADIUS_M = 0.08f
private const val PIN_TIP_HEIGHT_M = 0.35f

/** Size of the anchored model's largest side, in metres before [markerScale]. */
private const val MARKER_MODEL_SIZE_M = 0.8f

/**
 * A map pin floating above the anchor point: a head and a downward tip. Always drawn, so
 * a drop is visible even while its model loads, and a failed drop keeps a marker.
 */
@Composable
private fun NodeScope.GeospatialPin(material: MaterialInstance) {
    SphereNode(
        radius = PIN_HEAD_RADIUS_M,
        materialInstance = material,
        position = Position(y = PIN_HEIGHT_M),
    )
    // The cone's centre is its axis midpoint and its apex +Y; flipped, it points down
    // from inside the head.
    ConeNode(
        radius = PIN_TIP_RADIUS_M,
        height = PIN_TIP_HEIGHT_M,
        materialInstance = material,
        position = Position(y = PIN_HEIGHT_M - PIN_TIP_HEIGHT_M / 2f),
        rotation = Rotation(x = 180f),
    )
}

/**
 * The resolved anchor: its own model instance standing on the anchor, facing the user, and
 * the pin above it. One instance per anchor — a shared instance is drawn only once.
 */
@Composable
private fun NodeScope.GeospatialMarker(model: ModelInstance?, pin: MaterialInstance, scale: Float) {
    Node(scale = Scale(scale)) {
        model?.let {
            ModelNode(
                modelInstance = it,
                scaleToUnits = MARKER_MODEL_SIZE_M,
                // Bottom of the model on the anchor, not its centre half underground.
                centerOrigin = Position(0f, -1f, 0f),
            )
        }
        GeospatialPin(pin)
    }
}

/** Rooftop anchors land across the street and often above it: draw them bigger. */
private fun GeospatialAnchorMode.markerScale(): Float = when (this) {
    GeospatialAnchorMode.Terrain -> 1f
    GeospatialAnchorMode.Rooftop -> 3f
}

private fun GeospatialAnchorMode.dropDistance(): Float = when (this) {
    GeospatialAnchorMode.Terrain -> TERRAIN_DROP_DISTANCE_M
    GeospatialAnchorMode.Rooftop -> ROOFTOP_DROP_DISTANCE_M
}

private fun DropOutcome.isFailure(): Boolean =
    this == DropOutcome.NoDataHere || this == DropOutcome.NotAuthorized || this == DropOutcome.Failed

private fun Double.roundTo(step: Double): Double = Math.round(this / step) * step
