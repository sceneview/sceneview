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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.derivedStateOf
import io.github.sceneview.demo.ui.stage.ArHeroStage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import io.github.sceneview.demo.common.placement.BUNDLED_PLACEMENT_MODELS
import io.github.sceneview.demo.common.placement.TapToPlaceExperience
import io.github.sceneview.demo.common.placement.rememberPlacementPickerState
import io.github.sceneview.demo.common.placement.rememberTapToPlaceState
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.freshness
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.home.DemoMediaCard
import io.github.sceneview.demo.ui.home.FEATURED_MEDIA_ALIGNMENT
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.isArDemo
import io.github.sceneview.demo.R
import io.github.sceneview.demo.ui.LIST_BOTTOM_GUTTER
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
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
 * placement screen, shared verbatim with the `ar-placement` demo (#2482).
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

    // The shared placement session holder (#2482, PR 3/4). Hoisted here so
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
        // A fresh holder for the next *Start AR Camera*: the state is keyed on
        // `arSceneId`, not on `sessionStarted`, so without this bump the next
        // session would inherit the dismissed controller.
        arSceneId = UUID.randomUUID()
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

    // The one canonical placement experience (#2482) — the same composable the
    // `ar-placement` demo renders, inside the same chrome. [TapToPlaceExperience] used to
    // float its own back disc and model bar over the camera; both are gone, so this tab
    // and the demo now share one back arrow, one dock (Models · Reset · Settings) and one
    // picker sheet, and this tab holds no placement UI of its own.
    //
    // The scaffold sits *outside* `key(arSceneId)`: a Reset must recreate the ARCore
    // session, not the chrome around it. `RootScreen` has already hidden the tab bar and
    // the system bars for the live session (#2238), so the dock has the bottom band to
    // itself here exactly as it does in the demo.
    DemoScaffold(
        title = stringResource(R.string.tab_ar_view),
        onBack = exitArSession,
        // Hard reset, from the Settings sheet: bumping the UUID recomposes the whole AR
        // subtree, which is the only way to discard ARCore state without a wrapper-level
        // resetSession() API (iOS does the same via arViewID).
        onReset = {
            state.clearAll()
            arSceneId = UUID.randomUUID()
        },
        // Same two items, same order, same words as the `ar-placement` demo — the
        // scaffold appends Settings itself.
        dock = listOf(
            DockItem(
                icon = Icons.Filled.ViewInAr,
                label = stringResource(R.string.ar_dock_models_label),
                caption = stringResource(R.string.ar_dock_models_caption),
                onClick = picker::openSheet,
            ),
            // §2.2 *Restarting placement*: removes the anchor, keeps the chosen asset,
            // scans again.
            DockItem(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.ar_dock_reset_label),
                caption = stringResource(R.string.ar_dock_reset_caption),
                onClick = { state.resetPlacement() },
                enabled = state.placedCount > 0,
            ),
        ),
    ) {
        key(arSceneId) {
            TapToPlaceExperience(
                models = BUNDLED_PLACEMENT_MODELS,
                picker = picker,
                state = state,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                // "View in 3D" on the no-surface card: leave the camera for the launcher,
                // whose featured tiles open the 3D demos.
                onViewIn3D = exitArSession,
                // "Try again" on the camera-error card: the same hard reset as Settings.
                onRestartSession = {
                    state.clearAll()
                    arSceneId = UUID.randomUUID()
                },
            )
        }
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
    val heroBottomPx = with(LocalDensity.current) {
        (AR_HERO_TOP_GAP + SceneViewTokens.ArHero.height).roundToPx()
    }
    // The hero's 3D only runs while some of it is on screen.
    val heroOnScreen by remember(scroll, heroBottomPx) {
        derivedStateOf { scroll.value < heroBottomPx }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(
                start = 20.dp,
                end = 20.dp,
                top = AR_HERO_TOP_GAP,
                bottom = LIST_BOTTOM_GUTTER,
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The hero (#wow): AR playing on its own before the camera is ever opened — a
        // detected floor, a reticle, bundled models placed on it — with the title, the
        // device's AR status and the one call to action drawn over the stage.
        ArHero(
            active = heroOnScreen,
            statusMessage = statusMessage,
            isChecking = isChecking,
            arSupported = arSupported,
            showCta = showCta,
            ctaEnabled = ctaEnabled,
            ctaLabel = ctaLabel,
            onCta = {
                if (!cameraGranted) {
                    onRequestCamera()
                } else {
                    onStartArSession()
                }
            },
        )

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

        // Featured grid — the curator's pick (FEATURED_AR_DEMOS, 6 cards). Each card is
        // the Home's own `DemoMediaCard`: the demo's preview picture with its caption on
        // frosted glass, not a category icon on a gradient band, so the AR tab reads as
        // the same app as the Showcase and Explore (#3993). The featured tiles keep their
        // curated title and subtitle over the registry entry's picture and status.
        val featured = remember { FEATURED_AR_DEMOS.toDemoEntries() }
        val featuredIds = remember(featured) { featured.map { it.id }.toSet() }
        ArDemoGrid(demos = featured, onArDemoClick = onArDemoClick)

        // All AR demos — every AR entry in ALL_DEMOS, minus the ones already shown
        // in Featured. Pre-#2231 these were reachable only via the Samples tab →
        // half the AR feature surface was hidden on this screen. Since #2239 split AR
        // across four catalogue sections the test is [isArDemo], not one category
        // equality.
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
            ArDemoGrid(demos = remainingArDemos, onArDemoClick = onArDemoClick)
        }

        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Two columns of `DemoMediaCard` — the Home's catalogue card, preview picture on top and
 * caption on the picture's own frosted glass — with the [SceneViewTokens.Home.gridGutter]
 * gutter. A row's two captions share one floor (`rowPeers`), so the cards of a row end level
 * whatever their subtitles' length. A card carries the same "New" / "Updated" marker as on
 * the Home, from the same [freshness] rule.
 */
@Composable
private fun ArDemoGrid(demos: List<DemoEntry>, onArDemoClick: (String) -> Unit) {
    val gutter = SceneViewTokens.Home.gridGutter
    Column(verticalArrangement = Arrangement.spacedBy(gutter)) {
        demos.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gutter)) {
                row.forEach { demo ->
                    DemoMediaCard(
                        demo = demo,
                        onClick = { onArDemoClick(demo.id) },
                        modifier = Modifier.weight(1f),
                        freshness = demo.freshness(BuildConfig.VERSION_NAME),
                        mediaAlignment = FEATURED_MEDIA_ALIGNMENT[demo.id] ?: Alignment.Center,
                        rowPeers = { row },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private data class FeaturedArDemo(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
)

/**
 * The registry entries behind the featured tiles, each under its curated title and subtitle.
 * A featured id with no registry entry is dropped rather than drawn as a card with no demo
 * behind it.
 */
private fun List<FeaturedArDemo>.toDemoEntries(): List<DemoEntry> = mapNotNull { featured ->
    ALL_DEMOS.firstOrNull { it.id == featured.id }
        ?.copy(titleRes = featured.titleRes, subtitleRes = featured.subtitleRes)
}

// The curator's pick for the top of the AR tab. Each tile shows its demo's preview
// picture (`DemoPreviews`); pre-#2195 every tile was the same `ViewInAr` glyph, then one
// Material icon per demo until the tab took the Home's picture cards.
private val FEATURED_AR_DEMOS = listOf(
    FeaturedArDemo(
        id = "ar-placement",
        titleRes = R.string.featured_ar_placement_title,
        subtitleRes = R.string.featured_ar_placement_subtitle,
    ),
    FeaturedArDemo(
        id = "ar-face",
        titleRes = R.string.featured_ar_face_title,
        subtitleRes = R.string.featured_ar_face_subtitle,
    ),
    FeaturedArDemo(
        id = "ar-cloud-anchor",
        titleRes = R.string.featured_ar_cloud_anchor_title,
        subtitleRes = R.string.featured_ar_cloud_anchor_subtitle,
    ),
    // #3463 — `ar-streetscape` became the second mode of the Scene Geometry card. The
    // featured tile names the live id, not the retired one: the "All AR demos" grid below
    // filters on ALL_DEMOS minus the featured ids, so a retired id here would have shown
    // the same demo twice under two different names.
    FeaturedArDemo(
        id = "ar-scene-mesh",
        titleRes = R.string.featured_ar_scene_geometry_title,
        subtitleRes = R.string.featured_ar_scene_geometry_subtitle,
    ),
    FeaturedArDemo(
        id = "ar-depth-occlusion",
        titleRes = R.string.featured_ar_depth_occlusion_title,
        subtitleRes = R.string.featured_ar_depth_occlusion_subtitle,
    ),
    FeaturedArDemo(
        id = "ar-pose",
        titleRes = R.string.featured_ar_pose_title,
        subtitleRes = R.string.featured_ar_pose_subtitle,
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

/** Gap above the AR hero — the launcher column's top padding. */
private val AR_HERO_TOP_GAP = 12.dp

/**
 * The AR tab's hero card: [ArHeroStage] playing behind the title, the device's AR status
 * and the one call to action. Dark in both themes, like the Home hero — the copy is white,
 * the pill is the Home hero's white pill.
 */
@Composable
private fun ArHero(
    active: Boolean,
    statusMessage: String,
    isChecking: Boolean,
    arSupported: Boolean,
    showCta: Boolean,
    ctaEnabled: Boolean,
    ctaLabel: String,
    onCta: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(SceneViewTokens.ArHero.height)
            .clip(RoundedCornerShape(SceneViewTokens.Radius.xl)),
    ) {
        ArHeroStage(active = active, modifier = Modifier.fillMaxSize())
        ViewfinderBrackets(Modifier.fillMaxSize())
        // Copy scrim at the foot of the stage: the status and the pill always read, whatever
        // model is standing behind them.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(SceneViewTokens.ArHero.height / 2)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, SceneViewTokens.ArHero.copyScrim),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(AR_HERO_COPY_WIDTH)
                .padding(
                    start = SceneViewTokens.Space.lg,
                    top = SceneViewTokens.Space.lg,
                ),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.xs),
        ) {
            Text(
                text = stringResource(R.string.ar_experiences_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = SceneViewTokens.HomeColor.heroTitle,
            )
            Text(
                text = stringResource(R.string.ar_experiences_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = SceneViewTokens.HomeColor.heroSubtitle,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm + SceneViewTokens.Space.xs),
        ) {
            Row(
                modifier = Modifier.padding(start = SceneViewTokens.Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(AR_HERO_STATUS_ICON),
                        strokeWidth = 2.dp,
                        color = SceneViewTokens.ArOverlay.accentProgress,
                    )
                } else {
                    Icon(
                        imageVector = if (arSupported) Icons.Filled.CheckCircle else Icons.Filled.Close,
                        contentDescription = null,
                        tint = if (arSupported) {
                            SceneViewTokens.ArOverlay.accentProgress
                        } else {
                            SceneViewTokens.ArOverlay.accentBlocked
                        },
                        modifier = Modifier.size(AR_HERO_STATUS_ICON),
                    )
                }
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SceneViewTokens.HomeColor.heroSubtitle,
                )
            }
            if (showCta) {
                Button(
                    onClick = onCta,
                    enabled = ctaEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SceneViewTokens.HomeColor.heroPillBackground,
                        contentColor = SceneViewTokens.HomeColor.heroPillText,
                        disabledContainerColor = SceneViewTokens.Glass.surface,
                        disabledContentColor = SceneViewTokens.Glass.onGlassMuted,
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
}

/** Share of the hero's width the title block may take: the models stand in the rest. */
private const val AR_HERO_COPY_WIDTH = 0.6f
private val AR_HERO_STATUS_ICON = 18.dp

/** Four viewfinder corners: the stage is a camera view, before the camera is opened. */
@Composable
private fun ViewfinderBrackets(modifier: Modifier) {
    val color = SceneViewTokens.ArHero.bracket
    androidx.compose.foundation.Canvas(modifier) {
        val inset = SceneViewTokens.ArHero.bracketInset.toPx()
        val length = SceneViewTokens.ArHero.bracketLength.toPx()
        val stroke = SceneViewTokens.ArHero.bracketStroke.toPx()
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y), Offset(x + dx * length, y), stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x, y + dy * length), stroke, cap = StrokeCap.Round)
        }
        corner(left, top, 1f, 1f)
        corner(right, top, -1f, 1f)
        corner(left, bottom, 1f, -1f)
        corner(right, bottom, -1f, -1f)
    }
}
