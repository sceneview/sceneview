@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.sceneview.demo.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.demo.feedback.DriveFeedbackChipReveal
import io.github.sceneview.demo.feedback.FEEDBACK_FAB_RESERVED_SPACE
import io.github.sceneview.demo.feedback.FeedbackChrome
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.R
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * AR View tab — opt-in live ARSceneView with a launcher screen that gates the
 * heavy ARCore + Filament initialization behind an explicit user tap. Pattern
 * mirrors Polycam / Reality Composer launchers: showing a giant CTA + a row of
 * AR demo cards rather than auto-starting the camera the moment the tab opens.
 *
 * Why a launcher and not auto-start (as iOS does):
 *  - Auto-starting ARSceneView on tab tap crashed the v4.1.0 Play Store build
 *    on devices without ARCore Services installed (Filament panic when the
 *    ARCore session failed to construct). The launcher lets us run an
 *    [ArCoreApk.checkAvailability] gate first.
 *  - Heavy resource use (camera, GPU, ARCore) shouldn't kick in until the user
 *    asks for it. Saves battery and avoids spurious permission dialogs when
 *    the user is just browsing.
 *
 * Once the user taps "Start AR Camera" we fall through to the legacy live AR
 * experience, identical to the iOS spec:
 *
 *  - Full-bleed ARSceneView underneath (camera passthrough)
 *  - Top: glass status pill — "Tap a surface to place {Model}" / "N placed"
 *  - Bottom: floating action bar with FAB "Pick model" + Reset + Screenshot
 *  - Modal bottom sheet for the model picker grid
 *
 * Reset is implemented by bumping a `key(arSceneId)` wrapper around the
 * ARSceneView — there is no `removeAllAnchors` on the wrapper, so we
 * recompose the whole subtree to clear ARCore state and start a fresh
 * session.
 */
@Composable
fun ArViewTabContent(
    onDemoClick: (String) -> Unit,
    /**
     * Invoked whenever the live AR camera session is entered or exited. The
     * caller (typically [RootScreen]) uses this to hide the bottom
     * NavigationBar + system bars while the camera is active so the AR
     * viewport gets the full screen (#2238). Default no-op keeps the
     * composable usable in tests / previews that don't host a Scaffold.
     */
    onSessionActiveChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current

    // ---------- Permission gate ----------
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionsResolved by remember { mutableStateOf(cameraGranted) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
        permissionsResolved = true
    }

    // ---------- ARCore availability + launcher gate ----------
    //
    // `sessionStarted` is `rememberSaveable` so process death (common on AR —
    // high GPU/camera memory pressure) doesn't dump the user back on the
    // launcher screen and re-prompt for everything. Anchors themselves are
    // not Parcelable so we accept the loss of placed models across a kill.
    var sessionStarted by rememberSaveable { mutableStateOf(false) }

    // Immersive-mode wiring (#2238) — when the live AR session is active:
    //   1. Tell [RootScreen] to hide its bottom NavigationBar (reclaims ~90 px
    //      of viewport for the camera);
    //   2. Hide the system status + nav bars via WindowInsetsControllerCompat
    //      so the AR view goes truly fullscreen.
    // Both reverse on exit / back / dispose so the user lands on the launcher
    // screen with all chrome restored.
    val view = LocalView.current
    DisposableEffect(sessionStarted) {
        onSessionActiveChange(sessionStarted)
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (sessionStarted && controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // On composable dispose (tab swap, process tear-down) always
            // restore chrome so the next screen renders normally.
            controller?.show(WindowInsetsCompat.Type.systemBars())
            onSessionActiveChange(false)
        }
    }
    var arCoreAvailability by remember {
        mutableStateOf<ArCoreApk.Availability?>(null)
    }
    val activity = remember(context) { context as? Activity }

    LaunchedEffect(activity) {
        if (activity == null) {
            arCoreAvailability = ArCoreApk.Availability.UNKNOWN_ERROR
            return@LaunchedEffect
        }
        // ArCoreApk.checkAvailability is async on first call — it returns
        // UNKNOWN_CHECKING until the Play Services lookup resolves. Poll until
        // we get a real answer or give up after ~3s.
        //
        // The whole flow is wrapped in runCatching: on some OEMs (Huawei
        // without Play Services, sideloaded Pixel builds) the lookup throws
        // an unchecked exception instead of returning a status. Without the
        // catch the coroutine would silently die and `arCoreAvailability`
        // would stay `null` forever — locking the CTA on "Checking…".
        runCatching {
            var availability = ArCoreApk.getInstance().checkAvailability(activity)
            var attempts = 0
            while (availability == ArCoreApk.Availability.UNKNOWN_CHECKING &&
                attempts < 15
            ) {
                delay(200)
                availability = ArCoreApk.getInstance().checkAvailability(activity)
                attempts++
            }
            arCoreAvailability = availability
        }.onFailure {
            arCoreAvailability = ArCoreApk.Availability.UNKNOWN_ERROR
        }
    }

    // Show launcher screen until the user explicitly starts an AR session.
    // The launcher is also our graceful fallback when ARCore isn't installed:
    // the "Start AR Camera" CTA disables itself and the row of AR demo cards
    // still works because each demo handles its own ARCore install prompt.
    if (!sessionStarted) {
        ArLauncherScreen(
            availability = arCoreAvailability,
            cameraGranted = cameraGranted,
            onRequestCamera = {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onStartArSession = { sessionStarted = true },
            onArDemoClick = onDemoClick,
        )
        return
    }

    // Hide the floating feedback FAB while the live ARSceneView is on screen
    // — the AR-View bottom action bar (model picker + Reset + Share) would
    // otherwise be masked on its left side by the chip (#2194). The chip
    // re-appears as soon as the user exits the AR session (back gesture /
    // Close button) or switches tabs (DisposableEffect cleanup).
    DisposableEffect(Unit) {
        FeedbackChrome.chipVisible = false
        onDispose { FeedbackChrome.chipVisible = true }
    }

    // From this point the user has tapped "Start AR Camera". Re-request the
    // permission if the system revoked it between launcher and now (process
    // resumed from background, settings toggled in another tab, etc.). If the
    // user denies, we don't strand them on a dead placeholder — flip
    // sessionStarted back to false so the launcher's "Grant Camera Access"
    // CTA becomes available again.
    LaunchedEffect(sessionStarted) {
        if (sessionStarted && !cameraGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        } else if (sessionStarted && cameraGranted) {
            permissionsResolved = true
        }
    }

    if (permissionsResolved && !cameraGranted) {
        // Permission was definitively denied. Drop back to the launcher so
        // the user can retry from the CTA instead of getting stuck on a
        // generic "Camera permission is required" placeholder with no
        // affordance.
        sessionStarted = false
        return
    }
    if (!permissionsResolved) {
        ArPermissionPlaceholder(granted = false)
        return
    }

    // ---------- State ----------
    val arModels = remember { AR_MODELS }
    var selectedModelIndex by remember { mutableStateOf(0) }
    val selectedModel = arModels[selectedModelIndex]

    val placedAnchors = remember { mutableStateListOf<PlacedAr>() }
    var nextId by remember { mutableStateOf(0) }

    // Live AR session state for the status pill.
    var isTracking by remember { mutableStateOf(false) }
    var trackingFailureReason by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var latestFrame by remember { mutableStateOf<Frame?>(null) }
    // True once at least one detected ARCore plane has reached
    // [TrackingState.TRACKING]. The camera frame's own tracking state flips
    // long before any plane is detected, so `isTracking` alone is not enough
    // to know whether the user actually has a surface to tap (#2234). Without
    // this gate the "Tap a surface" prompt showed for ~10–20 s before any
    // real surface was actually trackable — confusing in the screen-record QA
    // (2026-05-26, frames f_061 → f_069).
    var anyPlaneTracked by remember { mutableStateOf(false) }

    // Force-rebuild key for the ARSceneView. Bumping this UUID recomposes the
    // whole AR subtree, which is the only way to discard ARCore state without
    // a wrapper-level resetSession() API (iOS does the same via arViewID).
    var arSceneId by remember { mutableStateOf(UUID.randomUUID()) }

    var showModelPicker by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()

    // ---------- Engine / loaders ----------
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Shared exit path used by both the system back gesture (BackHandler) and
    // the top-end Close button. Detaches every ARCore anchor first so the
    // underlying session releases its native refs before the wrapper
    // recomposes away.
    val exitArSession: () -> Unit = {
        placedAnchors.forEach { runCatching { it.anchor.detach() } }
        placedAnchors.clear()
        sessionStarted = false
    }

    // System back gesture exits the live AR session instead of dropping the
    // user out of the tab. Combined with `android:enableOnBackInvokedCallback="true"`
    // in AndroidManifest.xml, Android 13+ routes back via the new
    // OnBackInvokedDispatcher (a prerequisite for any future
    // PredictiveBackHandler upgrade); today the user still sees the system's
    // default home-peek animation during the swipe rather than an in-app
    // preview of the launcher screen — see #1206 follow-up.
    BackHandler {
        exitArSession()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Live ARSceneView — full bleed under the overlays.
        key(arSceneId) {
            ARSceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                planeRenderer = true,
                sessionConfiguration = { _: Session, config: Config ->
                    config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
                },
                onSessionUpdated = { session, frame ->
                    latestFrame = frame
                    isTracking = frame.camera.trackingState == TrackingState.TRACKING
                    // Recompute "is there any plane the user can actually tap?"
                    // each frame (#2234). Detection is cheap — ARCore caches
                    // the trackable set internally and we only scan Planes.
                    anyPlaneTracked = session.getAllTrackables(Plane::class.java)
                        .any { it.trackingState == TrackingState.TRACKING }
                },
                onTrackingFailureChanged = { reason ->
                    trackingFailureReason = reason
                },
                onGestureListener = rememberOnGestureListener(
                    onSingleTapConfirmed = { event: MotionEvent, node ->
                        // Tap on an existing editable model -> let it handle
                        // the gesture. Avoid stacking new models on top.
                        if (node != null) return@rememberOnGestureListener

                        val frame = latestFrame ?: return@rememberOnGestureListener
                        if (frame.camera.trackingState != TrackingState.TRACKING) {
                            return@rememberOnGestureListener
                        }
                        val hit = frame.hitTest(event).firstOrNull { result ->
                            val trackable = result.trackable
                            trackable is Plane &&
                                trackable.isPoseInPolygon(result.hitPose) &&
                                result.distance <= 5.0f
                        }
                        if (hit != null) {
                            placedAnchors.add(
                                PlacedAr(
                                    id = nextId++,
                                    anchor = hit.createAnchor(),
                                    assetPath = selectedModel.assetPath,
                                    scale = selectedModel.scale,
                                ),
                            )
                        }
                    },
                ),
            ) {
                placedAnchors.forEach { placed ->
                    key(placed.id) {
                        AnchorNode(anchor = placed.anchor) {
                            val instance = rememberModelInstance(modelLoader, placed.assetPath)
                            instance?.let {
                                ModelNode(
                                    modelInstance = it,
                                    scaleToUnits = placed.scale,
                                    centerOrigin = Position(0.0f, 0.0f, 0.0f),
                                    isEditable = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Top-end exit button — back affordance from the live AR session.
        // Mirrors the BackHandler above so users have a visible CTA in addition
        // to the system gesture.
        FilledIconButton(
            onClick = exitArSession,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 8.dp, end = 12.dp)
                .size(40.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.ar_exit_session),
                modifier = Modifier.size(20.dp),
            )
        }

        // Top status pill — translucent surface, capsule shape, M3 Expressive.
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(50),
            tonalElevation = 6.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val placed = placedAnchors.size
                Icon(
                    imageVector = if (placed == 0) {
                        Icons.Filled.TouchApp
                    } else {
                        Icons.Filled.CheckCircle
                    },
                    contentDescription = null,
                    tint = if (placed == 0) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(16.dp),
                )
                val scanningLabel = stringResource(R.string.ar_status_scanning)
                val tapToPlaceLabel = stringResource(R.string.ar_status_tap_to_place, selectedModel.name)
                val onePlacedLabel = stringResource(R.string.ar_status_one_placed)
                val nPlacedLabel = stringResource(R.string.ar_status_n_placed, placed)
                Text(
                    // State machine (#2234):
                    //   1. Camera not tracking yet OR tracking failure → "Scanning…"
                    //      (or specific failure reason).
                    //   2. Camera tracking but no plane has reached TRACKING yet →
                    //      keep "Scanning…" — the user has nothing to tap on yet,
                    //      and the "Tap a surface" prompt would be a lie.
                    //   3. At least one plane tracked, nothing placed → "Tap a
                    //      surface to place {Model}".
                    //   4. ≥ 1 placed → "N placed · tap to add".
                    text = when {
                        !isTracking -> trackingFailureReason?.let { friendly(it) }
                            ?: scanningLabel
                        placed > 0 -> if (placed == 1) onePlacedLabel else nPlacedLabel
                        anyPlaneTracked -> tapToPlaceLabel
                        else -> scanningLabel
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // Bottom floating action bar.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // FAB "Pick model" — primary, fills available width.
                ExtendedFloatingActionButton(
                    onClick = { showModelPicker = true },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    expanded = true,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.ViewInAr,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = stringResource(R.string.ar_picker_model_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                    alpha = 0.7f,
                                ),
                            )
                            Text(
                                text = selectedModel.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    },
                )

                // Reset.
                FilledIconButton(
                    onClick = {
                        placedAnchors.forEach { runCatching { it.anchor.detach() } }
                        placedAnchors.clear()
                        arSceneId = UUID.randomUUID()
                    },
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.ar_reset_scene),
                    )
                }

                // Screenshot — share-stub for now (live capture is out of scope
                // for this UI refactor; ARSceneView GL readback requires
                // Filament SwapChain plumbing).
                val screenshotToast = stringResource(R.string.ar_screenshot_toast)
                FilledIconButton(
                    onClick = {
                        android.widget.Toast.makeText(
                            context,
                            screenshotToast,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.ar_share_screenshot),
                    )
                }
            }
        }
    }

    if (showModelPicker) {
        ModalBottomSheet(
            onDismissRequest = { showModelPicker = false },
            sheetState = sheetState,
        ) {
            ModelPickerGrid(
                models = arModels,
                selectedIndex = selectedModelIndex,
                onSelect = { idx ->
                    selectedModelIndex = idx
                    coroutineScope.launch {
                        sheetState.hide()
                        showModelPicker = false
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelPickerGrid(
    models: List<ArModel>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.ar_pick_a_model),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
        ) {
            items(models.size) { index ->
                val model = models[index]
                val selected = index == selectedIndex
                Card(
                    onClick = { onSelect(index) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                    modifier = Modifier
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(18.dp),
                        ),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(
                                        alpha = 0.6f,
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ViewInAr,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Launcher screen shown when the AR tab opens. Gates the heavy ARCore +
 * Filament init behind an explicit "Start AR Camera" tap and surfaces a row
 * of the six headline AR demos so users have something to interact with even
 * when ARCore isn't installed on their device (each demo handles its own
 * install prompt independently of the live tab).
 *
 * Visual reference: Polycam launcher + Reality Composer entry screen.
 */
@Composable
private fun ArLauncherScreen(
    availability: ArCoreApk.Availability?,
    cameraGranted: Boolean,
    onRequestCamera: () -> Unit,
    onStartArSession: () -> Unit,
    onArDemoClick: (String) -> Unit,
) {
    val isChecking = availability == null ||
        availability == ArCoreApk.Availability.UNKNOWN_CHECKING
    val arSupported = availability == ArCoreApk.Availability.SUPPORTED_INSTALLED ||
        availability == ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ||
        availability == ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD

    val statusMessage = when (availability) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> stringResource(R.string.ar_status_ready)
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
            stringResource(R.string.ar_status_not_installed)
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD ->
            stringResource(R.string.ar_status_apk_old)
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
            stringResource(R.string.ar_status_unsupported)
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        ArCoreApk.Availability.UNKNOWN_ERROR ->
            stringResource(R.string.ar_status_unknown_error)
        ArCoreApk.Availability.UNKNOWN_CHECKING, null ->
            stringResource(R.string.ar_status_checking)
    }

    val ctaLabel = when {
        isChecking -> stringResource(R.string.ar_cta_checking)
        !cameraGranted -> stringResource(R.string.ar_cta_grant_camera)
        else -> stringResource(R.string.ar_cta_start_camera)
    }

    // CTA enabled only when ARCore is positively supported. UNKNOWN_* and
    // UNSUPPORTED_DEVICE_NOT_CAPABLE leave the CTA disabled so we never
    // re-enter the libfilament panic path that motivated the launcher in
    // the first place ("try anyway" sounded helpful but landed users back
    // in the SIGABRT). UNSUPPORTED also hides the button entirely below.
    val ctaEnabled = !isChecking && arSupported
    val showCta = availability != ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE

    // Hide the floating feedback chip at rest and reveal it on scroll, so it
    // never masks a card resting in its fixed bottom-left band — #2358. (The
    // chip is already fully hidden during a live AR session via chipVisible.)
    val scroll = rememberScrollState()
    DriveFeedbackChipReveal(scroll)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            // Bottom padding leaves a gutter for the floating feedback FAB so
            // it does not mask the last row of the AR demo grid (#2194).
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 12.dp,
                bottom = FEEDBACK_FAB_RESERVED_SPACE,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Compact hero — icon + title on one line, tagline below. Cards
        // get the screen real estate, not chrome.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.70f),
                            ),
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.ViewInAr,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.ar_experiences_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ar_experiences_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }
        }

        // Status line + CTA
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (arSupported || isChecking) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (arSupported) {
                                Icons.Filled.CheckCircle
                            } else {
                                Icons.Filled.Close
                            },
                            contentDescription = null,
                            tint = if (arSupported) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (showCta) {
                    Button(
                        onClick = {
                            if (!cameraGranted) {
                                onRequestCamera()
                            } else {
                                onStartArSession()
                            }
                        },
                        enabled = ctaEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.ViewInAr,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(ctaLabel, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        // Featured section title — kept under the existing `ar_try_an_ar_demo`
        // string (translated as "Featured" in en, "Mises en avant" in fr, …)
        // because the legacy callers / a11y bots key off it.
        Text(
            text = stringResource(R.string.ar_featured_section),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )

        // Featured grid — the curator's pick (FEATURED_AR_DEMOS, 6 cards).
        // Mirrors the Samples-tab `DemoCard` pattern (gradient icon header
        // on top + title + subtitle below) so the AR View tab feels like
        // the same app when the user switches tabs (#1185).
        val featured = remember { FEATURED_AR_DEMOS }
        val featuredIds = remember(featured) { featured.map { it.id }.toSet() }
        val dark = isSystemInDarkTheme()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            featured.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { demo ->
                        ArDemoCard(
                            title = stringResource(demo.titleRes),
                            subtitle = stringResource(demo.subtitleRes),
                            icon = demo.icon,
                            dark = dark,
                            onClick = { onArDemoClick(demo.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // All AR demos — every entry in ALL_DEMOS with category
        // AUGMENTED_REALITY, minus the ones already shown in Featured. Pre-#2231
        // these were reachable only via the Samples tab → half the AR feature
        // surface was hidden on this screen. Each DemoEntry already carries
        // titleRes / subtitleRes / icon, so the same ArDemoCard renders them.
        val remainingArDemos = remember(featuredIds) {
            ALL_DEMOS
                .filter { it.category == DemoCategory.AUGMENTED_REALITY }
                .filterNot { it.id in featuredIds }
        }
        if (remainingArDemos.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.ar_all_demos_section,
                    remainingArDemos.size + featured.size,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                remainingArDemos.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { demo ->
                            ArDemoCard(
                                title = stringResource(demo.titleRes),
                                subtitle = stringResource(demo.subtitleRes),
                                icon = demo.icon,
                                dark = dark,
                                onClick = { onArDemoClick(demo.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Mirrors `DemoListScreen.kt`'s `DemoCard` — gradient-tinted icon header
 * on top + title + subtitle below — using the "Augmented Reality" category
 * green accent so the launcher's demo cards feel like the same component
 * as the Samples-tab grid (#1185).
 */
@Composable
private fun ArDemoCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    dark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Match the Samples-tab "Augmented Reality" accent verbatim so the two
    // grids look like a single app surface. Keep these in sync with
    // `DemoListScreen.categoryAccent*` if either palette ever changes.
    val accent = if (dark) Color(0xFFA5D6A7) else Color(0xFF66BB6A)

    Surface(
        modifier = modifier
            .heightIn(min = 168.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.32f),
                                accent.copy(alpha = 0.14f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

private data class FeaturedArDemo(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val icon: ImageVector,
)

// Per-demo icons differentiate the AR-View grid tiles at a glance — pre-#2195
// every tile rendered the same generic `Icons.Filled.ViewInAr` and the user
// could not tell them apart. Choices mirror the semantic intent rather than
// the demo's literal subject so a quick visual scan reads "place / face /
// cloud / city / layers / pose" without needing to read the title.
private val FEATURED_AR_DEMOS = listOf(
    FeaturedArDemo(
        id = "ar-placement",
        titleRes = R.string.featured_ar_placement_title,
        subtitleRes = R.string.featured_ar_placement_subtitle,
        icon = Icons.Filled.AddLocationAlt,
    ),
    FeaturedArDemo(
        id = "ar-face",
        titleRes = R.string.featured_ar_face_title,
        subtitleRes = R.string.featured_ar_face_subtitle,
        icon = Icons.Filled.Face,
    ),
    FeaturedArDemo(
        id = "ar-cloud-anchor",
        titleRes = R.string.featured_ar_cloud_anchor_title,
        subtitleRes = R.string.featured_ar_cloud_anchor_subtitle,
        icon = Icons.Filled.Cloud,
    ),
    FeaturedArDemo(
        id = "ar-streetscape",
        titleRes = R.string.featured_ar_streetscape_title,
        subtitleRes = R.string.featured_ar_streetscape_subtitle,
        icon = Icons.Filled.LocationCity,
    ),
    FeaturedArDemo(
        id = "ar-depth-occlusion",
        titleRes = R.string.featured_ar_depth_occlusion_title,
        subtitleRes = R.string.featured_ar_depth_occlusion_subtitle,
        icon = Icons.Filled.Layers,
    ),
    FeaturedArDemo(
        id = "ar-pose",
        titleRes = R.string.featured_ar_pose_title,
        subtitleRes = R.string.featured_ar_pose_subtitle,
        icon = Icons.Filled.SelfImprovement,
    ),
)

@Composable
private fun ArPermissionPlaceholder(granted: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Cached,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (granted) {
                    stringResource(R.string.ar_starting_session)
                } else {
                    stringResource(R.string.ar_permission_required_title)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (granted) {
                    stringResource(R.string.ar_starting_session_subtitle)
                } else {
                    stringResource(R.string.ar_permission_required_subtitle)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun friendly(reason: TrackingFailureReason): String = when (reason) {
    TrackingFailureReason.NONE -> stringResource(R.string.ar_status_scanning)
    TrackingFailureReason.BAD_STATE -> stringResource(R.string.ar_tracking_bad_state)
    TrackingFailureReason.INSUFFICIENT_LIGHT -> stringResource(R.string.ar_tracking_insufficient_light)
    TrackingFailureReason.EXCESSIVE_MOTION -> stringResource(R.string.ar_tracking_excessive_motion)
    TrackingFailureReason.INSUFFICIENT_FEATURES ->
        stringResource(R.string.ar_tracking_insufficient_features)
    TrackingFailureReason.CAMERA_UNAVAILABLE -> stringResource(R.string.ar_tracking_camera_unavailable)
}

// ---------- Model catalogue ----------

internal data class ArModel(
    val name: String,
    val assetPath: String,
    val scale: Float,
)

internal data class PlacedAr(
    val id: Int,
    val anchor: com.google.ar.core.Anchor,
    val assetPath: String,
    val scale: Float,
)

// `animated_dragon.glb` dropped from the bundle in #1152 Stage 3 (slim-down).
// 8 MB GLB → replaced where canonical with `threejs_soldier.glb`, removed
// from this list (Soldier covers the "animated character" role).
private val AR_MODELS = listOf(
    ArModel("Damaged Helmet", "models/khronos_damaged_helmet.glb", 0.3f),
    ArModel("Fox", "models/khronos_fox.glb", 0.3f),
    ArModel("Lantern", "models/khronos_lantern.glb", 0.3f),
    ArModel("Toy Car", "models/khronos_toy_car.glb", 0.3f),
    ArModel("Shiba", "models/shiba.glb", 0.3f),
    ArModel("Soldier", "models/threejs_soldier.glb", 0.3f),
)
