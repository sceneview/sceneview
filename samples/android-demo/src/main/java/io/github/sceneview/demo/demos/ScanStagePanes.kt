package io.github.sceneview.demo.demos

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.PaneTransform
import io.github.sceneview.demo.demos.internal.ScanStageLayout
import io.github.sceneview.demo.demos.internal.StageRect
import io.github.sceneview.demo.demos.internal.ThumbCorner
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Where the two panes of a room's live scan are drawn, eased from one place to the next: the
 * camera shrinking into its thumbnail as the scan starts, the map growing out of the corner the
 * 3D preview sat in before it, the two trading places on a tap, a dragged thumbnail settling in
 * its corner. Both panes stay laid out over the whole stage; only their drawing moves
 * ([stagePane]), so neither view is resized.
 */
@Stable
internal class ScanStagePanes(private val scope: CoroutineScope) {
    private val camera = Animatable(Rect.Zero, Rect.VectorConverter)
    private val map = Animatable(Rect.Zero, Rect.VectorConverter)
    private val mapAlpha = Animatable(0f)
    private val cameraAlpha = Animatable(1f)
    private var cameraPlaced = false
    private var mapShown = false
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var dragJob: Job? = null

    /**
     * Whether the camera pane is drawn at all. Once faded out on the finish screen it drops under
     * the map, whose touches it would otherwise take, invisible, in the thumbnail's corner.
     */
    val cameraShown: Boolean by derivedStateOf { cameraAlpha.value > 0f }

    /** The camera pane's rectangle now, null while it is drawn as laid out (before a measure). */
    fun cameraRect(): StageRect? = if (cameraPlaced) camera.value.toStage() else null

    /** The map pane's rectangle now. */
    fun mapRect(): StageRect = map.value.toStage()

    fun mapAlpha(): Float = mapAlpha.value

    fun cameraAlpha(): Float = cameraAlpha.value

    /** The thumbnail's rectangle now: the camera's, or the map's once swapped. */
    fun thumbRect(mapFirst: Boolean): StageRect = if (mapFirst) camera.value.toStage() else map.value.toStage()

    internal suspend fun moveCamera(target: StageRect) {
        if (target.width <= 0f || target.height <= 0f) return
        if (!cameraPlaced) {
            camera.snapTo(target.toRect())
            cameraPlaced = true
            return
        }
        camera.animateTo(target.toRect(), MOVE)
    }

    internal suspend fun moveMap(target: StageRect?, entry: StageRect?) {
        if (target == null) {
            mapShown = false
            mapAlpha.snapTo(0f)
            return
        }
        if (!mapShown) {
            mapShown = true
            map.snapTo((entry ?: target).toRect())
        }
        coroutineScope {
            launch { mapAlpha.animateTo(1f, FADE) }
            launch { map.animateTo(target.toRect(), MOVE) }
        }
    }

    internal suspend fun showCamera(shown: Boolean) = cameraAlpha.animateTo(if (shown) 1f else 0f, FADE)

    /**
     * A drag of the thumbnail by ([dx], [dy]): it follows the finger. The deltas add up while a
     * move is pending, one coroutine for the drag rather than one per pointer event.
     */
    fun dragBy(mapFirst: Boolean, dx: Float, dy: Float) {
        pendingDx += dx
        pendingDy += dy
        if (dragJob?.isActive == true) return
        val pane = if (mapFirst) camera else map
        dragJob = scope.launch {
            while (pendingDx != 0f || pendingDy != 0f) {
                val x = pendingDx
                val y = pendingDy
                pendingDx = 0f
                pendingDy = 0f
                pane.snapTo(pane.value.translate(x, y))
            }
        }
    }

    /** The thumbnail let go: eased into [target], its corner's place. */
    fun settle(mapFirst: Boolean, target: StageRect) {
        dragJob?.cancel()
        pendingDx = 0f
        pendingDy = 0f
        val pane = if (mapFirst) camera else map
        scope.launch { pane.animateTo(target.toRect(), MOVE) }
    }

    private companion object {
        val MOVE = tween<Rect>(durationMillis = 480, easing = FastOutSlowInEasing)
        val FADE = tween<Float>(durationMillis = 320, easing = FastOutSlowInEasing)
    }
}

/**
 * The panes of a stage [stageWidth] × [stageHeight] laid out by [layout] (null: the camera over
 * the whole stage, no map); [cameraHidden] fades the camera's thumbnail out, on the finish screen.
 */
@Composable
internal fun rememberScanStagePanes(
    stageWidth: Float,
    stageHeight: Float,
    layout: ScanStageLayout?,
    cameraHidden: Boolean,
): ScanStagePanes {
    val scope = rememberCoroutineScope()
    val panes = remember { ScanStagePanes(scope) }
    val cameraTarget = layout?.camera ?: StageRect(0f, 0f, stageWidth, stageHeight)
    val mapTarget = layout?.map
    // The map grows out of the top corner the 3D preview sat in before the scan.
    val entry = layout?.thumbAt(ThumbCorner.TopEnd)
    LaunchedEffect(cameraTarget) { panes.moveCamera(cameraTarget) }
    LaunchedEffect(mapTarget) { panes.moveMap(mapTarget, entry) }
    LaunchedEffect(cameraHidden) { panes.showCamera(!cameraHidden) }
    return panes
}

/**
 * Draws a pane laid out over the whole stage into [rect] (read at draw time, so an animation or a
 * drag moves it without recomposing), corners rounded [radius] pixels, at [alpha]; null draws it
 * as laid out. A transform, never a resize: the views in it keep their size and swap chain.
 */
internal fun Modifier.stagePane(rect: () -> StageRect?, radius: Float, alpha: () -> Float = { 1f }): Modifier =
    graphicsLayer {
        this.alpha = alpha()
        val target = rect() ?: return@graphicsLayer
        val t = PaneTransform.of(size.width, size.height, target)
        if (t.scale == 1f && t.translationX == 0f && t.translationY == 0f) return@graphicsLayer
        scaleX = t.scale
        scaleY = t.scale
        translationX = t.translationX
        translationY = t.translationY
        transformOrigin = TransformOrigin.Center
        shape = CropShape(t.crop, radius / t.scale)
        clip = true
    }

/** [crop] of a pane, corners rounded [radius], in the pane's own pixels. */
private data class CropShape(val crop: StageRect, val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(crop.left, crop.top, crop.right, crop.bottom, CornerRadius(radius)))
}

/**
 * The thumbnail's handle, over it: a tap swaps the two panes, a drag moves the thumbnail and lets
 * it settle in the nearest corner. It takes the thumbnail's touches, so a tap on the small camera
 * places nothing in the room. [layout] is the stage's at rest.
 */
@Composable
internal fun ScanThumbHandle(
    panes: ScanStagePanes,
    layout: ScanStageLayout,
    onSwap: () -> Unit,
    onCorner: (ThumbCorner) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapFirst = layout.mapFirst
    val density = LocalDensity.current
    val label = stringResource(if (mapFirst) R.string.scan_live_map_swap_camera else R.string.scan_live_map_swap_map)
    val shape = RoundedCornerShape(ThumbRadius)
    val size = with(density) { androidx.compose.ui.unit.DpSize(layout.thumb.width.toDp(), layout.thumb.height.toDp()) }
    Box(
        modifier = modifier
            .offset {
                val r = panes.thumbRect(mapFirst)
                IntOffset(r.left.roundToInt(), r.top.roundToInt())
            }
            .size(size)
            .border(ThumbBorder, ArOverlay.onScrim.copy(alpha = BORDER_ALPHA), shape)
            .pointerInput(layout) { detectTapGestures(onTap = { onSwap() }) }
            .pointerInput(layout) {
                detectDragGestures(
                    onDrag = { change, delta ->
                        change.consume()
                        panes.dragBy(mapFirst, delta.x, delta.y)
                    },
                    onDragEnd = {
                        val r = panes.thumbRect(mapFirst)
                        val corner = ScanStageLayout.nearestCorner(
                            r.left + r.width / 2f,
                            r.top + r.height / 2f,
                            layout.main.width,
                            layout.clearTop,
                            layout.clearBottom,
                        )
                        panes.settle(mapFirst, layout.thumbAt(corner))
                        onCorner(corner)
                    },
                    onDragCancel = { panes.settle(mapFirst, layout.thumb) },
                )
            }
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label) {
                    onSwap()
                    true
                }
            }
            .testTag(SCAN_LIVE_MAP_SWAP_TAG),
    ) {
        Icon(
            imageVector = Icons.Rounded.SwapHoriz,
            contentDescription = null,
            tint = ArOverlay.onScrim,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Space.xs)
                .clip(RoundedCornerShape(SceneViewTokens.Radius.full))
                .background(ArOverlay.scrimDark)
                .padding(Space.xs / 2)
                .size(BadgeSize),
        )
    }
}

private fun Rect.toStage() = StageRect(left, top, width, height)

private fun StageRect.toRect() = Rect(left, top, right, bottom)

/** The thumbnail's corners: the radius of the stage's cards. */
internal val ThumbRadius = SceneViewTokens.Radius.md

private val ThumbBorder = Space.xs / 4
private val BadgeSize = Space.md
private const val BORDER_ALPHA = 0.7f

/**
 * Where the status card ends and where the controls start over the 3D Scan stage, in root pixels,
 * NaN until measured. Each edge has one writer, and it holds: the card's lowest bottom, the
 * controls' highest top. A coaching line wrapping, a pill showing up or the controls changing on
 * pause would otherwise move the thumbnail, the map's framing and cancel a drag in progress.
 */
@Stable
internal class StageEdges {
    var cardBottom by mutableFloatStateOf(Float.NaN)
        private set
    var controlsTop by mutableFloatStateOf(Float.NaN)
        private set

    fun card(bottom: Float) {
        cardBottom = ScanStageLayout.holdLowest(cardBottom, bottom)
    }

    fun controls(top: Float) {
        controlsTop = ScanStageLayout.holdHighest(controlsTop, top)
    }
}

/** Measures the status card's bottom edge into [edges] while [measuring]. */
internal fun Modifier.cardEdge(edges: StageEdges, measuring: Boolean): Modifier =
    onGloballyPositioned { if (measuring) edges.card(it.positionInRoot().y + it.size.height) }

/** Measures the controls' top edge into [edges] while [measuring]. */
internal fun Modifier.controlsEdge(edges: StageEdges, measuring: Boolean): Modifier =
    onGloballyPositioned { if (measuring) edges.controls(it.positionInRoot().y) }
