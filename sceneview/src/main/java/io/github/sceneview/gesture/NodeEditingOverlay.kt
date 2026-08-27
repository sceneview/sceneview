package io.github.sceneview.gesture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
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

    val showRotation = NodeEditingKind.Rotate in state.activeKinds
    val showScale = NodeEditingKind.Scale in state.activeKinds
    val showMove = NodeEditingKind.Move in state.activeKinds
    // "Grabbed, mode undecided" — shown from touch-down, so first contact is acknowledged
    // instead of waiting for a detector threshold.
    val showArmed = state.isArmed
    val showSelection = selected && !state.isEditing && !showArmed

    // Breathing halo on the armed ring. One infinite transition, only collected while the
    // armed state is actually on screen.
    val armedPulse = if (showArmed) {
        rememberInfiniteTransition(label = "armed").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(ARMED_PULSE_MILLIS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "armedPulse",
        ).value
    } else {
        0f
    }

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

            // Everything is drawn in a Y-squashed space pivoted on the base point, so the
            // ring and every glyph fused into it lie on the model's ground plane in
            // perspective instead of reading as a flat sticker on the screen.
            scale(scaleX = 1f, scaleY = squash, pivot = base) {
                val hairline = 1.5.dp.toPx()
                val track = 2.dp.toPx()
                val bold = 4.dp.toPx()

                if (showMove) {
                    // Soft contact shadow — grounds the model on its target plane while it
                    // is being dragged, on top of the ring which stays readable on dark
                    // grounds where the shadow alone would not.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(colors.shadow, Color.Transparent),
                            center = base,
                            radius = rx * 1.25f,
                        ),
                        radius = rx * 1.25f,
                        center = base,
                    )
                }

                if (showSelection) {
                    glowRing(base, rx, hairline, colors.selection, coreAlpha = 0.9f)
                }

                if (showArmed) {
                    // Grabbed, mode not yet decided. A translucent disc fills the base
                    // footprint and a double ring breathes around it: an acknowledgement
                    // of the touch, deliberately carrying no direction.
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                colors.accent.copy(alpha = 0.18f + 0.06f * armedPulse),
                                Color.Transparent,
                            ),
                            center = base,
                            radius = rx,
                        ),
                        radius = rx,
                        center = base,
                    )
                    glowRing(base, rx, track, colors.accent, glowAlpha = 0.22f + 0.1f * armedPulse)
                    glowRing(
                        base,
                        rx * (1f + ARMED_RING_SPREAD * armedPulse),
                        hairline,
                        colors.accent,
                        coreAlpha = 0.45f * (1f - armedPulse),
                    )
                }

                if (showMove) {
                    // Four chevrons on the ground-plane axes, pointing outward off the
                    // ring: the mark says "this translates" without naming a direction.
                    glowRing(base, rx, track, colors.accent, coreAlpha = 0.85f)
                    for (angle in AXIS_ANGLES) {
                        drawRingChevron(
                            center = base,
                            radius = rx,
                            angleDegrees = angle,
                            outward = true,
                            size = rx * CHEVRON_RATIO,
                            width = track,
                            color = colors.accent,
                        )
                    }
                }

                if (showScale) {
                    val tint = if (state.scaleLimit != null) colors.limit else colors.accent
                    glowRing(base, rx, track, tint, coreAlpha = 0.9f)
                    // Diagonals, so resize never reads like translate: outward while
                    // growing, inward while shrinking.
                    for (angle in DIAGONAL_ANGLES) {
                        drawRingChevron(
                            center = base,
                            radius = rx,
                            angleDegrees = angle,
                            outward = state.isGrowing,
                            size = rx * CHEVRON_RATIO,
                            width = bold,
                            color = tint,
                        )
                    }
                }

                if (showRotation) {
                    // A faint dial track…
                    glowRing(base, rx, track, colors.accent, coreAlpha = 0.3f, glowAlpha = 0.1f)
                    // …a bold live sweep growing with the twist since the gesture began…
                    val sweep = state.rotationDeltaDegrees
                    val arcTopLeft = Offset(base.x - rx, base.y - rx)
                    val arcSize = Size(rx * 2f, rx * 2f)
                    // Same near/far split as the ring, applied by clipping instead of by
                    // half-arcs: the sweep starts and ends wherever the twist has reached,
                    // so its far portion cannot be expressed as a fixed angular range.
                    for (back in booleanArrayOf(true, false)) {
                        val depth = if (back) BACK_HALF_ALPHA else 1f
                        clipRect(
                            top = if (back) base.y - rx * 2f else base.y,
                            bottom = if (back) base.y else base.y + rx * 2f,
                        ) {
                            drawArc(
                                color = colors.accent.copy(alpha = 0.2f * depth),
                                startAngle = ROTATION_SWEEP_START,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(
                                    width = bold * GLOW_WIDTH_FACTOR,
                                    cap = StrokeCap.Round,
                                ),
                            )
                            drawArc(
                                color = colors.accent.copy(alpha = depth),
                                startAngle = ROTATION_SWEEP_START,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(width = bold, cap = StrokeCap.Round),
                            )
                        }
                    }
                    // …closed by a solid arrowhead tangential to the ring, so the
                    // direction of the twist is legible at a glance.
                    if (abs(sweep) > MIN_SWEEP_FOR_HEAD) {
                        drawSweepArrowHead(
                            center = base,
                            radius = rx,
                            angleDegrees = ROTATION_SWEEP_START + sweep,
                            clockwise = sweep > 0f,
                            size = rx * ARROW_HEAD_RATIO,
                            color = colors.accent,
                        )
                    }
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
        if (node.isDestroyed) {
            // The overlay can outlive the node by a frame (disposal ordering) — never
            // touch released Filament components.
            base = null
            top = null
            return
        }
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

/**
 * Strokes a ring as emissive light rather than as a UI outline: a wide, very transparent
 * bloom pass under a crisp core pass. Two passes are enough to read as glow over a camera
 * frame and cost nothing next to a real blur.
 */
private fun DrawScope.glowRing(
    center: Offset,
    radius: Float,
    width: Float,
    color: Color,
    coreAlpha: Float = 1f,
    glowAlpha: Float = 0.18f,
) {
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arcSize = Size(radius * 2f, radius * 2f)
    // Two half-arcs rather than one circle: the far half is dimmed so the model reads as
    // standing *in* the ring instead of in front of a sticker. The overlay is a flat
    // Compose layer with no depth buffer, so this fade is the only occlusion cue there is
    // — and the split line runs through the ellipse's leftmost and rightmost points, where
    // the curve is tangent to it, so the two halves join without a visible seam.
    for (back in booleanArrayOf(true, false)) {
        val depth = if (back) BACK_HALF_ALPHA else 1f
        val start = if (back) 180f else 0f
        drawArc(
            color = color.copy(alpha = color.alpha * glowAlpha * depth),
            startAngle = start,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = width * GLOW_WIDTH_FACTOR),
        )
        drawArc(
            color = color.copy(alpha = color.alpha * coreAlpha * depth),
            startAngle = start,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = width),
        )
    }
}

/**
 * How much of a glyph's opacity survives at [angleDegrees] on the base ellipse.
 *
 * `1` on the near half (screen-down, in front of the model), falling to
 * [BACK_HALF_ALPHA] at the far point — the same occlusion cue [glowRing] applies, for
 * marks that are drawn at one angle instead of swept around the whole ring.
 */
private fun depthAlpha(angleDegrees: Float): Float {
    val s = sin(angleDegrees * PI.toFloat() / 180f)
    return if (s >= 0f) 1f else BACK_HALF_ALPHA + (1f - BACK_HALF_ALPHA) * (1f + s)
}

/**
 * Draws one chevron pointing along the ring's radius at [angleDegrees], set slightly
 * **off** the ring so it reads as an affordance the ring emits rather than as a notch cut
 * into it (the reference art direction: detached, thin, glowing).
 *
 * @param outward Away from the center when `true`, toward it when `false` — the chevron
 * always points the way the gesture is going.
 */
private fun DrawScope.drawRingChevron(
    center: Offset,
    radius: Float,
    angleDegrees: Float,
    outward: Boolean,
    size: Float,
    width: Float,
    color: Color,
) {
    val a = angleDegrees * PI.toFloat() / 180f
    val radial = Offset(cos(a), sin(a))
    val dir = if (outward) radial else Offset(-radial.x, -radial.y)
    // Chevrons on the far side of the base sit behind the model — dim them to match the
    // ring they belong to (see [depthAlpha]).
    val tint = color.copy(alpha = color.alpha * depthAlpha(angleDegrees))
    // Tip sits just beyond (or inside) the ring, leaving a hairline gap.
    val tipRadius = if (outward) radius + size * CHEVRON_GAP else radius - size * CHEVRON_GAP
    val tip = Offset(center.x + radial.x * tipRadius, center.y + radial.y * tipRadius)
    val back = Offset(-dir.x, -dir.y)
    val normal = Offset(-dir.y, dir.x)
    for (side in intArrayOf(1, -1)) {
        val leg = Offset(
            back.x * (1f - CHEVRON_SPREAD) + normal.x * CHEVRON_SPREAD * side,
            back.y * (1f - CHEVRON_SPREAD) + normal.y * CHEVRON_SPREAD * side,
        )
        val len = hypot(leg.x, leg.y).coerceAtLeast(1e-4f)
        val end = Offset(tip.x + leg.x / len * size, tip.y + leg.y / len * size)
        drawLine(
            color = tint.copy(alpha = tint.alpha * 0.2f),
            start = tip,
            end = end,
            strokeWidth = width * GLOW_WIDTH_FACTOR,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = tip,
            end = end,
            strokeWidth = width,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Solid arrowhead closing the rotation sweep, tangential to the ring and pointing the way
 * the twist is going. Filled (not stroked) so it terminates the arc as one continuous
 * gesture mark — the single most legible cue in the reference art direction.
 */
private fun DrawScope.drawSweepArrowHead(
    center: Offset,
    radius: Float,
    angleDegrees: Float,
    clockwise: Boolean,
    size: Float,
    color: Color,
) {
    val a = angleDegrees * PI.toFloat() / 180f
    val radial = Offset(cos(a), sin(a))
    val tangent = Offset(-radial.y, radial.x).let {
        if (clockwise) it else Offset(-it.x, -it.y)
    }
    val onRing = Offset(center.x + radial.x * radius, center.y + radial.y * radius)
    val tip = Offset(onRing.x + tangent.x * size, onRing.y + tangent.y * size)
    val half = size * ARROW_HEAD_WIDTH
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(onRing.x + radial.x * half, onRing.y + radial.y * half)
        lineTo(onRing.x - radial.x * half, onRing.y - radial.y * half)
        close()
    }
    // A tight bloom hugging the outline — a wide stroke here reads as a misplaced drop
    // shadow rather than as glow, because a stroked triangle grows outward from all three
    // edges at once.
    val tint = color.copy(alpha = color.alpha * depthAlpha(angleDegrees))
    drawPath(
        path,
        color = tint.copy(alpha = tint.alpha * 0.22f),
        style = Stroke(size * ARROW_HEAD_GLOW),
    )
    drawPath(path, color = tint)
}

private const val DEFAULT_HALF_EXTENT = 0.25f

/** Ground-plane axes — the translate affordance. */
private val AXIS_ANGLES = floatArrayOf(0f, 90f, 180f, 270f)

/** Diagonals — the resize affordance, kept off the axes so the two never read alike. */
private val DIAGONAL_ANGLES = floatArrayOf(45f, 135f, 225f, 315f)

/** Chevron leg length as a fraction of the ring radius. */
private const val CHEVRON_RATIO = 0.20f

/** How far the legs open away from the pointing axis, `0` = closed, `1` = perpendicular. */
private const val CHEVRON_SPREAD = 0.72f

/** Gap between the ring and a chevron tip, as a fraction of the chevron size. */
private const val CHEVRON_GAP = 0.45f

/** Bloom pass width, as a multiple of the core stroke. */
private const val GLOW_WIDTH_FACTOR = 4f

/** Rotation arrowhead length as a fraction of the ring radius. */
private const val ARROW_HEAD_RATIO = 0.20f

/** Arrowhead half-base, as a fraction of its length. */
private const val ARROW_HEAD_WIDTH = 0.42f

/** Arrowhead bloom stroke, as a fraction of its length — see [drawSweepArrowHead]. */
private const val ARROW_HEAD_GLOW = 0.22f

/**
 * Opacity kept by the half of the base ring that lies behind the model.
 *
 * The overlay is a flat Compose layer over the render surface with no depth buffer, so
 * nothing can truly occlude it. Dimming the far half is the substitute cue, and it is
 * what makes the model read as standing inside the ring rather than behind a decal.
 */
private const val BACK_HALF_ALPHA = 0.18f

/** How far the outer armed ring drifts out over one pulse. */
private const val ARMED_RING_SPREAD = 0.16f

/** Twelve o'clock: the sweep grows from the far side of the ellipse, in view of the model. */
private const val ROTATION_SWEEP_START = -90f

/** Below this much twist the arrowhead would overlap the arc's own round cap. */
private const val MIN_SWEEP_FOR_HEAD = 6f

private const val ARMED_PULSE_MILLIS = 900

/** See the [NodeScreenAnchors.update] comment — AABB corner → visual footprint. */
private const val RING_RADIUS_FACTOR = 0.85f

/** Keep the base ellipse readable even when the camera is nearly at node level. */
private const val MIN_RING_SQUASH = 0.10f

private val BADGE_GAP = 14.dp

// The badge is anchored by its offset's top-left; nudge it toward center so the pill
// visually centers on the anchor without a two-pass layout measurement.
private const val BADGE_HALF_WIDTH_HINT_PX = 28
private const val BADGE_HALF_HEIGHT_HINT_PX = 14
