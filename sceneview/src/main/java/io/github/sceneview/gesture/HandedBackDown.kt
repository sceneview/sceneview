package io.github.sceneview.gesture

import android.view.MotionEvent
import androidx.annotation.RestrictTo
import io.github.sceneview.collision.HitResult
import io.github.sceneview.node.Node

/**
 * A node that captured the current touch stream on its `DOWN` may give it back mid-gesture — a
 * drag that started on an interactive [io.github.sceneview.node.ViewNode] (#4033). The scene's
 * detectors never saw that stream's `DOWN`, so replay one, at the current pointer, before the rest
 * of the stream reaches them. [cameraAbsorbed] keeps it from the camera, like the rest of the stream.
 *
 * The scene [gestureDetector] takes it through [GestureDetector.onHandedBackDown], which confirms
 * an earlier pending tap before keeping this drag from becoming a tap, long press or double tap.
 *
 * Shared by `SceneView` and `ARSceneView`; not part of the public API.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
fun replayHandedBackDown(
    event: MotionEvent,
    capturedNode: Node?,
    hitResult: HitResult?,
    gestureDetector: GestureDetector,
    cameraGestureDetector: CameraGestureDetector?,
    cameraAbsorbed: Boolean = false,
) {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) return
    if (capturedNode?.takeTouchStreamHandBack() != true) return
    val replayedDown = MotionEvent.obtain(
        event.downTime, event.eventTime, MotionEvent.ACTION_DOWN,
        event.x, event.y, event.metaState
    )
    try {
        gestureDetector.onHandedBackDown(replayedDown, hitResult)
        if (!cameraAbsorbed) cameraGestureDetector?.onTouchEvent(replayedDown)
    } finally {
        replayedDown.recycle()
    }
}
