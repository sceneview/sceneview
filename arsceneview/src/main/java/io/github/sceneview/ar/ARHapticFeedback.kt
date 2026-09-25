package io.github.sceneview.ar

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import io.github.sceneview.haptic.ARHapticEvent
import io.github.sceneview.haptic.SceneViewHaptic
import io.github.sceneview.haptic.play
import io.github.sceneview.haptic.rememberHapticFeedback

/**
 * Opt-in semantic haptics for an [AutoPlacementScene] flow. Off unless you call it:
 *
 * ```kotlin
 * val state = rememberAutoPlacementState()
 * ARHapticFeedback(state)
 * AutoPlacementScene(assetReady = …, state = state) { placement -> AutoPlacementModel(placement, state, model) }
 * ```
 *
 * Plays each [ARHapticEvent] once, on the transition that the user also sees:
 *
 * | Event | When |
 * |---|---|
 * | [ARHapticEvent.Placed] | the object stands on the surface |
 * | [ARHapticEvent.Selected] | the standing object is tapped or grabbed |
 * | [ARHapticEvent.ScaleSnapped] | a pinch enters the 100 % detent (with the model's elastic rebound) |
 * | [ARHapticEvent.LimitReached] | a pinch reaches 25 % or 400 % |
 * | [ARHapticEvent.InvalidMove] | a drag leaves the supported surface |
 * | [ARHapticEvent.TrackingLost] | tracking is lost, once it had been established |
 * | [ARHapticEvent.Recovered] | the placement is found again |
 * | [ARHapticEvent.HelpNeeded] | the no-surface or recovery-failed card appears |
 *
 * The same event never repeats within 400 ms. Every haptic honours the system *Touch
 * feedback* setting. Pass [enabled] = `false` to expose an in-app toggle.
 */
@Composable
fun ARHapticFeedback(
    state: AutoPlacementState,
    haptic: SceneViewHaptic = rememberHapticFeedback(),
    enabled: Boolean = true,
) {
    val currentHaptic by rememberUpdatedState(haptic)
    val transitions = remember(state, enabled) { ARHapticTransitions() }
    LaunchedEffect(transitions) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { state.phase to state.isSelected }.collect { (phase, selected) ->
            val snapshot = ARHapticTransitions.Snapshot(phase, state.placementsCreated, selected)
            transitions.next(snapshot, SystemClock.uptimeMillis()).forEach { currentHaptic.play(it) }
        }
    }
    DisposableEffect(transitions) {
        if (enabled) {
            state.gestureHapticSink = { event ->
                if (transitions.accept(event, SystemClock.uptimeMillis())) currentHaptic.play(event)
            }
        }
        onDispose { state.gestureHapticSink = null }
    }
}

/**
 * Turns [AutoPlacementState] snapshots into [ARHapticEvent]s. Pure, so the table is pinned by
 * JVM tests.
 *
 * Reads the same phases as [ArGuidanceState], so a haptic and the coaching cue it accompanies
 * always change together ([PlacementPhase.TRACKING_LOST] is both [ARHapticEvent.TrackingLost]
 * and [ArGuidanceCue.TRACKING_LIMITED]). "Tracking was established" is not tracked here: the
 * state machine never enters [PlacementPhase.TRACKING_LOST] from
 * [PlacementPhase.INITIALIZING] — untracked start-up frames keep the session initializing — so
 * the session-start silence has a single source of truth.
 */
internal class ARHapticTransitions(private val throttleMs: Long = THROTTLE_MS) {

    data class Snapshot(val phase: PlacementPhase, val placementsCreated: Int, val isSelected: Boolean)

    private var previous: Snapshot? = null
    private val lastPlayed = HashMap<ARHapticEvent, Long>()

    /** Events for the transition into [snapshot]. The first snapshot only records. */
    fun next(snapshot: Snapshot, nowMillis: Long): List<ARHapticEvent> {
        val before = previous
        previous = snapshot
        val events = if (before == null) emptyList() else transition(before, snapshot)
        return events.filter { accept(it, nowMillis) }
    }

    private fun transition(before: Snapshot, now: Snapshot): List<ARHapticEvent> {
        if (now.placementsCreated > before.placementsCreated) return listOf(ARHapticEvent.Placed)
        if (now.phase != before.phase) {
            val event = when {
                now.phase == PlacementPhase.TRACKING_LOST -> ARHapticEvent.TrackingLost
                now.phase in STANDING && before.phase in LOST -> ARHapticEvent.Recovered
                now.phase == PlacementPhase.NO_SURFACE || now.phase == PlacementPhase.RECOVERY_FAILED ->
                    ARHapticEvent.HelpNeeded
                else -> null
            }
            if (event != null) return listOf(event)
        }
        if (now.isSelected && !before.isSelected) return listOf(ARHapticEvent.Selected)
        return emptyList()
    }

    /** Throttle: `false` when [event] already played less than [throttleMs] ago. */
    fun accept(event: ARHapticEvent, nowMillis: Long): Boolean {
        val last = lastPlayed[event]
        if (last != null && nowMillis - last < throttleMs) return false
        lastPlayed[event] = nowMillis
        return true
    }

    companion object {
        const val THROTTLE_MS: Long = 400L
        private val STANDING = setOf(PlacementPhase.PLACED, PlacementPhase.ADJUSTING)
        private val LOST = setOf(
            PlacementPhase.TRACKING_LOST, PlacementPhase.RECOVERING, PlacementPhase.RECOVERY_FAILED,
        )
    }
}
