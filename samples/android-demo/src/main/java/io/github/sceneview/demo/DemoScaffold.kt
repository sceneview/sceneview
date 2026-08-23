@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.sceneview.demo

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.GlassIconButton
import io.github.sceneview.demo.ui.GlassPill
import io.github.sceneview.haptic.SceneViewHaptic
import io.github.sceneview.haptic.rememberHapticFeedback
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Asset source state surfaced as the suffix of the identity pill (#1152 Stage 3).
 *
 * Demos that load models via `SketchfabAssetResolver` expose this state to
 * advertise the offline / streaming / cached origin of the currently visible
 * asset.
 *
 * - [Streamed] — model came from the Sketchfab CDN and is now cached on disk
 *   (LRU). Subsequent launches are instant.
 * - [Streaming] — fetch in progress; the visual is the bundled fallback for
 *   the moment, swapping to the streamed model when [Streamed] is reached.
 * - [Bundled] — no API key or network → showing the offline fallback declared
 *   in the slug registry. Demos still render fully.
 *
 * Demos that never touch the resolver pass `null` so no suffix is shown.
 */
enum class AssetSourceState { Streamed, Streaming, Bundled }

/**
 * One item of the [DemoScaffold] bottom dock.
 *
 * Rendered as a 48 dp icon button inside the floating toolbar; [label] is its
 * content description. [selected] tints the icon with the theme primary so a
 * toggle (wireframe on, grid on) reads as active. At most four items are shown
 * before the auto-appended Controls item and the optional accent.
 */
data class DockItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val selected: Boolean = false,
)

/**
 * Shared scaffold for all demo screens — version 3 (edge-to-edge glass chrome).
 *
 * The 3D / AR scene fills the **whole window**. There is no top app bar: the
 * chrome is a glass layer floating over the media, padded by `safeDrawing`
 * insets, and it is theme-independent — white on media in light and dark alike
 * (`final-spec.md` §3, `DESIGN.md` Liquid Glass). The sheets, by contrast,
 * follow the app theme.
 *
 * **Top row** (16 dp margins): back glass button (contentDescription
 * "Navigate back" — Maestro's per-demo liveness gate taps it) · identity pill
 * with the demo [title] and the optional [assetSource] suffix · the QA pill
 * (`" QA ×"`, unchanged — the rendering suite keys on it) · overflow glass
 * button opening a menu with Reset ([onReset]), Reset settings
 * ([onResetSettings]), Feedback and the QA-mode toggle.
 *
 * **Dock** (bottom-centre, [HorizontalFloatingToolbar]): up to four [dock]
 * items, the auto-appended *Controls* item when [controls] is non-null (it
 * carries `SETTINGS_FAB` + "Demo settings", so `DemoInteractionTest` opens the
 * same sheet it always did), and an optional [dockAccent] rendered as a filled
 * primary button — the AR action. Hidden entirely when there is nothing to put
 * in it. [SETTINGS_FAB_RESERVED_SPACE] names the band it occupies; the
 * `bottomOverlay` slot stacks **above** that band, measured from the real dock.
 *
 * **Chrome toggle**: a tap on the scene — observed in the `Initial` pointer
 * pass without consuming, so the 3D view keeps every drag, tap and pinch —
 * fades the whole chrome out and back (300 ms). Forced visible under
 * TalkBack (touch exploration) and in `DemoSettings.qaMode`, so automation
 * that taps the viewport and then asserts on the chrome keeps working.
 *
 * **Loading**: `firstFrameRendered != null` covers the viewport until the
 * first Filament frame is presented (#1022) — with the demo's [previewRes]
 * image when given, else a surface-tinted scrim — and crossfades it out over
 * 350 ms. After 12 s without a frame the cover gives way to an explicit
 * "Still loading…" card with a Retry action ([onReset]) instead of a blank
 * viewport. AR demos pass `null` on purpose: their viewport is the live camera
 * feed (#1361).
 *
 * **Controls sheet**: `controls != null` → a [ModalBottomSheet] on the theme's
 * `surfaceContainer` with a 28 dp top radius, opened from the dock's Controls
 * item at the detent this demo was last left at (#2084, persisted per demo via
 * [DemoSheetDetentStore]). [onResetSettings] adds a "Reset" text button in the
 * sheet header. [peekHeader] — a short live status such as "3 anchors placed"
 * — is rendered as a glass status pill at the top of the bottom band; the old
 * peek chip it used to label is gone.
 *
 * **Overlays**: [topOverlay] and [bottomOverlay] are scaffold-owned,
 * collision-free slots (#2779, #3237). The top slot starts below the identity
 * row and the bottom slot ends above the dock, both from **measured** bounds,
 * so no demo constant can drift out of sync with the chrome. Both are Columns:
 * two children stack, they never overlap. [bottomOverlayReservesScene] insets
 * the scene by the measured bottom band so the hero object can never descend
 * under it (#2957).
 */

/**
 * Height of the glass identity row (back button + title pill) plus its gutter,
 * provided to the `scene` slot so demos that draw their own top-centre status
 * (e.g. the AR "Scanning for surfaces…" pill) can start below the chrome. Zero
 * outside a [DemoScaffold] — `ArViewTab` draws the same pill in a plain tab.
 */
val LocalDemoChromeTopInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

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
    topOverlay: (@Composable DemoTopOverlayScope.() -> Unit)? = null,
    bottomOverlay: (@Composable DemoBottomOverlayScope.() -> Unit)? = null,
    bottomOverlayReservesScene: Boolean = false,
    dock: List<DockItem> = emptyList(),
    dockAccent: DockItem? = null,
    previewRes: Int? = null,
    chromeToggleOnTap: Boolean = false,
    scene: @Composable BoxScope.() -> Unit
) {
    val haptic = rememberHapticFeedback()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val resetScope = rememberCoroutineScope()
    val resetConfirmation = stringResource(R.string.demo_reset_done)

    // Chrome visibility. Starts visible; a scene tap toggles it. TalkBack users
    // explore by touch, so hiding controls behind a viewport tap would strand
    // them — and qa_mode automation taps the viewport then asserts the chrome.
    val touchExploration = remember(context) {
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)
            ?.isTouchExplorationEnabled == true
    }
    var chromeToggled by rememberSaveable { mutableStateOf(true) }
    val chromeVisible = chromeToggled || touchExploration || DemoSettings.qaMode

    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    val hasDock = dock.isNotEmpty() || dockAccent != null || controls != null

    Scaffold(
        // Edge-to-edge: the scene owns every pixel; the chrome applies the insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        // Height of the `bottomOverlay` band, measured (never assumed) so
        // `bottomOverlayReservesScene` can inset the viewport by exactly the room the
        // overlay takes — including the dock band it stacks on, the system-bar inset,
        // and whatever the font scale does to the chip text (#2957).
        var bottomOverlayBandPx by remember { mutableIntStateOf(0) }
        val bottomOverlayBand = with(LocalDensity.current) { bottomOverlayBandPx.toDp() }

        // Height of the dock band (toolbar + gutter, excluding the navigation-bar
        // inset), measured for the same reason: the toolbar height is a token, but a
        // measurement is what keeps the reserve honest under a future redesign. Kept
        // as a high-water mark so a chrome fade-out cannot reflow the overlay.
        var dockBandPx by remember { mutableIntStateOf(0) }
        val dockBand = with(LocalDensity.current) { dockBandPx.toDp() }

        // Height of the identity row (glass buttons + gutter, excluding the status-bar
        // inset) — the top band's mirror of the dock band.
        var identityRowPx by remember { mutableIntStateOf(0) }
        val identityRow = with(LocalDensity.current) { identityRowPx.toDp() }

        // `consumeWindowInsets(padding)` gives this Box's whole subtree ONE inset
        // reference frame (#3237). With `contentWindowInsets = 0` the padding is
        // empty, so every child that applies `safeDrawing` gets the real bars once.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        bottom = if (bottomOverlayReservesScene) bottomOverlayBand else 0.dp
                    )
                    // Observe taps without taking them: the 3D view keeps its drags.
                    .sceneTapToggle(enabled = chromeToggleOnTap && !touchExploration) {
                        chromeToggled = !chromeToggled
                    },
                content = {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalDemoChromeTopInset provides identityRow + SceneViewTokens.Space.sm,
                    ) { scene() }
                },
            )

            if (firstFrameRendered != null) {
                FirstFrameCover(
                    firstFrameRendered = firstFrameRendered,
                    previewRes = previewRes,
                    onRetry = onReset,
                )
            }

            // Top band: the demo's own overlays below the identity row, then the
            // chrome on top so scaffold controls always win the z-order.
            if (topOverlay != null) {
                DemoTopOverlay(
                    reservedTop = identityRow,
                    content = topOverlay,
                )
            }

            // Bottom band: status pill + demo overlays stacked above the dock.
            if (bottomOverlay != null || peekHeader != null) {
                DemoBottomOverlay(
                    reservedBottom = if (hasDock) maxOf(SETTINGS_FAB_RESERVED_SPACE, dockBand) else 0.dp,
                    onBandHeightChanged = { bottomOverlayBandPx = it },
                    status = peekHeader,
                    content = bottomOverlay,
                )
            }

            DemoChrome(
                visible = chromeVisible,
                title = title,
                assetSource = assetSource,
                onBack = onBack,
                onReset = onReset?.let { reset ->
                    {
                        haptic.medium()
                        reset()
                        resetScope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                message = resetConfirmation,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                },
                onResetSettings = onResetSettings,
                haptic = haptic,
                dock = dock,
                dockAccent = dockAccent,
                controlsItem = if (controls != null) {
                    DockItem(
                        icon = Icons.Outlined.Tune,
                        label = stringResource(R.string.demo_settings_fab_cd),
                        onClick = {
                            haptic.selection()
                            settingsExpanded = true
                        },
                    )
                } else null,
                onIdentityRowHeight = { identityRowPx = maxOf(identityRowPx, it) },
                onDockBandHeight = { dockBandPx = maxOf(dockBandPx, it) },
            )

            if (controls != null && settingsExpanded) {
                DemoSettingsSheet(
                    demoTitle = title,
                    controlsContent = controls,
                    haptic = haptic,
                    onResetSettings = onResetSettings,
                    onDismissed = { settingsExpanded = false },
                )
            }
        }
    }
}

/**
 * Observes a tap on the scene without consuming anything, so the 3D view under
 * it keeps every gesture. Runs in [PointerEventPass.Initial]; a tap is a single
 * pointer going down and up within the touch slop and before the long-press
 * timeout. A second pointer, a drag, or a long press is not a tap.
 */
private fun Modifier.sceneTapToggle(enabled: Boolean, onTap: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        val longPress = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var isTap = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) isTap = false
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) isTap = false
                if (change.uptimeMillis - down.uptimeMillis > longPress) isTap = false
                if (!change.pressed) {
                    if (isTap) onTap()
                    break
                }
            }
        }
    }

/**
 * The whole glass layer — identity row on top, dock at the bottom — under one
 * fade so a scene tap hides and shows it as a unit. The two bands report their
 * measured heights so the overlay slots can clear them.
 */
@Composable
private fun BoxScope.DemoChrome(
    visible: Boolean,
    title: String,
    assetSource: AssetSourceState?,
    onBack: () -> Unit,
    onReset: (() -> Unit)?,
    onResetSettings: (() -> Unit)?,
    haptic: SceneViewHaptic,
    dock: List<DockItem>,
    dockAccent: DockItem?,
    controlsItem: DockItem?,
    onIdentityRowHeight: (Int) -> Unit,
    onDockBandHeight: (Int) -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(SceneViewTokens.Motion.fade()),
        exit = fadeOut(SceneViewTokens.Motion.fade()),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            DemoIdentityRow(
                title = title,
                assetSource = assetSource,
                onBack = onBack,
                onReset = onReset,
                onResetSettings = onResetSettings,
                haptic = haptic,
                onHeightChanged = onIdentityRowHeight,
            )
            val items = dock.take(DOCK_MAX_ITEMS) + listOfNotNull(controlsItem)
            if (items.isNotEmpty() || dockAccent != null) {
                DemoDock(
                    items = items,
                    accent = dockAccent,
                    controlsItem = controlsItem,
                    onHeightChanged = onDockBandHeight,
                )
            }
        }
    }
}

/** Maximum number of demo-supplied dock items before the Controls item and the accent. */
private const val DOCK_MAX_ITEMS = 4

@Composable
private fun BoxScope.DemoIdentityRow(
    title: String,
    assetSource: AssetSourceState?,
    onBack: () -> Unit,
    onReset: (() -> Unit)?,
    onResetSettings: (() -> Unit)?,
    haptic: SceneViewHaptic,
    onHeightChanged: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                )
            )
            // After the inset, before the gutter: the overlay slot applies the same
            // inset itself and must not count it twice, but the gutter is real pixels
            // it must stay clear of.
            .onSizeChanged { onHeightChanged(it.height) }
            .padding(SceneViewTokens.Space.md)
            .testTag(DemoScaffoldTestTags.IDENTITY_ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_back_button),
            onClick = onBack,
        )
        // The identity pill: full title in the tree, one line on screen.
        val sourceLabel = assetSource?.let {
            stringResource(
                when (it) {
                    AssetSourceState.Streamed -> R.string.demo_source_streamed
                    AssetSourceState.Streaming -> R.string.demo_source_streaming
                    AssetSourceState.Bundled -> R.string.demo_source_bundled
                }
            )
        }
        GlassPill(
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (sourceLabel != null) {
                        Modifier
                            .testTag(DemoScaffoldTestTags.ASSET_SOURCE_CHIP)
                            .semantics { contentDescription = "Asset source: $sourceLabel" }
                    } else Modifier
                ),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                color = SceneViewTokens.Glass.onGlass,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (sourceLabel != null) {
                Spacer(Modifier.width(SceneViewTokens.Space.sm))
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = SceneViewTokens.Glass.onGlassMuted,
                    maxLines = 1,
                )
            }
        }
        if (DemoSettings.qaMode) {
            // Tappable QA pill: tap to disable — a single-tap escape hatch for a
            // user who toggled QA mode from the overflow menu by accident (#951).
            // Text is exactly `" QA ×"`: the rendering suite keys on it.
            GlassPill(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptic.medium()
                        DemoSettings.qaMode = false
                    }
                    .testTag(DemoScaffoldTestTags.QA_PILL),
            ) {
                Text(
                    text = " QA ×",
                    style = MaterialTheme.typography.labelSmall,
                    color = SceneViewTokens.Glass.onGlass,
                    maxLines = 1,
                )
            }
        }
        DemoOverflowMenu(
            onReset = onReset,
            onResetSettings = onResetSettings,
            haptic = haptic,
        )
    }
}

/**
 * Overflow glass button + [DropdownMenu]. Feedback keeps `FEEDBACK_ACTION` and
 * raises [io.github.sceneview.demo.feedback.FeedbackOpenRequest] exactly as the
 * former top-bar action did (#1930); Reset keeps `RESET_ACTION` (#1966). The
 * QA-mode toggle moved here from the long-press on the deleted peek chip.
 *
 * `wrapContentSize(Alignment.TopEnd)` on the anchor [Box] is the documented
 * Compose fix for a menu anchored to a button flush against the trailing edge
 * of the screen (AndroidX b/168594123, "DropdownMenu is positioned strangely
 * for toggles up against the edge of the screen") — exactly this button's
 * position, last in the identity row on every demo screen. Without it the
 * `DropdownMenu` opened detached from the glass button it belongs to (#3323).
 */
@Composable
private fun DemoOverflowMenu(
    onReset: (() -> Unit)?,
    onResetSettings: (() -> Unit)?,
    haptic: SceneViewHaptic,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
        GlassIconButton(
            icon = Icons.Filled.MoreVert,
            contentDescription = stringResource(R.string.demo_menu_cd),
            onClick = { open = true },
            modifier = Modifier.testTag(DemoScaffoldTestTags.OVERFLOW_MENU),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(SceneViewTokens.Radius.md),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            if (onReset != null) {
                val resetCd = stringResource(R.string.demo_reset_cd)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.demo_settings_reset)) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        open = false
                        onReset()
                    },
                    modifier = Modifier
                        .semantics { contentDescription = resetCd }
                        .testTag(DemoScaffoldTestTags.RESET_ACTION),
                )
            }
            if (onResetSettings != null) {
                val resetSettingsCd = stringResource(R.string.demo_settings_reset_cd)
                DropdownMenuItem(
                    text = { Text(resetSettingsCd) },
                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                    onClick = {
                        open = false
                        haptic.selection()
                        onResetSettings()
                    },
                )
            }
            val feedbackCd = stringResource(R.string.feedback_action_cd)
            DropdownMenuItem(
                text = { Text(feedbackCd) },
                leadingIcon = { Icon(Icons.Outlined.Feedback, contentDescription = null) },
                onClick = {
                    open = false
                    haptic.selection()
                    io.github.sceneview.demo.feedback.FeedbackOpenRequest.request()
                },
                modifier = Modifier
                    .semantics { contentDescription = feedbackCd }
                    .testTag(DemoScaffoldTestTags.FEEDBACK_ACTION),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.demo_menu_qa_mode)) },
                leadingIcon = { Icon(Icons.Outlined.Science, contentDescription = null) },
                trailingIcon = if (DemoSettings.qaMode) {
                    { Text("✓", style = MaterialTheme.typography.labelLarge) }
                } else null,
                onClick = {
                    open = false
                    haptic.medium()
                    DemoSettings.qaMode = !DemoSettings.qaMode
                },
            )
        }
    }
}

/**
 * Bottom dock: a glass [HorizontalFloatingToolbar] with 48 dp items, 22 dp icons,
 * and the optional accent as a filled primary button. The dock's shown height
 * (toolbar + gutter, after the navigation-bar inset) is reported so the bottom
 * overlay slot can stack above it.
 */
@Composable
private fun BoxScope.DemoDock(
    items: List<DockItem>,
    accent: DockItem?,
    controlsItem: DockItem?,
    onHeightChanged: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .onSizeChanged { onHeightChanged(it.height) }
            .padding(bottom = SceneViewTokens.Space.md),
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .height(SceneViewTokens.Layout.dockHeight)
                .testTag(DemoScaffoldTestTags.DOCK),
            colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
                toolbarContainerColor = SceneViewTokens.Glass.surface,
                toolbarContentColor = SceneViewTokens.Glass.onGlass,
            ),
            shape = RoundedCornerShape(SceneViewTokens.Radius.full),
            trailingContent = if (accent != null) {
                {
                    FilledIconButton(
                        onClick = accent.onClick,
                        enabled = accent.enabled,
                        modifier = Modifier
                            .size(SceneViewTokens.Layout.touchTarget)
                            .testTag(DemoScaffoldTestTags.DOCK_ACCENT),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            imageVector = accent.icon,
                            contentDescription = accent.label,
                            modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
                        )
                    }
                }
            } else null,
        ) {
            items.forEach { item ->
                DockIconButton(
                    item = item,
                    modifier = if (item === controlsItem) {
                        Modifier.testTag(DemoScaffoldTestTags.SETTINGS_FAB)
                    } else Modifier,
                )
            }
        }
    }
}

@Composable
private fun DockIconButton(item: DockItem, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) SceneViewTokens.Motion.pressScale else 1f,
        animationSpec = SceneViewTokens.Motion.spring(),
        label = "dock-press",
    )
    IconButton(
        onClick = item.onClick,
        enabled = item.enabled,
        interactionSource = interaction,
        modifier = modifier
            .size(SceneViewTokens.Layout.touchTarget)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = item.label },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (item.selected) {
                MaterialTheme.colorScheme.primary
            } else {
                SceneViewTokens.Glass.onGlass
            },
            disabledContentColor = SceneViewTokens.Glass.onGlass.copy(alpha = 0.38f),
        ),
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = null,
            modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
        )
    }
}

/**
 * Covers the 3D viewport until the SceneView presents its first Filament frame
 * (#1022): the demo's [previewRes] image when it has one — the same image the
 * home card shows, so the transition reads as the card coming alive — else a
 * surface-tinted scrim with a progress indicator. Cross-fades out over
 * `duration-medium` (350 ms) once [firstFrameRendered] flips, never the reverse.
 *
 * After [FIRST_FRAME_SCRIM_TIMEOUT_MS] without a frame the cover fades and an
 * explicit "Still loading…" card takes its place, with Retry wired to the
 * demo's reset when it has one — a stalled demo must say so, not go blank.
 */
@Composable
private fun BoxScope.FirstFrameCover(
    firstFrameRendered: androidx.compose.runtime.State<Boolean>,
    previewRes: Int?,
    onRetry: (() -> Unit)?,
) {
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(FIRST_FRAME_SCRIM_TIMEOUT_MS)
        timedOut = true
    }
    val rendered = firstFrameRendered.value
    val dismissed = rendered || timedOut
    val alpha by animateFloatAsState(
        targetValue = if (dismissed) 0f else 1f,
        animationSpec = tween(durationMillis = SceneViewTokens.Duration.mediumMillis),
        label = "first-frame-cover",
    )
    if (alpha > 0f) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(alpha)
                // The viewport is media: the cover is the stage colour in both
                // themes so the white glass chrome stays readable over it.
                .background(SceneViewTokens.Stage.background)
                // Swallow touches while the cover is opaque so a stray tap can't
                // reach the (not-yet-rendered) scene; lets them through once fading.
                .then(
                    if (dismissed) Modifier
                    else Modifier.pointerInput(Unit) { detectTapGestures { } }
                )
                .testTag(DemoScaffoldTestTags.FIRST_FRAME_SCRIM),
            contentAlignment = Alignment.Center,
        ) {
            if (previewRes != null) {
                Image(
                    painter = painterResource(previewRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(SceneViewTokens.Space.xl + SceneViewTokens.Space.sm),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = SceneViewTokens.Space.xs,
                )
            }
        }
    }
    if (timedOut && !rendered) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(SceneViewTokens.Space.lg)
                .testTag(DemoScaffoldTestTags.LOADING_STALLED),
            shape = RoundedCornerShape(SceneViewTokens.Radius.lg),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(SceneViewTokens.Space.lg),
                verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
            ) {
                Text(
                    text = stringResource(R.string.demo_loading_stalled_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.demo_loading_stalled_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.demo_loading_retry))
                    }
                }
            }
        }
    }
}

private const val FIRST_FRAME_SCRIM_TIMEOUT_MS = 12_000L

/**
 * Stable test tags consumed by `DemoInteractionTest`, the band tests and any
 * future visual smoke tooling so the chrome can be driven deterministically
 * without relying on accessibility-tree heuristics.
 */
object DemoScaffoldTestTags {
    /** The dock's Controls item — historically the settings FAB; the name is the contract. */
    const val SETTINGS_FAB = "demo-settings-fab"
    const val SETTINGS_SHEET = "demo-settings-sheet"
    const val SETTINGS_RESET = "demo-settings-reset"
    const val RESET_ACTION = "demo-reset-action"
    const val FEEDBACK_ACTION = "demo-feedback-action"
    const val OVERFLOW_MENU = "demo-overflow-menu"
    const val QA_PILL = "demo-qa-pill"
    /** The identity pill, tagged only when it carries an asset-source suffix. */
    const val ASSET_SOURCE_CHIP = "demo-asset-source-chip"
    const val IDENTITY_ROW = "demo-identity-row"
    const val DOCK = "demo-dock"
    const val DOCK_ACCENT = "demo-dock-accent"
    /** Glass pill carrying `peekHeader`, first child of the bottom band. */
    const val STATUS_PILL = "demo-status-pill"
    const val FIRST_FRAME_SCRIM = "demo-first-frame-scrim"
    const val LOADING_STALLED = "demo-loading-stalled"
    const val BOTTOM_OVERLAY = "demo-bottom-overlay"
    const val TOP_OVERLAY = "demo-top-overlay"
}

/**
 * Height of the band the dock reserves at the **bottom** of a demo's scene
 * area (#2779, now the dock band).
 *
 * 104 dp: a 64 dp toolbar, its 16 dp gutter, and 24 dp of breathing room so a
 * bottom overlay reads as stacked above the dock rather than resting on it.
 *
 * It is a floor, not the answer (#3229): [DemoScaffold] **measures** the real
 * dock band every composition and reserves `maxOf(this, measured)`. The
 * constant is what the first composition has to go on, before any measurement
 * has landed. Do **not** hard-code it in a demo — the `bottomOverlay` slot
 * already stacks above the band.
 */
val SETTINGS_FAB_RESERVED_SPACE = 104.dp

/**
 * Receiver of the [DemoScaffold] `bottomOverlay` slot.
 *
 * ## It is a [ColumnScope], and that is the point
 *
 * The slot lays its children out in a bottom-aligned [Column] that the scaffold
 * has already placed **above the dock band**, so a demo that needs a status
 * banner *and* an action bar *and* a legend writes all three and they
 * **stack**. They cannot be made to overlap, because siblings in a Column do not
 * share pixels — and they cannot land under the dock, because the Column's
 * bottom edge is the dock's measured top edge (#2779, #3229, #2957).
 */
@Stable
class DemoBottomOverlayScope internal constructor(
    private val columnScope: ColumnScope,
    /**
     * Width of the bottom-**end** band a demo overlay must keep clear: `0.dp`.
     *
     * This used to be the Settings FAB cluster. The dock is now bottom-centre and
     * the whole `bottomOverlay` slot is stacked above its band by the scaffold, so
     * there is no corner left to clear and the documented idiom
     * (`padding(end = settingsFabReservedSpace)`) is a no-op. Kept so existing
     * call sites keep compiling and laying out identically.
     */
    val settingsFabReservedSpace: Dp,
) : ColumnScope by columnScope

/**
 * Renders the `bottomOverlay` slot: full scene width, pinned to the bottom of
 * the scene area, clear of the system bars and **above the dock band**.
 *
 * [onBandHeightChanged] reports the band's full height — content, the demo's
 * own gutter, the dock reserve, and the system-bar inset — which is what
 * `bottomOverlayReservesScene` insets the viewport by (#2957). `onSizeChanged`
 * sits *before* `windowInsetsPadding` in the chain on purpose: a size read
 * after it would exclude the inset and under-reserve by a navigation bar.
 *
 * [status] — the demo's short live status (`peekHeader`) — is the first child
 * of the Column, as a glass pill, so it stacks with the demo's own overlays.
 */
@Composable
private fun BoxScope.DemoBottomOverlay(
    reservedBottom: Dp,
    onBandHeightChanged: (Int) -> Unit,
    status: String?,
    content: (@Composable DemoBottomOverlayScope.() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .onSizeChanged { onBandHeightChanged(it.height) }
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .padding(bottom = reservedBottom)
            .testTag(DemoScaffoldTestTags.BOTTOM_OVERLAY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OVERLAY_STACK_SPACING),
    ) {
        if (status != null) {
            GlassPill(modifier = Modifier.testTag(DemoScaffoldTestTags.STATUS_PILL)) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = SceneViewTokens.Glass.onGlass,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (content != null) {
            DemoBottomOverlayScope(this, 0.dp).content()
        }
    }
}

/** Gap between two elements stacked in an overlay slot. */
private val OVERLAY_STACK_SPACING = SceneViewTokens.Space.sm

/**
 * Receiver of the [DemoScaffold] `topOverlay` slot — the mirror of
 * [DemoBottomOverlayScope]. The slot is a top-aligned [Column] that the
 * scaffold has already placed **below the identity row**, so children stack and
 * never cross the chrome (#3237).
 */
@Stable
class DemoTopOverlayScope internal constructor(
    private val columnScope: ColumnScope,
    /**
     * Width of the top-**end** band a demo overlay must keep clear: `0.dp`.
     *
     * The asset-source chip that used to occupy the top-end corner is now the
     * suffix of the identity pill, inside the identity row the whole slot sits
     * below. Kept so `padding(end = assetSourceChipReservedSpace)` call sites
     * keep compiling as a no-op.
     */
    val assetSourceChipReservedSpace: Dp,
) : ColumnScope by columnScope

/**
 * Renders the `topOverlay` slot: full scene width, pinned to the top of the
 * scene area, clear of the system bars and **below the identity row**
 * ([reservedTop], measured from the real row including its gutter).
 */
@Composable
private fun BoxScope.DemoTopOverlay(
    reservedTop: Dp,
    content: @Composable DemoTopOverlayScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Top
                )
            )
            .padding(top = reservedTop)
            .testTag(DemoScaffoldTestTags.TOP_OVERLAY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OVERLAY_STACK_SPACING),
    ) {
        DemoTopOverlayScope(this, 0.dp).content()
    }
}

/**
 * The controls [ModalBottomSheet], opened from the dock's Controls item.
 *
 * Follows the app theme (`surfaceContainer`, 28 dp top radius) — it is a
 * themed surface, unlike the glass chrome over the media.
 *
 * [demoTitle] keys the per-demo last-detent memory (#2084): the sheet opens at
 * the detent the user last settled it at for *this* demo, persisted in
 * [DemoSheetDetentStore] so it survives navigation and process death — a demo
 * never seen before defaults to the partial detent.
 */
@Composable
private fun DemoSettingsSheet(
    demoTitle: String,
    controlsContent: @Composable ColumnScope.() -> Unit,
    haptic: SceneViewHaptic,
    onResetSettings: (() -> Unit)?,
    onDismissed: () -> Unit,
) {
    val context = LocalContext.current
    val restoredDetent: SheetValue = remember(demoTitle) {
        DemoSheetDetentStore.lastDetent(context, demoTitle)
    }
    val sheetState: SheetState = rememberModalBottomSheetState(
        // partially expanded is the default open state — keeps ~45 % of viewport
        // visible so the showcase stays alive while you tweak settings.
        skipPartiallyExpanded = false,
    )
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = {
            scope.launch {
                sheetState.hide()
                onDismissed()
            }
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(
            topStart = SceneViewTokens.Radius.xl,
            topEnd = SceneViewTokens.Radius.xl,
        ),
        modifier = Modifier.testTag(DemoScaffoldTestTags.SETTINGS_SHEET),
    ) {
        // Header row pinned above the scrolling controls — carries the sheet
        // title and, when the demo opts in, a "Reset" text button (#1154 Stage 3).
        // It sits OUTSIDE the verticalScroll so a long controls list never
        // scrolls the Reset affordance offscreen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SceneViewTokens.Space.md,
                    end = SceneViewTokens.Space.sm,
                    bottom = SceneViewTokens.Space.xs,
                ),
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
                .padding(
                    start = SceneViewTokens.Space.md,
                    end = SceneViewTokens.Space.md,
                    bottom = SceneViewTokens.Space.lg,
                ),
            content = controlsContent,
        )
    }

    // The `SheetState` starts at `Hidden` and animates open, so this effect fires
    // once with `Hidden` *before* the sheet has shown. Treating that as a dismiss
    // would kill the sheet on every demo (#1420): only honour `Hidden` after the
    // sheet has settled in a shown detent at least once.
    var hasShown by remember { mutableStateOf(false) }

    // #2084: restore this demo's last-used detent. The sheet always animates
    // open to `PartiallyExpanded`; when the persisted detent is `Expanded`, expand
    // the rest of the way as a one-shot once it first settles.
    LaunchedEffect(Unit) {
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
                // #2084: persist the detent the user just settled on.
                DemoSheetDetentStore.setLastDetent(context, demoTitle, sheetState.currentValue)
            }
            SheetValue.Hidden -> {
                if (hasShown) {
                    // Subtle tick on drag-down-to-dismiss (#1154 Stage 3).
                    haptic.selection()
                    onDismissed()
                }
            }
        }
    }
}
