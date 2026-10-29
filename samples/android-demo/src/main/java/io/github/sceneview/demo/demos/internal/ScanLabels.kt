package io.github.sceneview.demo.demos.internal

/** A label's rectangle on screen, in pixels: its top left corner and its size. */
data class LabelRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    /** Whether this and [other] come closer than [gap] pixels: touching edges count as clear at 0. */
    fun overlaps(other: LabelRect, gap: Int = 0): Boolean =
        left < other.right + gap && other.left < right + gap && top < other.bottom + gap && other.top < bottom + gap

    fun at(x: Int, y: Int): LabelRect = copy(left = x, top = y)

    /** Whether this lies wholly inside [area]. */
    fun inside(area: LabelRect): Boolean =
        left >= area.left && top >= area.top && right <= area.right && bottom <= area.bottom
}

/**
 * The recognised parts' labels over a scan's 3D view, laid out so none covers another (the
 * labels of a bed, a second bed and a chair a metre apart piled up into one unreadable stack).
 *
 * Plain geometry on pixels: each label wants the place over its own box ([ScanChrome.labelAt]);
 * the surest first keeps it, and each next one, where it would cover one already placed, slides
 * to the nearest free place beside or over or under them — a leader line then ties it to its box
 * ([leaderEnd]). One that has no free place within reach is not written: its box is still drawn,
 * and a tap on it still gives its size. Polycam and Reality Composer declutter their tags so.
 */
object ScanLabels {

    /**
     * Where each of [wanted] goes, in the same order, `null` for one not written: [wanted] is in
     * priority order (surest first), `null` for a label with no place this frame. A label goes in
     * [area], covers none of [obstacles] — the replay's corner card — and keeps [gap] from every
     * other; one that would have to move farther than [maxShift] from where it wants is dropped.
     */
    fun declutter(
        wanted: List<LabelRect?>,
        area: LabelRect,
        gap: Int,
        maxShift: Int,
        obstacles: List<LabelRect> = emptyList(),
    ): List<LabelRect?> {
        val taken = ArrayList<LabelRect>(obstacles.size + wanted.size).apply { addAll(obstacles) }
        val limit = maxShift.toLong() * maxShift
        return wanted.map { want ->
            if (want == null) return@map null
            val home = clamp(want, area)
            val xs = LinkedHashSet<Int>().apply { add(home.left) }
            val ys = LinkedHashSet<Int>().apply { add(home.top) }
            for (other in taken) {
                xs += other.left - gap - want.width
                xs += other.right + gap
                ys += other.top - gap - want.height
                ys += other.bottom + gap
            }
            var best: LabelRect? = null
            var bestCost = Long.MAX_VALUE
            for (x in xs) {
                for (y in ys) {
                    val candidate = want.at(x, y)
                    if (!candidate.inside(area)) continue
                    val cost = distance2(candidate, want)
                    if (cost > limit || cost >= bestCost) continue
                    if (taken.any { candidate.overlaps(it, gap) }) continue
                    best = candidate
                    bestCost = cost
                }
            }
            best?.also { taken += it }
        }
    }

    /**
     * Where the leader line from a box's point ([x], [y]) meets the label [rect] moved off it: the
     * nearest point of its border. `null` when the point is under the label — nothing to draw.
     */
    fun leaderEnd(x: Float, y: Float, rect: LabelRect): Pair<Float, Float>? {
        val nx = x.coerceIn(rect.left.toFloat(), rect.right.toFloat())
        val ny = y.coerceIn(rect.top.toFloat(), rect.bottom.toFloat())
        return if (nx == x && ny == y) null else nx to ny
    }

    private fun clamp(rect: LabelRect, area: LabelRect): LabelRect = rect.at(
        rect.left.coerceIn(area.left, maxOf(area.left, area.right - rect.width)),
        rect.top.coerceIn(area.top, maxOf(area.top, area.bottom - rect.height)),
    )

    private fun distance2(a: LabelRect, b: LabelRect): Long {
        val dx = (a.left - b.left).toLong()
        val dy = (a.top - b.top).toLong()
        return dx * dx + dy * dy
    }
}
