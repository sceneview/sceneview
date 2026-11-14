package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import io.github.sceneview.capture.SceneRecorder
import io.github.sceneview.demo.common.RequestLightStatusBarIcons
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.LiveCoverageGround
import io.github.sceneview.demo.demos.internal.OrbitBand
import io.github.sceneview.demo.demos.internal.RescanHole
import io.github.sceneview.demo.demos.internal.RescanSources
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.demos.internal.ScanHoleMarks
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.demos.internal.RoomMeasure
import io.github.sceneview.demo.demos.internal.ScanGroundMeshes
import io.github.sceneview.demo.demos.internal.ScanLiveMapFraming
import io.github.sceneview.demo.demos.internal.ScanModelFollow
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.StageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The scan's main view while capturing: the model being built, in the colours the camera saw,
 * on the stage's own ground (2026-11-13, Thomas: « on s'en fiche de la caméra… on est en train
 * de construire un modèle 3D »). The camera stays in a small window cut in that ground, where
 * the captured points sit on the world. The phone stands in the model as its view cone, and the
 * view follows it from behind and above ([ScanModelFollow]): eased, so its turns swing the view
 * round over seconds and never spin it, and stepping back as the model grows. The snapshot is rebuilt on a worker at
 * most every [REFRESH_MS]; ARCore stays the capture's clock. The camera owns the whole screen
 * until a surface exists, then the model fades in.
 */
@Composable
internal fun ScanModelPreview(
    session: ArDebugSession,
    media: RerunReplayMedia,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    cameraWindow: () -> Rect?,
    modifier: Modifier = Modifier,
    onShown: (Boolean) -> Unit = {},
    /** The dimensions written on the live model, each time they change: its result keeps them. */
    onMeasured: (RoomMeasure?) -> Unit = {},
    /** Photographs this view alone at Finish: its result opens on that picture, then fades to its model. */
    recorder: SceneRecorder? = null,
    /** Where the model's view stands in the window: the result lays the picture back there. */
    onViewBounds: (Rect) -> Unit = {},
    /** The floor the scan covers: drawn under the model, what is still empty hatched. */
    coverage: ScanCoverageState? = null,
    /** The empty areas of that floor large enough to go back to, centres (x, z): the guidance names one. */
    onHoles: (List<Pair<Float, Float>>) -> Unit = {},
    /** The centre of the area the guidance names, drawn brighter than the rest. */
    target: () -> Pair<Float, Float>? = { null },
) {
    val snapshot by produceState<ReplayDenseLayer?>(null, media) {
        var previous: ReplayDenseLayer? = null
        while (true) {
            val next = media.liveDense?.invoke() ?: media.dense
            if (next != null && next !== previous) {
                value = withContext(Dispatchers.Default) {
                    ReplayDenseLayer(
                        cloud = next.cloud,
                        voxelM = next.voxelM,
                        mesh = DenseSurfels.mesh(
                            next.cloud, next.voxelM,
                            halfSideVoxels = MODEL_HALF_SIDE_VOXELS, levelCap = MODEL_LEVEL_CAP,
                        ),
                        atlas = DenseSurfels.atlas(next.cloud, ReplayDenseLayer.DENSE_FALLBACK_COLOR),
                        whole = next.whole,
                        // Covered where anything is drawn, a surfel one view saw included: the
                        // map's own cells keep only those seen twice, and striped drawn floor.
                        coveredCells = (next.coveredCells + CoverageGrid.coveredKeys(
                            next.cloud.positions, next.cloud.count, minPoints = 1,
                        )).distinct().toLongArray(),
                    )
                }
                previous = next
            }
            delay(REFRESH_MS)
        }
    }
    val current by rememberUpdatedState(snapshot)
    // The framing only grows: a snapshot that trims a far patch never pulls the view in again.
    val grown = remember(media) { arrayOfNulls<FloatArray>(1) }
    val bounds = remember(snapshot) {
        snapshot?.whole?.let { ArDebugGeometry.robustBounds(it) }?.let { next ->
            val before = grown[0]
            val union = if (before == null) next else FloatArray(6) { i ->
                if (i < 3) minOf(before[i], next[i]) else maxOf(before[i], next[i])
            }
            union.also { grown[0] = it }
        } ?: grown[0]
    }
    // Framed on a room-sized box at least: the first surfels, a metre of floor, never fill the
    // screen only to shrink away as the room grows (2026-11-13, Thomas: « le zoom va pas »).
    val currentBounds by rememberUpdatedState(bounds?.let(::atLeastRoomSized))
    val shown by animateFloatAsState(
        targetValue = if (bounds != null) 1f else 0f,
        animationSpec = tween(SceneViewTokens.Duration.mediumMillis),
        label = "scan-model-in",
    )
    val present = bounds != null
    LaunchedEffect(present) { onShown(present) }
    DisposableEffect(Unit) { onDispose { onShown(false) } }
    if (shown == 0f) return
    // White status icons over the dark stage, in both themes.
    RequestLightStatusBarIcons(active = present)
    // The room's dimensions fitted to the model's own points, squared to the walls the planes
    // find: one fit per snapshot and heading, not per frame, and never smaller than the last.
    val measured = rememberUpdatedState(onMeasured)
    val measureFit = remember(media) {
        var fitFor: Any? = null
        var fitYaw = Float.NaN
        var fitted: RoomMeasure? = null
        val fit: (RoomMeasure) -> RoomMeasure? = { planned ->
            val points = current?.whole
            if (points !== fitFor || planned.yaw != fitYaw) {
                fitFor = points
                fitYaw = planned.yaw
                // Grow-only: more of the room seen widens it; a sparser snapshot never narrows it.
                fitted = RoomMeasure.grow(fitted, points?.let { RoomMeasure.ofPoints(it, planned.yaw) })
                measured.value(fitted)
                fitted?.let {
                    Log.i(MEASURE_TAG, "yaw %.1f°: %.2f × %.2f m over %d points".format(
                        Math.toDegrees(planned.yaw.toDouble()), it.width, it.depth, (points?.size ?: 0) / 3))
                }
            }
            fitted
        }
        fit
    }
    // The floor still empty under the model (2026-11-13, Thomas's workshop: say which part is
    // missing, and show it): the gap the guidance names alone is hatched (the review of u25: half
    // the floor in amber read as site tape; intention.md: one indication of where to go).
    var ground by remember { mutableStateOf<ScanGroundMeshes?>(null) }
    val holes by rememberUpdatedState(onHoles)
    val named by rememberUpdatedState(target)
    if (coverage != null) {
        LaunchedEffect(coverage, session) {
            var version = -1
            var extent: List<Pair<Float, Float>>? = null
            var layer: ReplayDenseLayer? = null
            var floor: ScanGround? = null
            var gaps: List<RescanHole> = emptyList()
            var markedFor: Pair<Float, Float>? = null
            var markedOn: ScanGround? = null
            while (true) {
                val grid = coverage.grid
                val outline = coverage.extent
                val dense = current
                if (grid.version != version || outline !== extent || dense !== layer) {
                    version = grid.version
                    extent = outline
                    layer = dense
                    // Covered where the model the user sees has surface, not where ARCore's sparse
                    // points fell: an area drawn full is never called empty.
                    val keys = dense?.coveredCells?.takeIf { it.isNotEmpty() } ?: LongArray(grid.covered).also { out ->
                        var n = 0
                        grid.forEachCovered { cx, cz -> out[n++] = CoverageGrid.key(cx, cz) }
                    }
                    val trace = session.trace
                    val floorY = stageFloorY(trace.frameAt(trace.duration))
                    val cell = grid.cellMetres
                    floor = withContext(Dispatchers.Default) {
                        // Empty means a gap inside what the model already spans, its hull: the
                        // planes' outline ran metres past the room and striped all of it.
                        val hull = RoomDollhouse.convexHull(
                            keys.map { ((CoverageGrid.cxOf(it) + 0.5f) * cell) to ((CoverageGrid.czOf(it) + 0.5f) * cell) },
                        )
                        LiveCoverageGround.of(keys, cell, hull, floorY)
                    }
                    gaps = floor?.let { f ->
                        withContext(Dispatchers.Default) {
                            RescanSources.holes(f).filter { it.area >= ScanLiveMapFraming.MIN_ZONE_M2 }
                        }
                    }.orEmpty()
                    holes(gaps.map(::centreOf))
                }
                // Rebuilt when the floor changes or the guidance names another gap.
                val aim = named()
                if (floor !== markedOn || aim != markedFor) {
                    markedOn = floor
                    markedFor = aim
                    val f = floor
                    ground = if (f == null) null else withContext(Dispatchers.Default) {
                        val box = aim?.let { (x, z) ->
                            gaps.minByOrNull { gap(centreOf(it), x, z) }?.takeIf { gap(centreOf(it), x, z) <= SAME_GAP_M2 }?.bounds()
                        }
                        // One indication of where to go (intention.md, 13/11: no orange zones, one
                        // cue that means something): the named gap alone, on the ground's weak layer.
                        ScanGroundMeshes(DebugMesh(), DebugMesh(), weakFill = ScanHoleMarks.of(f, box))
                    }
                }
                delay(MARKS_REFRESH_MS)
            }
        }
    }
    // The guidance amber on the gap named, toned into the dark stage.
    val groundColors = ScanGroundColors.currentLive().let { base ->
        base.copy(weak = lerp(StageChrome.Media.ground, SceneViewTokens.ArOverlay.accentGuidance, NAMED_HATCH_TINT))
    }
    val orbit = remember(media) {
        ArDebugOrbitCamera(
            drift = false, band = OrbitBand.CARD,
            approachRate = FOLLOW_APPROACH_RATE, maxTurnDegreesPerSecond = FOLLOW_MAX_TURN,
        )
    }
    val phoneFollow = remember(media) { ScanModelFollow() }
    val drawn = remember(media) { media.withLiveDense(overCamera = { false }) { current } }
    // The replay's dark stage in both themes (2026-11-13, the mother's review of the 4.6x replay
    // Thomas liked): the room in its true colours reads on it, its grid and its dimensions too.
    val chrome = StageChrome.Media
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // This view's place in the window, to read the recorder's camera window in its own space.
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.fillMaxSize()
            .graphicsLayer { alpha = shown }
            .onGloballyPositioned { origin = it.positionInWindow() }
            .testTag("scan_live_model"),
    ) {
        // The stage's ground everywhere but the camera window, read at draw time.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val window = cameraWindow()?.translate(-origin)
                val ground = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    window?.let { addRoundRect(RoundRect(it, CornerRadius(CameraWindowCorner.toPx()))) }
                }
                drawPath(ground, chrome.ground)
            },
        )
        CompositionLocalProvider(LocalStageChrome provides chrome) {
            ArDebugSceneView(
                session = session,
                orbit = orbit,
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                replay = drawn,
                // The model frames between the figures above and the window and Done below.
                modifier = Modifier.fillMaxSize().padding(top = statusTop + MODEL_TOP, bottom = navBottom + MODEL_BOTTOM)
                    .onGloballyPositioned { onViewBounds(it.boundsInWindow()) },
                compact = false,
                transparent = true,
                maxFps = 30,
                // The floor's grid, a line every metre, and the room's dimensions as the replay drew them.
                measuresRoom = true,
                stageAxes = false,
                measureWithoutPlanes = true,
                measureFit = measureFit,
                // Over the model: a wall's points hid half of a figure ("10 in" for 13 ft 10 in).
                measureOnTop = true,
                surfaceMirrorer = recorder?.surfaceMirrorer,
                // The room, its grid, and the phone in it: its view cone and the path it walked.
                visible = {
                    it == DebugGroup.Points || it == DebugGroup.Stage || it == DebugGroup.Cameras || it == DebugGroup.Trail
                },
                phoneOnly = true,
                drivesSession = false,
                // From behind and above the phone, eased, stepping back as the room grows.
                content = { ScanLiveGroundLayer(ground, engine, materialLoader, groundColors) },
                follow = { frame ->
                    phoneFollow.poseFor(frame.camera, frame.time, ArDebugFraming.HOME_AZIMUTH) { heading ->
                        ArDebugFraming.home(
                            currentBounds, heading, orbit.verticalFovDegrees,
                            orbit.aspect, elevationDegrees = MODEL_ELEVATION, band = OrbitBand.CARD, fill = 1f,
                        )
                    }
                },
            )
        }
    }
}

/** [bounds] grown about its middle to [MIN_HALF_WIDE_M] across and [MIN_HALF_HIGH_M] up at least. */
private fun atLeastRoomSized(bounds: FloatArray): FloatArray = FloatArray(6) { i ->
    val axis = i % 3
    val least = if (axis == 1) MIN_HALF_HIGH_M else MIN_HALF_WIDE_M
    val middle = (bounds[axis] + bounds[axis + 3]) / 2f
    val half = maxOf(least, (bounds[axis + 3] - bounds[axis]) / 2f)
    if (i < 3) middle - half else middle + half
}

/** The least the live model is framed on: a small room, 3 m across and 2 m up. */
private const val MIN_HALF_WIDE_M = 1.5f
private const val MIN_HALF_HIGH_M = 1f

/** How much of the guidance amber the named gap's hatching takes over the dark stage. */
private const val NAMED_HATCH_TINT = 0.75f

/** How often the floor's marks are checked for a new floor or a newly named gap. */
private const val MARKS_REFRESH_MS = 500L

/** A gap whose centre lies within ~0.7 m of the one named is the one named. */
private const val SAME_GAP_M2 = 0.5f

private fun centreOf(hole: RescanHole): Pair<Float, Float> {
    val o = hole.outline
    var x = 0f
    var z = 0f
    val n = o.size / 2
    for (i in 0 until n) {
        x += o[i * 2]
        z += o[i * 2 + 1]
    }
    return x / n to z / n
}

private fun gap(a: Pair<Float, Float>, x: Float, z: Float) = (a.first - x) * (a.first - x) + (a.second - z) * (a.second - z)

/** How often the model's snapshot is rebuilt, at most. */
private const val REFRESH_MS = 600L

/**
 * The model's surfels as fine points, 2 cm on a well-seen wall (2026-11-13, Thomas's workshop
 * scan: 7 cm squares read as mush). Neighbours of the finest level just touch, so a wall seen
 * well reads as a surface and one seen thinly as scattered points: more sweeps, more matter.
 */
private const val MODEL_HALF_SIDE_VOXELS = 0.5f

/** Coarse surfels drawn no wider than level 1 (4 cm): a thin wall shows as sparse, not as tiles. */
private const val MODEL_LEVEL_CAP = 1

/** The model seen from above the phone at this angle. */
private const val MODEL_ELEVATION = 45f

/** The follow's own ease and turn, gentler than the orbit's: the view drifts after the phone. */
private const val FOLLOW_APPROACH_RATE = 2f
private const val FOLLOW_MAX_TURN = 40f

/** Under the back button and the figures, over the line and the recorder. */
private val MODEL_TOP = 72.dp
private val MODEL_BOTTOM = 200.dp

private const val MEASURE_TAG = "ScanMeasure"

/** The live model as the capture last drew it, and where in the window: its result opens on it. */
internal class ScanStill(val bitmap: Bitmap, val bounds: Rect)
