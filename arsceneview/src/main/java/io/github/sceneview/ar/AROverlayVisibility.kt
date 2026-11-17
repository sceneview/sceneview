package io.github.sceneview.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import io.github.sceneview.ExperimentalSceneViewApi

/**
 * Android overlay lifetime notifications for hosts that defer transient messages while an SDK
 * card occupies the scene. Provide a stable callback above [ARSceneView] or standalone overlays.
 *
 * Any centred SDK card calls the listener with `true` on entering composition and `false` on
 * leaving: each [ARCoachingOverlay] card (including its exit transition),
 * [ARCameraPermissionOverlay], [ARCoreAvailabilityOverlay] and the "camera in use" card that
 * [ARSceneView] shows while another scene holds the camera. Several cards may coexist: count the
 * balanced notifications rather than assigning the last boolean. Replacing the listener releases
 * each card from the previous one before registering it with the new one. The small "Surface
 * found" pill does not report. Nothing is reported unless a listener is provided. This is an
 * Android Compose host contract; other platforms have their own overlay lifecycles.
 *
 * Experimental: the set of reporting cards and the callback shape may evolve.
 */
@ExperimentalSceneViewApi
val LocalAROverlayVisibility = compositionLocalOf<((Boolean) -> Unit)?> { null }

/** Keeps a card registered until its content, including any exit transition, leaves. */
@OptIn(ExperimentalSceneViewApi::class)
@Composable
internal fun ReportAROverlayVisibility() {
    val listener = LocalAROverlayVisibility.current
    DisposableEffect(listener) {
        listener?.invoke(true)
        onDispose { listener?.invoke(false) }
    }
}
