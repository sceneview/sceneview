package io.github.sceneview.gesture

import androidx.annotation.RestrictTo
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node

/**
 * Where a coasting drag travels — library plumbing an AR scene sets on its gesture detector
 * ([GestureDetector.moveCoastSpace]).
 *
 * Without one, a drag coasts a pointer across the screen and the node follows whatever lies under
 * it. That holds while the camera stands still, and fails in AR, where the phone keeps moving:
 * turned mid-coast, the same pixel hit-tests another spot of the floor and the object follows the
 * camera. With one, the coast slides a point along the surface the node was dragged on, in world
 * space, and the pointer handed to the node is that point as the camera sees it now — the node's
 * own hit test still runs, so it still stops where the surface ends.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
interface MoveCoastSpace {

    /**
     * The finger lifted a drag of [owner] at ([x], [y]) px. Returns the surface the coast slides
     * on, or `null` to coast in screen space.
     */
    fun release(owner: Node, x: Float, y: Float): Surface?

    /** Where [position] is on screen now, in px, or `null` once it is out of view. */
    fun toScreen(position: Position): Float2?

    /**
     * The point under the finger at release, on the dragged-on surface, and how far one pixel of
     * screen travel along x / y moves it along that surface, as seen at release.
     */
    class Surface(val origin: Position, val perPixelX: Float3, val perPixelY: Float3)
}
