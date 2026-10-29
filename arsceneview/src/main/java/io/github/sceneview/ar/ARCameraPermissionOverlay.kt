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
 * @property request shows the system camera permission dialog again.
 * @property openSettings opens the app's system settings page. Call it from an explicit
 *   user action only — never automatically, that is the bug this state replaces.
 */
@Immutable
class ARCameraPermissionState(
    val permanentlyDenied: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/** Test tags for [ARCameraPermissionOverlay]. */
object ARCameraPermissionOverlayTags {
    const val CARD = "sceneview-camera-permission"
    const val ACTION = "sceneview-camera-permission-action"
}

/**
 * The built-in explanation drawn by [ARSceneView] while the camera permission is denied:
 * a dark card in the [PlaneDiscoveryHelpCard] style — the ground is a camera frame (here,
 * black) so it does not follow the app theme — with one action. Before the system stops
 * asking, that action re-requests; afterwards it opens the app settings.
 *
 * Public so hosts that pass their own `cameraPermissionOverlay` can still reuse it, and so
 * previews can render both variants without an ARCore session.
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
    )
}
