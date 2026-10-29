package io.github.sceneview.demo.demos.internal

import kotlin.math.max
import kotlin.math.min

/** A rectangle of the 3D Scan stage, in pixels from its top-left corner. */
data class StageRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val area: Float get() = width * height

    companion object {
        /** The rectangle [t] of the way from [from] to [to], each edge eased alike. */
        fun lerp(from: StageRect, to: StageRect, t: Float): StageRect = StageRect(
            left = from.left + (to.left - from.left) * t,
            top = from.top + (to.top - from.top) * t,
            width = from.width + (to.width - from.width) * t,
            height = from.height + (to.height - from.height) * t,
        )
    }
}

/**
 * How a pane laid out over the whole stage is drawn into a rectangle of it, without resizing its
 * views (each resize rebuilds a swap chain): scaled alike on both axes about its centre to cover
 * the rectangle, moved there, and cropped to [crop] (in the pane's own, unscaled pixels).
 */
data class PaneTransform(val scale: Float, val translationX: Float, val translationY: Float, val crop: StageRect) {
    companion object {
        /** The transform drawing a [width] × [height] pane into [rect]. */
        fun of(width: Float, height: Float, rect: StageRect): PaneTransform {
            if (width <= 0f || height <= 0f) return PaneTransform(1f, 0f, 0f, StageRect(0f, 0f, width, height))
            val scale = max(rect.width / width, rect.height / height).coerceAtLeast(MIN_SCALE)
            val cropWidth = (rect.width / scale).coerceAtMost(width)
            val cropHeight = (rect.height / scale).coerceAtMost(height)
            return PaneTransform(
                scale = scale,
                translationX = rect.left + rect.width / 2f - width / 2f,
                translationY = rect.top + rect.height / 2f - height / 2f,
                crop = StageRect((width - cropWidth) / 2f, (height - cropHeight) / 2f, cropWidth, cropHeight),
            )
        }

        private const val MIN_SCALE = 0.01f
    }
}

/** Where the thumbnail of a room's live scan sits, between the status card and the controls. */
enum class ThumbCorner { TopStart, TopEnd, BottomStart, BottomEnd }

/**
 * How a room's live scan shares the stage: the live 3D map over the whole of it, the room growing
 * as it is scanned, and the camera in a thumbnail in one corner of the band between the status
 * card and the controls — Polycam's and Scaniverse's live overview. [mapFirst] off swaps the two:
 * the camera takes the stage, the map the thumbnail. All values in pixels from the stage's top.
 */
data class ScanStageLayout(
    /** The view drawn over the whole stage. */
    val main: StageRect,
    /** The other, in its corner. */
    val thumb: StageRect,
    /** The status card's bottom edge: the stage above it is under the card. */
    val clearTop: Float,
    /** The controls' top edge: the stage below it is under the controls. */
    val clearBottom: Float,
    val mapFirst: Boolean,
    val corner: ThumbCorner,
    private val stageArea: Float,
    private val margin: Float,
) {
    val map: StageRect get() = if (mapFirst) main else thumb
    val camera: StageRect get() = if (mapFirst) thumb else main

    /** The share of the stage where the map shows, clear of the card, controls and thumbnail. */
    val mapVisibleShare: Float
        get() {
            if (stageArea <= 0f) return 0f
            val visible = if (mapFirst) (clearBottom - clearTop) * main.width - thumb.area else thumb.area
            return (visible / stageArea).coerceIn(0f, 1f)
        }

    /** The thumbnail's rectangle in [at], its size kept: where a dragged thumbnail settles. */
    fun thumbAt(at: ThumbCorner): StageRect = place(at, main.width, clearTop, clearBottom, thumb.width, thumb.height, margin)

    companion object {
        /** The thumbnail's width, as a share of the stage's. */
        const val THUMB_WIDTH_SHARE = 0.3f

        /** Its height per width: portrait, as the phone is held. */
        const val THUMB_ASPECT = 4f / 3f

        /** At most this share of the band between card and controls, a short band shrinks it. */
        const val THUMB_MAX_BAND_SHARE = 0.45f

        /** Not measured yet: where the status card usually ends, and the controls start. */
        const val DEFAULT_HUD_BOTTOM = 0.2f
        const val DEFAULT_CONTROLS_TOP = 0.78f

        /**
         * The status card's bottom edge held for the capture's length: the lowest it has reached,
         * so a coaching line going from one line to two does not move the thumbnail. NaN = unmeasured.
         */
        fun holdLowest(held: Float, measured: Float): Float =
            if (held.isFinite()) maxOf(held, measured) else measured

        /** The controls' top edge held the same way: the highest it has reached. NaN = unmeasured. */
        fun holdHighest(held: Float, measured: Float): Float =
            if (held.isFinite()) minOf(held, measured) else measured

        /**
         * The phases the map leads in: scanning, paused and the finish screen, the room seen whole
         * before it is saved. Saving goes back to the camera's own orbit.
         */
        val MAP_PHASES = setOf(ScanPhase.Scanning, ScanPhase.Paused, ScanPhase.Reviewing)

        /**
         * The layout of a stage [stageWidth] × [stageHeight] whose status card ends at [hudBottom]
         * and whose controls start at [controlsTop] (both from the stage's top, NaN before they
         * are measured), the thumbnail in [corner], [margin] from the band's edges; null on a stage
         * wider than tall (a phone in landscape, split screen), which keeps the 2D map card. A tall
         * card at the bottom (the finish screen's) shrinks the thumbnail, never drops the map.
         */
        @Suppress("LongParameterList")
        fun of(
            stageWidth: Float,
            stageHeight: Float,
            hudBottom: Float,
            controlsTop: Float,
            mapFirst: Boolean,
            corner: ThumbCorner,
            margin: Float,
        ): ScanStageLayout? {
            if (stageWidth <= 0f || stageHeight <= 0f || stageWidth > stageHeight) return null
            val hud = hudBottom.takeIf { it.isFinite() && it in 0f..stageHeight }
                ?: (stageHeight * DEFAULT_HUD_BOTTOM)
            val controls = controlsTop.takeIf { it.isFinite() && it in hud..stageHeight }
                ?: (stageHeight * DEFAULT_CONTROLS_TOP)
            val band = controls - hud
            val height = min(stageWidth * THUMB_WIDTH_SHARE * THUMB_ASPECT, band * THUMB_MAX_BAND_SHARE)
            val width = height / THUMB_ASPECT
            return ScanStageLayout(
                main = StageRect(0f, 0f, stageWidth, stageHeight),
                thumb = place(corner, stageWidth, hud, controls, width, height, margin),
                clearTop = hud,
                clearBottom = controls,
                mapFirst = mapFirst,
                corner = corner,
                stageArea = stageWidth * stageHeight,
                margin = margin,
            )
        }

        @Suppress("LongParameterList")
        private fun place(
            corner: ThumbCorner,
            stageWidth: Float,
            top: Float,
            bottom: Float,
            width: Float,
            height: Float,
            margin: Float,
        ): StageRect {
            val left = when (corner) {
                ThumbCorner.TopStart, ThumbCorner.BottomStart -> margin
                ThumbCorner.TopEnd, ThumbCorner.BottomEnd -> stageWidth - margin - width
            }
            val y = when (corner) {
                ThumbCorner.TopStart, ThumbCorner.TopEnd -> top + margin
                ThumbCorner.BottomStart, ThumbCorner.BottomEnd -> bottom - margin - height
            }
            return StageRect(left, y, width, height)
        }

        /**
         * The corner a thumbnail dropped with its centre at ([x], [y]) snaps to, in a stage
         * [stageWidth] wide whose band runs from [clearTop] to [clearBottom].
         */
        fun nearestCorner(x: Float, y: Float, stageWidth: Float, clearTop: Float, clearBottom: Float): ThumbCorner {
            val start = x < stageWidth / 2f
            val top = y < (clearTop + clearBottom) / 2f
            return when {
                top && start -> ThumbCorner.TopStart
                top -> ThumbCorner.TopEnd
                start -> ThumbCorner.BottomStart
                else -> ThumbCorner.BottomEnd
            }
        }
    }
}
