package io.github.sceneview.gesture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.filament.View
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.components.RenderableComponent
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.utils.worldToScreen
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Colors used by [NodeEditingOverlay].
 *
 * Defaults follow the SceneView AR-surface rules: the ground behind the overlay is an
 * arbitrary camera frame (or 3D scene), not an app surface, so the badge scrim and
 * accents keep their dark-scheme values in both themes.
 */
data class NodeEditingOverlayColors(
    /** Rotation ring / arc and active-gesture accents. */
    val accent: Color = Color(0xFFA4C1FF),
    /** Selection ring (gesture-idle). */
    val selection: Color = Color.White,
    /** Badge pill background — near-opaque dark scrim over the camera feed. */
    val badgeBackground: Color = Color(0xE6000000),
    /** Badge pill hairline. */
    val badgeBorder: Color = Color(0x29FFFFFF),
    /** Badge text. */
    val badgeText: Color = Color.White,
    /** Border/text tint while the pinch is pressing against `editableScaleRange`. */
    val limit: Color = Color(0xFFFFB4AB),
    /** Soft contact shadow under the node while dragging. */
    val shadow: Color = Color(0x80000000),
)

/**
 * Opt-in on-model gesture feedback, drawn over the scene in Compose.
 *
 * Renders, anchored to [NodeEditingFeedbackState.node] by world→screen projection:
 * - **Selection** (when [selected] and no gesture is active): a white ring around the
 *   node's base.
 * - **Rotate** (two-finger twist): an accent ring around the base with a live sweep arc
 *   and a yaw-angle badge.
 * - **Scale** (pinch): a live percentage badge above the node that bounces and tints
 *   [NodeEditingOverlayColors.limit] when the pinch presses against
 *   [Node.editableScaleRange].
 * - **Move** (drag): a soft contact shadow following the node's base.
 *
 * Place it in a `Box` **over** the scene composable, sized to exactly cover it, and pass
 * the same Filament [view] the scene renders with:
 *
 * ```kotlin
 * val engine = rememberEngine()
 * val view = rememberView(engine)
 * Box {
 *     SceneView(modifier = Modifier.fillMaxSize(), engine = engine, view = view, …)
 *     modelNode?.let { node ->
 *         NodeEditingOverlay(
 *             state = rememberNodeEditingFeedback(node),
 *             view = view,
 *             modifier = Modifier.matchParentSize(),
 *             selected = isSelected,
 *         )
 *     }
 * }
 * ```
 *
 * Nothing is drawn while the node is behind the camera or nothing is active — the
 * overlay never intercepts touch input.
 */
@Composable
fun NodeEditingOverlay(
    state: NodeEditingFeedbackState,
    view: View,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    colors: NodeEditingOverlayColors = NodeEditingOverlayColors(),
) {
    val anchors = remember(state.node, view) { NodeScreenAnchors() }

    // Re-project the node every displayed frame: the node moves under the gesture and
    // the camera moves in AR, so the anchors are only valid for one frame.
    LaunchedEffect(state.node, view) {
        while (true) {
            withFrameNanos { }
            anchors.update(state.node, view)
        }
    }

    val showSelection = selected && !state.isEditing
    val showRotation = NodeEditingKind.Rotate in state.activeKinds
    val showScale = NodeEditingKind.Scale in state.activeKinds
    val showMove = NodeEditingKind.Move in state.activeKinds

    // Bounce re-triggered on every rejected pinch update while at a scale limit.
    val badgeBounce = remember { Animatable(1f) }
    LaunchedEffect(state.scaleLimitHits) {
        if (state.scaleLimitHits > 0) {
            badgeBounce.snapTo(1f)
            badgeBounce.animateTo(1.18f, spring(stiffness = Spring.StiffnessHigh))
            badgeBounce.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    Box(modifier = modifier) {
        var overlaySize by remember { mutableStateOf(Size.Zero) }

        Canvas(
            modifier = Modifier
                .matchParentSize()
                .onSizeChanged { overlaySize = Size(it.width.toFloat(), it.height.toFloat()) }
        ) {
            val base = anchors.baseInCanvas(size) ?: return@Canvas
            val rx = anchors.radiusXInCanvas(size)
            val ry = anchors.radiusYInCanvas(size)
            if (rx <= 0f) return@Canvas
            val squash = (ry / rx).coerceIn(MIN_RING_SQUASH, 1f)

            if (showMove) {
                // Soft contact shadow following the node's base — grounds the model on
                // its target plane while it is being dragged. The thin accent ring keeps
                // the placement readable on grounds too dark for the shadow itself.
                scale(scaleX = 1f, scaleY = squash, pivot = base) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(colors.shadow, Color.Transparent),
                            center = base,
                            radius = rx * 1.25f,
                        ),
                        radius = rx * 1.25f,
                        center = base,
                    )
                    drawCircle(
                        color = colors.accent.copy(alpha = 0.7f),
                        radius = rx,
                        center = base,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }

            if (showSelection) {
                scale(scaleX = 1f, scaleY = squash, pivot = base) {
                    drawCircle(
                        color = colors.selection.copy(alpha = 0.25f),
                        radius = rx,
                        center = base,
                        style = Stroke(width = 6.dp.toPx()),
                    )
                    drawCircle(
                        color = colors.selection.copy(alpha = 0.9f),
                        radius = rx,
                        center = base,
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            }

            if (showRotation) {
                scale(scaleX = 1f, scaleY = squash, pivot = base) {
                    // Faint full track…
                    drawCircle(
                        color = colors.accent.copy(alpha = 0.3f),
                        radius = rx,
                        center = base,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    // …with a bold live sweep growing with the twist since the gesture
                    // began — thicker and full-opacity so it separates from the track.
                    drawArc(
                        color = colors.accent,
                        startAngle = -90f,
                        sweepAngle = state.rotationDeltaDegrees,
                        useCenter = false,
                        topLeft = Offset(base.x - rx, base.y - rx),
                        size = Size(rx * 2f, rx * 2f),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }

        val density = LocalDensity.current
        val badgeBase = anchors.baseInCanvas(overlaySize)
        val badgeTop = anchors.topInCanvas(overlaySize)

        if (showRotation && badgeBase != null && overlaySize != Size.Zero) {
            val ry = anchors.radiusYInCanvas(overlaySize)
            FeedbackBadge(
                text = "${state.yawDegrees.roundToInt()}°",
                textColor = colors.badgeText,
                background = colors.badgeBackground,
                border = colors.badgeBorder,
                modifier = Modifier.offset {
                    IntOffset(
                        badgeBase.x.roundToInt(),
                        (badgeBase.y + ry + with(density) { BADGE_GAP.toPx() }).roundToInt(),
                    )
                },
            )
        }

        if (showScale && badgeTop != null && overlaySize != Size.Zero) {
            val atLimit = state.scaleLimit != null
            FeedbackBadge(
                text = "${state.scalePercent.roundToInt()} %",
                textColor = if (atLimit) colors.limit else colors.badgeText,
                background = colors.badgeBackground,
                border = if (atLimit) colors.limit else colors.badgeBorder,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            badgeTop.x.roundToInt(),
                            (badgeTop.y - with(density) { BADGE_GAP.toPx() }).roundToInt(),
                        )
                    }
                    .graphicsLayer {
                        scaleX = badgeBounce.value
                        scaleY = badgeBounce.value
                    },
            )
        }
    }
}

/** Dark scrim pill with a hairline border — centered horizontally on its offset. */
@Composable
private fun FeedbackBadge(
    text: String,
    textColor: Color,
    background: Color,
    border: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        BasicText(
            text = text,
            style = TextStyle(
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = Modifier
                .offset { IntOffset(-BADGE_HALF_WIDTH_HINT_PX, -BADGE_HALF_HEIGHT_HINT_PX) }
                .background(background, RoundedCornerShape(50))
                .border(1.dp, border, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * One frame's world→screen projection of a node's base circle and top anchor.
 *
 * Raw values are in Filament viewport pixels together with the viewport size, so the
 * canvas can rescale them when the composable's size differs from the render surface.
 * `null` anchors mean the point is behind the camera — skip drawing, never approximate.
 */
private class NodeScreenAnchors {
    private var base by mutableStateOf<Offset?>(null)
    private var top by mutableStateOf<Offset?>(null)
    private var radiusX by mutableFloatStateOf(0f)
    private var radiusY by mutableFloatStateOf(0f)
    private var viewportSize by mutableStateOf(Size.Zero)

    fun update(node: Node, view: View) {
        val (center, halfExtent) = node.localBounds()
        val transform = node.worldTransform
        fun world(p: Float3) = (transform * Float4(p, 1f)).xyz

        // The AABB corner over-estimates the visual footprint of roundish models by up
        // to √2 — pull the ring in so it reads as hugging the base, not as a pedestal.
        val radius = max(halfExtent.x, halfExtent.z) * RING_RADIUS_FACTOR
        val baseY = center.y - halfExtent.y
        // Four points of the base circle. The on-screen ellipse is fitted to their
        // projections rather than centered on the projected 3D center — in perspective a
        // floor circle's near edge drops much further below the center than its far edge
        // rises above it, so a center-symmetric ellipse would overlap the model.
        val xP = view.worldToScreen(world(Float3(center.x + radius, baseY, center.z)))
        val xM = view.worldToScreen(world(Float3(center.x - radius, baseY, center.z)))
        val zP = view.worldToScreen(world(Float3(center.x, baseY, center.z + radius)))
        val zM = view.worldToScreen(world(Float3(center.x, baseY, center.z - radius)))
        val topPx = view.worldToScreen(world(center + Float3(0f, halfExtent.y, 0f)))

        viewportSize = Size(view.viewport.width.toFloat(), view.viewport.height.toFloat())
        top = topPx?.let { Offset(it.x, it.y) }
        if (xP != null && xM != null && zP != null && zM != null) {
            base = Offset(
                (xP.x + xM.x + zP.x + zM.x) / 4f,
                (xP.y + xM.y + zP.y + zM.y) / 4f,
            )
            // Semi-axes from the two projected chords: the X pair spans the ellipse
            // horizontally, the Z (depth) pair spans it vertically.
            radiusX = (abs(xP.x - xM.x) / 2f).coerceAtLeast(1f)
            radiusY = max(abs(zP.y - zM.y), abs(xP.y - xM.y)) / 2f
        } else {
            base = null
        }
    }

    private fun canvasScale(canvasSize: Size): Offset? {
        if (canvasSize == Size.Zero || viewportSize == Size.Zero) return null
        return Offset(canvasSize.width / viewportSize.width, canvasSize.height / viewportSize.height)
    }

    fun baseInCanvas(canvasSize: Size): Offset? = canvasScale(canvasSize)?.let { s ->
        base?.let { Offset(it.x * s.x, it.y * s.y) }
    }

    fun topInCanvas(canvasSize: Size): Offset? = canvasScale(canvasSize)?.let { s ->
        top?.let { Offset(it.x * s.x, it.y * s.y) }
    }

    fun radiusXInCanvas(canvasSize: Size): Float =
        canvasScale(canvasSize)?.let { radiusX * it.x } ?: 0f

    fun radiusYInCanvas(canvasSize: Size): Float =
        canvasScale(canvasSize)?.let { radiusY * it.y } ?: 0f
}

/**
 * Local-space bounds used to anchor the feedback visuals.
 *
 * An empty AABB is a real state for a model whose geometry is not ready yet
 * (`sanitizeEmptyBoundingBoxes`) — fall back to a small default so the ring never
 * collapses to zero.
 */
private fun Node.localBounds(): Pair<Float3, Float3> {
    val (center, halfExtent) = when (this) {
        is ModelNode -> center to halfExtent
        is RenderableComponent -> axisAlignedBoundingBox.let {
            Float3(it.center[0], it.center[1], it.center[2]) to
                Float3(it.halfExtent[0], it.halfExtent[1], it.halfExtent[2])
        }
        else -> Float3(0f) to Float3(0f)
    }
    return if (halfExtent.x <= 0f && halfExtent.y <= 0f && halfExtent.z <= 0f) {
        center to Float3(DEFAULT_HALF_EXTENT)
    } else {
        center to halfExtent
    }
}

private const val DEFAULT_HALF_EXTENT = 0.25f

/** See the [NodeScreenAnchors.update] comment — AABB corner → visual footprint. */
private const val RING_RADIUS_FACTOR = 0.85f

/** Keep the base ellipse readable even when the camera is nearly at node level. */
private const val MIN_RING_SQUASH = 0.10f

private val BADGE_GAP = 14.dp

// The badge is anchored by its offset's top-left; nudge it toward center so the pill
// visually centers on the anchor without a two-pass layout measurement.
private const val BADGE_HALF_WIDTH_HINT_PX = 28
private const val BADGE_HALF_HEIGHT_HINT_PX = 14
