package io.github.sceneview.demo.demos

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.google.ar.core.VpsAvailability
import com.google.ar.core.exceptions.ResourceExhaustedException
import io.github.sceneview.ar.ARCoreAvailability
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.awaitVpsAvailability
import io.github.sceneview.ar.node.MeshClassification
import io.github.sceneview.ar.node.toMeshClassification
import io.github.sceneview.ar.rememberHostLifecycle
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
import io.github.sceneview.demo.common.rememberIsLocationEnabled
import io.github.sceneview.demo.common.rememberIsNetworkAvailable
import io.github.sceneview.demo.common.toCloudServiceStatus
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.demos.internal.SceneGeometryWait
import io.github.sceneview.demo.demos.internal.VpsCoverage
import io.github.sceneview.demo.demos.internal.earthErrorMessage
import io.github.sceneview.demo.demos.internal.friendlyArSessionError
import io.github.sceneview.demo.demos.internal.sceneGeometryWait
import io.github.sceneview.demo.demos.internal.vpsCell
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.demo.demos.internal.rememberArMeshTintInstance
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val TAG = "ARStreetscapeDemo"

// How long the camera may track with zero streetscape geometry before the status
// banner swaps the spinner text for explicit "go outdoors" guidance (#1615).
// 15 s is long enough for a genuine outdoor VPS lookup to start returning meshes,
// short enough that an indoor QA pass isn't left guessing for a full minute.
private const val NO_GEOMETRY_HINT_DELAY_MS = 15_000L

// How long to wait before asking again when the Street View coverage request failed for a
// reason that can clear on its own (network blip, rate limit, a key restriction that was
// just fixed in the Cloud Console). Long enough not to feed a rate limit.
private const val VPS_RETRY_DELAY_MS = 30_000L

/**
 * The Streetscape mode of the `ar-geospatial-anchors` card: the Geospatial API's
 * Streetscape Geometry — the buildings and terrain around the user, as meshes.
 *
 * One switch in the settings sheet, **Colour buildings & terrain**, picks the node that
 * consumes the trackables:
 *
 * - **On** (default) — [io.github.sceneview.ar.node.SceneMeshNode] colour-codes each
 *   geometry by its [MeshClassification], the enum that gives ARKit `ARMeshAnchor` parity
 *   on Android, with a legend over the camera. This was the `ar-scene-mesh` card ("Scene
 *   Geometry") until the AR catalogue clean-up, wave 1; its link opens this mode.
 * - **Off** — [io.github.sceneview.ar.node.StreetscapeGeometryNode], the raw node the
 *   classified one subclasses: one material for every geometry, no classification. The
 *   retired `ar-streetscape` link opens it through [io.github.sceneview.demo.DeepLinkRouter].
 *
 * The screen keeps its **own** `ARSceneView` and its own [rememberEngine], so leaving it
 * tears its ARCore session and Filament engine down completely — the leak invariant the
 * #2239 Batch-1 review pinned.
 *
 * **Requirements:**
 * - ARCore Geospatial API enabled in Google Cloud Console + a Cloud API key
 * - Device supports the ARCore Geospatial API
 * - Outdoor environment with Google Street View coverage
 * - CAMERA + ACCESS_FINE_LOCATION permissions
 */
@Composable
fun ARStreetscapeDemo(onBack: () -> Unit) = GeospatialPermissionGate(
    title = stringResource(R.string.demo_ar_geospatial_anchors_title),
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

    // On by default: the colours are what tell a building from the ground at a glance,
    // and the retired `ar-scene-mesh` link lands here expecting them.
    var colourByClassification by rememberSaveable { mutableStateOf(true) }

    // Per-classification overlays — TERRAIN and BUILDING read at a glance. They tint the
    // camera image instead of covering it (#4623): the old 55 %-alpha brand blue and lilac
    // turned pastel over a bright wall once the AR view's Filmic tone mapper had its say.
    // Blue ground, orange structure: the pair stays apart under colour-vision deficiencies.
    val terrainMaterial = rememberArMeshTintInstance(materialLoader, SceneViewTokens.ArOverlay.meshGround)
    val buildingMaterial = rememberArMeshTintInstance(materialLoader, SceneViewTokens.ArOverlay.meshStructure)
    // Switch off: one semi-transparent material for every geometry — the raw node carries
    // no classification, and pretending otherwise would erase what the switch shows.
    val plainMaterial = rememberMaterialInstance(
        materialLoader,
        color = SceneViewColors.LandscapeOverlay,
        metallic = 0.1f,
        roughness = 0.9f,
        reflectance = 0.1f,
    )

    val state = rememberSceneGeometryState()
    val cloudStatus = rememberCloudStatus(state)
    val locationEnabled = rememberIsLocationEnabled()
    NoGeometryGuidanceEffect(state, cloudStatus)
    VpsCoverageEffect(TAG, state)
    SceneGeometryStatusLogEffect(TAG, state, cloudStatus, locationEnabled)

    DemoScaffold(
        title = stringResource(R.string.demo_ar_geospatial_anchors_title),
        onBack = onBack,
        controls = {
            SwitchRow(
                label = stringResource(R.string.demo_ar_scene_mesh_colour_switch),
                checked = colourByClassification,
                onCheckedChange = { colourByClassification = it },
            )
            Text(
                text = stringResource(
                    if (colourByClassification) {
                        R.string.demo_ar_scene_mesh_colour_on_explainer
                    } else {
                        R.string.demo_ar_scene_mesh_colour_off_explainer
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Developer-only debug toggle — lets QA force-emit each TrackingFailureReason
            // so the actionable-message overlay can be validated without staging a real
            // failure. See io.github.sceneview.demo.common.ForcedTrackingFailure / #1881.
            if (DemoSettings.qaMode) ForceTrackingFailureMenu()
        },
        // Legend + status pill are hosted by the scaffold's `bottomOverlay` slot, a
        // bottom-aligned Column: they stack instead of sharing the same strip of pixels,
        // and the banner is laid out against the dock instead of under it (#2779).
        bottomOverlay = {
            // Classification legend — only with the colours on, once geometry renders.
            if (colourByClassification && state.geometryCount > 0) {
                MeshClassificationLegend(
                    modifier = Modifier
                        .align(Alignment.Start)
                        .padding(start = SceneViewTokens.Space.md)
                )
            }
            SceneGeometryStatusBanner(
                state = state,
                cloudStatus = cloudStatus,
                locationEnabled = locationEnabled,
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
                lifecycle = rememberHostLifecycle(),
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                playbackDataset = arPlaybackDataset,
                planeRenderer = false,
                sessionConfiguration = { session, config ->
                    configureStreetscapeGeometry(TAG, session, config, state)
                },
                onSessionFailed = { exception ->
                    Log.e(TAG, "AR session failed", exception)
                    // Map to friendly copy (#2349) — never surface the raw
                    // "FatalException" class name to the user.
                    state.sessionError = friendlyArSessionError(exception)
                },
                onARCoreAvailability = { state.arCoreAvailability = it },
                onSessionUpdated = { session, frame -> state.onFrame(session, frame) },
                onTrackingFailureChanged = { reason -> state.trackingFailureReason = reason },
            ) {
                state.geometries.forEach { geo ->
                    if (colourByClassification) {
                        SceneMeshNode(
                            streetscapeGeometry = geo,
                            meshMaterialInstance = when (geo.type.toMeshClassification()) {
                                MeshClassification.TERRAIN -> terrainMaterial
                                else -> buildingMaterial
                            },
                        )
                    } else {
                        StreetscapeGeometryNode(
                            streetscapeGeometry = geo,
                            meshMaterialInstance = plainMaterial,
                        )
                    }
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

// ─── Shared session plumbing ─────────────────────────────────────────────────

/**
 * The Streetscape session state, and nothing else.
 *
 * [ARStreetscapeDemo] calls [rememberSceneGeometryState] at its own call site, so leaving
 * the mode drops the whole thing along with its `ARSceneView` and engine.
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

    /**
     * Whether Earth has a position. `earthState == ENABLED` only says Geospatial is
     * allowed to run; no geometry can arrive before Earth also *tracks*, and a phone
     * indoors or with Location switched off sits at `ENABLED` + not tracking forever.
     */
    var earthTracking by mutableStateOf(false)

    /** The running session, for the one call made outside a frame: the coverage check. */
    var session by mutableStateOf<Session?>(null)

    /**
     * The ~100 m cell Earth's position falls in, `null` until Earth tracks. The coverage
     * check is keyed on it; [latitude] / [longitude] are the position it was derived from
     * and are deliberately not Compose state — they change on every frame.
     */
    var coverageCell by mutableStateOf<Pair<Int, Int>?>(null)
    var latitude = 0.0
    var longitude = 0.0

    /** Google's answer to "is there Street View imagery where Earth says we are". */
    var vps by mutableStateOf(VpsCoverage.Unknown)

    /**
     * Set when the coverage request itself was refused (key rejected, quota, network).
     * It is the first Geospatial round-trip the session makes with a position in hand, so
     * a rejected key can show up here while [earthState] still reads `ENABLED`.
     */
    var vpsCloudStatus by mutableStateOf<CloudServiceStatus?>(null)

    /** The per-frame trackable bookkeeping. */
    fun onFrame(session: Session, frame: Frame) {
        cameraReady = true
        this.session = session
        isTracking = frame.camera.trackingState == TrackingState.TRACKING
        val earth = session.earth
        earthState = earth?.earthState
        val pose = earth
            ?.takeIf { it.trackingState == TrackingState.TRACKING }
            ?.let { runCatching { it.cameraGeospatialPose }.getOrNull() }
        earthTracking = pose != null
        if (pose != null) {
            latitude = pose.latitude
            longitude = pose.longitude
            coverageCell = vpsCell(pose.latitude, pose.longitude)
        }
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
        else -> state.earthState.toCloudServiceStatus("Geospatial")
            ?: state.vpsCloudStatus
            ?: CloudServiceStatus.Available
    }
}

/**
 * Asks Google whether Street View imagery covers Earth's position, once per ~100 m cell.
 *
 * Without it "no coverage in this street" and "covered, but you are indoors" were one
 * sentence. A request the service refused is retried, so a network blip or a key
 * restriction fixed in the Cloud Console clears without leaving the demo; a coverage
 * answer is final for the cell.
 */
@Composable
private fun VpsCoverageEffect(tag: String, state: SceneGeometryState) {
    LaunchedEffect(state.session, state.coverageCell) {
        val session = state.session ?: return@LaunchedEffect
        if (state.coverageCell == null) return@LaunchedEffect
        // A new cell is a new question: the previous cell's answer must not stand in for it.
        state.vps = VpsCoverage.Checking
        while (true) {
            val answer = try {
                session.awaitVpsAvailability(state.latitude, state.longitude)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ResourceExhaustedException) {
                // ARCore throttles the lookup itself by throwing, before any answer exists.
                Log.w(tag, "Street View coverage check rate-limited", e)
                VpsAvailability.ERROR_RESOURCE_EXHAUSTED
            } catch (e: Exception) {
                Log.w(tag, "Street View coverage check failed", e)
                null
            }
            state.vpsCloudStatus = answer.toCloudServiceStatus("Geospatial")
            state.vps = when (answer) {
                VpsAvailability.AVAILABLE -> VpsCoverage.Available
                VpsAvailability.UNAVAILABLE -> VpsCoverage.Unavailable
                else -> VpsCoverage.Error
            }
            if (state.vps != VpsCoverage.Error) break
            delay(VPS_RETRY_DELAY_MS)
        }
    }
}

/**
 * One logcat line per change of the inputs the status banner is derived from — never a
 * position. A report of "Streetscape shows nothing" can then be read off `adb logcat -s`
 * instead of guessed: which link of key / Earth / coverage / geometry did not hold.
 */
@Composable
private fun SceneGeometryStatusLogEffect(
    tag: String,
    state: SceneGeometryState,
    cloudStatus: CloudServiceStatus,
    locationEnabled: Boolean,
) {
    LaunchedEffect(
        state.isTracking,
        state.earthState,
        state.earthTracking,
        state.vps,
        state.geometryCount,
        state.noGeometryGuidance,
        cloudStatus,
        locationEnabled,
    ) {
        if (!state.cameraReady) return@LaunchedEffect
        Log.i(
            tag,
            "status: camera=${if (state.isTracking) "TRACKING" else "NOT_TRACKING"}" +
                " earth=${state.earthState}" +
                " earthTracking=${state.earthTracking}" +
                " locationSwitch=${if (locationEnabled) "on" else "off"}" +
                " cloud=${cloudStatus.logName()}" +
                " coverage=${state.vps}" +
                " geometries=${state.geometryCount}" +
                " waitedLong=${state.noGeometryGuidance}",
        )
    }
}

/** Spelled out rather than reflected: R8 renames the classes in the store build. */
private fun CloudServiceStatus.logName(): String = when (this) {
    CloudServiceStatus.Available -> "Available"
    CloudServiceStatus.ApiKeyMissing -> "ApiKeyMissing"
    is CloudServiceStatus.ApiKeyRejected -> "ApiKeyRejected"
    is CloudServiceStatus.QuotaExhausted -> "QuotaExhausted"
    CloudServiceStatus.NoNetwork -> "NoNetwork"
    CloudServiceStatus.EarthLocalizing -> "EarthLocalizing"
}

/**
 * Arms the outdoor-guidance timeout (#1615). Keyed on the inputs so the timer re-arms
 * whenever tracking drops or geometry appears.
 */
@Composable
private fun NoGeometryGuidanceEffect(state: SceneGeometryState, cloudStatus: CloudServiceStatus) {
    LaunchedEffect(
        state.isTracking,
        // Re-armed when Earth gains or loses its position: "still no location fix" and
        // "localized, still no geometry" each get their own grace period.
        state.earthTracking,
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
            delay(NO_GEOMETRY_HINT_DELAY_MS)
            state.noGeometryGuidance = true
        }
    }
}

/**
 * The VPS / tracking status pill, fed the three sentences
 * that name what the mode is rendering.
 */
@Composable
private fun DemoBottomOverlayScope.SceneGeometryStatusBanner(
    state: SceneGeometryState,
    cloudStatus: CloudServiceStatus,
    locationEnabled: Boolean,
    renderingText: String,
    lookingText: String,
    noGeometryHint: String,
) {
    // Earth states that are neither `ENABLED` nor one of the shared Cloud failures: an
    // outdated Google Play Services for AR, an internal error. They used to leave this
    // screen on "Looking for…" and then on the outdoor hint, as if the place were at fault.
    val earthError = earthErrorMessage(state.earthState?.name)
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
        earthError != null -> DemoStatusBanner(earthError, tone = DemoStatusTone.Blocked)
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
                // Camera tracking, nothing to draw: name the first link that does not
                // hold instead of one sentence for every cause (#1615 gave the timeout;
                // this tells Location off, not localized, no Street View imagery here and
                // "covered, but nothing in view" apart).
                else -> when (
                    sceneGeometryWait(
                        locationEnabled = locationEnabled,
                        earthTracking = state.earthTracking,
                        vps = state.vps,
                        waitedLong = state.noGeometryGuidance,
                    )
                ) {
                    SceneGeometryWait.LocationOff ->
                        stringResource(R.string.demo_ar_scene_mesh_location_off) to
                            DemoStatusTone.Blocked
                    SceneGeometryWait.Localizing ->
                        stringResource(R.string.demo_ar_scene_mesh_localizing) to
                            DemoStatusTone.Progress
                    SceneGeometryWait.NotLocalized ->
                        stringResource(R.string.demo_ar_scene_mesh_not_localized) to
                            DemoStatusTone.Guidance
                    SceneGeometryWait.NoCoverage ->
                        stringResource(R.string.demo_ar_scene_mesh_no_coverage) to
                            DemoStatusTone.Guidance
                    SceneGeometryWait.Looking -> lookingText to DemoStatusTone.Progress
                    SceneGeometryWait.CoveredNothingInView ->
                        stringResource(R.string.demo_ar_scene_mesh_covered_nothing_in_view) to
                            DemoStatusTone.Guidance
                    SceneGeometryWait.NothingFound -> noGeometryHint to DemoStatusTone.Guidance
                }
            }
            DemoStatusBanner(statusText, tone = statusTone)
        }
    }
}

/**
 * The `sessionConfiguration` the mode passes — the same `Config.GeospatialMode.ENABLED` +
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
        state.geospatialUnavailable = "Scene geometry isn't available here"
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
        state.geospatialUnavailable = "Scene geometry couldn't start"
        config.geospatialMode = Config.GeospatialMode.DISABLED
        config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.DISABLED
        config.planeFindingMode = Config.PlaneFindingMode.DISABLED
    }
}

// ─── Permission gate ─────────────────────────────────────────────────────────

/**
 * Gates the AR scene mount on ACCESS_FINE_LOCATION being granted: without it
 * `Session.configure(GeospatialMode.ENABLED)` throws
 * `FineLocationPermissionNotGrantedException`.
 *
 * The camera is not asked here: this demo only composes behind the AR demos' camera gate
 * (`DemoEntry.opensCameraOnEntry`, #4139), which also keeps the two system dialogs one
 * after the other — Android drops a request made while another is on screen.
 *
 * [granted] is only composed once the location is granted, so the mode's `rememberEngine`
 * still lives at that mode's own call site — the gate hoists nothing.
 */
@Composable
private fun GeospatialPermissionGate(
    title: String,
    deniedReason: String,
    onBack: () -> Unit,
    granted: @Composable () -> Unit,
) {
    val context = LocalContext.current

    var fineLocationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionsResolved by remember { mutableStateOf(fineLocationGranted) }
    var permissionDeniedMessage by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { result ->
        fineLocationGranted = result
        permissionDeniedMessage = deniedReason.takeUnless { result }
        permissionsResolved = true
    }

    LaunchedEffect(Unit) {
        if (!fineLocationGranted) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Permission gate — a status UI with Retry + Open Settings buttons. ARSceneView is
    // *not* composed in this branch, which keeps it from racing our own permission
    // request. QA finding 2026-05-11: the gate used to show only the error text, so the
    // user was stuck with no way out except Back.
    if (!permissionsResolved || !fineLocationGranted) {
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
                                permissionDeniedMessage = null
                                permissionsResolved = false
                                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
                            }) { Text(stringResource(R.string.ar_permission_open_settings)) }
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
internal fun MeshClassificationLegend(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    Column(
        modifier = modifier
            .shadow(elevation = SceneViewTokens.Elevation.lg, shape = shape, clip = false)
            .background(color = legendScrim(), shape = shape)
            // `over-media-edge`: the ground is a camera frame, so the edge is measured
            // against WCAG 1.4.11's 3:1 on a white wall and on a night scene.
            .overMediaEdge(shape)
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
            color = SceneViewTokens.ArOverlay.meshGround,
            label = stringResource(R.string.demo_ar_scene_mesh_legend_terrain),
        )
        LegendRow(
            color = SceneViewTokens.ArOverlay.meshStructure,
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

@Composable
private fun isLegendOnDarkTheme(): Boolean =
    MaterialTheme.colorScheme.surface.luminance() < 0.5f
