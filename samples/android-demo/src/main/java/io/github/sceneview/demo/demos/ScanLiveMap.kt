package io.github.sceneview.demo.demos

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.google.android.filament.Engine
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.LiveCoverageGround
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.ScanAreaCopy
import io.github.sceneview.demo.demos.internal.ScanFollow
import io.github.sceneview.demo.demos.internal.ScanGroundMeshes
import io.github.sceneview.demo.demos.internal.ScanLiveMapFraming
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.GlassIconButton
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The live 3D map of a room's scan (Thomas's QA of 2026-10-22, "un cadre 3D … qui voit en live la
 * complétion"): the cloud in the colours the camera saw, from above and behind the phone,
 * heading-up, the whole floor found so far in view — its covered part under the points, the rest
 * hatched, still to scan. It replaces the 112 dp flat map card and takes the larger share of the
 * screen, the camera the smaller one ([onSwap] trades them), as Polycam's and Scaniverse's live
 * scans do.
 *
 * A second view of the session the live view drives ([ArDebugSceneView] with `drivesSession =
 * false`), so it costs a render, not a second capture — and it is paced to [MAP_FPS], its dense
 * cloud taking a new snapshot every [DENSE_REFRESH_MS] and its floor rebuilt off the main thread
 * every [GROUND_REFRESH_MS] at most, so the live screen keeps the frame budget of #4770 and #4792.
 *
 * [clearTop] is how far down the map the chrome above it (the scan's status card) reaches, in
 * pixels: the phone is drawn in the part left clear. [showArea] says the area covered, which a
 * room's scan waits on two walls for.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // the demo's shared engine, handed down once
internal fun ScanLiveMap(
    session: ArDebugSession,
    media: RerunReplayMedia,
    coverage: ScanCoverageState,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    livePose: (traced: DebugPose?) -> DebugPose?,
    clearTop: Float,
    showArea: Boolean,
    mapFirst: Boolean,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var following by remember { mutableStateOf(true) }
    val orbit = remember {
        ArDebugOrbitCamera(
            drift = false,
            band = ScanFollow.BAND,
            approachRate = MAP_APPROACH_RATE,
            maxTurnDegreesPerSecond = ScanFollow.MAX_TURN_DEGREES_PER_SECOND,
            degreesPerViewWidth = ScanFollow.DRAG_DEGREES_PER_VIEW_WIDTH,
            elevationRange = ScanFollow.DRAG_ELEVATION_RANGE,
            maxCoastDegrees = ScanFollow.MAX_COAST_DEGREES,
        ).apply { onFollowingChange = { following = it } }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    SideEffect { orbit.band = ScanLiveMapFraming.band(clearTop, size.height.toFloat()) }
    // Swapped, the map is framed again round the phone.
    LaunchedEffect(mapFirst) { orbit.recenter() }

    // The same live cloud, at its full surfel size (not over the camera), a new snapshot at most
    // every few seconds: each one is an upload, and the live view already takes them all.
    val drawn = remember(media) {
        media.liveDense?.let { live ->
            val gate = SnapshotGate()
            media.withLiveDense(overCamera = { false }) { gate.pick(live()) }
        } ?: media
    }

    // The floor: what is covered, and the rest of the room's floor hatched, rebuilt when it grows.
    var ground by remember { mutableStateOf<ScanGroundMeshes?>(null) }
    LaunchedEffect(coverage, session) {
        var version = -1
        var extent: List<Pair<Float, Float>>? = null
        while (true) {
            val grid = coverage.grid
            val outline = coverage.extent
            if (grid.version != version || outline !== extent) {
                version = grid.version
                extent = outline
                val keys = LongArray(grid.covered)
                var n = 0
                grid.forEachCovered { cx, cz -> keys[n++] = CoverageGrid.key(cx, cz) }
                val trace = session.trace
                val floorY = stageFloorY(trace.frameAt(trace.duration))
                val cell = grid.cellMetres
                ground = withContext(Dispatchers.Default) {
                    LiveCoverageGround.of(keys, cell, outline, floorY)?.let { ScanGroundMeshes.of(it, hatchMissing = true) }
                }
            }
            delay(GROUND_REFRESH_MS)
        }
    }
    val groundColors = ScanGroundColors.currentLive()
    val pose by rememberUpdatedState(livePose)
    val heading = remember { HeadingHolder() }

    val area by remember(coverage) { derivedStateOf { ScanAreaCopy.area(coverage.coveredSquareMetres) } }
    val label = stringResource(R.string.scan_coverage_label, area)
    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .semantics { contentDescription = label }
            .testTag(SCAN_LIVE_MAP_TAG),
    ) {
        ArDebugSceneView(
            session = session,
            orbit = orbit,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            modifier = Modifier.fillMaxSize(),
            replay = drawn,
            measuresRoom = false,
            visible = { it == DebugGroup.Points || it == DebugGroup.Trail },
            follow = { frame ->
                pose(frame.camera)?.let { phone ->
                    ScanFollow.headingOf(phone, heading.degrees)?.let { degrees ->
                        heading.degrees = degrees
                        ScanLiveMapFraming.poseAt(phone, degrees, coverage.extent)
                    }
                }
            },
            maxFps = MAP_FPS,
            drivesSession = false,
            content = { ScanLiveGroundLayer(ground, engine, materialLoader, groundColors) },
        )
        if (following && size.height > 0) {
            // You: where the map keeps the phone, pointing the way it faces (heading-up).
            val band = ScanLiveMapFraming.band(clearTop, size.height.toFloat())
            Canvas(Modifier.fillMaxSize()) {
                drawYou(Offset(this.size.width / 2f, this.size.height * (HALF - band.lift)), YouRadius.toPx())
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            val chip = RoundedCornerShape(SceneViewTokens.Radius.full)
            Row(
                modifier = Modifier
                    .overMediaEdge(chip)
                    .clip(chip)
                    .background(ArOverlay.scrimDark, chip)
                    .padding(horizontal = Space.sm, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                // Clipped: its diagonals run past the swatch, and drew over the area's first digit.
                Canvas(Modifier.size(SwatchSize).clipToBounds()) {
                    drawRect(groundColors.missing)
                    val w = this.size.width
                    for (i in -1..2) {
                        drawLine(
                            groundColors.hatch,
                            Offset(i * w / 2f, w),
                            Offset(i * w / 2f + w, 0f),
                            strokeWidth = w / HATCH_STROKES,
                        )
                    }
                }
                Text(
                    text = if (showArea) {
                        stringResource(R.string.scan_live_map_area, area)
                    } else {
                        stringResource(R.string.scan_coverage_missing)
                    },
                    style = SceneViewTokens.Type.caption,
                    color = ArOverlay.onScrim,
                )
            }
            Box(Modifier.weight(1f))
            if (!following) {
                GlassIconButton(
                    icon = Icons.Rounded.MyLocation,
                    contentDescription = stringResource(R.string.demo_ar_rerun_recenter_description),
                    onClick = { orbit.recenter() },
                    ground = ArOverlay.scrimDark,
                )
            }
            GlassIconButton(
                icon = Icons.Rounded.SwapVert,
                contentDescription = stringResource(
                    if (mapFirst) R.string.scan_live_map_swap_camera else R.string.scan_live_map_swap_map,
                ),
                onClick = onSwap,
                // The legend's scrim: bare glass vanished over the light theme's pale floor.
                ground = ArOverlay.scrimDark,
                modifier = Modifier.testTag(SCAN_LIVE_MAP_SWAP_TAG),
            )
        }
    }
}

/** The phone's heading as the map last read it, kept while the phone looks straight down. */
private class HeadingHolder {
    var degrees: Float? = null
}

/**
 * The live cloud's snapshots, passed on [DENSE_REFRESH_MS] apart at most: the map's dense layer
 * uploads each one it is handed, and the live view over the camera already uploads them all.
 */
private class SnapshotGate {
    private var shown: ReplayDenseLayer? = null
    private var shownAt = 0L

    fun pick(latest: ReplayDenseLayer?): ReplayDenseLayer? {
        if (latest == null || latest === shown) return shown ?: latest
        val now = SystemClock.elapsedRealtime()
        if (shown == null || now - shownAt >= DENSE_REFRESH_MS) {
            shown = latest
            shownAt = now
        }
        return shown
    }
}

/** You on the map: a disc in the progress accent, ringed, its arrow pointing the way you face. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawYou(centre: Offset, r: Float) {
    drawCircle(ArOverlay.onScrim, radius = r * RING, center = centre)
    drawCircle(ArOverlay.accentProgress, radius = r, center = centre)
    val arrow = Path().apply {
        moveTo(centre.x, centre.y - r * ARROW_TIP)
        lineTo(centre.x + r * ARROW_HALF_WIDTH, centre.y + r * ARROW_BASE)
        lineTo(centre.x, centre.y + r * ARROW_NOTCH)
        lineTo(centre.x - r * ARROW_HALF_WIDTH, centre.y + r * ARROW_BASE)
        close()
    }
    drawPath(arrow, ArOverlay.onAccentProgress)
}

/** The map's pace: a map of the room, not the camera's 30 Hz — a third of the live view's work. */
private const val MAP_FPS = 12

/** Slower than the live view's follow: the map glides after the phone rather than shaking with it. */
private const val MAP_APPROACH_RATE = 4f

/** How often the map takes the live cloud's newest snapshot, at most. */
private const val DENSE_REFRESH_MS = 3_000L

/** How often the map's floor is looked at for growth. */
private const val GROUND_REFRESH_MS = 1_000L

private const val HALF = 0.5f
private const val RING = 1.18f
private const val ARROW_TIP = 0.62f
private const val ARROW_BASE = 0.5f
private const val ARROW_NOTCH = 0.22f
private const val ARROW_HALF_WIDTH = 0.46f
private const val HATCH_STROKES = 6f

/** The marker: 10 dp, read at arm's length over the room. */
private val YouRadius = Space.sm + Space.xs / 2

/** The legend's swatch: 12 dp. */
private val SwatchSize = Space.sm + Space.xs

internal const val SCAN_LIVE_MAP_TAG = "ar_rerun_scan_live_map"
internal const val SCAN_LIVE_MAP_SWAP_TAG = "ar_rerun_scan_live_map_swap"
