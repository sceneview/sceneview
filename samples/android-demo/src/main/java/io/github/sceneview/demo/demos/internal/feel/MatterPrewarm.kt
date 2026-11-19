package io.github.sceneview.demo.demos.internal.feel

/**
 * Draws every sample once, hidden under the floor, while the stage is idle, so that even the
 * FIRST switch to a material never pays its program link on screen.
 *
 * `Material.compile` queues backend work, but the first frame that actually draws a sample
 * still stalls (200-400 ms per material on emulator-5554): that frame is what a first switch
 * filmed on a phone shows as a hitch. Here that frame happens while nothing moves ([MatterCalm]):
 * one material at a time, each followed by [GAP_FRAMES] idle frames so that a stall, which
 * lands one to three frames after the draw, never chains into the next warm.
 *
 * Main thread only, called once per presented frame. Pure logic: the caller places the node.
 */
internal class MatterPrewarm {
    private val entries = FeelMaterial.entries
    private val warmed = mutableSetOf<FeelMaterial>()
    private var drawing: FeelMaterial? = null
    private var drawnFrames = 0
    private var gap = GAP_FRAMES
    // Frames still owed to the last warm, idle or not: its stall must reach a presented frame.
    private var cooldown = 0

    /** Every material has been drawn at least once. */
    val done: Boolean get() = warmed.size == entries.size

    /** Done, and the frames owed to the last warm presented: [next] has nothing left to do. */
    val retired: Boolean get() = done && cooldown == 0

    /**
     * True while a material is ready to be warmed, one is being drawn, or the frames after a
     * warm are still owed: keep frames coming.
     */
    fun needsFrames(available: (FeelMaterial) -> Boolean): Boolean {
        if (drawing != null || cooldown > 0) return true
        if (done) return false
        for (index in entries.indices) {
            val kind = entries[index]
            if (kind !in warmed && available(kind)) return true
        }
        return false
    }

    /**
     * Returns the material to draw hidden on THIS frame, or null.
     *
     * [shown] and [leaving]: the solids the stage draws anyway this frame; they count as warmed.
     * [available]: the sample's instance and node exist. [idle]: [MatterCalm.idle]. Any frame
     * that is not idle interrupts a draw and restarts the [GAP_FRAMES] wait from zero.
     */
    fun next(shown: FeelMaterial, leaving: FeelMaterial?, available: (FeelMaterial) -> Boolean, idle: Boolean): FeelMaterial? {
        warmed += shown
        if (leaving != null) warmed += leaving
        if (cooldown > 0) cooldown--
        drawing?.let { current ->
            // Interrupted (a press or a switch began): stop now, retry this one later.
            if (!idle) { drawing = null; drawnFrames = 0; gap = GAP_FRAMES; return null }
            if (++drawnFrames < DRAW_FRAMES) return current
            warmed += current
            drawing = null
            drawnFrames = 0
            gap = GAP_FRAMES
            cooldown = GAP_FRAMES
        }
        if (!idle) { gap = GAP_FRAMES; return null }
        if (done) return null
        if (gap > 0) { gap--; return null }
        for (index in entries.indices) {
            val kind = entries[index]
            if (kind !in warmed && available(kind)) {
                drawing = kind
                drawnFrames = 0
                return kind
            }
        }
        return null
    }

    companion object {
        /**
         * Idle frames before each warm and after it. The stall of a first draw lands one to three
         * frames after it (emulator-5554): twelve frames leave it alone, well clear of the next.
         */
        const val GAP_FRAMES = 12
        /** Consecutive frames each material is drawn hidden; the first one carries the link. */
        const val DRAW_FRAMES = 2
    }
}

/**
 * Whether the stage is calm enough for a hidden first draw: a stalled frame there must show
 * nothing at all.
 *
 * [busy]: anything the user does or the stage stages (unfocused, not revealed, the opening, a
 * finger down, a press, a switch, the settle after a landing). It restarts the calm clock.
 * [moving]: something still animates on its own (a foam rise, the gel's wave, a rigid ripple,
 * a trail fading, the idle orbit). It defers the draw without restarting the clock.
 *
 * Main thread only, once per presented frame.
 */
internal class MatterCalm {
    private var busyAt: Long? = null

    fun idle(now: Long, busy: Boolean, moving: Boolean): Boolean {
        if (busy) { busyAt = now; return false }
        val since = busyAt ?: now.also { busyAt = it }
        return !moving && now - since >= CALM_MILLIS
    }

    companion object {
        /**
         * Calm after the last release, landing or opening, before the first warm. 1.5 s
         * covers the hint's fade (300 ms), a landing's settle (700 ms) and a rigid ripple
         * (350 ms) with room to spare, and is still well inside the 4 s before the idle orbit.
         */
        const val CALM_MILLIS = 1_500L

        /** Everything that makes the stage busy, as named inputs so a test can read it. */
        fun busy(
            focused: Boolean,
            revealed: Boolean,
            openingFinished: Boolean,
            switching: Boolean,
            settling: Boolean,
            pressing: Boolean,
            touching: Boolean,
        ): Boolean = !focused || !revealed || !openingFinished || switching || settling || pressing || touching
    }
}
