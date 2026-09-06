package io.github.sceneview.demo.demos

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.MeshClassification
import io.github.sceneview.ar.node.toMeshClassification
import io.github.sceneview.demo.ARCameraInitScrim
import io.github.sceneview.demo.DemoBottomOverlayScope
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.CloudServiceStatus
import io.github.sceneview.demo.common.CloudServiceStatusBanner
import io.github.sceneview.demo.common.DemoStatusBanner
import io.github.sceneview.demo.common.DemoStatusTone
import io.github.sceneview.demo.common.ForceTrackingFailureMenu
import io.github.sceneview.demo.common.ForcedTrackingFailure
import io.github.sceneview.demo.common.rememberHasArcoreApiKey
import io.github.sceneview.demo.common.rememberIsNetworkAvailable
import io.github.sceneview.demo.common.toCloudServiceStatus
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.friendlyArSessionError
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance

private const val TAG_MESH = "ARSceneGeometryDemo/Mesh"
private const val TAG_STREETSCAPE = "ARSceneGeometryDemo/Streetscape"

// How long the camera may track with zero streetscape geometry before the status
// banner swaps the spinner text for explicit "go outdoors" guidance (#1615).
// 15 s is long enough for a genuine outdoor VPS lookup to start returning meshes,
// short enough that an indoor QA pass isn't left guessing for a full minute.
private const val NO_GEOMETRY_HINT_DELAY_MS = 15_000L

/**
 * Overlay opacity for both modes' meshes — semi-transparent so the camera feed and the
 * real-world structure remain visible through the coloured geometry.
 */
private const val MESH_OVERLAY_ALPHA = 0.55f

/**
 * Unified "Scene Geometry" demo — consolidates the retired `ar-streetscape` demo into the
 * `ar-scene-mesh` card behind a segmented-button toggle (#2239 / #3463).
 *
 * - **Mesh** — [io.github.sceneview.ar.node.SceneMeshNode] colour-codes each geometry by its
 *   [MeshClassification], the enum that gives ARKit `ARMeshAnchor` parity on Android.
 * - **Streetscape** — [io.github.sceneview.ar.node.StreetscapeGeometryNode], the raw node the
 *   classified one subclasses: one material for every geometry, no classification.
 *   (Formerly `ar-streetscape`.)
 *
 * **Why these two are one card.** 246 of 445 lines were identical, and they call the *same*
 * primary API: `Config.StreetscapeGeometryMode.ENABLED` plus
 * `frame.getUpdatedTrackables(StreetscapeGeometry::class.java)`. The whole difference is
 * which node consumes the trackable — the classified subclass or its base — which is the
 * definition of a mode, not of a second screen. Shipping them as two cards taught the reader
 * that ARCore has two scene-geometry APIs when it has one, with an optional classification
 * layer on top.
 *
 * Each mode keeps its **own** `ARSceneView` and its own [rememberEngine], so switching modes
 * tears the inactive ARCore session and Filament engine down completely — the leak invariant
 * the #2239 Batch-1 review pinned. Nothing is hoisted above the `when`.
 *
 * The card keeps `ar-scene-mesh` as its id: an id is a public deep-link surface and iOS ships
 * a screen under the same one. The retired `ar-streetscape` link routes through
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES] and pre-selects mode 1 through
 * [io.github.sceneview.demo.DeepLinkRouter.ALIAS_INITIAL_TAB].
 *
 * **Requirements** (both modes):
 * - ARCore Geospatial API enabled in Google Cloud Console + a Cloud API key
 * - Device supports the ARCore Geospatial API
 * - Outdoor environment with Google Street View coverage
 * - CAMERA + ACCESS_FINE_LOCATION permissions
 */
@Composable
fun ARSceneGeometryDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(SceneGeometryMode.entries, SceneGeometryMode.Mesh))
    }
    when (mode) {
        SceneGeometryMode.Mesh -> MeshSection(onBack, mode) { mode = it }
        SceneGeometryMode.Streetscape -> StreetscapeSection(onBack, mode) { mode = it }
    }
}

/**
 * Declaration order is the segmented-button order and
 * [io.github.sceneview.demo.DeepLinkRouter.ALIAS_INITIAL_TAB] indexes into it
 * (`ar-streetscape` = 1). Append, never reorder.
 */
private enum class SceneGeometryMode(@StringRes val labelRes: Int) {
    Mesh(R.string.demo_ar_scene_mesh_mode_mesh),
    Streetscape(R.string.demo_ar_scene_mesh_mode_streetscape),
}

@Composable
private fun ModeSelector(
    current: SceneGeometryMode,
    onModeChange: (SceneGeometryMode) -> Unit,
) {
    val modes = SceneGeometryMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(stringResource(m.labelRes)) },
            )
        }
    }
    Spacer(modifier = Modifier.height(SceneViewTokens.Space.sm))
}

// ─── Mesh section ────────────────────────────────────────────────────────────
// Formerly the whole of ARSceneMeshDemo (`ar-scene-mesh`).

@Composable
private fun MeshSection(
    onBack: () -> Unit,
    mode: SceneGeometryMode,
    onModeChange: (SceneGeometryMode) -> Unit,
) = GeospatialPermissionGate(
    title = stringResource(R.string.demo_ar_scene_mesh_title),
    deniedReason = stringResource(R.string.demo_ar_scene_mesh_location_denied),
    onBack = onBack,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val arPlaybackDataset = rememberArPlaybackDataset()

    // Per-classification material instances — colour coded so TERRAIN and BUILDING read
    // at a glance from the mesh overlay. Both hues come from the brand ramp
    // (`SceneViewColors`, mirrored from DESIGN.md): they are the two ends of it, which is
    // the pair that stays distinguishable over an arbitrary camera frame.
    val terrainMaterial = rememberMaterialInstance(
        materialLoader,
        color = SceneViewColors.Primary.copy(alpha = MESH_OVERLAY_ALPHA),
        metallic = 0.0f,
        roughness = 1.0f,
        reflectance = 0.0f,
    )
    val buildingMaterial = rememberMaterialInstance(
        materialLoader,
        color = SceneViewColors.TintSoft.copy(alpha = MESH_OVERLAY_ALPHA),
        metallic = 0.0f,
        roughness = 1.0f,
        reflectance = 0.0f,
    )

    val state = rememberSceneGeometryState()
    val cloudStatus = rememberCloudStatus(state)
    NoGeometryGuidanceEffect(state, cloudStatus)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_scene_mesh_title),
        onBack = onBack,
        // The sheet holds the mode toggle and the one-line explainer of what this mode
        // renders; the dev-only ForceTrackingFailureMenu joins it in QA mode (#1620).
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                text = stringResource(R.string.demo_ar_scene_mesh_mode_mesh_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (DemoSettings.qaMode) ForceTrackingFailureMenu()
        },
        // Legend + status pill are hosted by the scaffold's `bottomOverlay` slot, a
        // bottom-aligned Column: they stack instead of sharing the same strip of pixels,
        // and the banner is laid out against the dock instead of under it (#2779).
        bottomOverlay = {
            // Classification legend — only visible once geometry is rendering.
            if (state.geometryCount > 0) {
                MeshClassificationLegend(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(start = SceneViewTokens.Space.md)
                )
            }
            SceneGeometryStatusBanner(
                state = state,
                cloudStatus = cloudStatus,
                renderingText = stringResource(
                    R.string.demo_ar_scene_mesh_rendering,
                    state.geometryCount,
                ),
                lookingText = stringResource(R.string.demo_ar_scene_mesh_looking),
                noGeometryHint = stringResource(R.string.demo_ar_scene_mesh_no_geometry_hint),
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionConfiguration = { session, config ->
                    configureStreetscapeGeometry(TAG_MESH, session, config, state)
                },
                onSessionFailed = { exception ->
                    Log.e(TAG_MESH, "AR session failed", exception)
                    state.sessionError = friendlyArSessionError(exception)
                },
                onARCoreAvailability = { state.arCoreAvailability = it },
                onSessionUpdated = { session, frame -> state.onFrame(session, frame) },
                onTrackingFailureChanged = { reason -> state.trackingFailureReason = reason },
            ) {
                state.geometries.forEach { geo ->
                    // Colour-code by classification so TERRAIN and BUILDING are visually
                    // distinct — the one thing this mode has that Streetscape does not.
                    val material = when (geo.type.toMeshClassification()) {
                        MeshClassification.TERRAIN -> terrainMaterial
                        else -> buildingMaterial
                    }
                    SceneMeshNode(
                        streetscapeGeometry = geo,
                        meshMaterialInstance = material,
                    )
                }
            }

            // Cover the still-black AR viewport until the first camera frame; lifts on a
            // session error so the error banner below is never hidden (#2484).
            ARCameraInitScrim(
                initializing = !state.cameraReady && state.sessionError == null,
                arCoreAvailability = state.arCoreAvailability,
            )
        }
    }
}

// ─── Streetscape section ─────────────────────────────────────────────────────
// Formerly ARStreetscapeDemo (`ar-streetscape`).

@Composable
private fun StreetscapeSection(
    onBack: () -> Unit,
    mode: SceneGeometryMode,
    onModeChange: (SceneGeometryMode) -> Unit,
) = GeospatialPermissionGate(
    title = stringResource(R.string.demo_ar_scene_mesh_title),
    deniedReason = stringResource(R.string.demo_ar_scene_mesh_streetscape_denied),
    onBack = onBack,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch — see
    // `rememberArPlaybackDataset` — so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    // One semi-transparent material for every geometry: the raw node carries no
    // classification, and pretending otherwise would erase what separates the two modes.
    val buildingMaterial = rememberMaterialInstance(
        materialLoader,
        color = SceneViewColors.LandscapeOverlay,
        metallic = 0.1f,
        roughness = 0.9f,
        reflectance = 0.1f,
    )

    val state = rememberSceneGeometryState()
    val cloudStatus = rememberCloudStatus(state)
    NoGeometryGuidanceEffect(state, cloudStatus)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_scene_mesh_title),
        onBack = onBack,
        controls = {
            ModeSelector(mode, onModeChange)
            Text(
                text = stringResource(R.string.demo_ar_scene_mesh_mode_streetscape_explainer),
                style = MaterialTheme.typography.bodyMedium,
            )
            // Developer-only debug toggle — lets QA force-emit each TrackingFailureReason
            // so the actionable-message overlay can be validated without staging a real
            // failure. See io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            if (DemoSettings.qaMode) ForceTrackingFailureMenu()
        },
        bottomOverlay = {
            SceneGeometryStatusBanner(
                state = state,
                cloudStatus = cloudStatus,
                renderingText = stringResource(
                    R.string.demo_ar_scene_mesh_streetscape_rendering,
                    state.geometryCount,
                ),
                lookingText = stringResource(R.string.demo_ar_scene_mesh_streetscape_looking),
                noGeometryHint = stringResource(
                    R.string.demo_ar_scene_mesh_streetscape_no_geometry_hint,
                ),
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionConfiguration = { session, config ->
                    configureStreetscapeGeometry(TAG_STREETSCAPE, session, config, state)
                },
                onSessionFailed = { exception ->
                    Log.e(TAG_STREETSCAPE, "AR session failed", exception)
                    // Map to friendly copy (#2349) — never surface the raw
                    // "FatalException" class name to the user.
                    state.sessionError = friendlyArSessionError(exception)
                },
                onARCoreAvailability = { state.arCoreAvailability = it },
                onSessionUpdated = { session, frame -> state.onFrame(session, frame) },
                onTrackingFailureChanged = { reason -> state.trackingFailureReason = reason },
            ) {
                state.geometries.forEach { geo ->
                    StreetscapeGeometryNode(
                        streetscapeGeometry = geo,
                        meshMaterialInstance = buildingMaterial,
                    )
                }
            }

            ARCameraInitScrim(
                initializing = !state.cameraReady && state.sessionError == null,
                arCoreAvailability = state.arCoreAvailability,
            )
        }
    }
}

// ─── Shared session plumbing ─────────────────────────────────────────────────

/**
 * The per-mode session state both modes keep, and nothing else.
 *
 * It is deliberately *not* hoisted above the `when` in [ARSceneGeometryDemo]: each mode
 * calls [rememberSceneGeometryState] at its own call site, so switching modes drops the
 * whole thing along with that mode's `ARSceneView` and engine.
 */
private class SceneGeometryState {
    val geometries = mutableStateListOf<StreetscapeGeometry>()
    var isTracking by mutableStateOf(false)

    /**
     * Cover the jet-black ARSceneView surface until ARCore delivers its first camera
     * frame, so the ~1–3 s warm-up on entry doesn't read as a frozen screen (#2484).
     */
    var cameraReady by mutableStateOf(false)

    /**
     * #3341: non-null once ARCore has ruled this device out. [cameraReady] never flips
     * then, so the init scrim has to read the verdict or it covers the SDK's own
     * explanation card forever.
     */
    var arCoreAvailability by mutableStateOf<ARCoreAvailability?>(null)
    var trackingFailureReason by mutableStateOf<TrackingFailureReason?>(null)
    var geometryCount by mutableStateOf(0)

    /**
     * After [NO_GEOMETRY_HINT_DELAY_MS] tracking with no geometry and no unsupported-device
     * error, the spinner text is replaced with explicit "go outdoors" guidance (#1615).
     * Streetscape Geometry can never produce meshes indoors — the most common QA setting.
     */
    var noGeometryGuidance by mutableStateOf(false)

    /**
     * Tracks whether Geospatial / Streetscape mode could actually be enabled on the current
     * device + region. ARCore Geospatial requires a Cloud project key and VPS coverage from
     * Google Street View; when either is missing, `Session.isGeospatialModeSupported`
     * returns false and setting the mode would throw on `session.configure()`.
     */
    var geospatialUnavailable by mutableStateOf<String?>(null)
    var sessionError by mutableStateOf<String?>(null)

    /**
     * Geospatial has no failure callback: a rejected Cloud API key only shows up as
     * [Earth.EarthState.ERROR_NOT_AUTHORIZED] on the session, so it is sampled on every
     * frame and routed into the status banner (#3210).
     */
    var earthState by mutableStateOf<Earth.EarthState?>(null)

    /** The one per-frame update both modes run — identical trackable bookkeeping. */
    fun onFrame(session: Session, frame: Frame) {
        cameraReady = true
        isTracking = frame.camera.trackingState == TrackingState.TRACKING
        earthState = session.earth?.earthState
        frame.getUpdatedTrackables(StreetscapeGeometry::class.java).forEach { geo ->
            if (geo.trackingState == TrackingState.TRACKING && geometries.none { it == geo }) {
                geometries.add(geo)
            }
        }
        // Remove geometries that stopped tracking.
        geometries.removeAll { it.trackingState == TrackingState.STOPPED }
        geometryCount = geometries.size
    }
}

@Composable
private fun rememberSceneGeometryState(): SceneGeometryState = remember { SceneGeometryState() }

/**
 * The one shared "why can't this demo work right now" answer (#3262): a missing or rejected
 * key, an exhausted Cloud quota, or no network. `earthState` only reports the ARCore-side
 * rejection / quota reasons — the key-missing and no-network checks are known up front.
 */
@Composable
private fun rememberCloudStatus(state: SceneGeometryState): CloudServiceStatus {
    // Detect at runtime whether the build wired an ARCore Cloud API key into the manifest
    // (com.google.android.ar.API_KEY meta-data). When absent — fork builds without the
    // GitHub secret, dev machines with no key in local.properties — Geospatial endpoints
    // silently return no data, which otherwise reads as a demo that simply does not work.
    val hasArcoreApiKey = rememberHasArcoreApiKey()
    // Preemptive network check (#3262): Geospatial silently returns no data with no
    // network, which otherwise reads identically to a rejected API key.
    val isNetworkAvailable = rememberIsNetworkAvailable()
    return when {
        !hasArcoreApiKey -> CloudServiceStatus.ApiKeyMissing
        !isNetworkAvailable -> CloudServiceStatus.NoNetwork
        else -> state.earthState.toCloudServiceStatus("Geospatial") ?: CloudServiceStatus.Available
    }
}

/**
 * Arms the outdoor-guidance timeout (#1615). Keyed on the inputs so the timer re-arms
 * whenever tracking drops or geometry appears.
 */
@Composable
private fun NoGeometryGuidanceEffect(state: SceneGeometryState, cloudStatus: CloudServiceStatus) {
    LaunchedEffect(
        state.isTracking,
        state.geometryCount,
        state.geospatialUnavailable,
        state.sessionError,
        cloudStatus,
    ) {
        state.noGeometryGuidance = false
        val shouldShowNoGeometryHint = state.isTracking &&
            state.geometryCount == 0 &&
            state.geospatialUnavailable == null &&
            state.sessionError == null &&
            !cloudStatus.isUnavailable
        if (shouldShowNoGeometryHint) {
            kotlinx.coroutines.delay(NO_GEOMETRY_HINT_DELAY_MS)
            state.noGeometryGuidance = true
        }
    }
}

/**
 * The VPS / tracking status pill, identical in both modes apart from the three sentences
 * that name what the mode is rendering.
 */
@Composable
private fun DemoBottomOverlayScope.SceneGeometryStatusBanner(
    state: SceneGeometryState,
    cloudStatus: CloudServiceStatus,
    renderingText: String,
    lookingText: String,
    noGeometryHint: String,
) {
    // ForcedTrackingFailure.override shadows the real ARCore-reported reason when a
    // developer has picked one in the debug menu (#1881). Read it here so flipping the
    // override re-renders the overlay immediately.
    val effectiveReason = ForcedTrackingFailure.override ?: state.trackingFailureReason
    when {
        // friendlyArSessionError already yields a complete, honest sentence (#2349).
        state.sessionError != null ->
            DemoStatusBanner(state.sessionError, tone = DemoStatusTone.Blocked)
        // The one shared "Cloud service unavailable" banner (#3262): missing or rejected
        // key, exhausted quota, no network. Lives in the main AR view, never only in
        // the settings sheet.
        cloudStatus.isUnavailable -> CloudServiceStatusBanner(cloudStatus)
        state.geospatialUnavailable != null ->
            DemoStatusBanner(
                stringResource(
                    R.string.demo_ar_scene_mesh_geospatial_unavailable,
                    state.geospatialUnavailable.orEmpty(),
                ),
                tone = DemoStatusTone.Blocked,
            )
        else -> {
            // The tone is derived from the same branches as the text: a tracking failure
            // asks the user to move the phone (Guidance), everything else is a normal
            // transient state.
            val scanning = stringResource(R.string.demo_ar_scene_mesh_scanning)
            val (statusText, statusTone) = when {
                ForcedTrackingFailure.override != null ->
                    (trackingFailureMessage(effectiveReason) ?: scanning) to
                        DemoStatusTone.Guidance
                state.geometryCount > 0 -> renderingText to DemoStatusTone.Progress
                !state.isTracking -> {
                    val failure = trackingFailureMessage(effectiveReason)
                    if (failure != null) {
                        failure to DemoStatusTone.Guidance
                    } else {
                        scanning to DemoStatusTone.Progress
                    }
                }
                // Supported device, no VPS coverage / indoors (#1615): after a timeout
                // the perpetual spinner is replaced with explicit guidance to step
                // outside and point at buildings.
                state.noGeometryGuidance -> noGeometryHint to DemoStatusTone.Guidance
                else -> lookingText to DemoStatusTone.Progress
            }
            DemoStatusBanner(statusText, tone = statusTone)
        }
    }
}

/**
 * The `sessionConfiguration` both modes pass — the same `Config.GeospatialMode.ENABLED` +
 * `Config.StreetscapeGeometryMode.ENABLED` pair, guarded identically.
 *
 * Enabling the mode unconditionally throws on devices without the feature, in regions
 * without VPS coverage, or when the project lacks a configured ARCore Cloud API key. When
 * unsupported, keep a plain AR session running so the camera feed still renders and the
 * user sees a clear status rather than a black screen.
 */
private fun configureStreetscapeGeometry(
    tag: String,
    session: Session,
    config: Config,
    state: SceneGeometryState,
) {
    val geospatialOk = runCatching {
        session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)
    }.getOrElse { error ->
        Log.w(tag, "isGeospatialModeSupported threw", error)
        false
    }
    if (!geospatialOk) {
        state.geospatialUnavailable = "Geospatial API not available on this device"
        Log.w(tag, "Geospatial mode unsupported — running plain AR session")
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
        return
    }
    runCatching {
        config.geospatialMode = Config.GeospatialMode.ENABLED
        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
    }.onFailure { error ->
        // Most common cause: no ARCore Cloud API key configured for the app — Geospatial
        // then fails at configure() time with an IllegalStateException. Surface a readable
        // message instead of a hard crash.
        Log.w(tag, "Geospatial config failed — falling back", error)
        state.geospatialUnavailable = "Geospatial config failed: ${error.message}"
        config.geospatialMode = Config.GeospatialMode.DISABLED
        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
    }
}

// ─── Permission gate ─────────────────────────────────────────────────────────

/**
 * Gates the AR scene mount on both CAMERA and ACCESS_FINE_LOCATION being granted.
 *
 * Streetscape Geometry crashes on either:
 *   1. CAMERA missing → ARSceneView session creation aborts
 *   2. FINE_LOCATION missing → `Session.configure(GeospatialMode.ENABLED)` throws
 *      `FineLocationPermissionNotGrantedException`
 *
 * Mounting `ARSceneView` before both are granted produces a race where its own lifecycle
 * observer requests CAMERA in parallel with our LOCATION request, and Android drops one
 * ("Can request only one set of permissions at a time"). The fix is to *not* mount it
 * until both permissions resolve — this gate holds the composition while the system
 * dialogs are processed sequentially by a single launcher.
 *
 * [granted] is only composed once both are granted, so each mode's `rememberEngine` still
 * lives at that mode's own call site — the gate hoists nothing.
 */
@Composable
private fun GeospatialPermissionGate(
    title: String,
    deniedReason: String,
    onBack: () -> Unit,
    granted: @Composable () -> Unit,
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
    var permissionsResolved by remember { mutableStateOf(cameraGranted && fineLocationGranted) }
    var permissionDeniedMessage by remember { mutableStateOf<String?>(null) }
    val cameraDeniedMessage = stringResource(R.string.demo_ar_scene_mesh_camera_denied)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        fineLocationGranted =
            result[Manifest.permission.ACCESS_FINE_LOCATION] ?: fineLocationGranted
        permissionDeniedMessage = when {
            !cameraGranted -> cameraDeniedMessage
            !fineLocationGranted -> deniedReason
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

    // Permission gate — a status UI with Retry + Open Settings buttons. ARSceneView is
    // *not* composed in this branch, which keeps it from racing our own permission
    // request. QA finding 2026-05-11: the gate used to show only the error text, so the
    // user was stuck with no way out except Back.
    if (!permissionsResolved || !cameraGranted || !fineLocationGranted) {
        DemoScaffold(title = title, onBack = onBack) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = SceneViewTokens.Space.xl)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(SceneViewTokens.Radius.md)
                        )
                        .padding(
                            horizontal = SceneViewTokens.Space.lg,
                            vertical = SceneViewTokens.Space.md,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = permissionDeniedMessage
                            ?: stringResource(R.string.demo_ar_scene_mesh_requesting_permissions),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    if (permissionDeniedMessage != null) {
                        Spacer(Modifier.height(SceneViewTokens.Space.md))
                        Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
                            OutlinedButton(onClick = {
                                // Retry: re-launch the system permission request. Useful
                                // when the user hits "Don't allow" but changes their mind
                                // without going to Settings — Android re-prompts up to
                                // twice before silent denial.
                                val toRequest = buildList {
                                    if (!cameraGranted) {
                                        add(Manifest.permission.CAMERA)
                                    }
                                    if (!fineLocationGranted) {
                                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                }
                                if (toRequest.isNotEmpty()) {
                                    permissionDeniedMessage = null
                                    permissionsResolved = false
                                    permissionLauncher.launch(toRequest.toTypedArray())
                                }
                            }) { Text(stringResource(R.string.demo_ar_scene_mesh_retry)) }
                            Button(onClick = {
                                // Open Settings: deep-link to the app's permission page so
                                // the user can flip the toggle manually. Needed after a
                                // permanent deny ("Don't ask again"), where the launcher
                                // is ignored.
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null)
                                ).apply { flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK }
                                context.startActivity(intent)
                            }) { Text(stringResource(R.string.demo_ar_scene_mesh_open_settings)) }
                        }
                    }
                }
            }
        }
        return
    }

    granted()
}

// ─── Classification legend ───────────────────────────────────────────────────

/**
 * Small overlay legend mapping the two ARCore surface types to their overlay colour.
 *
 * Drawn as an **AR overlay card** (`DESIGN.md`): the ground behind it is a camera frame,
 * not a `surface`, so it keeps the `ar-scrim` dark ground and white text in both themes and
 * only its opacity moves with the theme.
 */
@Composable
private fun MeshClassificationLegend(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    Column(
        modifier = modifier
            .shadow(elevation = SceneViewTokens.Elevation.lg, shape = shape, clip = false)
            .background(color = legendScrim(), shape = shape)
            .border(
                width = SceneViewTokens.ArOverlay.borderWidth,
                color = legendBorder(),
                shape = shape,
            )
            .padding(
                horizontal = SceneViewTokens.Space.md,
                vertical = SceneViewTokens.Space.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
    ) {
        Text(
            text = stringResource(R.string.demo_ar_scene_mesh_legend_title),
            style = SceneViewTokens.Type.caption,
            color = SceneViewTokens.ArOverlay.onScrimMuted,
        )
        LegendRow(
            color = SceneViewColors.Primary,
            label = stringResource(R.string.demo_ar_scene_mesh_legend_terrain),
        )
        LegendRow(
            color = SceneViewColors.TintSoft,
            label = stringResource(R.string.demo_ar_scene_mesh_legend_building),
        )
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        Box(
            modifier = Modifier
                .size(SceneViewTokens.Space.sm + SceneViewTokens.Space.xs)
                .clip(RoundedCornerShape(SceneViewTokens.Radius.xs / 2))
                .background(color)
        )
        Text(
            text = label,
            style = SceneViewTokens.Type.caption,
            color = SceneViewTokens.ArOverlay.onScrim,
        )
    }
}

/**
 * The legend's ground, and its hairline.
 *
 * Reads the *theme's* darkness rather than the system's, exactly as `DemoStatusBanner` and
 * the Cloud Anchor cards do: `SceneViewDemoTheme` takes an explicit `darkTheme` flag that
 * previews and the in-app override both set, and `isSystemInDarkTheme()` would ignore it
 * and answer for the OS.
 */
@Composable
private fun legendScrim(): Color = if (isLegendOnDarkTheme()) {
    SceneViewTokens.ArOverlay.scrimDark
} else {
    SceneViewTokens.ArOverlay.scrimLight
}

/** @see legendScrim */
@Composable
private fun legendBorder(): Color = if (isLegendOnDarkTheme()) {
    SceneViewTokens.ArOverlay.borderDark
} else {
    SceneViewTokens.ArOverlay.borderLight
}

@Composable
private fun isLegendOnDarkTheme(): Boolean =
    MaterialTheme.colorScheme.surface.luminance() < 0.5f
