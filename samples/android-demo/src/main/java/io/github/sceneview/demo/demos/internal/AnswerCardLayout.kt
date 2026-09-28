package io.github.sceneview.demo.demos.internal

/*
 * Screen-space layout for Point & Ask's world-anchored answer cards (#4071).
 *
 * The cards are `ViewNode`s pinned in the room above the point the user tapped. Nothing kept
 * them on screen or apart: a card pinned near an edge ran off it, and two answers pinned a
 * hand's width apart drew one over the other. Every AR frame, the demo projects each card to
 * the screen, runs [layoutAnswerCards], and moves each card in the plane facing the camera by
 * the returned offset ([ScreenProjection.screenOffsetToWorld]). The card stays anchored: once
 * there is room again, it slides back over its point.
 *
 * Pure float math with no ARCore, Filament or Compose types, so `AnswerCardLayoutTest` pins the
 * invariants on the JVM.
 */

/** A card to lay out: where its anchor puts its centre on screen, and its size, all in px. */
internal data class CardRequest(
    val id: Int,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * Where a card ends up: the screen offset from its anchored centre, in px (y down). When
 * [visible] is false the card could not fit without covering a newer one and is hidden until
 * there is room again.
 */
internal data class CardPlacement(
    val id: Int,
    val offsetX: Float,
    val offsetY: Float,
    val visible: Boolean,
)

/** The part of the screen a card may occupy, in px: the viewport minus insets and chrome. */
internal data class SafeArea(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Lays [requests] out inside [safe], at least [gapPx] apart.
 *
 * [requests] is in priority order. The first card keeps the position closest to its anchor
 * that is inside [safe]. Each following card takes the nearest position that is inside [safe]
 * and clear of every card already placed: its own clamped position if that is free, otherwise
 * just above, below, left or right of a placed card. A card with no such position is hidden
 * ([CardPlacement.visible] = false) rather than drawn over another. Put the newest answer first
 * so the one being read never moves away from what it describes.
 *
 * A card larger than [safe] along an axis is centred on that axis. It cannot fit, and centring
 * shows the most of it.
 */
internal fun layoutAnswerCards(
    requests: List<CardRequest>,
    safe: SafeArea,
    gapPx: Float,
): List<CardPlacement> {
    val placed = ArrayList<Box>(requests.size)
    return requests.map { request ->
        val halfW = request.width / 2f
        val halfH = request.height / 2f
        fun clampX(x: Float) = clampCenter(x, halfW, safe.left, safe.right)
        fun clampY(y: Float) = clampCenter(y, halfH, safe.top, safe.bottom)
        val homeX = clampX(request.centerX)
        val homeY = clampY(request.centerY)

        val candidates = ArrayList<Pair<Float, Float>>(1 + placed.size * 4)
        candidates += homeX to homeY
        for (other in placed) {
            candidates += homeX to clampY(other.top - gapPx - halfH)
            candidates += homeX to clampY(other.bottom + gapPx + halfH)
            candidates += clampX(other.left - gapPx - halfW) to homeY
            candidates += clampX(other.right + gapPx + halfW) to homeY
        }
        val best = candidates
            .filter { (x, y) ->
                val box = Box.around(x, y, halfW, halfH)
                placed.none { it.overlaps(box, gapPx) }
            }
            // `minByOrNull` keeps the first of equal candidates, so ties resolve the same way
            // every frame and a card does not flip sides while the camera holds still.
            .minByOrNull { (x, y) -> (x - homeX) * (x - homeX) + (y - homeY) * (y - homeY) }

        if (best == null) {
            CardPlacement(request.id, 0f, 0f, visible = false)
        } else {
            placed += Box.around(best.first, best.second, halfW, halfH)
            CardPlacement(
                request.id,
                offsetX = best.first - request.centerX,
                offsetY = best.second - request.centerY,
                visible = true,
            )
        }
    }
}

/** [center] moved the least so a span of half-size [half] fits in [low]..[high]. */
private fun clampCenter(center: Float, half: Float, low: Float, high: Float): Float =
    if (high - low <= 2f * half) (low + high) / 2f else center.coerceIn(low + half, high - half)

private data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /**
     * Closer than [gap] on both axes. Half a pixel of slack lets cards sit exactly [gap] apart
     * despite float rounding.
     */
    fun overlaps(other: Box, gap: Float): Boolean {
        val g = gap - OVERLAP_SLACK_PX
        return left < other.right + g && other.left < right + g &&
            top < other.bottom + g && other.top < bottom + g
    }

    companion object {
        fun around(x: Float, y: Float, halfW: Float, halfH: Float) =
            Box(x - halfW, y - halfH, x + halfW, y + halfH)
    }
}

private const val OVERLAP_SLACK_PX = 0.5f

/** A world point on screen: position in px (y down) and depth in metres in front of the camera. */
internal data class ProjectedPoint(val x: Float, val y: Float, val depth: Float)

/**
 * Projects world points to the screen, and maps screen offsets back to world offsets, from an
 * ARCore camera's view and projection matrices (column-major, as `Camera.getViewMatrix` and
 * `Camera.getProjectionMatrix` return them) and the viewport size in px.
 */
internal class ScreenProjection(
    private val view: FloatArray,
    private val projection: FloatArray,
    val widthPx: Float,
    val heightPx: Float,
) {
    /** Screen position and depth of the world point ([x], [y], [z]), or null when behind the camera. */
    fun project(x: Float, y: Float, z: Float): ProjectedPoint? {
        val vx = view[0] * x + view[4] * y + view[8] * z + view[12]
        val vy = view[1] * x + view[5] * y + view[9] * z + view[13]
        val vz = view[2] * x + view[6] * y + view[10] * z + view[14]
        val depth = -vz
        if (depth <= MIN_DEPTH_M) return null
        val p = projection
        val cx = p[0] * vx + p[4] * vy + p[8] * vz + p[12]
        val cy = p[1] * vx + p[5] * vy + p[9] * vz + p[13]
        val cw = p[3] * vx + p[7] * vy + p[11] * vz + p[15]
        if (cw <= 0f) return null
        return ProjectedPoint(
            x = (cx / cw + 1f) * 0.5f * widthPx,
            y = (1f - cy / cw) * 0.5f * heightPx,
            depth = depth,
        )
    }

    /** How many horizontal px one metre spans, facing the camera at [depth]. */
    fun pixelsPerMeterX(depth: Float): Float = projection[0] * 0.5f * widthPx / depth

    /** How many vertical px one metre spans, facing the camera at [depth]. */
    fun pixelsPerMeterY(depth: Float): Float = projection[5] * 0.5f * heightPx / depth

    /**
     * The world-space vector that moves a point at [depth] by ([dxPx], [dyPx]) on screen (y
     * down), in the plane facing the camera, so the point keeps its depth.
     */
    fun screenOffsetToWorld(dxPx: Float, dyPx: Float, depth: Float): FloatArray {
        val right = dxPx / pixelsPerMeterX(depth)
        val up = -dyPx / pixelsPerMeterY(depth)
        // The view matrix's first two rows are the camera's right and up axes in world space.
        return floatArrayOf(
            view[0] * right + view[1] * up,
            view[4] * right + view[5] * up,
            view[8] * right + view[9] * up,
        )
    }

    private companion object {
        const val MIN_DEPTH_M = 0.05f
    }
}
