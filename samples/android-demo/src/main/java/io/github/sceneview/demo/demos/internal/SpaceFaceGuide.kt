package io.github.sceneview.demo.demos.internal

/**
 * Which face of a cupboard or a shelf to film now, one at a time, for someone who has never
 * scanned: the back, the bottom, each side, the top. A face is done once the scan closes it
 * ([found], twice running, so one stray reading closes nothing), or once its line has been on
 * screen for [ASK_MS] without it showing ([tick]): a shelf has no sides, a crate has no top, and
 * the line moves on.
 *
 * The scan looks [complete] once every face has had its turn and it holds the two a measure
 * cannot do without: the back, and the bottom a box rests on.
 */
class SpaceFaceGuide {
    private val seen = HashSet<SpaceFace>()
    private val passed = HashSet<SpaceFace>()
    private var before: Set<SpaceFace> = emptySet()
    private var asking: SpaceFace? = null
    private var askedMs = 0L
    private var lastMs: Long? = null

    /** Takes the faces the scan closes now ([SpaceFitter.closed]). */
    fun found(closed: Set<SpaceFace>) {
        for (face in closed) if (face in before) seen += face
        before = closed
    }

    /** The face to film now, `null` once none is left. */
    val face: SpaceFace? get() = ORDER.firstOrNull { it !in seen && it !in passed }

    /**
     * Counts [face]'s turn up to [nowMs]. It only runs while [shown]: the capture takes frames
     * and the line on screen is that face's. Paused, in review, during the opening line or
     * while a problem holds the line, nobody was asked anything (2026-11-15, a review: eight
     * seconds of pause gave a face up unasked).
     */
    fun tick(nowMs: Long, shown: Boolean) {
        val elapsed = lastMs?.let { nowMs - it } ?: 0L
        lastMs = nowMs
        val face = face
        if (face != asking) {
            asking = face
            askedMs = 0L
        } else if (face != null && shown) {
            askedMs += elapsed
            if (askedMs >= ASK_MS) passed += face
        }
    }

    /** How much of it the scan holds, `0..1`: the circle on the red button. */
    val share: Float get() = seen.size.toFloat() / ORDER.size

    /** Whether every face has had its turn: seen, or asked for long enough. */
    val asked: Boolean get() = face == null

    /** Whether the scan holds what a measure needs, every face having had its turn. */
    val complete: Boolean get() = asked && SpaceFace.Back in seen && SpaceFace.Bottom in seen

    companion object {
        /** How long a face's line stays up before it moves on: time to read it, aim and be seen. */
        const val ASK_MS = 8_000L

        /** The back first, to square the rest on; then what a box rests on; the sides; the top. */
        val ORDER = listOf(SpaceFace.Back, SpaceFace.Bottom, SpaceFace.Left, SpaceFace.Right, SpaceFace.Top)
    }
}
