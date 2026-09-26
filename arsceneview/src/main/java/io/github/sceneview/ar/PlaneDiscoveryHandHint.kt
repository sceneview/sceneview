/*
 * Compose-Canvas hand/phone sweep animation for PlaneDiscoveryGuide (#2241).
 *
 * Recreated from scratch (no Lottie, no drawable assets) in the spirit of the animated
 * hand asset in Google ARCore Elements' `PlaneDiscoveryGuide` (Apache 2.0,
 * Copyright 2018 Google LLC).
 */
package io.github.sceneview.ar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Animated "move your phone" hint — a phone glyph sweeping side-to-side above a dashed
 * motion arc, drawn entirely with Compose `Canvas` (#2241: no Lottie dependency, no
 * bitmap/vector asset).
 *
 * The default visual of [PlaneDiscoveryGuide]'s hint phase; public so it can be reused in
 * custom onboarding UIs and `@Preview`s. Fixed white-on-camera-feed styling by default —
 * pass [color] to tint (e.g. `MaterialTheme.colorScheme.onSurface` for a themed host).
 *
 * @param modifier layout modifier; the glyph draws inside a 160×120 dp canvas by default.
 * @param color stroke color of the phone glyph and motion arc. Default white — legible on
 *   a live camera feed in both light and dark themes.
 * @param sweepDurationMs duration of one full left-right-left sweep cycle.
 */
@Composable
fun PlaneDiscoveryHandHint(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    sweepDurationMs: Int = 2_600,
) {
    val contentDesc = stringResource(R.string.sceneview_plane_discovery_hand_animation)
    val transition = rememberInfiniteTransition(label = "planeDiscoveryHandHint")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(sweepDurationMs, easing = LinearEasing)),
        label = "sweepProgress",
    )

    Canvas(
        modifier = modifier
            .size(160.dp, 120.dp)
            .semantics { contentDescription = contentDesc }
    ) {
        // -1..1 oscillation — sine keeps the turn-around at each side smooth.
        val sweep = sin(progress * 2f * PI.toFloat())
        val stroke = 2.5.dp.toPx()

        // Dashed motion arc under the phone, hinting the sweep path.
        val arcTop = size.height * 0.68f
        val arcPath = Path().apply {
            moveTo(size.width * 0.14f, size.height * 0.86f)
            quadraticBezierTo(
                size.width * 0.5f, arcTop,
                size.width * 0.86f, size.height * 0.86f,
            )
        }
        drawPath(
            path = arcPath,
            color = color.copy(alpha = 0.45f),
            style = Stroke(
                width = stroke * 0.8f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(6.dp.toPx(), 6.dp.toPx())
                ),
            ),
        )

        // Phone glyph, sweeping and tilting with the sine phase.
        val phoneWidth = size.width * 0.24f
        val center = Offset(size.width * 0.5f, size.height * 0.42f)
        translate(left = sweep * size.width * 0.20f) {
            rotate(degrees = sweep * 12f, pivot = center) {
                drawCoachPhone(center = center, width = phoneWidth, color = color, stroke = stroke)
            }
        }
    }
}

/**
 * The phone-with-thumb glyph shared by [PlaneDiscoveryHandHint] and [ARCoachingOverlay], so
 * both onboarding surfaces draw the same device. Height is `1.9 × width`, centred on [center].
 *
 * @param fill optional body fill, drawn under the outline — lets the glyph occlude what it
 *   passes in front of (the coaching overlay's wall).
 */
internal fun DrawScope.drawCoachPhone(
    center: Offset,
    width: Float,
    color: Color,
    stroke: Float,
    fill: Color? = null,
) {
    val height = width * 1.9f
    val phoneRect = Rect(
        offset = Offset(center.x - width / 2f, center.y - height / 2f),
        size = Size(width, height),
    )
    val corner = CornerRadius(width * 0.13f)
    if (fill != null) {
        drawRoundRect(color = fill, topLeft = phoneRect.topLeft, size = phoneRect.size, cornerRadius = corner)
    }
    // Body.
    drawRoundRect(
        color = color,
        topLeft = phoneRect.topLeft,
        size = phoneRect.size,
        cornerRadius = corner,
        style = Stroke(width = stroke),
    )
    // Screen inset.
    val inset = width * 0.104f
    drawRoundRect(
        color = color.copy(alpha = color.alpha * 0.5f),
        topLeft = phoneRect.topLeft + Offset(inset, inset),
        size = Size(phoneRect.width - 2 * inset, phoneRect.height - 2 * inset),
        cornerRadius = CornerRadius(width * 0.065f),
        style = Stroke(width = stroke * 0.6f),
    )
    // Thumb resting on the lower edge — the "hand" suggestion.
    drawOval(
        color = color,
        topLeft = Offset(center.x - width * 0.18f, phoneRect.bottom - width * 0.12f),
        size = Size(width * 0.36f, width * 0.5f),
    )
}
