package io.github.sceneview.demo.demos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import io.github.sceneview.demo.theme.SceneViewTokens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.filament.Engine
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.ArDebugSession
import io.github.sceneview.demo.demos.internal.DebugGroup
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.OrbitBand
import io.github.sceneview.demo.theme.themedStageChrome
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The scan's main view while capturing: the model being built, in the colours the camera saw,
 * on the stage's own ground (2026-11-13, Thomas: « on s'en fiche de la caméra… on est en train
 * de construire un modèle 3D »). The camera stays in a small window cut in that ground, where
 * the captured points sit on the world. The view's heading is fixed and only its framing grows
 * with the model, so the phone's turns never spin it. The snapshot is rebuilt on a worker at
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
                        mesh = DenseSurfels.mesh(next.cloud, next.voxelM, halfSideVoxels = MODEL_HALF_SIDE_VOXELS),
                        atlas = DenseSurfels.atlas(next.cloud, ReplayDenseLayer.DENSE_FALLBACK_COLOR),
                        whole = next.whole,
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
        animationSpec = SceneViewTokens.Motion.focus(),
        label = "scan-model-in",
    )
    val present = bounds != null
    LaunchedEffect(present) { onShown(present) }
    DisposableEffect(Unit) { onDispose { onShown(false) } }
    if (shown == 0f) return
    val orbit = remember(media) { ArDebugOrbitCamera(drift = false, band = OrbitBand.CARD) }
    val drawn = remember(media) { media.withLiveDense(overCamera = { false }) { current } }
    val chrome = themedStageChrome()
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
                modifier = Modifier.fillMaxSize().padding(top = statusTop + MODEL_TOP, bottom = navBottom + MODEL_BOTTOM),
                compact = false,
                transparent = true,
                maxFps = 30,
                measuresRoom = false,
                visible = { it == DebugGroup.Points },
                drivesSession = false,
                follow = {
                    ArDebugFraming.home(
                        currentBounds, ArDebugFraming.HOME_AZIMUTH, orbit.verticalFovDegrees,
                        orbit.aspect, elevationDegrees = MODEL_ELEVATION, band = OrbitBand.CARD, fill = 1f,
                    )
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

/** How often the model's snapshot is rebuilt, at most. */
private const val REFRESH_MS = 600L

/** The model's surfels a little wider than saved, so the growing room reads as one surface. */
private const val MODEL_HALF_SIDE_VOXELS = 1f

/** The model seen from above at this angle, its heading fixed. */
private const val MODEL_ELEVATION = 40f

/** Under the back button and the figures, over the line and the recorder. */
private val MODEL_TOP = 72.dp
private val MODEL_BOTTOM = 200.dp
