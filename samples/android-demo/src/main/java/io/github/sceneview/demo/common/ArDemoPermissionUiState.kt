package io.github.sceneview.demo.common

/** The one screen an AR demo route may show for its camera-permission state. */
internal enum class ArDemoPermissionUiState {
    /** Camera granted: the demo is mounted and owns the screen. */
    ShowDemo,

    /** Not asked yet on this visit: the system dialog is on its way. */
    RequestPermission,

    /** Refused, and Android still shows its dialog: offer to ask again. */
    RetryPermission,

    /** Refused for good — Android answers without a dialog: only system settings can fix it. */
    OpenSettings,
}

/**
 * Chooses the only UI the AR demo route may show.
 *
 * A grant always shows the demo, wherever it comes from — the dialog or a round trip
 * through system settings — so coming back with the camera on needs no extra tap.
 */
internal fun arDemoPermissionUiState(
    granted: Boolean,
    requested: Boolean,
    shouldShowRationale: Boolean,
): ArDemoPermissionUiState = when {
    granted -> ArDemoPermissionUiState.ShowDemo
    !requested -> ArDemoPermissionUiState.RequestPermission
    shouldShowRationale -> ArDemoPermissionUiState.RetryPermission
    else -> ArDemoPermissionUiState.OpenSettings
}
