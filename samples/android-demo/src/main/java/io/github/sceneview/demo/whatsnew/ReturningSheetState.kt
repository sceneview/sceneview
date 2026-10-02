package io.github.sceneview.demo.whatsnew

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Visibility of a sheet that lists samples and stays open across a visit to one of them.
 *
 * The "What's new" sheets close themselves when a sample in them is tapped — the sheet is a
 * window of its own, and left open it would sit over the sample during the navigation. But
 * closing it for good meant that back from the sample landed on a bare Home: the list the user
 * was working through was gone, and every next feature cost a reopen. [leaveForSample] closes
 * the sheet for the trip and marks it to come back; [rememberReturningSheetState] reopens it
 * when the tab host is composed again on the way back.
 */
@Stable
internal class ReturningSheetState(initial: Phase = Phase.Closed) {

    internal enum class Phase { Closed, Open, ReopenOnReturn }

    var phase by mutableStateOf(initial)
        private set

    /** Whether the sheet is on screen now. */
    val isShown: Boolean get() = phase == Phase.Open

    fun open() {
        phase = Phase.Open
    }

    /** A plain dismissal: swipe, scrim, back, or an action that finishes with the sheet. */
    fun dismiss() {
        phase = Phase.Closed
    }

    /** A sample in the sheet was opened: hide the sheet now, show it again on the way back. */
    fun leaveForSample() {
        phase = Phase.ReopenOnReturn
    }

    /** The host is composed again after the visit. */
    internal fun onHostReturned() {
        if (phase == Phase.ReopenOnReturn) phase = Phase.Open
    }

    internal companion object {
        val Saver: Saver<ReturningSheetState, String> = Saver(
            save = { it.phase.name },
            restore = { name -> ReturningSheetState(Phase.valueOf(name)) },
        )
    }
}

/**
 * A [ReturningSheetState] saved with the host's state, so it outlives the host leaving
 * composition while a sample is on top (and a process death in between).
 *
 * [contentReady] holds the reopen back until the sheet has something to draw. The host is
 * composed fresh on the way back, so whatever it loads asynchronously starts empty again: a
 * sheet reopened at once would show a blank frame and then pop its list in.
 */
@Composable
internal fun rememberReturningSheetState(contentReady: Boolean = true): ReturningSheetState {
    val state = rememberSaveable(saver = ReturningSheetState.Saver) { ReturningSheetState() }
    // Runs once per composition of the host (a fresh one is exactly the return from a sample),
    // and again when its content arrives. While the host stays composed through the outgoing
    // half of the navigation, the content is already in and the key does not change, so the
    // sheet stays shut.
    LaunchedEffect(state, contentReady) {
        if (contentReady) state.onHostReturned()
    }
    return state
}
