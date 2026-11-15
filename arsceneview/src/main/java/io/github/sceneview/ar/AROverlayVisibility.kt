package io.github.sceneview.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf

/**
 * Android overlay lifetime notifications for hosts that defer transient messages while an SDK
 * card occupies the scene. Provide a stable callback above [ARSceneView] or standalone overlays.
 *
 * Each [ARCoachingOverlay] card, [ARCameraPermissionOverlay] and [ARCoreAvailabilityOverlay]
 * calls the listener with `true` on entering composition and `false` on leaving, including
 * coaching's exit transition. Several cards may coexist: count the balanced notifications
 * rather than assigning the last boolean. The small "Surface found" pill does not report.
 * Nothing is reported unless a listener is provided. This is an Android Compose host contract;
 * other platforms have their own overlay lifecycles.
 */
val LocalAROverlayVisibility = compositionLocalOf<((Boolean) -> Unit)?> { null }

/** Keeps a card registered until its content, including any exit transition, leaves. */
@Composable
internal fun ReportAROverlayVisibility() {
    val listener = LocalAROverlayVisibility.current
    DisposableEffect(listener) {
        listener?.invoke(true)
        onDispose { listener?.invoke(false) }
    }
}
