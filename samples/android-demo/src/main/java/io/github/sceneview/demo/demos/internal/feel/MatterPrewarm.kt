package io.github.sceneview.demo.demos.internal.feel

/**
 * Draws every sample once, hidden under the floor, while the stage is idle, so that even the
 * FIRST switch to a material never pays its program link on screen.
 *
 * `Material.compile` queues backend work, but the first frame that actually draws a sample
 * still stalls (200-400 ms per material on emulator-5554): that frame is what a first switch
 * filmed on a phone shows as a hitch. Here that frame happens while nothing moves: after the
 * opening, between presses, one material at a time, [GAP_FRAMES] frames apart.
 *
 * Main thread only, called once per presented frame. Pure logic: the caller places the node.
 */
internal class MatterPrewarm {
    private val warmed = mutableSetOf<FeelMaterial>()
    private var drawing: FeelMaterial? = null
    private var drawnFrames = 0
    private var gap = GAP_FRAMES

    /** Every material has been drawn at least once. */
    val done: Boolean get() = warmed.size == FeelMaterial.entries.size

    /** True while a material is ready to be warmed, or one is being drawn: keep frames coming. */
    fun needsFrames(available: (FeelMaterial) -> Boolean): Boolean =
        drawing != null || FeelMaterial.entries.any { it !in warmed && available(it) }

    /**
     * Returns the material to draw hidden on THIS frame, or null.
     *
     * [drawn]: materials the stage draws anyway this frame (the displayed and outgoing solids):
     * they count as warmed. [available]: the sample's instance and node exist. [idle]: revealed,
     * opening finished, no press, no finger down, no switch, window focused.
     */
    fun next(drawn: Collection<FeelMaterial>, available: (FeelMaterial) -> Boolean, idle: Boolean): FeelMaterial? {
        warmed += drawn
        drawing?.let { current ->
            // Interrupted (a press or a switch began): stop now, retry this one later.
            if (!idle) { drawing = null; drawnFrames = 0; gap = GAP_FRAMES; return null }
            if (++drawnFrames < DRAW_FRAMES) return current
            warmed += current
            drawing = null
            drawnFrames = 0
            gap = GAP_FRAMES
        }
        if (!idle || done) return null
        if (gap > 0) { gap--; return null }
        val next = FeelMaterial.entries.firstOrNull { it !in warmed && available(it) } ?: return null
        drawing = next
        drawnFrames = 0
        return next
    }

    companion object {
        /** Frames between two warm draws, so a stall never chains into the next one. */
        const val GAP_FRAMES = 4
        /** Consecutive frames each material is drawn hidden; the first one carries the link. */
        const val DRAW_FRAMES = 2
    }
}
