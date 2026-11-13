package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.R
import androidx.compose.ui.platform.testTag
import com.google.android.filament.Engine
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ScanObserveGate
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader

/*
 * Live Room: a room's scan that keeps the camera clear. The room builds up in a live 3D map card
 * under the camera — the cloud in the colours the camera saw, heading-up — instead of a cloud
 * drawn over the camera, walls, borders or chevrons. The card is the scan's only view while it
 * scans: it drives the session's figures and observes the floor's coverage, the m² the HUD says.
 */

/**
 * The live 3D map of a room's scan, in a card of the bottom band: [ScanLiveMap] drawn compact,
 * the coloured cloud only, following the phone heading-up while it scans, stepped back over the
 * whole room while [paused].
 */
@Composable
@Suppress("LongParameterList") // the demo's shared engine, handed down once
internal fun LiveRoomMapCard(
    session: ArDebugSession,
    media: RerunReplayMedia,
    coverage: ScanCoverageState,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    livePose: (traced: DebugPose?) -> DebugPose?,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    val pose by rememberUpdatedState(livePose)
    val observed = remember(coverage) { ScanObserveGate() }
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(horizontal = Space.md)
                .widthIn(max = ArOverlay.maxWidth)
                .fillMaxWidth()
                .aspectRatio(MAP_CARD_ASPECT)
                .clip(shape)
                .background(chrome.ground, shape)
                .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
                .testTag(LIVE_ROOM_MAP_TAG),
        ) {
            ScanLiveMap(
                session = session,
                media = media,
                coverage = coverage,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                livePose = livePose,
                clearTop = 0f,
                clearBottom = 0f,
                showArea = true,
                overview = paused,
                compact = true,
                legendOnTop = false,
                drivesSession = true,
                showGround = false,
                observe = { frame ->
                    val now = pose(frame.camera)
                    val cells = media.liveDense?.invoke()?.coveredCells
                    // The map hands the same frame to every render until it rebuilds one.
                    if (observed.isNew(frame, now, cells)) {
                        coverage.observe(frame, pose = now, snapshotCells = cells, record = media.trace)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The phone's position jumped and has not come back for a few seconds ([PoseContinuity]): the
 * scan has taken nothing since, and asks whether to keep what it has or to start again. Plain
 * words: the screen never says "tracking".
 */
@Composable
internal fun ScanSlippedCard(onFinish: () -> Unit, onRestart: () -> Unit) {
    OverlayCard(testTag = SCAN_SLIPPED_TAG) {
        Text(text = stringResource(R.string.scan_live_slipped_title), style = OnScrimTitle)
        Text(text = stringResource(R.string.scan_live_slipped_body), style = OnScrimBody)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            FilledTonalButton(onClick = onRestart, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.scan_live_slipped_restart)) }
            Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.scan_live_slipped_finish)) }
        }
    }
}

/** The map card's width over its height: low enough to leave the camera most of the screen. */
private const val MAP_CARD_ASPECT = 2f


internal const val LIVE_ROOM_MAP_TAG = "ar_rerun_live_room_map"
internal const val SCAN_SLIPPED_TAG = "ar_rerun_scan_slipped"
