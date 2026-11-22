package io.github.sceneview.ar

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * What an automatic placement shows around the object right now: one rule for every app, so
 * a viewer, a furniture app and a measuring app teach the same gestures (#5209).
 *
 * | Moment | [planes] | [ring] | [dimensions] |
 * |---|---|---|---|
 * | Looking for a surface | every surface | — | — |
 * | The object lands | for [PlacementGuidePolicy.LAND_MS] | for [PlacementGuidePolicy.RING_MS] | for [PlacementGuidePolicy.DIMENSIONS_MS] |
 * | Placed, left alone | — | while selected | while selected, or always with [AutoPlacementState.alwaysShowsDimensions] |
 * | Dragged or turned | yes, and [PlacementGuidePolicy.RELEASE_MS] after release | yes, wider | yes |
 *
 * The surfaces say where the object can go, so they show while it is looking for one and while
 * it moves, then fade: a placed object stands on its contact shadow, and nothing else moves on
 * screen. Scene Viewer and IKEA Place show their dots the same way; AR Quick Look draws none.
 *
 * [AutoPlacementScene] reads it for the plane renderer and the selection ring. Read it from
 * [AutoPlacementState.guide] for marks of your own, such as `DimensionMarks`.
 */
class PlacementGuide internal constructor(
    /** The detected surfaces are drawn. */
    val planes: Boolean,
    /** The footprint ring around the placed object is drawn. */
    val ring: Boolean,
    /** Dimension marks around the placed object should be drawn. */
    val dimensions: Boolean,
    /** The object is under the fingers: the ring is drawn wider. */
    val held: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is PlacementGuide && planes == other.planes &&
        ring == other.ring && dimensions == other.dimensions && held == other.held

    override fun hashCode(): Int =
        (if (planes) 1 else 0) + (if (ring) 2 else 0) + (if (dimensions) 4 else 0) + (if (held) 8 else 0)

    override fun toString(): String =
        "PlacementGuide(planes=$planes, ring=$ring, dimensions=$dimensions, held=$held)"

    internal companion object {
        val Searching = PlacementGuide(planes = true, ring = false, dimensions = false)
        val Nothing = PlacementGuide(planes = false, ring = false, dimensions = false)
    }
}

/** The timing behind [PlacementGuide] — pure, pinned in `PlacementGuideTest`. */
internal object PlacementGuidePolicy {
    /** The surfaces stay this long after the object lands, so it is seen landing on them. */
    const val LAND_MS = 800L

    /** The ring closes around a new placement and stays this long. */
    const val RING_MS = 1_500L

    /** Dimension marks show this long after the first landing, so they are discovered. */
    const val DIMENSIONS_MS = 3_000L

    /** After a drag or a twist, the surfaces and the ring stay this long. */
    const val RELEASE_MS = 1_000L

    /**
     * The guide for [phase], [sincePlacedMs] after the object last landed from a search and
     * [sinceReleaseMs] after the last gesture ended (`null`: not since the last search).
     */
    fun guide(
        phase: PlacementPhase,
        selected: Boolean,
        sincePlacedMs: Long?,
        sinceReleaseMs: Long?,
        alwaysShowsDimensions: Boolean = false,
    ): PlacementGuide {
        fun within(since: Long?, ms: Long) = since != null && since in 0 until ms
        return when (phase) {
            PlacementPhase.SCANNING, PlacementPhase.NO_SURFACE, PlacementPhase.RECOVERING,
            PlacementPhase.TRACKING_LOST -> PlacementGuide.Searching
            PlacementPhase.ADJUSTING -> PlacementGuide(planes = true, ring = true, dimensions = true, held = true)
            PlacementPhase.PLACED -> PlacementGuide(
                planes = within(sincePlacedMs, LAND_MS) || within(sinceReleaseMs, RELEASE_MS),
                ring = selected || within(sincePlacedMs, RING_MS) || within(sinceReleaseMs, RELEASE_MS),
                dimensions = alwaysShowsDimensions || selected || within(sincePlacedMs, DIMENSIONS_MS),
            )
            PlacementPhase.INITIALIZING, PlacementPhase.RECOVERY_FAILED,
            PlacementPhase.CAMERA_ERROR -> PlacementGuide.Nothing
        }
    }

    /** Milliseconds until the guide can next change on its own, `null` when it cannot. */
    fun nextChangeMs(sincePlacedMs: Long?, sinceReleaseMs: Long?): Long? {
        val ahead = buildList {
            sincePlacedMs?.let { since -> listOf(LAND_MS, RING_MS, DIMENSIONS_MS).forEach { add(it - since) } }
            sinceReleaseMs?.let { add(RELEASE_MS - it) }
        }.filter { it > 0 }
        return ahead.minOrNull()
    }
}

/**
 * Keeps [AutoPlacementState.guide] current: records when the object lands and when a gesture
 * ends, and wakes at each moment the guide changes on its own. Composed once by
 * [AutoPlacementScene].
 */
@Composable
internal fun PlacementGuideClock(state: AutoPlacementState) {
    var placedAt by remember(state) { mutableStateOf<Long?>(null) }
    var releasedAt by remember(state) { mutableStateOf<Long?>(null) }
    var previous by remember(state) { mutableStateOf(state.phase) }
    val phase = state.phase
    val selected = state.isSelected
    val always = state.alwaysShowsDimensions
    LaunchedEffect(state, phase, selected, always) {
        val now = SystemClock.uptimeMillis()
        when (phase) {
            PlacementPhase.INITIALIZING, PlacementPhase.SCANNING, PlacementPhase.NO_SURFACE -> {
                placedAt = null
                releasedAt = null
            }
            PlacementPhase.PLACED -> when {
                previous == PlacementPhase.ADJUSTING -> releasedAt = now
                placedAt == null -> placedAt = now
            }
            else -> Unit
        }
        previous = phase
        while (true) {
            val t = SystemClock.uptimeMillis()
            val sincePlaced = placedAt?.let { t - it }
            val sinceRelease = releasedAt?.let { t - it }
            state.guide = PlacementGuidePolicy.guide(phase, selected, sincePlaced, sinceRelease, always)
            val wait = PlacementGuidePolicy.nextChangeMs(sincePlaced, sinceRelease) ?: break
            delay(wait)
        }
    }
}
