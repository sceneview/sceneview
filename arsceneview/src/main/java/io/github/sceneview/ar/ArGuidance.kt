package io.github.sceneview.ar

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * What the AR coaching overlay is telling the user right now — one animated glyph per cue,
 * the same vocabulary as Apple's `ARCoachingOverlayView` (surface onboarding, "move more",
 * relocalization) and as `ARGuidanceCue` in SceneViewSwift.
 *
 * [NONE] means *nothing to say*: the placement is fine, or a card (no surface, recovery
 * failed, camera error) is already explaining the state. Hide your own chrome while the cue
 * is anything else — see [ArGuidanceState.isCoaching].
 */
enum class ArGuidanceCue {
    /** Nothing to coach — the overlay is hidden. */
    NONE,

    /** The session has not tracked a single frame yet (after a short grace delay). */
    INITIALIZING,

    /** Searching for a floor/table ([PlacementSurface.SURFACE]) or a wall ([PlacementSurface.WALL]). */
    SCAN,

    /** A surface was just found and the object placed on it — a short "found" beat, then [NONE]. */
    SURFACE_FOUND,

    /** The camera stopped tracking — ask the user to slow down / move more. */
    TRACKING_LIMITED,

    /** Tracking is back but the placed object's anchor has not re-tracked — "look back". */
    RELOCALIZING,
}

/**
 * Derives the coaching [cue] from an [AutoPlacementState] — pure logic, driven by an
 * explicit clock so it is testable on the JVM and animated without a per-frame poll.
 *
 * Rules (the only ones):
 *  - [PlacementPhase.INITIALIZING] → [ArGuidanceCue.INITIALIZING], but only after
 *    [INITIALIZING_DELAY_MS] so a fast start shows nothing at all.
 *  - [PlacementPhase.SCANNING] → [ArGuidanceCue.SCAN].
 *  - A **new** placement (a changed [AutoPlacementState.placedAtMillis]) →
 *    [ArGuidanceCue.SURFACE_FOUND] for [FOUND_HOLD_MS], then [ArGuidanceCue.NONE]. An
 *    anchor that re-tracks after a loss is not a new placement and gets no "found" beat.
 *  - [PlacementPhase.TRACKING_LOST] → [ArGuidanceCue.TRACKING_LIMITED];
 *    [PlacementPhase.RECOVERING] → [ArGuidanceCue.RELOCALIZING].
 *  - [PlacementPhase.NO_SURFACE], [PlacementPhase.RECOVERY_FAILED] and
 *    [PlacementPhase.CAMERA_ERROR] → [ArGuidanceCue.NONE]: those states offer a choice, so
 *    the host shows a card and the overlay stays silent (one surface at a time).
 *
 * Normally obtained from [rememberArGuidanceState]; construct it directly only to drive it
 * yourself (tests, a custom state machine).
 *
 * @param surface which scan glyph to draw — a floor diamond or an upright wall.
 */
@Stable
class ArGuidanceState(val surface: PlacementSurface = PlacementSurface.SURFACE) {

    /** The cue to render. Snapshot state — reading it in composition subscribes to changes. */
    var cue: ArGuidanceCue by mutableStateOf(ArGuidanceCue.NONE)
        private set

    /**
     * The overlay is on screen. Hide non-essential app UI (status pills, hints) while this
     * is true and show it again when it turns false — Apple's HIG rule for coaching, applied
     * on both platforms. Cards that offer a choice are never hidden: the overlay is silent
     * whenever one is due.
     */
    val isCoaching: Boolean get() = cue != ArGuidanceCue.NONE

    private var started = false
    private var knownPlacedAt = 0L
    private var initializingSince: Long? = null
    private var foundUntil: Long? = null
    private var lastPhase = PlacementPhase.INITIALIZING

    /**
     * Recomputes [cue] for the placement's current [phase].
     *
     * @param placedAtMillis [AutoPlacementState.placedAtMillis] — only its *changes* matter,
     *   so any clock works.
     * @param nowMillis the guidance clock (e.g. `SystemClock.uptimeMillis()`); monotonic.
     */
    fun update(phase: PlacementPhase, placedAtMillis: Long, nowMillis: Long) {
        lastPhase = phase
        val newPlacement = started && placedAtMillis != 0L && placedAtMillis != knownPlacedAt
        knownPlacedAt = placedAtMillis
        started = true

        initializingSince = if (phase == PlacementPhase.INITIALIZING) {
            initializingSince ?: nowMillis
        } else {
            null
        }
        val placed = phase == PlacementPhase.PLACED || phase == PlacementPhase.ADJUSTING
        foundUntil = when {
            !placed -> null
            newPlacement -> nowMillis + FOUND_HOLD_MS
            else -> foundUntil
        }

        cue = when (phase) {
            PlacementPhase.INITIALIZING -> {
                val since = initializingSince ?: nowMillis
                if (nowMillis - since >= INITIALIZING_DELAY_MS) ArGuidanceCue.INITIALIZING else ArGuidanceCue.NONE
            }
            PlacementPhase.SCANNING -> ArGuidanceCue.SCAN
            PlacementPhase.PLACED, PlacementPhase.ADJUSTING -> {
                val until = foundUntil
                if (until != null && nowMillis < until) ArGuidanceCue.SURFACE_FOUND else ArGuidanceCue.NONE
            }
            PlacementPhase.TRACKING_LOST -> ArGuidanceCue.TRACKING_LIMITED
            PlacementPhase.RECOVERING -> ArGuidanceCue.RELOCALIZING
            PlacementPhase.NO_SURFACE,
            PlacementPhase.RECOVERY_FAILED,
            PlacementPhase.CAMERA_ERROR -> ArGuidanceCue.NONE
        }
        if ((foundUntil ?: Long.MAX_VALUE) <= nowMillis) foundUntil = null
    }

    /**
     * Milliseconds until [cue] can change without any input changing, `null` when only a new
     * phase or placement can change it. Lets the driver sleep instead of polling.
     */
    fun nextTransitionDelayMillis(nowMillis: Long): Long? {
        val initSince = initializingSince
        val until = foundUntil
        return when {
            lastPhase == PlacementPhase.INITIALIZING && initSince != null &&
                cue == ArGuidanceCue.NONE ->
                (initSince + INITIALIZING_DELAY_MS - nowMillis).coerceAtLeast(MIN_DELAY_MS)
            until != null -> (until - nowMillis).coerceAtLeast(MIN_DELAY_MS)
            else -> null
        }
    }

    companion object {
        /** A session that starts tracking faster than this shows no initializing glyph at all. */
        const val INITIALIZING_DELAY_MS = 500L

        /**
         * How long the "surface found" beat stays up: the 450 ms `motion-coach-resolve` of
         * `DESIGN.md` (diamond fills, cube lands) plus a 150 ms hold so the result registers.
         */
        const val FOUND_HOLD_MS = 600L

        /** Floor for [nextTransitionDelayMillis] — never a zero-delay spin. */
        const val MIN_DELAY_MS = 16L
    }
}

/**
 * Coaching state for [placement], for a custom coaching UI or to hide your chrome while
 * the built-in [ARCoachingOverlay] is showing ([ArGuidanceState.isCoaching]).
 *
 * [AutoPlacementScene] already renders [ARCoachingOverlay] with `coaching = true` (the
 * default). Pass `coaching = false` to it when you draw your own from this state.
 *
 * ```kotlin
 * val placement = rememberAutoPlacementState()
 * val guidance = rememberArGuidanceState(placement)
 * Box {
 *     AutoPlacementScene(assetReady = ready, state = placement, coaching = false) { … }
 *     ARCoachingOverlay(guidance)                       // or your own UI from guidance.cue
 *     if (!guidance.isCoaching) MyStatusPill()          // hide chrome while coaching
 * }
 * ```
 */
@Composable
fun rememberArGuidanceState(
    placement: AutoPlacementState,
    surface: PlacementSurface = PlacementSurface.SURFACE,
): ArGuidanceState {
    val guidance = remember(placement, surface) { ArGuidanceState(surface) }
    val phase = placement.phase
    // placedAtMillis is plain state; a new placement always moves the phase to PLACED in the
    // same frame, so keying on the phase re-reads it at the right moment.
    val placedAt = placement.placedAtMillis
    LaunchedEffect(guidance, phase, placedAt) {
        guidance.update(phase, placedAt, SystemClock.uptimeMillis())
        while (true) {
            val wait = guidance.nextTransitionDelayMillis(SystemClock.uptimeMillis()) ?: break
            delay(wait)
            guidance.update(phase, placedAt, SystemClock.uptimeMillis())
        }
    }
    return guidance
}
