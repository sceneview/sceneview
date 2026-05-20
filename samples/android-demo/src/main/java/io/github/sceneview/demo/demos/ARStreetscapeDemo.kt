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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.common.trackingFailureMessage
import io.github.sceneview.demo.rememberArPlaybackDataset
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.rememberMaterialInstance

private const val TAG = "ARStreetscapeDemo"

/**
 * Geospatial API streetscape geometry demo.
 *
 * Enables the ARCore Geospatial API and Streetscape Geometry mode to render detected building
 * and terrain meshes. Each [StreetscapeGeometryNode] displays a semi-transparent mesh overlay
 * on real-world structures.
 *
 * Requires:
 * - ARCore Geospatial API enabled in Google Cloud Console
 * - Device supports ARCore Geospatial API
 * - Outdoor environment with Google Street View coverage
 */
@Composable
fun ARStreetscapeDemo(onBack: () -> Unit) {
    val context = LocalContext.current

    // Gate the AR scene mount on both CAMERA and ACCESS_FINE_LOCATION being
    // granted. Streetscape Geometry crashes on either:
    //   1. CAMERA missing → ARSceneView session creation aborts
    //   2. FINE_LOCATION missing → Session.configure(GeospatialMode.ENABLED)
    //      throws FineLocationPermissionNotGrantedException
    // Mounting ARSceneView before both are granted produces a race where
    // ARSceneView's own lifecycle observer requests CAMERA in parallel with
    // our LOCATION request, and Android drops one ("Can request only one
    // set of permissions at a time"). The fix is to *not* mount ARSceneView
    // until both permissions are resolved — the gate UI below holds the
    // composition while the system permission dialogs are processed
    // sequentially by our single launcher.
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
            !cameraGranted ->
                "Camera permission denied — AR cannot run"
            !fineLocationGranted ->
                "Location permission denied — Streetscape Geometry needs ACCESS_FINE_LOCATION"
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

    // Permission gate — render a status UI with Retry + Open Settings buttons.
    // ARSceneView is *not* composed in this branch, which keeps it from racing
    // with our own permission request. QA finding 2026-05-11: previously the gate
    // showed only the error text → user was stuck with no way out except Back.
    if (!permissionsResolved || !cameraGranted || !fineLocationGranted) {
        DemoScaffold(title = stringResource(R.string.demo_ar_streetscape_title), onBack = onBack) {
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
                        Spacer(
                            modifier = Modifier.height(16.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(onClick = {
                                // Retry: re-launch the system permission request. Useful when the user
                                // hits "Don't allow" but later changes their mind without going to
                                // Settings — Android will re-prompt up to twice before silent denial.
                                val toRequest = buildList {
                                    if (!cameraGranted) add(Manifest.permission.CAMERA)
                                    if (!fineLocationGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                if (toRequest.isNotEmpty()) {
                                    permissionDeniedReason = null
                                    permissionsResolved = false
                                    permissionLauncher.launch(toRequest.toTypedArray())
                                }
                            }) {
                                Text("Retry")
                            }
                            Button(onClick = {
                                // Open Settings: deep-link to the app's permission page so the
                                // user can flip the toggle manually. Needed after a permanent
                                // deny ("Don't ask again") since the launcher will be ignored.
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", context.packageName, null)
                                ).apply {
                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }) {
                                Text("Open Settings")
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // Both permissions granted — proceed with the AR session.
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    // Replay a recorded ARCore dataset when the device-QA harness deep-links this demo
    // with `--es ar_playback_file <path>` (#1576). `null` for every normal launch - see
    // `rememberArPlaybackDataset` - so live AR is completely unchanged for real users.
    val arPlaybackDataset = rememberArPlaybackDataset()

    val geometries = remember { mutableStateListOf<StreetscapeGeometry>() }
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var geometryCount by remember { mutableStateOf(0) }
    // Tracks whether Geospatial / Streetscape mode could actually be enabled on the
    // current device + region. ARCore Geospatial requires a Cloud project key wired
    // through `arcore-cloud-anchor` config and VPS coverage from Google Street View;
    // when either is missing, [Session.isGeospatialModeSupported] returns false and
    // attempting to set the mode would throw on `session.configure()`.
    var geospatialUnavailable by remember { mutableStateOf<String?>(null) }
    var sessionError by remember { mutableStateOf<String?>(null) }

    // Detect at runtime whether the build wired an ARCore Cloud API key into the
    // manifest (com.google.android.ar.API_KEY meta-data). When absent — fork
    // builds without the GitHub secret, dev machines that haven't put the key in
    // local.properties — Geospatial endpoints silently return no data. We pre-fill
    // the banner so the user sees "Looking for streetscape geometry…" forever
    // turned into a clear "API key not configured" diagnostic instead.
    val hasArcoreApiKey = remember {
        runCatching {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            !ai.metaData?.getString("com.google.android.ar.API_KEY").isNullOrBlank()
        }.getOrDefault(false)
    }

    // Semi-transparent material for streetscape geometry overlays — SceneView TintLight at
    // low alpha so the real camera feed of buildings/sidewalks stays readable through the
    // overlay mesh.
    val buildingMaterial = rememberMaterialInstance(
        materialLoader,
        color = SceneViewColors.LandscapeOverlay,
        metallic = 0.1f,
        roughness = 0.9f,
        reflectance = 0.1f,
    )

    DemoScaffold(
        title = stringResource(R.string.demo_ar_streetscape_title),
        onBack = onBack
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
                    // ACCESS_FINE_LOCATION + CAMERA are guaranteed granted at this
                    // point — the permission gate above held the composition until
                    // they resolved, so ARSceneView never mounts otherwise.
                    // Guard: enabling the mode unconditionally throws on devices
                    // without the feature, on regions without VPS coverage, or when
                    // the project lacks a configured ARCore Cloud API key. When
                    // unsupported, keep a plain AR session running so the camera
                    // feed still renders and the user sees a clear status rather
                    // than a black screen.
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
                        // Most common cause: no ARCore Cloud API key configured for
                        // the app — Geospatial then fails at configure() time with
                        // an IllegalStateException. Surface a readable message
                        // instead of a hard crash.
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
                            if (geometries.none { it == geo }) {
                                geometries.add(geo)
                            }
                        }
                    }
                    // Remove geometries that stopped tracking
                    geometries.removeAll { it.trackingState == TrackingState.STOPPED }
                    geometryCount = geometries.size
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                }
            ) {
                geometries.forEach { geo ->
                    StreetscapeGeometryNode(
                        streetscapeGeometry = geo,
                        meshMaterialInstance = buildingMaterial,
                        onTrackingStateChanged = { state ->
                            // Could update UI for individual geometry tracking
                        }
                    )
                }
            }

            // Status overlay
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val statusText = when {
                    sessionError != null -> "AR session error: $sessionError"
                    !hasArcoreApiKey ->
                        "ARCore Cloud API key not configured \u2014 see samples/android-demo/STREETSCAPE_SETUP.md"
                    geospatialUnavailable != null ->
                        "${geospatialUnavailable!!} \u2014 needs outdoor area with Street View coverage + Cloud API key"
                    geometryCount > 0 -> "Rendering $geometryCount structure(s)"
                    !isTracking -> trackingFailureMessage(trackingFailureReason)
                        ?: "Scanning environment\u2026"
                    else -> "Looking for streetscape geometry\u2026"
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
