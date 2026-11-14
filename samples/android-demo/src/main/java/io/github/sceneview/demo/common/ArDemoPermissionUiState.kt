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
