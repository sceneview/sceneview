package io.github.sceneview.ar

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.ar.core.TrackingFailureReason
import kotlinx.coroutines.delay

/**
 * What the AR coaching overlay is telling the user right now — the same vocabulary as
 * Apple's `ARCoachingOverlayView` (surface onboarding, "move more", relocalization), which
 * SceneViewSwift hosts on iOS.
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

    /** A surface was just found — a short "found" beat, then [NONE]. */
    SURFACE_FOUND,

    /** The camera stopped tracking — ask the user to slow down / move more. */
    TRACKING_LIMITED,

    /** Tracking is back but the placed object's anchor has not re-tracked — "look back". */
    RELOCALIZING,
}

/**
 * Why tracking is poor, in words a user can act on — the reason chip of the coaching card.
 * Read from ARCore's [TrackingFailureReason] by [from]; only reasons the user can fix by
 * moving have a hint.
 */
enum class ArTrackingHint {
    /** No actionable reason. */
    NONE,

    /** [TrackingFailureReason.INSUFFICIENT_LIGHT] — "Too dark". */
    TOO_DARK,

    /** [TrackingFailureReason.EXCESSIVE_MOTION] — "Too fast". */
    TOO_FAST,

    /** [TrackingFailureReason.INSUFFICIENT_FEATURES] — "Low detail" (a bare wall, a plain floor). */
    LOW_DETAIL;

    companion object {
        /** The hint for an ARCore failure [reason]; [NONE] for `null` and non-actionable reasons. */
        fun from(reason: TrackingFailureReason?): ArTrackingHint = when (reason) {
            TrackingFailureReason.INSUFFICIENT_LIGHT -> TOO_DARK
            TrackingFailureReason.EXCESSIVE_MOTION -> TOO_FAST
            TrackingFailureReason.INSUFFICIENT_FEATURES -> LOW_DETAIL
            else -> NONE
        }
    }
}

/**
 * The coaching model: [cue], [hint] and [scanLingering], derived from either an
 * [AutoPlacementState] phase ([update] with a [PlacementPhase]) or a tracking-driven
 * [PlaneDiscoveryGuideState] phase ([update] with a [PlaneDiscoveryPhase]). Pure logic driven
 * by an explicit clock, so it is testable on the JVM and animated without a per-frame poll.
 *
 * Placement rules:
 *  - [PlacementPhase.INITIALIZING] → [ArGuidanceCue.INITIALIZING], but only after
 *    [INITIALIZING_DELAY_MS] so a fast start shows nothing at all.
 *  - [PlacementPhase.SCANNING] → [ArGuidanceCue.SCAN]; [scanLingering] after
 *    [SCAN_LINGER_MS].
 *  - A **new** placement (a changed [AutoPlacementState.placedAtMillis]) →
 *    [ArGuidanceCue.SURFACE_FOUND] for [FOUND_HOLD_MS], then [ArGuidanceCue.NONE]. An
 *    anchor that re-tracks after a loss is not a new placement and gets no "found" beat.
 *  - [PlacementPhase.TRACKING_LOST] → [ArGuidanceCue.TRACKING_LIMITED];
 *    [PlacementPhase.RECOVERING] → [ArGuidanceCue.RELOCALIZING].
 *  - [PlacementPhase.NO_SURFACE], [PlacementPhase.RECOVERY_FAILED] and
 *    [PlacementPhase.CAMERA_ERROR] → [ArGuidanceCue.NONE] at once: those states offer a
 *    choice, so the host shows a card and the overlay stays silent (one surface at a time).
 *
 * Steadiness — ARCore flips between tracking and paused several times a second when the
 * phone moves fast, and a coach that blinks with it is worse than none:
 *  - once shown, the card stays at least [MIN_SHOWN_MS];
 *  - after it has left, it comes back only for a cue that held [REENTER_STABLE_MS];
 *  - a paused camera goes back to scanning only after [RECOVER_STABLE_MS] of tracking;
 *  - a [hint] appears once its reason held [HINT_STABLE_MS], and stays [HINT_MIN_SHOWN_MS].
 *
 * Normally obtained from [rememberArGuidanceState]; construct it directly only to drive it
 * yourself (tests, a custom state machine).
 *
 * @param surface which scan illustration to draw — a floor or an upright wall.
 */
@Stable
class ArGuidanceState(val surface: PlacementSurface = PlacementSurface.SURFACE) {

    /** The cue to render. Snapshot state — reading it in composition subscribes to changes. */
    var cue: ArGuidanceCue by mutableStateOf(ArGuidanceCue.NONE)
        private set

    /**
     * Why tracking is poor, shown as a chip with one actionable line. Only ever set while
     * [cue] is [ArGuidanceCue.INITIALIZING] or [ArGuidanceCue.TRACKING_LIMITED].
     */
    var hint: ArTrackingHint by mutableStateOf(ArTrackingHint.NONE)
        private set

    /** The scan has gone on long enough that the card suggests a better spot. */
    var scanLingering: Boolean by mutableStateOf(false)
        private set

    /**
     * The overlay is on screen. Hide non-essential app UI (status pills, hints) while this
     * is true and show it again when it turns false — Apple's HIG rule for coaching, applied
     * on both platforms. Cards that offer a choice are never hidden: the overlay is silent
     * whenever one is due.
     */
    val isCoaching: Boolean get() = cue != ArGuidanceCue.NONE

    // ── Raw derivation (what the inputs say right now) ──────────────────────────────────
    private var started = false
    private var knownPlacedAt = 0L
    private var initializingSince: Long? = null
    private var foundUntil: Long? = null
    private var scanSince: Long? = null
    private var hasTracked = false
    private var lastRaw = ArGuidanceCue.NONE

    // ── Steadiness (what the card shows) ────────────────────────────────────────────────
    private var rawSince = 0L
    private var shownSince = 0L
    private var hasExited = false
    private var rawHint = ArTrackingHint.NONE
    private var rawHintSince = 0L
    private var hintShownSince = 0L
    private var nextDeadline: Long? = null

    /**
     * Recomputes the cue for the placement's current [phase].
     *
     * @param placedAtMillis [AutoPlacementState.placedAtMillis] — only its *changes* matter,
     *   so any clock works.
     * @param nowMillis the guidance clock (e.g. `SystemClock.uptimeMillis()`); monotonic.
     */
    fun update(phase: PlacementPhase, placedAtMillis: Long, nowMillis: Long) =
        update(phase, placedAtMillis, null, nowMillis)

    /**
     * Recomputes the cue for the placement's current [phase] and the camera's
     * [trackingFailureReason] (from `onTrackingFailureChanged`), which feeds [hint].
     */
    fun update(
        phase: PlacementPhase,
        placedAtMillis: Long,
        trackingFailureReason: TrackingFailureReason?,
        nowMillis: Long,
    ) {
        val newPlacement = started && placedAtMillis != 0L && placedAtMillis != knownPlacedAt
        knownPlacedAt = placedAtMillis
        started = true
        val deadlines = Deadlines(nowMillis)

        initializingSince = if (phase == PlacementPhase.INITIALIZING) initializingSince ?: nowMillis else null
        val placed = phase == PlacementPhase.PLACED || phase == PlacementPhase.ADJUSTING
        foundUntil = when {
            !placed -> null
            newPlacement -> nowMillis + FOUND_HOLD_MS
            else -> foundUntil
        }

        val raw = when (phase) {
            PlacementPhase.INITIALIZING -> initializingCue(nowMillis, deadlines)
            PlacementPhase.SCANNING -> ArGuidanceCue.SCAN
            PlacementPhase.PLACED, PlacementPhase.ADJUSTING -> foundCue(nowMillis, deadlines)
            PlacementPhase.TRACKING_LOST -> ArGuidanceCue.TRACKING_LIMITED
            PlacementPhase.RECOVERING -> ArGuidanceCue.RELOCALIZING
            PlacementPhase.NO_SURFACE,
            PlacementPhase.RECOVERY_FAILED,
            PlacementPhase.CAMERA_ERROR -> ArGuidanceCue.NONE
        }
        val cardDue = phase == PlacementPhase.NO_SURFACE ||
            phase == PlacementPhase.RECOVERY_FAILED ||
            phase == PlacementPhase.CAMERA_ERROR
        val linger = lingering(raw, nowMillis, deadlines)
        settle(raw, ArTrackingHint.from(trackingFailureReason), linger, cardDue, deadlines)
    }

    /**
     * Recomputes the cue for a tracking-driven screen from a [PlaneDiscoveryGuideState]
     * [phase] — what [rememberArGuidanceState] with camera and plane signals does for you.
     */
    fun update(
        phase: PlaneDiscoveryPhase,
        trackingFailureReason: TrackingFailureReason?,
        nowMillis: Long,
    ) {
        started = true
        val deadlines = Deadlines(nowMillis)
        // Before the first tracked frame, a failure is still start-up: "Getting ready", with
        // the reason as its chip (#4070), never "Keep looking around".
        val starting = !hasTracked && (phase == PlaneDiscoveryPhase.WAITING || phase == PlaneDiscoveryPhase.LOST)
        initializingSince = if (starting) {
            initializingSince ?: nowMillis
        } else {
            null
        }
        val surfaceDone = phase == PlaneDiscoveryPhase.FADING_OUT || phase == PlaneDiscoveryPhase.DONE
        // The discovery guide skips its own fade when its hint was not up yet; the card is up
        // from the first scanning frame, so the found beat keys on what the card showed.
        if (surfaceDone && lastRaw == ArGuidanceCue.SCAN) foundUntil = nowMillis + FOUND_HOLD_MS
        if (!surfaceDone) foundUntil = null

        val raw = when (phase) {
            PlaneDiscoveryPhase.WAITING ->
                if (hasTracked) ArGuidanceCue.TRACKING_LIMITED else initializingCue(nowMillis, deadlines)
            PlaneDiscoveryPhase.SILENT,
            PlaneDiscoveryPhase.HAND_HINT,
            PlaneDiscoveryPhase.HELP_OFFERED -> ArGuidanceCue.SCAN
            PlaneDiscoveryPhase.LOST ->
                if (hasTracked) ArGuidanceCue.TRACKING_LIMITED else initializingCue(nowMillis, deadlines)
            PlaneDiscoveryPhase.FADING_OUT,
            PlaneDiscoveryPhase.DONE -> foundCue(nowMillis, deadlines)
        }
        if (phase != PlaneDiscoveryPhase.WAITING && phase != PlaneDiscoveryPhase.LOST) hasTracked = true
        val linger = phase == PlaneDiscoveryPhase.HELP_OFFERED || lingering(raw, nowMillis, deadlines)
        settle(raw, ArTrackingHint.from(trackingFailureReason), linger, cardDue = false, deadlines)
    }

    /**
     * Milliseconds until [cue] or [hint] can change without any input changing, `null` when
     * only a new input can change them. Lets the driver sleep instead of polling.
     */
    fun nextTransitionDelayMillis(nowMillis: Long): Long? =
        nextDeadline?.let { (it - nowMillis).coerceAtLeast(MIN_DELAY_MS) }

    private fun initializingCue(nowMillis: Long, deadlines: Deadlines): ArGuidanceCue {
        val since = initializingSince ?: nowMillis
        val due = since + INITIALIZING_DELAY_MS
        deadlines.add(due)
        return if (nowMillis >= due) ArGuidanceCue.INITIALIZING else ArGuidanceCue.NONE
    }

    private fun foundCue(nowMillis: Long, deadlines: Deadlines): ArGuidanceCue {
        val until = foundUntil ?: return ArGuidanceCue.NONE
        if (nowMillis >= until) {
            foundUntil = null
            return ArGuidanceCue.NONE
        }
        deadlines.add(until)
        return ArGuidanceCue.SURFACE_FOUND
    }

    private fun lingering(raw: ArGuidanceCue, nowMillis: Long, deadlines: Deadlines): Boolean {
        if (raw != ArGuidanceCue.SCAN) {
            scanSince = null
            return false
        }
        val since = scanSince ?: nowMillis.also { scanSince = it }
        deadlines.add(since + SCAN_LINGER_MS)
        return nowMillis - since >= SCAN_LINGER_MS
    }

    /** The steadiness layer: from what the inputs say to what the card shows. */
    private fun settle(
        raw: ArGuidanceCue,
        rawHintNow: ArTrackingHint,
        linger: Boolean,
        cardDue: Boolean,
        deadlines: Deadlines,
    ) {
        val next = settleCue(raw, cardDue, deadlines)
        hint = settleHint(next, rawHintNow, deadlines)
        scanLingering = linger && next == ArGuidanceCue.SCAN
        nextDeadline = deadlines.earliest
    }

    /** Holds the card long enough to read, and re-enters only on a steady signal. */
    private fun settleCue(raw: ArGuidanceCue, cardDue: Boolean, deadlines: Deadlines): ArGuidanceCue {
        val now = deadlines.now
        if (raw != lastRaw) {
            lastRaw = raw
            rawSince = now
        }
        val shown = cue
        val next = when {
            raw == shown -> shown
            // A card that offers a choice takes the screen at once; a timed beat ends on time.
            raw == ArGuidanceCue.NONE && (cardDue || shown == ArGuidanceCue.SURFACE_FOUND) -> raw
            raw == ArGuidanceCue.NONE && now - shownSince >= MIN_SHOWN_MS -> raw
            raw == ArGuidanceCue.NONE -> shown.also { deadlines.add(shownSince + MIN_SHOWN_MS) }
            // The found beat is an event, never debounced.
            raw == ArGuidanceCue.SURFACE_FOUND -> raw
            shown == ArGuidanceCue.NONE && hasExited && now - rawSince < REENTER_STABLE_MS ->
                shown.also { deadlines.add(rawSince + REENTER_STABLE_MS) }
            shown == ArGuidanceCue.TRACKING_LIMITED && raw == ArGuidanceCue.SCAN &&
                now - rawSince < RECOVER_STABLE_MS ->
                shown.also { deadlines.add(rawSince + RECOVER_STABLE_MS) }
            else -> raw
        }
        if (shown == ArGuidanceCue.NONE && next != ArGuidanceCue.NONE) shownSince = now
        if (shown != ArGuidanceCue.NONE && next == ArGuidanceCue.NONE) hasExited = true
        cue = next
        return next
    }

    /** A reason must hold before its chip shows, and a shown chip stays long enough to read. */
    private fun settleHint(next: ArGuidanceCue, rawHintNow: ArTrackingHint, deadlines: Deadlines): ArTrackingHint {
        val now = deadlines.now
        if (rawHintNow != rawHint) {
            rawHint = rawHintNow
            rawHintSince = now
        }
        val hintAllowed = next == ArGuidanceCue.INITIALIZING || next == ArGuidanceCue.TRACKING_LIMITED
        val stableHint = if (now - rawHintSince >= HINT_STABLE_MS) {
            rawHint
        } else {
            if (rawHint != hint) deadlines.add(rawHintSince + HINT_STABLE_MS)
            null
        }
        val nextHint = when {
            !hintAllowed -> ArTrackingHint.NONE
            stableHint == hint -> hint
            hint != ArTrackingHint.NONE && now - hintShownSince < HINT_MIN_SHOWN_MS ->
                hint.also { deadlines.add(hintShownSince + HINT_MIN_SHOWN_MS) }
            stableHint != null -> stableHint
            else -> hint
        }
        if (nextHint != hint && nextHint != ArTrackingHint.NONE) hintShownSince = now
        return nextHint
    }

    /** Collects the future instants at which a re-evaluation can change the output. */
    private class Deadlines(val now: Long) {
        var earliest: Long? = null
            private set

        fun add(at: Long) {
            if (at <= now) return
            earliest = earliest?.let { minOf(it, at) } ?: at
        }
    }

    companion object {
        /** A session that starts tracking faster than this shows no initializing card at all. */
        const val INITIALIZING_DELAY_MS = 500L

        /**
         * How long the "surface found" beat stays up: `motion-coach-resolve` (fill 350 ms, the
         * check settles by 750 ms) plus a hold so the result registers.
         */
        const val FOUND_HOLD_MS = 1_000L

        /** A scan this long without a surface changes the card's second line to a tip. */
        const val SCAN_LINGER_MS = 8_000L

        /** Once shown, the card stays at least this long. */
        const val MIN_SHOWN_MS = 1_500L

        /** After the card has left, a cue must hold this long to bring it back. */
        const val REENTER_STABLE_MS = 600L

        /** A paused camera must track this long before the card goes back to scanning. */
        const val RECOVER_STABLE_MS = 500L

        /** A tracking-failure reason must hold this long before its chip appears. */
        const val HINT_STABLE_MS = 700L

        /** Once shown, a chip stays at least this long. */
        const val HINT_MIN_SHOWN_MS = 1_500L

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
 * var failure by remember { mutableStateOf<TrackingFailureReason?>(null) }
 * val guidance = rememberArGuidanceState(placement, trackingFailureReason = failure)
 * Box {
 *     AutoPlacementScene(
 *         assetReady = ready, state = placement, coaching = false,
 *         onTrackingFailureChanged = { failure = it },
 *     ) { … }
 *     ARCoachingOverlay(guidance)                       // or your own UI from guidance.cue
 *     if (!guidance.isCoaching) MyStatusPill()          // hide chrome while coaching
 * }
 * ```
 *
 * @param trackingFailureReason the latest `onTrackingFailureChanged` value; drives the
 *   "Too dark" / "Too fast" / "Low detail" chip.
 */
@Composable
fun rememberArGuidanceState(
    placement: AutoPlacementState,
    surface: PlacementSurface = PlacementSurface.SURFACE,
    trackingFailureReason: TrackingFailureReason? = null,
): ArGuidanceState {
    val guidance = remember(placement, surface) { ArGuidanceState(surface) }
    val phase = placement.phase
    // placedAtMillis is plain state; a new placement always moves the phase to PLACED in the
    // same frame, so keying on the phase re-reads it at the right moment.
    val placedAt = placement.placedAtMillis
    LaunchedEffect(guidance, phase, placedAt, trackingFailureReason) {
        guidance.update(phase, placedAt, trackingFailureReason, SystemClock.uptimeMillis())
        while (true) {
            val wait = guidance.nextTransitionDelayMillis(SystemClock.uptimeMillis()) ?: break
            delay(wait)
            guidance.update(phase, placedAt, trackingFailureReason, SystemClock.uptimeMillis())
        }
    }
    return guidance
}

/** Binary-compatibility shim for the pre-`trackingFailureReason` descriptor. */
@Deprecated(
    "Binary-compatibility overload. Use the overload that takes `trackingFailureReason`.",
    level = DeprecationLevel.HIDDEN,
)
@Composable
fun rememberArGuidanceState(
    placement: AutoPlacementState,
    surface: PlacementSurface = PlacementSurface.SURFACE,
): ArGuidanceState = rememberArGuidanceState(placement, surface, null)

/**
 * Coaching state for any AR screen that is not an [AutoPlacementScene]: feed it the camera
 * and plane signals your `ARSceneView` callbacks already produce, render
 * [ARCoachingOverlay] with it, and hide your own status chrome while
 * [ArGuidanceState.isCoaching].
 *
 * ```kotlin
 * var cameraReady by remember { mutableStateOf(false) }
 * var isTracking by remember { mutableStateOf(false) }
 * var planeFound by remember { mutableStateOf(false) }
 * var failure by remember { mutableStateOf<TrackingFailureReason?>(null) }
 * Box {
 *     ARSceneView(
 *         onSessionUpdated = { _, frame ->
 *             cameraReady = true
 *             isTracking = frame.camera.trackingState == TrackingState.TRACKING
 *             if (!planeFound) planeFound = frame.getUpdatedTrackables(Plane::class.java)
 *                 .any { it.trackingState == TrackingState.TRACKING }
 *         },
 *         onTrackingFailureChanged = { failure = it },
 *     )
 *     val guidance = rememberArGuidanceState(cameraReady, isTracking, planeFound, failure)
 *     ARCoachingOverlay(guidance)
 * }
 * ```
 *
 * A screen that needs no surface (point clouds, depth, meshes) passes `surfaceFound = true`:
 * the card then only covers the start-up and tracking problems.
 *
 * @param cameraReady true once ARCore delivered its first frame.
 * @param isTracking `frame.camera.trackingState == TrackingState.TRACKING`.
 * @param surfaceFound a plane is tracked. Latch it — a found surface stays found.
 * @param trackingFailureReason the latest `onTrackingFailureChanged` value.
 */
@Composable
fun rememberArGuidanceState(
    cameraReady: Boolean,
    isTracking: Boolean,
    surfaceFound: Boolean,
    trackingFailureReason: TrackingFailureReason?,
    surface: PlacementSurface = PlacementSurface.SURFACE,
): ArGuidanceState {
    val guidance = remember(surface) { ArGuidanceState(surface) }
    val discovery = remember { PlaneDiscoveryGuideState() }
    LaunchedEffect(guidance, cameraReady, isTracking, surfaceFound, trackingFailureReason) {
        fun step(now: Long) {
            val phase = discovery.update(cameraReady, isTracking, surfaceFound, trackingFailureReason, now)
            guidance.update(phase, trackingFailureReason, now)
        }
        step(SystemClock.uptimeMillis())
        while (true) {
            val now = SystemClock.uptimeMillis()
            val wait = listOfNotNull(
                discovery.nextTransitionDelayMillis(now),
                guidance.nextTransitionDelayMillis(now),
            ).minOrNull() ?: break
            delay(wait)
            step(SystemClock.uptimeMillis())
        }
    }
    return guidance
}
