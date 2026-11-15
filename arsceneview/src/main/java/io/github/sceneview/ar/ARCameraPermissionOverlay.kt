package io.github.sceneview.ar

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/**
 * What [ARSceneView] knows about a denied camera permission (#3308).
 *
 * @property recovery The available recovery actions. Settings is the primary action for
 *   [ARCameraPermissionRecovery.RequestWithSettings], with [request] still available.
 * @property permanentlyDenied Legacy conservative recommendation: `true` when settings is
 *   available, including ambiguous answers. It does not prove the dialog is blocked. Hosts
 *   that need all actions should use [recovery].
 * @property request requests camera permission again. Android may suppress its dialog.
 * @property openSettings opens the app's system settings page. Call it from an explicit
 *   user action only — never automatically, that is the bug this state replaces.
 */
@Immutable
class ARCameraPermissionState(
    val recovery: ARCameraPermissionRecovery,
    val request: () -> Unit,
    val openSettings: () -> Unit,
) {
    val permanentlyDenied: Boolean get() = recovery != ARCameraPermissionRecovery.Request

    /** Retains the original constructor and its request/settings behavior. */
    constructor(
        permanentlyDenied: Boolean,
        request: () -> Unit,
        openSettings: () -> Unit,
    ) : this(
        if (permanentlyDenied) ARCameraPermissionRecovery.Settings else ARCameraPermissionRecovery.Request,
        request,
        openSettings,
    )
}

/** Test tags for [ARCameraPermissionOverlay]. */
object ARCameraPermissionOverlayTags {
    const val CARD = "sceneview-camera-permission"
    const val ACTION = "sceneview-camera-permission-action"
    const val REQUEST_ACTION = "sceneview-camera-permission-request-action"
}

/**
 * The built-in explanation drawn by [ARSceneView] while the camera permission is denied:
 * a dark card in the [PlaneDiscoveryHelpCard] style — the ground is a camera frame (here,
 * black) so it does not follow the app theme. Ambiguous answers lead with settings and
 * keep a secondary request action with neutral copy; confirmed denials offer only settings.
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
            when (state.recovery) {
                ARCameraPermissionRecovery.Request -> R.string.sceneview_camera_permission_body
                ARCameraPermissionRecovery.RequestWithSettings -> R.string.sceneview_camera_permission_body_fallback
                ARCameraPermissionRecovery.Settings -> R.string.sceneview_camera_permission_body_settings
            }
        ),
        action = stringResource(
            if (state.permanentlyDenied) R.string.sceneview_camera_permission_settings
            else R.string.sceneview_camera_permission_grant
        ),
        onAction = { if (state.permanentlyDenied) state.openSettings() else state.request() },
        cardTag = ARCameraPermissionOverlayTags.CARD,
        actionTag = ARCameraPermissionOverlayTags.ACTION,
        modifier = modifier,
        secondaryAction = if (state.recovery == ARCameraPermissionRecovery.RequestWithSettings) {
            stringResource(R.string.sceneview_camera_permission_grant)
        } else null,
        onSecondaryAction = state.request,
        secondaryActionTag = ARCameraPermissionOverlayTags.REQUEST_ACTION,
    )
}
