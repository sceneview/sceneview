// Extensions of NodeGestureDelegate, split out of NodeGestureDelegate.kt, which is at detekt's
// TooManyFunctions class cap — same package, so imports are unaffected.
package io.github.sceneview.node

import io.github.sceneview.gesture.NodeEditingListener

/**
 * Touch-down on this node, dispatched by [io.github.sceneview.gesture.GestureDetector] ahead of
 * any gesture recognition — see [NodeEditingListener.onEditingPressed].
 */
internal fun NodeGestureDelegate.notifyEditingPressed() =
    notifyEditingListeners { onEditingPressed(node) }

/** Touch-up on this node — see [NodeEditingListener.onEditingReleased]. */
internal fun NodeGestureDelegate.notifyEditingReleased() =
    notifyEditingListeners { onEditingReleased(node) }
