package io.github.sceneview.demo.demos.internal

/**
 * How a room's live scan shares the screen between its live 3D map and the camera: the map on
 * top, under the status card, the camera below it down to the scan's controls — the camera's
 * middle, where the phone aims, stays clear of both. Polycam and Scaniverse give the model the
 * larger part while a room is scanned; [mapFirst] off hands it back to the camera.
 *
 * All values are pixels from the top of the stage.
 */
data class ScanStageSplit(
    /** The map's bottom edge: it runs from the stage's top, under the status card, to here. */
    val mapBottom: Float,
    /** The camera pane's top edge, the map's bottom. */
    val cameraTop: Float,
    /** The camera pane's bottom edge, the controls' top. */
    val cameraBottom: Float,
    /** How much of the map's top the status card covers. */
    val clearTop: Float,
) {
    companion object {
        /** The map's share of the band between the status card and the controls. */
        const val MAP_SHARE = 0.6f

        /** Swapped, the camera takes the larger part and the map keeps this. */
        const val MAP_SHARE_SWAPPED = 0.3f

        /** Below this share of the stage between card and controls, no split: the 2D card. */
        const val MIN_FREE_SHARE = 0.25f

        /** Not measured yet: where the status card usually ends, and the controls start. */
        const val DEFAULT_HUD_BOTTOM = 0.2f
        const val DEFAULT_CONTROLS_TOP = 0.78f

        /**
         * The status card's bottom edge held for a capture: the lowest it has reached, so a
         * coaching line wrapping to two lines and back never moves the camera band. NaN = unmeasured.
         */
        fun holdLowest(held: Float, measured: Float): Float =
            if (held.isFinite()) maxOf(held, measured) else measured

        /** The controls' top edge held the same way: the highest it has reached. NaN = unmeasured. */
        fun holdHighest(held: Float, measured: Float): Float =
            if (held.isFinite()) minOf(held, measured) else measured

        /**
         * The split of a stage [stageHeight] tall whose status card ends at [hudBottom] and
         * whose controls start at [controlsTop] (both from the stage's top, NaN before they are
         * measured), or null when it is too short to share (a phone in landscape).
         */
        fun of(stageHeight: Float, hudBottom: Float, controlsTop: Float, mapFirst: Boolean): ScanStageSplit? {
            if (stageHeight <= 0f) return null
            val hud = hudBottom.takeIf { it.isFinite() && it in 0f..stageHeight }
                ?: (stageHeight * DEFAULT_HUD_BOTTOM)
            val controls = controlsTop.takeIf { it.isFinite() && it in hud..stageHeight }
                ?: (stageHeight * DEFAULT_CONTROLS_TOP)
            val free = controls - hud
            if (free < stageHeight * MIN_FREE_SHARE) return null
            val split = hud + free * (if (mapFirst) MAP_SHARE else MAP_SHARE_SWAPPED)
            return ScanStageSplit(mapBottom = split, cameraTop = split, cameraBottom = controls, clearTop = hud)
        }
    }
}
