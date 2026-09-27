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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
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
 * The Record screen of the Rerun demo: a room scanned live, over the camera. It is meant to be
 * read over the shoulder, a metre away — so the figures are in the display size, white on the
 * dark scrim, and the scan itself grows in a large 3D card right under them.
 */

/**
 * The live figures of a scan: the red recording dot and the clock, then the points, surfaces
 * and photos taken so far — each counted off the scan itself, never estimated.
 */
@Composable
internal fun ScanHud(figures: ScanFigures, seconds: Float, photoLimitReached: Boolean) {
    OverlayCard(testTag = SCAN_HUD_TAG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(ScanDotSize)
                    .background(ArOverlay.accentRecord, CircleShape),
            )
            Spacer(Modifier.width(Space.sm))
            Text(text = SCANNING, style = OnScrimTitle, modifier = Modifier.weight(1f))
            val clock = ArDebugFormat.clock(seconds)
            Text(
                text = clock,
                style = SceneViewTokens.Type.title.copy(color = ArOverlay.onScrim),
                modifier = Modifier.semantics { contentDescription = "Recording for $clock" },
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ScanFigure(figures.points, "point", "points", Modifier.weight(1f))
            ScanFigure(figures.surfaces, "surface", "surfaces", Modifier.weight(1f))
            ScanFigure(figures.photos, "photo", "photos", Modifier.weight(1f))
        }
        if (photoLimitReached) {
            Text(
                text = ScanCopy.FULL,
                style = SceneViewTokens.Type.body.copy(color = ArOverlay.accentGuidance),
            )
        }
    }
}

@Composable
private fun ScanFigure(value: Int, one: String, many: String, modifier: Modifier) {
    val label = ScanCopy.label(value, one, many)
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = ScanCopy.figure(value),
            style = SceneViewTokens.Type.display.copy(color = ArOverlay.onScrim),
            maxLines = 1,
        )
        Text(text = label, style = OnScrimTitle.copy(color = ArOverlay.onScrimMuted), maxLines = 1)
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
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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

/** Wider than tall: the room reads across, and the camera keeps the lower half of the screen. */
private const val SCAN_STAGE_ASPECT = 1.35f

/** A notch above the capture card's 10 dp dot: this one is read from a metre away. */
private val ScanDotSize = Space.md - Space.xs / 2

internal const val SCAN_HUD_TAG = "ar_rerun_scan_hud"
internal const val SCAN_STAGE_TAG = "ar_rerun_scan_stage"
