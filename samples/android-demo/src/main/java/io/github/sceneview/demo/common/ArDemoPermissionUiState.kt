package io.github.sceneview.demo.common

/** The one screen a camera-gated AR surface may show for its permission state. */
internal enum class ArDemoPermissionUiState {
    /** Camera granted: the demo is mounted and owns the screen. */
    ShowDemo,

    /** Not granted, and Android still shows its dialog: explain, and offer to ask. */
    AskPermission,

    /** Android answers without a dialog: only system settings can turn the camera on. */
    OpenSettings,
}

/**
 * Chooses the only UI a camera-gated AR surface may show.
 *
 * A grant always shows the demo, wherever it comes from — the dialog or a round trip
 * through system settings — so coming back with the camera on needs no extra tap.
 */
internal fun arDemoPermissionUiState(
    granted: Boolean,
    blocked: Boolean,
): ArDemoPermissionUiState = when {
    granted -> ArDemoPermissionUiState.ShowDemo
    blocked -> ArDemoPermissionUiState.OpenSettings
    else -> ArDemoPermissionUiState.AskPermission
}

/**
 * Whether the gate opens the system dialog by itself on arrival.
 *
 * Once per visit ([promptLaunched] survives an activity recreation, so a rotation behind
 * the dialog does not ask twice), and never for someone who already refused once
 * ([shouldShowRationale]): they get the explanation and a button first, not the same
 * dialog thrown back at them.
 */
internal fun shouldAutoPromptForCamera(
    granted: Boolean,
    blocked: Boolean,
    shouldShowRationale: Boolean,
    promptLaunched: Boolean,
): Boolean = !granted && !blocked && !shouldShowRationale && !promptLaunched

/** Below this, a refusal came back too fast for a dialog to have been on screen. */
internal const val CAMERA_PROMPT_INSTANT_RETURN_MS = 500L

/**
 * Whether a refused request means Android has stopped showing the dialog.
 *
 * Android has no "permanently denied" query: a refusal with no rationale flag is what a
 * permanent denial looks like, and also what a dialog dismissed with Back looks like. They
 * are told apart by what surrounds the answer:
 *  - the rationale flag was up before and is down after: that was the second refusal;
 *  - the answer came back at once: no dialog was shown;
 *  - it is the second such answer in a row ([earlierUnexplainedRefusals]): whichever it
 *    was, asking again is not getting anywhere, so settings is the way out.
 *
 * A first slow, unexplained refusal is therefore *not* blocked — a dismissed dialog keeps
 * its "Try again".
 */
internal fun cameraPromptBlocked(
    granted: Boolean,
    rationaleBefore: Boolean,
    rationaleAfter: Boolean,
    elapsedMs: Long,
    earlierUnexplainedRefusals: Int,
): Boolean = !granted && !rationaleAfter && (
    rationaleBefore ||
        elapsedMs < CAMERA_PROMPT_INSTANT_RETURN_MS ||
        earlierUnexplainedRefusals >= 1
    )
