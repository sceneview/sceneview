package io.github.sceneview.demo.demos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.google.android.filament.Engine
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanFigures
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.theme.motionSpring
import io.github.sceneview.demo.ui.GlassIconButton
import io.github.sceneview.demo.ui.GlassPill
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/*
 * A room being scanned, over the camera it is scanned with. The camera is the screen: one glass
 * line says a scan is running, for how long and how many points it holds; the room being rebuilt
 * stands beside it on glass, the camera showing through, and grows to the row's width on a tap;
 * a limit reached says so under the line; the shutter keeps the bottom. The counts — never read
 * while walking a room — are rows of the settings sheet.
 */

/**
 * A scan in progress, on one line of glass: the red recording dot, the clock, and the points
 * the scan holds ([ScanCopy.pointsLine]) — counted against their budget once it is in sight, and
 * `full` when it is spent, so a count that has stopped does not read as a frozen screen.
 */
@Composable
internal fun ScanHud(seconds: Float, figures: ScanFigures, modifier: Modifier = Modifier) {
    val clock = ArDebugFormat.clock(seconds)
    val spoken = "Scanning for $clock, ${ScanCopy.pointsSpoken(figures.points, figures.pointBudget)}"
    GlassPill(
        modifier = modifier.testTag(SCAN_HUD_TAG).clearAndSetSemantics { contentDescription = spoken },
        // The line stands where the camera is brightest (a ceiling, a window): its own scrim.
        ground = SceneViewTokens.Glass.scrimDock,
    ) {
        Box(Modifier.size(ScanDotSize).background(ArOverlay.accentRecord, CircleShape))
        Spacer(Modifier.width(Space.sm))
        // Tabular figures: the pill does not twitch as the seconds turn.
        Text(text = clock, style = SceneViewTokens.Type.card.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        Spacer(Modifier.width(Space.sm))
        Text(
            text = ScanCopy.pointsLine(figures.points, figures.pointBudget),
            // A spent budget reads in the colour of the notice that explains it.
            style = SceneViewTokens.Type.caption.copy(
                fontFeatureSettings = "tnum",
                color = if (figures.pointsFull) ArOverlay.accentGuidance else Color.Unspecified,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The one thing to know mid-scan, under the line that counts it: a budget is spent, and what
 * that changes ([ScanCopy.limitNotice]). It stays as long as it is true, so it stands beside the
 * 3D card and not over the shutter, where it would cost the camera its height for the rest of
 * the scan. Nothing while [text] is `null`.
 */
@Composable
internal fun ScanNotice(text: String?, modifier: Modifier = Modifier) {
    // The notice that is leaving is still the one drawn while it fades.
    var shown by remember { mutableStateOf(text) }
    if (text != null) shown = text
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = fadeIn(motionFade()),
        exit = fadeOut(motionFade()),
    ) {
        Text(
            text = shown.orEmpty(),
            style = SceneViewTokens.Type.caption.copy(color = ArOverlay.accentGuidance),
            modifier = Modifier
                .background(ArOverlay.scrimDark, RoundedCornerShape(SceneViewTokens.Radius.md))
                .padding(horizontal = Space.md, vertical = Space.sm)
                .testTag(SCAN_NOTICE_TAG),
        )
    }
}

/**
 * The top of a scan in progress on an upright phone: [hud] and [stage] side by side, the stage
 * [SceneViewTokens.DebugView.liveCardShare] of the row wide, and [notice] under the line, in the
 * width the stage leaves. Tapped, the stage grows to the whole row and drops under them; its own
 * button brings it back. Line and stage arrive together when the scan starts.
 *
 * A line too wide to stand beside the stage (a large font, a narrow window) keeps the stage
 * under it instead of being cut.
 */
@Composable
internal fun ScanLive(
    hud: @Composable () -> Unit,
    notice: @Composable () -> Unit,
    stage: @Composable (expanded: Boolean, onExpandedChange: (Boolean) -> Unit) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val grow by animateFloatAsState(if (expanded) 1f else 0f, motionSpring(), label = "scan-stage")
    val arrival = motionSpring<Float>()
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, arrival) }
    Layout(
        content = {
            Box(Modifier.graphicsLayer { alpha = enter.value.coerceIn(0f, 1f) }) { hud() }
            Box(
                Modifier.graphicsLayer {
                    val shown = enter.value.coerceIn(0f, 1f)
                    alpha = shown
                    // Grows out of its own corner, the way it grows when tapped.
                    transformOrigin = TransformOrigin(1f, 0f)
                    scaleX = ENTER_SCALE + (1f - ENTER_SCALE) * shown
                    scaleY = scaleX
                },
            ) { stage(expanded) { expanded = it } }
            Box { notice() }
        },
        modifier = Modifier
            .padding(horizontal = Space.md)
            .widthIn(max = ArOverlay.maxWidth)
            .fillMaxWidth()
            .testTag(SCAN_LIVE_TAG),
    ) { measurables, constraints ->
        val full = constraints.maxWidth
        val gap = Space.sm.roundToPx()
        val line = measurables[0].measure(Constraints(maxWidth = full))
        val small = (full * SceneViewTokens.DebugView.liveCardShare).roundToInt()
        val beside = line.width + gap + small <= full
        val share = grow.coerceIn(0f, 1f)
        val width = small + ((full - small) * share).roundToInt()
        val card = measurables[1].measure(Constraints.fixedWidth(width))
        // The notice keeps the width beside the small stage, so it does not reflow as it grows.
        val word = measurables[2].measure(Constraints(maxWidth = if (beside) full - small - gap else full))
        val head = line.height + if (word.height > 0) gap + word.height else 0
        val drop = head + gap
        val top = if (beside) (drop * share).roundToInt() else drop
        layout(full, max(head, top + card.height)) {
            line.placeRelative(0, 0)
            word.placeRelative(0, line.height + gap)
            card.placeRelative(full - width, top)
        }
    }
}

/**
 * What the scan has taken so far, as rows of the settings sheet: its points against their
 * budget, how much surface it has found, its photos against theirs, and what kind of scan this
 * phone makes — each read off the scan itself, never estimated.
 *
 * "Surfaces" is an area, not a count (#4306): ARCore's planes are born, merge and are dropped,
 * so their count went 0, 1, 4, 1, 6 over one room.
 */
@Composable
internal fun ScanFiguresSection(figures: ScanFigures, depthScan: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().testTag(SCAN_FIGURES_TAG)) {
        Text(
            text = SCAN_FIGURES_TITLE,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = Space.xs).semantics { heading() },
        )
        SheetFigureRow("Points", ScanCopy.budgeted(figures.points, figures.pointBudget))
        SheetFigureRow("Surfaces found", ArDebugFormat.area(figures.surfaceMetres2))
        SheetFigureRow("Photos", ScanCopy.budgeted(figures.photos, figures.photoBudget))
        SheetFigureRow(
            "Scan type",
            if (depthScan) ScanCopy.TIER_DEPTH else ScanCopy.TIER_SPARSE,
            Modifier.testTag(SCAN_TIER_TAG),
        )
    }
}

/**
 * The scan growing in 3D while it records, on glass: the camera's path drawing itself, the points
 * in the colours the camera saw, the planes and the photos, framed as they grow — and the camera
 * it is made with showing through, behind a tint. The same view the replay opens on once the scan
 * stops.
 *
 * [expanded] is `null` where the card has one size (a phone on its side). Otherwise the small
 * card is one button that grows it, and the grown one turns under a finger and carries the
 * button that brings it back.
 */
@Composable
@Suppress("LongParameterList") // the demo's shared engine, handed down once
internal fun ScanStage(
    session: ArDebugSession,
    orbit: ArDebugOrbitCamera,
    media: RerunReplayMedia,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    modifier: Modifier = Modifier,
    expanded: Boolean? = null,
    onExpandedChange: (Boolean) -> Unit = {},
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    val chrome = LocalStageChrome.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(SCAN_STAGE_ASPECT)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .clip(shape)
            .background(SceneViewTokens.DebugView.liveGlass)
            .testTag(SCAN_STAGE_TAG),
    ) {
        ArDebugSceneView(
            session = session,
            orbit = orbit,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            modifier = Modifier.fillMaxSize(),
            // Small, the room is a few hundred pixels: finer points, no measure, half the frames.
            compact = expanded == false,
            replay = media,
            glass = true,
        )
        when (expanded) {
            false -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClickLabel = STAGE_EXPAND, role = Role.Button) { onExpandedChange(true) }
                    .semantics { contentDescription = STAGE_LABEL },
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInFull,
                    contentDescription = null,
                    tint = chrome.onGlass,
                    modifier = Modifier.padding(Space.sm).size(StageGlyphSize),
                )
            }
            true -> GlassIconButton(
                icon = Icons.Rounded.CloseFullscreen,
                contentDescription = STAGE_COLLAPSE,
                onClick = { onExpandedChange(false) },
                modifier = Modifier.align(Alignment.TopEnd).padding(Space.xs),
                ground = SceneViewTokens.Glass.scrimDock,
            )
            null -> Unit
        }
    }
}

/**
 * Record, and the one line over it: a wait while the scan is packed, or how to stop — which
 * leaves after [STOP_HINT_MS] and gives the camera its height back. Nothing while idle (the
 * status card says what Record does).
 */
@Composable
internal fun ScanShutter(
    recording: Boolean,
    finishing: Boolean,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    var hinting by remember(recording) { mutableStateOf(recording) }
    LaunchedEffect(recording) {
        if (recording) {
            delay(STOP_HINT_MS)
            hinting = false
        }
    }
    val line = when {
        finishing -> ScanCopy.FINISHING
        recording && hinting -> ScanCopy.STOP_HINT
        else -> null
    }
    // The line that is leaving is still the one drawn while it fades.
    var shown by remember { mutableStateOf(line) }
    if (line != null) shown = line
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        AnimatedVisibility(visible = line != null, enter = fadeIn(motionFade()), exit = fadeOut(motionFade())) {
            Text(
                text = shown.orEmpty(),
                style = SceneViewTokens.Type.body.copy(color = ArOverlay.onScrim),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = Space.md)
                    .widthIn(max = ArOverlay.maxWidth)
                    .background(ArOverlay.scrimDark, RoundedCornerShape(SceneViewTokens.Radius.lg))
                    .padding(horizontal = Space.md, vertical = Space.sm)
                    .testTag(SCAN_LINE_TAG),
            )
        }
        RecordShutter(
            isRecording = recording,
            startEnabled = startEnabled && !finishing,
            onStart = onStart,
            onStop = { if (!finishing) onStop() },
        )
    }
}

/** The scan type's row in the settings sheet, for the QA harness. */
internal const val SCAN_TIER_TAG = "rerun_scan_tier"

/** Wider than tall: the room reads across. */
private const val SCAN_STAGE_ASPECT = 1.35f

/** The stage and the line arrive from just under their size. */
private const val ENTER_SCALE = 0.92f

/** How long a scan says how to stop it: long enough to read twice, then the camera has the height. */
private const val STOP_HINT_MS = 8_000L

/** The capture card's 10 dp dot. */
private val ScanDotSize = Space.sm + Space.xs / 2

/** The small card's "grows" glyph: a mark, not a button — the whole card is the button. */
private val StageGlyphSize = Space.md

private const val SCAN_FIGURES_TITLE = "This scan"
private const val STAGE_LABEL = "Your scan in 3D"
private const val STAGE_EXPAND = "Enlarge"
private const val STAGE_COLLAPSE = "Shrink the 3D view"

internal const val SCAN_HUD_TAG = "ar_rerun_scan_hud"

/** The line and the 3D card together, on an upright phone. */
internal const val SCAN_LIVE_TAG = "ar_rerun_scan_live"

/** The line over the shutter: how to stop, or the wait while the scan is packed. */
internal const val SCAN_LINE_TAG = "ar_rerun_scan_line"

/** A limit reached, under the scan's line. */
internal const val SCAN_NOTICE_TAG = "ar_rerun_scan_notice"

/** The scan's figures in the settings sheet. */
internal const val SCAN_FIGURES_TAG = "ar_rerun_scan_figures"
internal const val SCAN_STAGE_TAG = "ar_rerun_scan_stage"
