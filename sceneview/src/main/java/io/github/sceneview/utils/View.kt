package io.github.sceneview.utils

import android.os.Looper
import android.view.MotionEvent
import com.google.android.filament.Renderer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Ray
import io.github.sceneview.Entity
import io.github.sceneview.math.Position
import io.github.sceneview.math.toFloat3
import io.github.sceneview.node.Node

/**
 * Get a world space position from a screen space position
 *
 * Screen space is in Android device screen coordinates
 *
 * @param xPx Horizontal screen coordinate in pixels where you want the world position.
 * (0 = left, View Width = right)
 * The x value is negative when the point is left of the [View.getViewport], between 0 and the width
 * of the [View.getViewport] width when the point is within the viewport, and greater than the width
 * when the point is to the right of the viewport.
 * @param yPx Vertical screen coordinate in pixels where you want the world position.
 * (0 = top, View Height = bottom)
 * The y value is negative when the point is above the [View.getViewport], between 0 and the height
 * of the [View.getViewport] height when the point is within the viewport, and greater than the
 * height when the point is below the viewport.
 * @param z Z is used for the depth between 1 and 0
 *  (1 = near, 0 = infinity).
 *
 * @return The world position of the point
 */
fun View.screenToWorld(xPx: Float, yPx: Float, z: Float = 1.0f): Position? =
    camera?.viewToWorld(
        viewPosition = Float2(
            x = xPx / viewport.width,
            // Invert Y because screen Y points down and ViewPort Y points up.
            y = 1.0f - (yPx / viewport.height)
        ),
        z = z
    )

/**
 * Get a world space position from a screen space position
 *
 * Screen space is in Android device screen coordinates
 *
 * @param motionEvent The motion event where you want the world position.
 *
 * @return The world position of the point
 */
fun View.motionEventToWorld(motionEvent: MotionEvent): Position? = screenToWorld(
    xPx = motionEvent.getX(motionEvent.actionIndex),
    yPx = motionEvent.getY(motionEvent.actionIndex)
)

/**
 * Get a screen space position from a world position.
 *
 * The device coordinate space is unaffected by the orientation of the device.
 *
 * Returns `null` when the point cannot be placed on screen: when this view has no camera, or when
 * [worldPosition] is **behind the camera** (at or behind the camera's eye plane). A behind-camera point has no
 * screen location — historically it was projected to a finite, *mirrored* pixel on the wrong side
 * of the view, which made overlays that project e.g. the 8 corners of a bounding box flicker as a
 * corner crossed the camera's eye plane. Always handle the `null` case: skip the point rather than draw at
 * a bogus pixel.
 *
 * @param worldPosition The world position to convert.
 *
 * @return Screen coordinate in pixels where the world position is, or `null` if there is no camera
 * or [worldPosition] is at or behind the camera's eye plane.
 * (0 = left, View Width = right)
 * (0 = top, View Height = bottom)
 * Screen space is in Android device screen coordinates
 */
fun View.worldToScreen(worldPosition: Position): Float2? =
    camera?.worldToView(worldPosition)?.apply {
        x *= viewport.width
        // Invert Y because screen Y points down and ViewPort Y points up.
        y = (1.0f - y) * viewport.height
    }

/**
 * Calculates a ray in world space going from the near-plane of the camera and through a point in
 * view space.
 *
 * Screen space is in Android device screen coordinates: TopLeft = (0, 0),  BottomRight =
 * (Screen Width, Screen Height).
 * The device coordinate space is unaffected by the orientation of the device.
 *
 * @param xPx Horizontal screen coordinate in pixels where you want the world position.
 * (0 = left, View Width = right)
 * The x value is negative when the point is left of the [View.getViewport], between 0 and the width
 * of the [View.getViewport] width when the point is within the viewport, and greater than the width
 * when the point is to the right of the viewport.
 * @param yPx Vertical screen coordinate in pixels where you want the world position.
 * (0 = top, View Height = bottom)
 * The y value is negative when the point is above the [View.getViewport], between 0 and the height
 * of the [View.getViewport] height when the point is within the viewport, and greater than the
 * height when the point is below the viewport.
 *
 * @return A Ray from the camera near to far / infinity
 */
fun View.screenToRay(xPx: Float, yPx: Float): Ray? = camera?.viewToRay(
    Float2(
        x = xPx / viewport.width,
        // Invert Y because screen Y points down and ViewPort Y points up.
        y = 1.0f - (yPx / viewport.height)
    )
)

/**
 * Calculates a ray in world space going from the near-plane of the camera and going through a point
 * in screen space.
 *
 * Screen space is in Android device screen coordinates:
 * TopLeft = (0, 0),  BottomRight = (Screen Width, Screen Height).
 * The device coordinate space is unaffected by the orientation of the device.
 *
 * @param motionEvent The motion event where you want the world position.
 *
 * @return A Ray from the camera to far / infinity
 */
fun View.motionEventToRay(motionEvent: MotionEvent): Ray? =
    screenToRay(xPx = motionEvent.x, yPx = motionEvent.y)

/**
 * Creates a picking query. Multiple queries can be created (e.g.: multi-touch).
 *
 * Picking queries are all executed when [Renderer.render] is called on this View.
 * The provided callback is guaranteed to be called at some point in the future.
 *
 * Typically it takes a couple frames to receive the result of a picking query.
 *
 * @param xPx Horizontal screen coordinate in pixels where you want to pick.
 * (0 = left, View Width = right)
 * The x value is negative when the point is left of the [View.getViewport], between 0 and the width
 * of the [View.getViewport] width when the point is within the viewport, and greater than the width
 * when the point is to the right of the viewport.
 * @param yPx Vertical screen coordinate in pixels where you want to pick.
 * (0 = top, View Height = bottom)
 * The y value is negative when the point is above the [View.getViewport], between 0 and the height
 * of the [View.getViewport] height when the point is within the viewport, and greater than the
 * height when the point is below the viewport.
 * @param handler An [java.util.concurrent.Executor].
 * On Android this can also be a [android.os.Handler].
 * @param onCompleted User callback executed by `handler` when the picking query result is
 * available.
 */
fun View.pick(
    xPx: Float,
    yPx: Float,
    handler: Any = Looper.getMainLooper(),
    onCompleted: (
        /** The Renderable Entity at the picking query location  */
        renderable: Entity,
        /** The value of the depth buffer at the picking query location  */
        depth: Float,
        /** The fragment coordinate in GL convention at the picking query location  */
        fragCoords: Float3
    ) -> Unit
) =
// Wrap in a try/catch to handle the case where we receive a pick event as the screen is
// being swiped to emulate a back button press. In that case, the view will be destroyed but
// there is still a possibility of this method being called after the fact but before the
// sceneview is fully destroyed which would trigger an IllegalStateException and crash the app
    runCatching {
    pick(
        xPx.toInt(),
        // Invert the y coordinate since its origin must be at the bottom
        (viewport.height - yPx).toInt(),
        handler
    ) { result ->
        onCompleted(
            result.renderable,
            result.depth,
            result.fragCoords.toFloat3()
        )
    }
}

/**
 * Creates a picking query. Multiple queries can be created (e.g.: multi-touch).
 *
 * Picking queries are all executed when [Renderer.render] is called on this View.
 * The provided callback is guaranteed to be called at some point in the future.
 *
 * Typically it takes a couple frames to receive the result of a picking query.
 *
 * @param xPx Horizontal screen coordinate in pixels where you want to pick.
 * (0 = left, View Width = right)
 * The x value is negative when the point is left of the [View.getViewport], between 0 and the width
 * of the [View.getViewport] width when the point is within the viewport, and greater than the width
 * when the point is to the right of the viewport.
 * @param yPx Vertical screen coordinate in pixels where you want to pick.
 * (0 = top, View Height = bottom)
 * The y value is negative when the point is above the [View.getViewport], between 0 and the height
 * of the [View.getViewport] height when the point is within the viewport, and greater than the
 * height when the point is below the viewport.
 * @param nodes List of nodes you want the pick to be made on.
 * @param handler An [java.util.concurrent.Executor].
 * On Android this can also be a [android.os.Handler].
 * @param onCompleted User callback executed by `handler` when the picking query result is
 * available.
 */
fun View.pickNode(
    xPx: Float,
    yPx: Float,
    nodes: List<Node>,
    handler: Any = Looper.getMainLooper(),
    onCompleted: (
        /** The Renderable Node at the picking query location  */
        node: Node?,
        /** The value of the depth buffer at the picking query location  */
        depth: Float,
        /** The fragment coordinate in GL convention at the picking query location  */
        fragCoords: Float3
    ) -> Unit
) = pick(xPx = xPx, yPx = yPx, handler = handler) { renderable, depth, fragCoords ->
    onCompleted(nodes.firstOrNull { it.entity == renderable }, depth, fragCoords)
}

/**
 * Creates a picking query. Multiple queries can be created (e.g.: multi-touch).
 *
 * Picking queries are all executed when [Renderer.render] is called on this View.
 * The provided callback is guaranteed to be called at some point in the future.
 *
 * Typically it takes a couple frames to receive the result of a picking query.
 *
 * @param motionEvent The motion event where you want to pick.
 * @param nodes List of nodes you want the pick to be made on.
 * @param handler An [java.util.concurrent.Executor].
 * On Android this can also be a [android.os.Handler].
 * @param onCompleted User callback executed by `handler` when the picking query result is
 * available.
 */
fun View.pickNode(
    motionEvent: MotionEvent,
    nodes: List<Node>,
    handler: Any = Looper.getMainLooper(),
    onCompleted: (
        /** The Renderable Node at the picking query location  */
        node: Node?,
        /** The value of the depth buffer at the picking query location  */
        depth: Float,
        /** The fragment coordinate in GL convention at the picking query location  */
        fragCoords: Float3
    ) -> Unit
) = pickNode(motionEvent.x, motionEvent.y, nodes, handler, onCompleted)

/**
 * Creates a picking query. Multiple queries can be created (e.g.: multi-touch).
 *
 * Picking queries are all executed when [Renderer.render] is called on this View.
 * The provided callback is guaranteed to be called at some point in the future.
 *
 * Typically it takes a couple frames to receive the result of a picking query.
 *
 * @param viewPosition normalized view coordinate
 * x = (0 = left, 0.5 = center, 1 = right)
 * y = (0 = bottom, 0.5 = center, 1 = top)
 * @param nodes List of nodes you want the pick to be made on.
 * @param handler An [java.util.concurrent.Executor].
 * On Android this can also be a [android.os.Handler].
 * @param onCompleted User callback executed by `handler` when the picking query result is
 * available.
 */
fun View.pickNode(
    viewPosition: Float2,
    nodes: List<Node>,
    handler: Any = Looper.getMainLooper(),
    onCompleted: (
        /** The Renderable Node at the picking query location  */
        node: Node?,
        /** The value of the depth buffer at the picking query location  */
        depth: Float,
        /** The fragment coordinate in GL convention at the picking query location  */
        fragCoords: Float3
    ) -> Unit
) = pickNode(
    xPx = viewPosition.x * viewport.width,
    // Invert Y because screen Y points down and Filament Y points up.
    yPx = (1.0f - viewPosition.y) * viewport.height,
    nodes = nodes,
    handler = handler,
    onCompleted = onCompleted
)

val Viewport.size get() = Float2(width.toFloat(), height.toFloat())
