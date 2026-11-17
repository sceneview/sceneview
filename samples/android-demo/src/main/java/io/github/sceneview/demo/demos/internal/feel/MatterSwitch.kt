package io.github.sceneview.demo.demos.internal.feel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Chip selection → solid on stage. Main thread only, advanced once per PRESENTED frame.
 *
 * The first frame that draws a sample can stall for hundreds of milliseconds while the backend
 * links its program (up to 1.5 s per material on emulator-5554). A wall-clock tween spent that
 * stall on a frozen picture and popped the new solid in at rest. Here the slide advances only by
 * the presented-frame interval, capped at [MAX_STEP_MILLIS], so a hitch slows it down but never
 * skips it; and the incoming solid is drawn from the first frame at its hidden pose under the
 * floor, so its first draw happens while the outgoing one leaves. The slide waits at
 * [MatterTransition.HIDDEN], on an empty stage, until the incoming solid has been drawn for
 * [PRIME_FRAMES] presented frames.
 *
 * Requests are never refused. Until the incoming solid shows, a new request replaces it; once
 * it is rising, the latest request waits for it to land.
 */
internal class MatterSwitch(initial: FeelMaterial = FeelMaterial.MemoryFoam) {
    /** The chip, the title and the readout. Changes on every request. */
    var selected by mutableStateOf(initial)
        private set
    /** The solid that owns touch and the readout; the INCOMING one during a slide. */
    var displayed by mutableStateOf(initial)
        private set
    var outgoing by mutableStateOf<FeelMaterial?>(null)
        private set
    /** 0 at the start of a slide, 1 at rest. */
    var progress = 1f
        private set
    /** +1 when the outgoing solid leaves towards the next material, -1 towards the previous. */
    var direction = 1f
        private set
    /** Uptime when the current or last slide began, null before any switch. */
    var startedAt: Long? = null
        private set
    /** Uptime when the incoming solid reached its rest height ([ARRIVE]). */
    var landedAt: Long? = null
        private set
    private var primeLeft = 0
    private var lastFrame = 0L

    val switching: Boolean get() = selected != displayed || outgoing != null
    /** The incoming solid has not yet been drawn (hidden) for [PRIME_FRAMES] presented frames. */
    val priming: Boolean get() = outgoing != null && primeLeft > 0

    /** A chip tap or a fling. Returns false only for the material already requested. */
    fun request(kind: FeelMaterial): Boolean {
        if (kind == selected) return false
        selected = kind
        return true
    }

    /**
     * Returns true on the frame a slide starts. [ready]: the selected solid exists and can be
     * drawn. While a slide runs, the frame step is the interval since the previous presented frame.
     */
    fun advance(now: Long, ready: Boolean, motion: Boolean): Boolean {
        val leaving = outgoing
        if (leaving == null) {
            if (selected == displayed || !ready) return false
            direction = if (selected.ordinal >= displayed.ordinal) 1f else -1f
            outgoing = displayed
            displayed = selected
            progress = 0f
            primeLeft = PRIME_FRAMES
            startedAt = now
            landedAt = null
            lastFrame = now
            return true
        }
        if (selected != displayed && selected != leaving && progress < MatterTransition.HIDDEN && ready) {
            // The previous target is still under the floor: replace it, keep the exit direction.
            // Back to the solid that is leaving queues instead: it cannot leave and rise at once.
            displayed = selected
            primeLeft = PRIME_FRAMES
        }
        val step = (now - lastFrame).coerceIn(0L, MAX_STEP_MILLIS)
        lastFrame = now
        val primed = primeLeft == 0
        if (primeLeft > 0) primeLeft--
        progress = when {
            !motion -> if (primed) 1f else 0f
            !primed -> (progress + step / DURATION_MILLIS.toFloat()).coerceAtMost(MatterTransition.HIDDEN)
            else -> (progress + step / DURATION_MILLIS.toFloat()).coerceAtMost(1f)
        }
        if (progress >= ARRIVE && landedAt == null) landedAt = now
        if (progress >= 1f) outgoing = null
        return false
    }

    /** Seconds since the incoming solid landed, negative before. Drives the settle wobble. */
    fun landingAge(now: Long): Float = landedAt?.let { (now - it) / 1000f } ?: -1f

    companion object {
        /** Same as SceneViewTokens.Motion.containerMillis, kept here for the JVM tests. */
        const val DURATION_MILLIS = 450L
        const val MAX_STEP_MILLIS = 34L
        const val PRIME_FRAMES = 2
        const val ARRIVE = .82f

        /** A horizontal fling on the empty stage: leftwards is the next material. */
        fun flingTarget(selected: FeelMaterial, velocityX: Float): FeelMaterial =
            FeelMaterial.entries[(selected.ordinal + if (velocityX < 0f) 1 else -1)
                .coerceIn(0, FeelMaterial.entries.lastIndex)]

        /**
         * Pixels to scroll a chip row so the chip at [start]..[end] (item offsets) sits inside
         * the viewport with [margin] to spare. Zero when it is already there: a visible chip
         * never moves under the finger that tapped it.
         */
        fun chipScroll(start: Int, end: Int, viewportStart: Int, viewportEnd: Int, margin: Int): Float = when {
            start - margin < viewportStart -> (start - margin - viewportStart).toFloat()
            end + margin > viewportEnd -> (end + margin - viewportEnd).toFloat()
            else -> 0f
        }
    }
}
