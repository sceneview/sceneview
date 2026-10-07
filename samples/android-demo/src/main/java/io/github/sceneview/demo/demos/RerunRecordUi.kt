package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.google.android.filament.Engine
import io.github.sceneview.demo.demos.internal.ArDebugFormat
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanFigures
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader

/*
 * The Record screen of the Rerun demo: a room scanned live, over the camera. The camera is what
 * the scan is made with, so it keeps the screen (#4379): one line says a scan is running and for
 * how long, the scan itself grows in a 3D card under it, and the counts — never read while
 * walking a room — are rows of the settings sheet.
 */

/**
 * A scan in progress, on one line: the red recording dot, "Scanning", and the clock.
 *
 * A [depthScan] (ARCore raw depth, Rerun v2 tier `depth`) says so beside "Scanning"; a sparse
 * scan says that too. A second line appears only at the photo limit — the one thing to know
 * mid-scan. The counts are in the settings sheet ([ScanFiguresSection]).
 */
@Composable
internal fun ScanHud(seconds: Float, photoLimitReached: Boolean, depthScan: Boolean = false) {
    OverlayCard(testTag = SCAN_HUD_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(ScanDotSize)
                    .background(ArOverlay.accentRecord, CircleShape),
            )
            Spacer(Modifier.width(Space.sm))
            Text(text = SCANNING, style = OnScrimTitle)
            Spacer(Modifier.width(Space.sm))
            Text(
                text = if (depthScan) ScanCopy.TIER_DEPTH else ScanCopy.TIER_SPARSE,
                style = OnScrimTitle.copy(color = ArOverlay.onScrimMuted),
                maxLines = 1,
                modifier = Modifier.weight(1f).testTag(SCAN_TIER_TAG),
            )
            val clock = ArDebugFormat.clock(seconds)
            Text(
                text = clock,
                style = SceneViewTokens.Type.title.copy(color = ArOverlay.onScrim),
                modifier = Modifier.semantics { contentDescription = "Recording for $clock" },
            )
        }
        if (photoLimitReached) {
            Text(
                text = ScanCopy.FULL,
                style = SceneViewTokens.Type.body.copy(color = ArOverlay.accentGuidance),
            )
        }
    }
}

/**
 * What the scan has taken so far, as rows of the settings sheet: its points (the dense map's
 * for a [depthScan], ARCore's feature points otherwise), its surfaces and its photos — each
 * counted off the scan itself, never estimated.
 *
 * They were three display-size figures over the camera for the whole scan (#4379).
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
        SheetFigureRow("Points", ScanCopy.figure(if (depthScan) figures.dense else figures.points))
        SheetFigureRow("Surfaces", ScanCopy.figure(figures.surfaces))
        SheetFigureRow("Photos", ScanCopy.figure(figures.photos))
    }
}

/**
 * The scan growing in 3D while it records: the camera's path drawing itself, the points in the
 * colours the camera saw, the planes and the photos, framed as they grow. The same view the
 * replay opens on once the scan stops.
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
) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    // The scaffold stacks top overlays Space.sm apart; one more brings the card to the Space.md
    // the HUD keeps from the header and the screen's edges.
    Box(modifier = Modifier.fillMaxWidth().padding(top = Space.sm), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(horizontal = Space.md)
                .widthIn(max = ArOverlay.maxWidth)
                .fillMaxWidth()
                .aspectRatio(SCAN_STAGE_ASPECT)
                .shadow(elevation = SceneViewTokens.Elevation.lg, shape = shape, clip = false)
                .clip(shape)
                .background(SceneViewTokens.Stage.background)
                .testTag(SCAN_STAGE_TAG),
        ) {
            ArDebugSceneView(
                session = session,
                orbit = orbit,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                modifier = Modifier.fillMaxSize(),
                replay = media,
            )
            Box(Modifier.fillMaxSize().overMediaEdge(shape))
        }
    }
}

/**
 * Record, and the one line under it: nothing while idle (the status card says what Record does),
 * how to stop while recording, and a wait while the scan is packed.
 */
@Composable
internal fun ScanShutter(
    recording: Boolean,
    finishing: Boolean,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val hint = when {
        finishing -> ScanCopy.FINISHING
        recording -> ScanCopy.STOP_HINT
        else -> null
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (hint != null) {
            Text(
                text = hint,
                style = SceneViewTokens.Type.body.copy(color = ArOverlay.onScrim),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(ArOverlay.scrimDark, CircleShape)
                    .padding(horizontal = Space.md, vertical = Space.sm),
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

private const val SCANNING = "Scanning"

/** The HUD's tier label, for the QA harness. */
internal const val SCAN_TIER_TAG = "rerun_scan_tier"

/** Wider than tall: the room reads across, and the camera keeps the lower half of the screen. */
private const val SCAN_STAGE_ASPECT = 1.35f

/** A notch above the capture card's 10 dp dot: this one is read from a metre away. */
private val ScanDotSize = Space.md - Space.xs / 2

private const val SCAN_FIGURES_TITLE = "This scan"

internal const val SCAN_HUD_TAG = "ar_rerun_scan_hud"

/** The scan's counts in the settings sheet. */
internal const val SCAN_FIGURES_TAG = "ar_rerun_scan_figures"
internal const val SCAN_STAGE_TAG = "ar_rerun_scan_stage"
