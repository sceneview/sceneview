package io.github.sceneview.demo.demos.internal

/**
 * The three ways of measuring in the `ar-measure` demo.
 *
 * - [Distance]: tap point after point, every segment gets its length. Tap the first point
 *   again to close an outline and read its area.
 * - [Wall]: tap the two ends of a wall where it meets the floor; the wall then rises with
 *   the aim, a tap locks its height and reads its area.
 * - [Room]: trace the floor corner by corner, tap the first corner to close it; all the
 *   walls rise together, a tap locks the ceiling height.
 */
enum class MeasureMode { Distance, Wall, Room }

/** What the next tap does — drives the hint, the accent button and the reticle. */
enum class MeasurePhase {
    /** No point yet in this figure. */
    First,

    /** At least one point; the next tap adds a point (or closes the outline). */
    Next,

    /** Walls follow the aim; the next tap locks their height. */
    Raising,
}

/**
 * One measured figure. [P] is whatever the demo anchors a point with (an ARCore anchor on
 * device, a plain position in tests): the board only orders them.
 *
 * @property closed the outline loops back to its first point.
 * @property raising the walls follow the aim (wall or room still open for a height).
 * @property height the locked wall height, metres; `null` until locked.
 */
data class MeasureFigure<P>(
    val mode: MeasureMode,
    val points: List<P> = emptyList(),
    val closed: Boolean = false,
    val raising: Boolean = false,
    val height: Float? = null,
) {
    /** Done: no tap can change it any more, the next tap starts a new figure. */
    val finished: Boolean
        get() = when (mode) {
            MeasureMode.Distance -> closed
            MeasureMode.Wall, MeasureMode.Room -> height != null
        }

    /** Has walls to draw, live or locked. */
    val hasWalls: Boolean get() = raising || height != null
}

/**
 * Every figure measured so far, the last one possibly in progress. Immutable: each user
 * action returns a new board, which makes undo a pure function and the whole flow testable
 * without a camera.
 */
data class MeasureBoard<P>(
    val mode: MeasureMode = MeasureMode.Distance,
    val figures: List<MeasureFigure<P>> = emptyList(),
) {
    /** The figure the next tap edits, or `null` when the next tap starts a new one. */
    val active: MeasureFigure<P>?
        get() = figures.lastOrNull()?.takeIf { !it.finished && it.mode == mode }

    val phase: MeasurePhase
        get() {
            val figure = active ?: return MeasurePhase.First
            return when {
                figure.raising -> MeasurePhase.Raising
                figure.points.isEmpty() -> MeasurePhase.First
                else -> MeasurePhase.Next
            }
        }

    /** Every anchored point on the board, for cleanup. */
    val allPoints: List<P> get() = figures.flatMap { it.points }

    val isEmpty: Boolean get() = figures.all { it.points.isEmpty() }

    /** Switches mode. A half-drawn figure of the old mode is kept on screen as it is. */
    fun withMode(next: MeasureMode): MeasureBoard<P> = copy(mode = next)

    /**
     * The tap of the accent button with the reticle on [point].
     *
     * @param closesOutline the reticle sits on the active figure's first point — the snapper
     *                      decided it, the board only acts on it.
     */
    fun tap(point: P?, closesOutline: Boolean): MeasureBoard<P> {
        val figure = active
        if (figure == null) {
            if (point == null) return this
            val started = MeasureFigure<P>(mode = mode, points = listOf(point))
            return copy(figures = figures + started)
        }
        val next = when {
            figure.raising -> figure // Locking needs the height: see lockHeight.
            point == null -> figure
            closesOutline && figure.points.size >= MIN_OUTLINE_POINTS && mode != MeasureMode.Wall ->
                figure.copy(closed = true, raising = mode == MeasureMode.Room)
            else -> {
                val grown = figure.copy(points = figure.points + point)
                if (mode == MeasureMode.Wall && grown.points.size == 2) grown.copy(raising = true) else grown
            }
        }
        return replaceActive(next)
    }

    /** Locks the rising walls of the active figure at [height] metres. */
    fun lockHeight(height: Float): MeasureBoard<P> {
        val figure = active?.takeIf { it.raising } ?: return this
        return replaceActive(
            figure.copy(raising = false, height = height.coerceIn(WALL_MIN_HEIGHT, WALL_MAX_HEIGHT)),
        )
    }

    /** Steps back one action: unlock a height, lower the walls, reopen, or drop a point. */
    fun undo(): MeasureBoard<P> {
        val figure = figures.lastOrNull() ?: return this
        val stepped: MeasureFigure<P>? = when {
            figure.height != null -> figure.copy(height = null, raising = true)
            figure.raising && figure.mode == MeasureMode.Wall ->
                figure.copy(raising = false, points = figure.points.dropLast(1))
            figure.raising -> figure.copy(raising = false, closed = false)
            figure.closed -> figure.copy(closed = false)
            figure.points.size > 1 -> figure.copy(points = figure.points.dropLast(1))
            else -> null
        }
        val rest = figures.dropLast(1)
        return copy(
            mode = stepped?.mode ?: mode,
            figures = if (stepped == null) rest else rest + stepped,
        )
    }

    fun clear(): MeasureBoard<P> = copy(figures = emptyList())

    private fun replaceActive(figure: MeasureFigure<P>): MeasureBoard<P> =
        copy(figures = figures.dropLast(1) + figure)

    companion object {
        const val MIN_OUTLINE_POINTS = 3
    }
}
