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

/**
 * Below this, a refusal came back too fast for a dialog to have been read and answered.
 *
 * The value of the library's own constant (`arsceneview`, `ARCore.kt`, #4452). A Pixel 4a
 * answers a blocked request in 290 ms on an idle screen and around 510 ms under load: the
 * 500 ms this used to be sat in the middle of that, and nobody reads and dismisses a dialog
 * inside a second.
 */
internal const val CAMERA_PROMPT_INSTANT_RETURN_MS = 1000L

/**
 * Whether a refused request means Android has stopped showing the dialog.
 *
 * Android has no "permanently denied" query: a refusal with no rationale flag is what a
 * permanent denial looks like, and also what a dialog dismissed with Back, or with a tap
 * outside it, looks like. They are told apart by what surrounds the answer — the first two
 * signals of the library's `isCameraPromptBlocked`:
 *  - the rationale flag was up before and is down after: that was the second refusal;
 *  - the answer came back at once ([CAMERA_PROMPT_INSTANT_RETURN_MS]): no dialog was shown.
 *
 * Any other refusal was a dialog somebody saw and did not answer. Android will show it
 * again, so it keeps its "Allow camera" however many times it is dismissed (#4460). The
 * library also gives up after two such answers in a row; the gate does not, because the
 * count of dismissals says nothing about what Android does next — two Back presses sent a
 * Pixel 4a to system settings for a switch the dialog would have flipped.
 */
internal fun cameraPromptBlocked(
    granted: Boolean,
    rationaleBefore: Boolean,
    rationaleAfter: Boolean,
    elapsedMs: Long,
): Boolean = !granted && !rationaleAfter &&
    (rationaleBefore || elapsedMs < CAMERA_PROMPT_INSTANT_RETURN_MS)

/** What the card says while Android still shows its dialog. */
internal enum class ArCameraAskReason {
    /** Never answered: not asked yet, or the dialog was dismissed without a choice. */
    NotAnswered,

    /** "Don't allow" was chosen once: Android asks one more time, and wants a reason given. */
    Refused,
}

/** Which explanation goes with "Allow camera": a refusal gets the reason, a dismissal does not. */
internal fun arCameraAskReason(shouldShowRationale: Boolean): ArCameraAskReason =
    if (shouldShowRationale) ArCameraAskReason.Refused else ArCameraAskReason.NotAnswered
