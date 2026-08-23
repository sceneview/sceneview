package io.github.sceneview.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    Column(
        modifier = modifier
            .align(Alignment.Center)
            .padding(24.dp)
            .widthIn(max = 320.dp)
            .background(OverlayCardBackground, RoundedCornerShape(16.dp))
            .padding(20.dp)
            .testTag(ARCameraPermissionOverlayTags.CARD),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = stringResource(R.string.sceneview_camera_permission_title),
            style = TextStyle(
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            text = stringResource(
                if (state.permanentlyDenied) R.string.sceneview_camera_permission_body_settings
                else R.string.sceneview_camera_permission_body
            ),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            ),
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .background(Color.White, RoundedCornerShape(22.dp))
                .clickable(role = Role.Button) {
                    if (state.permanentlyDenied) state.openSettings() else state.request()
                }
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .testTag(ARCameraPermissionOverlayTags.ACTION),
        ) {
            BasicText(
                text = stringResource(
                    if (state.permanentlyDenied) R.string.sceneview_camera_permission_settings
                    else R.string.sceneview_camera_permission_grant
                ),
                style = TextStyle(
                    color = Color(0xFF1A1A2E),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

private val OverlayCardBackground = Color.Black.copy(alpha = 0.85f)
