package io.github.sceneview.ar

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Explains a camera turn yielded to another scene; the arbiter resumes this view automatically. */
@Composable
internal fun BoxScope.ARCameraInUseOverlay(isWaitingForCamera: Boolean) {
    if (!isWaitingForCamera) return
    AROverlayCard(
        title = stringResource(R.string.sceneview_camera_in_use_title),
        body = stringResource(R.string.sceneview_camera_in_use_body),
        action = null,
        onAction = {},
        cardTag = "sceneview-camera-in-use",
        actionTag = "",
    )
}
