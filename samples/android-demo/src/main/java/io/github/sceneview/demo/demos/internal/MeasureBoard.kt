package io.github.sceneview.demo.demos.internal

/**
 * The three ways of measuring in the `ar-measure` demo.
 *
 * - [Distance]: tap point after point, every segment gets its length. Tap the first point
 *   again to close an outline and read its area, or tap Done to end an open chain.
 * - [Wall]: tap the two ends of a wall where it meets the floor; the wall then rises with
 *   the aim, a tap locks its height and reads its area. Doors and windows marked on it then
 *   come off its net area.
 * - [Room]: trace the floor corner by corner, tap the first corner to close it; all the
 *   walls rise together, a tap locks the ceiling height. Doors and windows marked on any of
 *   its walls then come off the walls' net area, as on a wall.
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
 * @property ended a Distance chain the user ended with Done, open: the next point starts a new chain.
 * @property raising the walls follow the aim (wall or room still open for a height).
 * @property height the locked wall height, metres; `null` until locked.
 * @property openings the doors and windows marked on a measured wall or a measured room's walls,
 *   left out of their net area; on a room, each on the wall it was marked on ([MeasureOpening]).
 */
data class MeasureFigure<P>(
    val mode: MeasureMode,
    val points: List<P> = emptyList(),
    val closed: Boolean = false,
    val ended: Boolean = false,
    val raising: Boolean = false,
    val height: Float? = null,
    val openings: List<MeasureOpening> = emptyList(),
) {
    /** Done: no tap can change it any more, the next tap starts a new figure. */
    val finished: Boolean
        get() = when (mode) {
            MeasureMode.Distance -> closed || ended
            MeasureMode.Wall, MeasureMode.Room -> height != null
        }

    /** Has walls to draw, live or locked. */
    val hasWalls: Boolean get() = raising || height != null
}

/**
 * Every figure measured so far, the last one possibly in progress. Immutable: each user
 * action returns a new board, which makes undo a pure function and the whole flow testable
 * without a camera.
 *
 * @property markingOpening Door or window is on: the next taps mark an opening on the [openingWall].
 * @property openingCorner the opening's first corner once placed; the next tap places the opposite one.
 */
data class MeasureBoard<P>(
    val mode: MeasureMode = MeasureMode.Distance,
    val figures: List<MeasureFigure<P>> = emptyList(),
    val markingOpening: Boolean = false,
    val openingCorner: MeasureWallPoint? = null,
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

    /**
     * Switches mode. A half-drawn figure of the old mode is kept on screen as it is; a door or
     * window being marked ends.
     */
    fun withMode(next: MeasureMode): MeasureBoard<P> = keepingOpening(copy(mode = next))

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
            return keepingOpening(copy(figures = figures + started))
        }
        val next = when {
            figure.raising -> figure // Locking needs the height: see lockHeight.
            // Closing reuses the first point, so it needs no new one: check it before the null.
            closesOutline && figure.points.size >= MIN_OUTLINE_POINTS && mode != MeasureMode.Wall ->
                figure.copy(closed = true, raising = mode == MeasureMode.Room)
            point == null -> figure
            else -> {
                val grown = figure.copy(points = figure.points + point)
                if (mode == MeasureMode.Wall && grown.points.size == 2) grown.copy(raising = true) else grown
            }
        }
        return keepingOpening(replaceActive(next))
    }

    /** A Distance chain with a segment can be ended with Done; walls and rooms end themselves. */
    val canFinish: Boolean
        get() = active?.let { it.mode == MeasureMode.Distance && it.points.size >= 2 } == true

    /**
     * Done: ends the active Distance chain where it is. The chain stays on screen with its
     * lengths; the next point starts a new chain instead of adding a segment to this one.
     */
    fun finish(): MeasureBoard<P> {
        val figure = active?.takeIf { canFinish } ?: return this
        return keepingOpening(replaceActive(figure.copy(ended = true)))
    }

    /** Locks the rising walls of the active figure at [height] metres. */
    fun lockHeight(height: Float): MeasureBoard<P> {
        val figure = active?.takeIf { it.raising } ?: return this
        return keepingOpening(
            replaceActive(figure.copy(raising = false, height = height.coerceIn(WALL_MIN_HEIGHT, WALL_MAX_HEIGHT))),
        )
    }

    /**
     * The measured figure an opening goes on: the last figure, when it is a wall with its height
     * or a closed room with its ceiling height, and its mode is still the mode. `null` otherwise.
     */
    val openingWall: MeasureFigure<P>?
        get() = figures.lastOrNull()?.takeIf {
            it.mode == mode && it.height != null && when (mode) {
                MeasureMode.Wall -> it.points.size == 2
                MeasureMode.Room -> it.closed
                MeasureMode.Distance -> false
            }
        }

    /** Marking a door or a window: on, with no corner placed yet. */
    fun startOpening(): MeasureBoard<P> = copy(markingOpening = true, openingCorner = null)

    /** Marking off, its first corner dropped. */
    fun stopOpening(): MeasureBoard<P> = copy(markingOpening = false, openingCorner = null)

    /** The opening's first corner, placed at [corner] on the wall. */
    fun withOpeningCorner(corner: MeasureWallPoint): MeasureBoard<P> = copy(openingCorner = corner)

    /** Adds [opening] to the [openingWall]; the board as it is without one. */
    fun addOpening(opening: MeasureOpening): MeasureBoard<P> {
        val wall = openingWall ?: return this
        return replaceActive(wall.copy(openings = wall.openings + opening))
    }

    /**
     * Steps back one action: drop an opening's first corner, the last opening, unlock a height,
     * lower the walls, reopen, resume a chain, or drop a point.
     */
    fun undo(): MeasureBoard<P> {
        if (openingCorner != null) return copy(openingCorner = null)
        val figure = figures.lastOrNull() ?: return this
        val stepped: MeasureFigure<P>? = when {
            figure.openings.isNotEmpty() -> figure.copy(openings = figure.openings.dropLast(1))
            figure.height != null -> figure.copy(height = null, raising = true)
            figure.raising && figure.mode == MeasureMode.Wall ->
                figure.copy(raising = false, points = figure.points.dropLast(1))
            figure.raising -> figure.copy(raising = false, closed = false)
            figure.closed -> figure.copy(closed = false)
            figure.ended -> figure.copy(ended = false)
            figure.points.size > 1 -> figure.copy(points = figure.points.dropLast(1))
            else -> null
        }
        val rest = figures.dropLast(1)
        return keepingOpening(
            copy(
                mode = stepped?.mode ?: mode,
                figures = if (stepped == null) rest else rest + stepped,
            ),
        )
    }

    fun clear(): MeasureBoard<P> = keepingOpening(copy(figures = emptyList()))

    /**
     * [next], still marking a door or window only while it stays on this board's [openingWall]:
     * a mode change, a new figure, an unlocked height or a clear ends the marking, first corner
     * and all, so a corner placed on one wall never resumes on another.
     */
    private fun keepingOpening(next: MeasureBoard<P>): MeasureBoard<P> {
        val wall = openingWall
        val sameWall = wall != null && next.mode == mode && next.openingWall?.points == wall.points
        return if (sameWall) next else next.copy(markingOpening = false, openingCorner = null)
    }

    private fun replaceActive(figure: MeasureFigure<P>): MeasureBoard<P> =
        copy(figures = figures.dropLast(1) + figure)

    companion object {
        const val MIN_OUTLINE_POINTS = 3
    }
}
