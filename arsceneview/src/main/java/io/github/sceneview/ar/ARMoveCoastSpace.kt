package io.github.sceneview.ar

import com.google.android.filament.View
import com.google.ar.core.Point
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.length
import io.github.sceneview.ar.node.PoseNode
import io.github.sceneview.gesture.MoveCoastSpace
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.utils.worldToScreen
import kotlin.math.abs

/**
 * An AR drag coasts along the surface it was dragged on, in world space: the phone moving
 * mid-coast no longer drags the object with the camera.
 *
 * The surface is the dragged [PoseNode]'s own: its [PoseNode.pose] is the last accepted hit, whose
 * Y axis is the surface normal (planes, walls and the auto-placement surfaces alike). Any other
 * owner keeps the screen-space coast, and so does a drag that last landed on a feature point with
 * no estimated normal. A position-editable child of an [io.github.sceneview.ar.node.AnchorNode] is
 * not a [PoseNode] either: it keeps the screen-space coast.
 */
internal class ARMoveCoastSpace(private val view: View) : MoveCoastSpace {

    override fun release(owner: Node, x: Float, y: Float): MoveCoastSpace.Surface? {
        val node = owner as? PoseNode ?: return null
        // A feature point with no estimated normal has a Y axis that only points up the world,
        // not out of the surface: there is no plane to slide along.
        val point = node.lastMoveTrackable as? Point
        if (point?.orientationMode == Point.OrientationMode.INITIALIZED_TO_IDENTITY) return null
        val pose = node.pose
        val onPlane = Position(pose.tx(), pose.ty(), pose.tz())
        val normal = pose.getTransformedAxis(1, 1f).let { Position(it[0], it[1], it[2]) }
        fun onSurface(sx: Float, sy: Float): Position? {
            val ray = view.screenToRay(sx, sy) ?: return null
            return rayPlane(ray.origin, ray.direction, onPlane, normal)
        }
        val origin = onSurface(x, y) ?: return null
        val alongX = onSurface(x + PROBE_PX, y) ?: return null
        val alongY = onSurface(x, y + PROBE_PX) ?: return null
        return surfaceOf(origin, alongX, alongY)
    }

    override fun toScreen(position: Position): Float2? {
        val screen = view.worldToScreen(position) ?: return null
        val viewport = view.viewport
        return screen.takeIf {
            it.x in 0f..viewport.width.toFloat() && it.y in 0f..viewport.height.toFloat()
        }
    }

    companion object {
        /** The screen offset the release maps to the surface, along x and along y. */
        const val PROBE_PX = 8f

        /**
         * Past this, one pixel is too much floor — a surface seen almost edge-on: the coast would
         * shoot to the horizon, so it stays in screen space.
         */
        const val MAX_METERS_PER_PX = 0.05f

        /**
         * Where the ray from [origin] along [direction] meets the plane through [point] with
         * [normal], or `null` when it runs parallel to it or meets it behind the camera.
         */
        fun rayPlane(origin: Position, direction: Position, point: Position, normal: Position): Position? {
            val facing = direction.x * normal.x + direction.y * normal.y + direction.z * normal.z
            if (abs(facing) < 1e-6f) return null
            val toPlane = (point.x - origin.x) * normal.x +
                (point.y - origin.y) * normal.y +
                (point.z - origin.z) * normal.z
            val t = toPlane / facing
            if (t <= 0f || !t.isFinite()) return null
            return origin + direction * t
        }

        /** The release surface from the release point and its two [PROBE_PX] neighbours. */
        fun surfaceOf(origin: Position, alongX: Position, alongY: Position): MoveCoastSpace.Surface? {
            val perPixelX = (alongX - origin) / PROBE_PX
            val perPixelY = (alongY - origin) / PROBE_PX
            if (length(perPixelX) > MAX_METERS_PER_PX || length(perPixelY) > MAX_METERS_PER_PX) {
                return null
            }
            return MoveCoastSpace.Surface(origin, perPixelX, perPixelY)
        }
    }
}
