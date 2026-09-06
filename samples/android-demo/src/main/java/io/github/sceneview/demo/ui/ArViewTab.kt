@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)

package io.github.sceneview.demo.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.sceneview.demo.common.placement.BUNDLED_PLACEMENT_MODELS
import io.github.sceneview.demo.common.placement.TapToPlaceExperience
import io.github.sceneview.demo.common.placement.rememberPlacementPickerState
import io.github.sceneview.demo.common.placement.rememberTapToPlaceState
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoCategory
import io.github.sceneview.demo.isArDemo
import io.github.sceneview.demo.R
import io.github.sceneview.demo.ui.LIST_BOTTOM_GUTTER
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.sample.ui.DemoCategoryAccent
import kotlinx.coroutines.delay
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
 * Once the user taps "Start AR Camera" we fall through to
 * [io.github.sceneview.demo.common.placement.TapToPlaceExperience] — the ONE
 * tap-to-place screen, shared verbatim with the `ar-placement` demo (#2482).
 * It brings the whole thing with it: the session engine (centre reticle #1882,
 * texture-settle gating #1435, per-asset rotation correction #1477,
 * PAUSED-surviving anchors, camera-init scrim #2484, unified status vocabulary
 * #2234), the top-start back arrow, the "Model · <name>" bar and the picker
 * sheet — so this file owns **no** placement UI of its own and cannot drift
 * away from the demo a second time.
 *
 * What is still this tab's own job is the *launcher*: the ARCore availability
 * gate, the camera permission dance, the immersive-mode wiring and the AR demo
 * grid. That is the "quick launcher vs feature demo" role split #2482 asked for
 * — a difference in role, not a second implementation of the same screen.
 *
 * Reset is implemented by bumping a `key(arSceneId)` wrapper around the
 * experience — there is no `removeAllAnchors` on the wrapper, so we recompose
 * the whole subtree (and its `TapToPlaceState` holder) to clear ARCore state
 * and start a fresh session. The picker's selection is deliberately hoisted
 * *outside* that key: Reset clears the room, not the user's choice.
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
    // Selection for the canonical picker, hoisted OUTSIDE `key(arSceneId)`: a Reset
    // wipes the placements, not the user's choice of what to place next.
    val picker = rememberPlacementPickerState(BUNDLED_PLACEMENT_MODELS.first().id)

    // Force-rebuild key for the ARSceneView. Bumping this UUID recomposes the
    // whole AR subtree, which is the only way to discard ARCore state without
    // a wrapper-level resetSession() API (iOS does the same via arViewID).
    var arSceneId by remember { mutableStateOf(UUID.randomUUID()) }

    // The shared tap-to-place session holder (#2482, PR 3/4). Hoisted here so
    // `exitArSession` (defined below at an outer scope) can call
    // `state.clearAll()`, while still wrapped in `key(arSceneId)` so a Reset —
    // which bumps `arSceneId` — recreates a fresh holder and drops every placed
    // anchor along with the recomposed ARCore session.
    val state = key(arSceneId) { rememberTapToPlaceState() }

    // ---------- Engine / loaders ----------
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)

    // Shared exit path used by both the system back gesture (BackHandler) and
    // the top-start back arrow. Detaches every ARCore anchor first so the
    // underlying session releases its native refs before the wrapper
    // recomposes away.
    val exitArSession: () -> Unit = {
        state.clearAll()
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

    // The one canonical tap-to-place experience (#2482) — the same composable the
    // `ar-placement` demo renders. It owns the session, the top-start back arrow, the
    // model bar and the picker sheet, so this tab holds no placement UI of its own and
    // cannot drift away from the demo again.
    //
    // `key(arSceneId)` is how Reset works: bumping the UUID recomposes the whole AR
    // subtree, which is the only way to discard ARCore state without a wrapper-level
    // resetSession() API (iOS does the same via arViewID).
    key(arSceneId) {
        TapToPlaceExperience(
            models = BUNDLED_PLACEMENT_MODELS,
            picker = picker,
            state = state,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            onBack = exitArSession,
            onReset = {
                state.clearAll()
                arSceneId = UUID.randomUUID()
            },
        )
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

    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = 12.dp,
                bottom = LIST_BOTTOM_GUTTER,
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

        // All AR demos — every AR entry in ALL_DEMOS, minus the ones already shown
        // in Featured. Pre-#2231 these were reachable only via the Samples tab →
        // half the AR feature surface was hidden on this screen. Each DemoEntry
        // already carries titleRes / subtitleRes / icon, so the same ArDemoCard
        // renders them. Since #2239 split AR across four catalogue sections the
        // test is [isArDemo], not one category equality.
        val remainingArDemos = remember(featuredIds) {
            ALL_DEMOS
                .filter { it.isArDemo }
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
 * on top + title + subtitle below — using the AR category
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
    // The Samples-tab AR accent, read from the shared palette rather than
    // recopied, so the two grids cannot drift into looking like two apps.
    // The AR View tab is one screen about AR as a whole, so it takes the first of
    // the four AR section accents rather than any one section's (#2239).
    val accent = DemoCategoryAccent[DemoCategory.AR_PLACEMENT, dark]

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
    // #3463 — `ar-streetscape` became the second mode of the Scene Geometry card. The
    // featured tile names the live id, not the retired one: the "All AR demos" grid below
    // filters on ALL_DEMOS minus the featured ids, so a retired id here would have shown
    // the same demo twice under two different names.
    FeaturedArDemo(
        id = "ar-scene-mesh",
        titleRes = R.string.featured_ar_scene_geometry_title,
        subtitleRes = R.string.featured_ar_scene_geometry_subtitle,
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
