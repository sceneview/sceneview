@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.sceneview.haptic.SceneViewHaptic
import io.github.sceneview.haptic.rememberHapticFeedback
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Asset source state surfaced in the per-demo indicator chip (#1152 Stage 3).
 *
 * Demos that load models via `SketchfabAssetResolver` expose this state to
 * advertise the offline / streaming / cached origin of the currently visible
 * asset. The chip floats top-end of the scene area and is intentionally
 * compact + low-contrast so it doesn't compete with the 3D content.
 *
 * - [Streamed] — model came from the Sketchfab CDN and is now cached on disk
 *   (LRU). Subsequent launches are instant.
 * - [Streaming] — fetch in progress; the visual is the bundled fallback for
 *   the moment, swapping to the streamed model when [Streamed] is reached.
 * - [Bundled] — no API key or network → showing the offline fallback declared
 *   in the slug registry. Demos still render fully.
 *
 * Demos that never touch the resolver should pass `null` for the chip param so
 * the chip is hidden entirely.
 */
enum class AssetSourceState { Streamed, Streaming, Bundled }

/**
 * Shared scaffold for all demo screens — version 2 (modal bottom sheet).
 *
 * Renders the 3D scene **full-screen** under the top bar, with the user controls
 * tucked behind a ModalBottomSheet — a settings FAB and a peek chip make the
 * controls discoverable without stealing viewport real estate from the showcase.
 *
 * - `controls == null` → no FAB / no sheet, scene fills the entire area below the top bar.
 * - `controls != null` → a `Tune` FAB pinned to the bottom-end opens the controls
 *   sheet. While the sheet is closed, a small peek chip ("Settings") sits above
 *   the FAB to advertise the gesture (see [#951] discoverability lesson).
 * - `assetSource != null` → an "Streamed / Streaming / Offline model" chip
 *   pinned to the top-end of the scene area, advertising the offline origin
 *   of the currently visible asset (#1152 Stage 3). The chip auto-hides when
 *   `null` so legacy demos stay untouched.
 *
 * Gestures:
 * - **Tap FAB / tap peek chip** → opens the sheet at the detent this demo was
 *   last left at — partial for a demo never opened before, or whatever detent
 *   the user last settled it on (#2084, persisted per demo across process
 *   death via [DemoSheetDetentStore]).
 * - **Long-press peek chip** → toggles `DemoSettings.qaMode` (deterministic
 *   captures for screenshot suites). Previously this gesture lived on the
 *   top-app-bar title; moved to the peek chip so the title can carry the demo
 *   name verbatim and the QA toggle is hidden away from the primary FAB tap
 *   target (long-pressing a FAB is a confusing dual-purpose pattern).
 * - **Drag handle / outside tap / back gesture** → dismiss the sheet.
 * - **AR**: opening the sheet does NOT pause the AR session (the sheet sits on
 *   top of the existing scene; AR keeps tracking 6DOF underneath).
 *
 * The sheet content is rendered inside a vertically-scrolling Column so the same
 * `controls = { ... }` blocks that worked with the v1 side-panel work unchanged
 * — 35 demo call-sites stay byte-identical.
 *
 * - `firstFrameRendered != null` → a surface-tinted scrim covers the 3D viewport
 *   until the first Filament frame is presented, hiding the 5–12 s cold-start
 *   black viewport that reads as a crash to users (#1022). Wire it with
 *   [rememberFirstFrameState] + `SceneView(onFrame = …)`. A defensive 12 s
 *   timeout dismisses the scrim even if `onFrame` never fires. Demos that load
 *   models can still layer their own [LoadingScrim] spinner on top.
 *
 *   All 3D (non-AR) demos wire `firstFrameRendered`. The AR demos
 *   (`ARSceneView`-based: `AR*Demo` + `OrbitalARDemo`) **intentionally skip it**
 *   — their viewport is the live camera feed, which appears instantly with no
 *   cold-start black frame, and they already gate behind their own ARCore
 *   permission / availability screens. Passing `firstFrameRendered = null`
 *   from an AR demo is correct, not an oversight (#1361).
 *
 * Stage 3 polish (#1154):
 * - `peekHeader` → a short status string (e.g. `"3 anchors placed"`) rendered on
 *   the closed peek chip in place of the generic "Settings" label. Mirrors the
 *   top-center status pills without stealing more viewport. `null` falls back to
 *   the plain "Settings" label.
 * - `onResetSettings != null` → a "Reset" text button is shown in the sheet
 *   header. Tapping it lets a demo clear any in-memory tweaks back to its
 *   defaults. `null` hides the button entirely so demos opt in.
 *
 * Predictable demo reset (#1966):
 * - `onReset != null` → a single, **consistent** reset action is rendered in the
 *   top app bar (a `Refresh` icon, always in the same place across every demo).
 *   Tapping it returns the demo to its initial state and re-arms its core
 *   interaction (clear placed anchors, drop highlights, re-centre the camera,
 *   etc.). A brief confirmation snackbar ("Demo reset — ready to try again")
 *   then tells the user the demo is ready for re-interaction. `null` hides the
 *   action entirely so demos opt in.
 *
 *   This complements (does not replace) [io.github.sceneview.demo.common.SceneActionBar]:
 *   a demo whose reset is *contextual* (only meaningful once something is placed)
 *   can still use the bottom-start action bar, while `onReset` gives every demo
 *   one always-available, always-discoverable reset path in a fixed location —
 *   the predictable re-interaction path #1966 asks for.
 */
@Composable
fun DemoScaffold(
    title: String,
    onBack: () -> Unit,
    controls: (@Composable ColumnScope.() -> Unit)? = null,
    assetSource: AssetSourceState? = null,
    firstFrameRendered: androidx.compose.runtime.State<Boolean>? = null,
    peekHeader: String? = null,
    onResetSettings: (() -> Unit)? = null,
    onReset: (() -> Unit)? = null,
    scene: @Composable BoxScope.() -> Unit
) {
    val haptic = rememberHapticFeedback()
    val snackbarHostState = remember { SnackbarHostState() }
    val resetScope = rememberCoroutineScope()
    val resetConfirmation = stringResource(R.string.demo_reset_done)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row {
                        Text(title)
                        if (DemoSettings.qaMode) {
                            // Tappable QA pill: tap to disable, so a user who
                            // long-pressed the peek chip by accident has a
                            // single-tap escape hatch instead of having to
                            // guess that another long-press toggles it back
                            // off. See #951.
                            Text(
                                text = " QA ×",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        haptic.medium()
                                        DemoSettings.qaMode = false
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag(DemoScaffoldTestTags.QA_PILL),
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back_button)
                        )
                    }
                },
                actions = {
                    // In-app feedback entry point — #1930 requires the feedback
                    // button "on the 4 tabs AND inside every demo", and a bug
                    // hit inside a demo is the highest-value feedback case. A
                    // top-app-bar action is the right slot: a FAB would collide
                    // with the demo controls `Tune` FAB at the bottom-end.
                    // Tapping it raises FeedbackOpenRequest, which
                    // SceneViewDemoApp observes to open the shared bug-report
                    // sheet (BugReportSheet).
                    val feedbackCd = stringResource(R.string.feedback_action_cd)
                    IconButton(
                        onClick = {
                            haptic.selection()
                            io.github.sceneview.demo.feedback.FeedbackOpenRequest.request()
                        },
                        modifier = Modifier
                            .semantics { contentDescription = feedbackCd }
                            .testTag(DemoScaffoldTestTags.FEEDBACK_ACTION),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Feedback,
                            contentDescription = null,
                        )
                    }
                    // Predictable, always-in-the-same-place reset action (#1966).
                    // Every demo that opts in surfaces this single Refresh icon
                    // in the top bar, so users have one consistent path back to
                    // a demo's initial state regardless of which demo they are
                    // in. A brief snackbar then confirms the demo is re-armed.
                    if (onReset != null) {
                        val resetCd = stringResource(R.string.demo_reset_cd)
                        IconButton(
                            onClick = {
                                haptic.medium()
                                onReset()
                                resetScope.launch {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(
                                        message = resetConfirmation,
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                            },
                            modifier = Modifier
                                .semantics { contentDescription = resetCd }
                                .testTag(DemoScaffoldTestTags.RESET_ACTION),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        // Scene always full-screen below the top app bar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize(), content = scene)

            if (firstFrameRendered != null) {
                FirstFrameScrim(firstFrameRendered = firstFrameRendered)
            }

            if (assetSource != null) {
                AssetSourceChip(state = assetSource)
            }

            if (controls != null) {
                DemoSettingsLayer(
                    demoTitle = title,
                    controlsContent = controls,
                    haptic = haptic,
                    peekHeader = peekHeader,
                    onResetSettings = onResetSettings,
                )
            }
        }
    }
}

/**
 * Compact chip surfacing the [AssetSourceState] of the demo's currently
 * visible asset. Pinned to the top-end of the scene area below the system
 * bars so it doesn't crash into the controls FAB at the bottom-end.
 */
@Composable
private fun BoxScope.AssetSourceChip(state: AssetSourceState) {
    val (label, tint) = when (state) {
        AssetSourceState.Streamed -> stringResource(R.string.demo_chip_streamed) to
            MaterialTheme.colorScheme.tertiary
        AssetSourceState.Streaming -> stringResource(R.string.demo_chip_streaming) to
            MaterialTheme.colorScheme.primary
        AssetSourceState.Bundled -> stringResource(R.string.demo_chip_bundled) to
            MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(DemoScaffoldTestTags.ASSET_SOURCE_CHIP)
            .semantics { contentDescription = "Asset source: $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(tint),
        )
        Text(
            text = " $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Surface-tinted scrim that covers the 3D viewport until the SceneView presents
 * its first Filament frame (#1022). Without it, a 3D demo renders a black
 * viewport for 5–12 s on a cold start while shaders compile — which reads as a
 * crash to a first-time user. All 3D demos wire it; AR demos skip it (#1361).
 *
 * The scrim is an opaque [MaterialTheme.colorScheme.surface] fill (light + dark
 * both covered by the theme token) carrying a small centred progress indicator.
 * Once [firstFrameRendered] flips true it cross-fades out over 350 ms (the
 * `DESIGN.md` `duration-medium` token), revealing the rendered scene underneath.
 *
 * Defensive timeout: if `onFrame` never fires (a broken demo, a viewport that
 * never composes a SceneView) the scrim still dismisses after 12 s so the
 * controls are never permanently blocked.
 */
@Composable
private fun BoxScope.FirstFrameScrim(
    firstFrameRendered: androidx.compose.runtime.State<Boolean>,
) {
    // Defensive fallback: dismiss even if the first frame is never reported.
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(FIRST_FRAME_SCRIM_TIMEOUT_MS)
        timedOut = true
    }
    val dismissed = firstFrameRendered.value || timedOut
    // Cross-fade out on the M3 `duration-medium` (350 ms) — the surface fades to
    // reveal the rendered scene, never the reverse, so it can't imply readiness
    // and then flash black again (see #1022 rejected-timeout note).
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (dismissed) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 350),
        label = "first-frame-scrim",
    )
    if (alpha <= 0f) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surface)
            // Swallow touches while the scrim is opaque so a stray tap can't
            // reach the (not-yet-rendered) scene; lets them through once fading.
            .then(
                if (dismissed) Modifier
                else Modifier.pointerInput(Unit) { detectTapGestures { } }
            )
            .testTag(DemoScaffoldTestTags.FIRST_FRAME_SCRIM),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 4.dp,
        )
    }
}

private const val FIRST_FRAME_SCRIM_TIMEOUT_MS = 12_000L

/**
 * Stable test tags consumed by `DemoInteractionTest` and any future visual smoke
 * tooling so the controls sheet can be opened deterministically without relying
 * on accessibility-tree heuristics. Kept in the public surface of this file so
 * tests can `import io.github.sceneview.demo.DemoScaffoldTestTags`.
 */
object DemoScaffoldTestTags {
    const val SETTINGS_FAB = "demo-settings-fab"
    const val SETTINGS_PEEK = "demo-settings-peek"
    const val SETTINGS_SHEET = "demo-settings-sheet"
    const val SETTINGS_RESET = "demo-settings-reset"
    const val RESET_ACTION = "demo-reset-action"
    const val FEEDBACK_ACTION = "demo-feedback-action"
    const val QA_PILL = "demo-qa-pill"
    const val ASSET_SOURCE_CHIP = "demo-asset-source-chip"
    const val FIRST_FRAME_SCRIM = "demo-first-frame-scrim"
}

/**
 * Peek chip + FAB + ModalBottomSheet — pulled into its own composable so the
 * sheet `LaunchedEffect`s scope to the `expanded` state without re-running on
 * every recomposition of the parent Scaffold body.
 *
 * [demoTitle] keys the per-demo last-detent memory (#2084): when the sheet is
 * (re-)created it opens at the detent the user last settled it at for *this*
 * demo, persisted in [DemoSheetDetentStore] so it survives navigation away from
 * the demo and full process death — a demo never seen before defaults to the
 * partial detent (unchanged behaviour).
 */
@Composable
private fun BoxScope.DemoSettingsLayer(
    demoTitle: String,
    controlsContent: @Composable ColumnScope.() -> Unit,
    haptic: SceneViewHaptic,
    peekHeader: String? = null,
    onResetSettings: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Per-demo last-detent memory (#2084): read the persisted detent so the
    // sheet can reopen where the user last left it for this demo. Resolved
    // against the persistent settings store ([DemoSheetDetentStore]), so it
    // survives navigating away from the demo and process death — not just
    // config changes. A demo never opened before resolves to
    // `PartiallyExpanded`, the unchanged default.
    val restoredDetent: SheetValue = remember(demoTitle) {
        DemoSheetDetentStore.lastDetent(context, demoTitle)
    }
    val sheetState: SheetState = rememberModalBottomSheetState(
        // partially expanded is the default open state — keeps ~45 % of viewport
        // visible so the showcase stays alive while you tweak settings.
        skipPartiallyExpanded = false,
    )
    val scope = rememberCoroutineScope()
    val openSettingsCd = stringResource(R.string.demo_settings_open)
    val fabCd = stringResource(R.string.demo_settings_fab_cd)

    // FAB + peek chip pinned to the bottom-end of the scene area.
    Column(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Peek chip — only shown while the sheet is closed. Tap opens the sheet
        // at this demo's last-used detent (#2084, same as tapping the FAB).
        // Long-press toggles
        // QA mode (deterministic captures for screenshot suites — previously
        // on the top-bar title). The chip is intentionally semi-transparent so
        // it disappears against busy 3D scenes but stays legible on plain
        // backgrounds.
        if (!expanded) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                haptic.selection()
                                expanded = true
                            },
                            onLongPress = {
                                haptic.medium()
                                DemoSettings.qaMode = !DemoSettings.qaMode
                            },
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics {
                        contentDescription = openSettingsCd
                    }
                    .testTag(DemoScaffoldTestTags.SETTINGS_PEEK),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // Stage 3: surface a short demo status (e.g. "3 anchors
                    // placed") on the closed chip when the demo provides one,
                    // else fall back to the generic "Settings" label.
                    text = " " + (peekHeader ?: stringResource(R.string.demo_settings_title)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Standard M3 FAB — single short tap opens the sheet. Long-press lives
        // on the peek chip below (visible, discoverable, no hidden gesture on
        // a clickable element). Wrapping the FAB in `Modifier.combinedClickable`
        // collapsed its hit-target to ~24 dp on Compose Material3 1.5.x.
        FloatingActionButton(
            onClick = {
                haptic.selection()
                expanded = true
            },
            shape = CircleShape,
            modifier = Modifier
                .semantics {
                    contentDescription = fabCd
                }
                .testTag(DemoScaffoldTestTags.SETTINGS_FAB),
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = null,
            )
        }
    }

    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = {
                scope.launch {
                    sheetState.hide()
                    expanded = false
                }
            },
            sheetState = sheetState,
            modifier = Modifier.testTag(DemoScaffoldTestTags.SETTINGS_SHEET),
        ) {
            // Header row pinned above the scrolling controls — carries the
            // sheet title and, when the demo opts in, a "Reset" text button
            // that clears any in-memory tweaks back to the demo's defaults
            // (#1154 Stage 3). The header sits OUTSIDE the verticalScroll so a
            // long controls list never scrolls the Reset affordance offscreen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.demo_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (onResetSettings != null) {
                    val resetCd = stringResource(R.string.demo_settings_reset_cd)
                    TextButton(
                        onClick = {
                            haptic.selection()
                            onResetSettings()
                        },
                        modifier = Modifier
                            .semantics { contentDescription = resetCd }
                            .testTag(DemoScaffoldTestTags.SETTINGS_RESET),
                    ) {
                        Text(stringResource(R.string.demo_settings_reset))
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                content = controlsContent,
            )
        }

        // Medium-tick haptic when the user moves between detents.
        //
        // The `SheetState` starts at `SheetValue.Hidden` (the sheet animates
        // open from hidden to its restored #2084 detent), so this effect fires
        // once with `Hidden` *before* the sheet has animated open.
        // Treating that initial value as a dismiss would slam `expanded = false`
        // and kill the sheet on every demo (#1420). Only honour `Hidden` as a
        // dismiss after the sheet has actually settled in a shown detent at
        // least once — the actual outside-tap / back-gesture dismiss is already
        // handled by `onDismissRequest`, this branch just keeps `expanded` in
        // sync if the sheet collapses by any other path.
        var hasShown by remember { mutableStateOf(false) }

        // #2084: restore this demo's last-used detent. `rememberModalBottomSheetState`
        // always animates open to `PartiallyExpanded`, so when the persisted
        // detent is `Expanded` we expand the sheet the rest of the way as a
        // one-shot, right after it first settles in a shown detent. Keyed to
        // `expanded` so it re-arms each time the sheet is (re-)opened. A
        // never-seen demo resolves to `PartiallyExpanded` → this is a no-op and
        // the unchanged default behaviour holds.
        LaunchedEffect(expanded) {
            if (restoredDetent == SheetValue.Expanded) {
                snapshotFlow { sheetState.currentValue }
                    .filter { it != SheetValue.Hidden }
                    .first()
                if (sheetState.currentValue != SheetValue.Expanded) {
                    sheetState.expand()
                }
            }
        }

        LaunchedEffect(sheetState.currentValue) {
            when (sheetState.currentValue) {
                SheetValue.Expanded,
                SheetValue.PartiallyExpanded -> {
                    hasShown = true
                    haptic.selection()
                    // #2084: persist the detent the user just settled on so this
                    // demo's sheet reopens here next time — including after the
                    // demo screen leaves composition or the process is killed.
                    DemoSheetDetentStore.setLastDetent(
                        context,
                        demoTitle,
                        sheetState.currentValue,
                    )
                }
                SheetValue.Hidden -> {
                    if (hasShown) {
                        // Subtle tick on drag-down-to-dismiss so the gesture
                        // gets the same tactile confirmation as a detent
                        // change (#1154 Stage 3).
                        haptic.selection()
                        expanded = false
                    }
                }
            }
        }
    }
}
