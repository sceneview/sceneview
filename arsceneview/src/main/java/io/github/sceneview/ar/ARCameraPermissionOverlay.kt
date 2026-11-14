package io.github.sceneview.ar

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * What [ARSceneView] knows about a denied camera permission (#3308).
 *
 * @property permanentlyDenied `true` once the system stops showing the dialog — only
 *   [openSettings] can help; otherwise [request] shows the dialog again.
 * @property settingsAvailable offers settings as a fallback for an ambiguous answer, while
 *   keeping [request] as the main action.
 * @property request shows the system camera permission dialog again.
 * @property openSettings opens the app's system settings page. Call it from an explicit
 *   user action only — never automatically, that is the bug this state replaces.
 */
@Immutable
class ARCameraPermissionState(
    val permanentlyDenied: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
    val settingsAvailable: Boolean,
) {
    /** Retains the original constructor for hosts that only distinguish confirmed denials. */
    constructor(
        permanentlyDenied: Boolean,
        request: () -> Unit,
        openSettings: () -> Unit,
    ) : this(permanentlyDenied, request, openSettings, settingsAvailable = permanentlyDenied)
}

/** Test tags for [ARCameraPermissionOverlay]. */
object ARCameraPermissionOverlayTags {
    const val CARD = "sceneview-camera-permission"
    const val ACTION = "sceneview-camera-permission-action"
    const val SETTINGS_ACTION = "sceneview-camera-permission-settings-action"
}

/**
 * The built-in explanation drawn by [ARSceneView] while the camera permission is denied:
 * a dark card in the [PlaneDiscoveryHelpCard] style — the ground is a camera frame (here,
 * black) so it does not follow the app theme. Ambiguous answers keep the request action
 * and offer settings as a fallback; only confirmed denials replace it with settings.
 *
 * Public so hosts that pass their own `cameraPermissionOverlay` can still reuse it, and so
 * previews can render all variants without an ARCore session.
 */
@Composable
fun BoxScope.ARCameraPermissionOverlay(
    state: ARCameraPermissionState,
    modifier: Modifier = Modifier,
) {
    AROverlayCard(
        title = stringResource(R.string.sceneview_camera_permission_title),
        body = stringResource(
            if (state.permanentlyDenied) R.string.sceneview_camera_permission_body_settings
            else if (state.settingsAvailable) R.string.sceneview_camera_permission_body_fallback
            else R.string.sceneview_camera_permission_body
        ),
        action = stringResource(
            if (state.permanentlyDenied) R.string.sceneview_camera_permission_settings
            else R.string.sceneview_camera_permission_grant
        ),
        onAction = { if (state.permanentlyDenied) state.openSettings() else state.request() },
        cardTag = ARCameraPermissionOverlayTags.CARD,
        actionTag = ARCameraPermissionOverlayTags.ACTION,
        modifier = modifier,
        secondaryAction = if (!state.permanentlyDenied && state.settingsAvailable) {
            stringResource(R.string.sceneview_camera_permission_settings)
        } else null,
        onSecondaryAction = state.openSettings,
        secondaryActionTag = ARCameraPermissionOverlayTags.SETTINGS_ACTION,
    )
}
