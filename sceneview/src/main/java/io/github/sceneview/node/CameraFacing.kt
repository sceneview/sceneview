package io.github.sceneview.node

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.lookTowards
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.toQuaternion
import kotlin.math.sqrt

/**
 * The one implementation of "turn this quad to face the camera", shared by [BillboardNode] (and so
 * [TextNode]) and [ViewNode] so the three cannot drift apart (#4387).
 *
 * ## What "facing" means
 *
 * A **full look-at**: the quad's front face (local `+Z`, the side that shows the picture with
 * upright, un-mirrored UVs) points at the camera *position*, and its top edge stays toward world
 * `+Y`. That is yaw **and** pitch, never roll — a label above the viewer tilts down toward them, and
 * its text stays level with the horizon. See [cameraFacingQuaternion].
 *
 * It is the position that is faced, not the camera's view direction: every quad in the scene turns
 * toward the same eye point instead of sitting parallel to the screen, which is what keeps a wide
 * row of labels readable at the edges of a perspective view.
 *
 * ## Render-on-demand
 *
 * Under [io.github.sceneview.FrameRatePolicy.OnDemand] a billboard has to get two things right at
 * once, and the naive version gets both wrong in opposite directions (#3718).
 *
 * *It must not pin the loop.* Re-orienting from the public [Node.onFrame] slot reads as a standing
 * request for frames, so any scene holding a billboard ran at full cadence forever. The owner calls
 * [onFrame] from [Node.internalOnFrame] instead, which carries no such meaning, and ORs [isPending]
 * into its own `isFrameActive`.
 *
 * *It must not lag a frame either.* The camera position usually comes from
 * `SceneView(onFrame = …)`, which fires *after* its frame was presented — on the frame a camera move
 * causes, the provider still reports the previous position. Writing the transform unconditionally
 * papered over that by re-pushing a frame every tick. Instead the write is skipped while nothing it
 * depends on moved ([cameraFacingIsStale]), and [isPending] keeps the loop running for as long as
 * the applied inputs and the current ones disagree. It converges by construction: applying sets one
 * to the other.
 *
 * The write lands before the picture it belongs to: `Node.onFrame(frameTimeNanos)` runs in
 * `SceneView`'s update block, ahead of the GPU submit, so the frame that carries a camera move also
 * carries the orientation built for it.
 */
internal class CameraFacing(
    private val node: Target,
    cameraPositionProvider: (() -> Position)? = null
) {

    /**
     * The four things this needs from the node it turns. An interface rather than a [Node] so the
     * whole behaviour — when it writes, when it asks for a frame, that it converges — runs in a
     * plain JVM test, where a [Node] cannot exist (it creates Filament entities).
     */
    interface Target {
        val worldPosition: Position

        /** `null` for a node without a parent. */
        val parentWorldQuaternion: Quaternion?

        fun faceWith(worldQuaternion: Quaternion)

        fun requestRender()
    }

    /**
     * Where the camera is, in world space — or `null` to leave the node's orientation alone.
     *
     * Read every frame, so swapping it takes effect on the next one. Clearing it stops the
     * re-orientation and leaves the node as it was last turned; setting it again re-orients even if
     * the camera has not moved in between.
     */
    var cameraPositionProvider: (() -> Position)? = cameraPositionProvider
        set(value) {
            val wasFacing = field != null
            field = value
            // Forget what was applied: whoever cleared the provider is free to write the
            // orientation by hand, and a later provider must turn the node back whatever it finds.
            if (value == null) applied = null
            // A parked loop polls nothing. Turning the behaviour on (or off) is a change it cannot
            // observe, so ask for the frame that will run `onFrame` below.
            if (wasFacing != (value != null)) node.requestRender()
        }

    /** The inputs the node's orientation was last built from, or `null` if never oriented. */
    private var applied: AppliedCameraFacing? = null

    /** True while the node has not yet turned to face where the camera now is. */
    val isPending: Boolean
        get() {
            val camera = cameraPositionProvider?.invoke() ?: return false
            return cameraFacingIsStale(camera, node.worldPosition, node.parentWorldQuaternion, applied)
        }

    /** Re-orients the node if — and only if — something its orientation depends on has moved. */
    fun onFrame() {
        val camera = cameraPositionProvider?.invoke() ?: return
        val nodePosition = node.worldPosition
        val parentQuaternion = node.parentWorldQuaternion
        // Only write when an input actually moved. The world-transform setter is a push source:
        // an unconditional write would request a frame every tick, forever.
        if (!cameraFacingIsStale(camera, nodePosition, parentQuaternion, applied)) return
        cameraFacingQuaternion(nodePosition, camera)?.let { node.faceWith(it) }
        // Recorded even when the direction was degenerate, so a camera sitting exactly on the node
        // does not spin the loop trying to face itself. Copies, because both types are mutable and
        // a provider is free to hand back the same instance every frame.
        applied = AppliedCameraFacing(
            camera = camera.copy(),
            node = nodePosition.copy(),
            parentQuaternion = parentQuaternion?.copy()
        )
    }
}

/** A real [Node] as a [CameraFacing.Target]. */
internal class NodeCameraFacingTarget(private val node: Node) : CameraFacing.Target {
    override val worldPosition: Position get() = node.worldPosition
    override val parentWorldQuaternion: Quaternion? get() = node.parent?.worldQuaternion

    // Through `worldTransform(…)` rather than the `worldQuaternion` setter so a node with smooth
    // transforms enabled eases round to the camera, as it does for every other transform change.
    override fun faceWith(worldQuaternion: Quaternion) {
        node.worldTransform(quaternion = worldQuaternion)
    }

    override fun requestRender() = node.requestRender()
}

/** What a camera-facing orientation was built from. See [cameraFacingIsStale]. */
internal class AppliedCameraFacing(
    val camera: Position,
    val node: Position,
    val parentQuaternion: Quaternion?
)

/**
 * The world-space orientation that turns a quad at [nodePosition] to face [cameraPosition]: local
 * `+Z` toward the camera, local `+Y` as close to world `+Y` as that allows (yaw and pitch, no roll).
 *
 * Returns `null` when there is no direction to face — the camera sits on the node, or a coordinate
 * is `NaN` — so the caller keeps the orientation it has instead of writing a `NaN` transform.
 *
 * kotlin-math's `lookTowards(eye, forward, up)` builds `Mat4(right, up, -forward, eye)`, so local
 * `+Z` lands on `-forward`. Passing `nodePosition - cameraPosition` as `forward` therefore points
 * `+Z` at the camera; `lookAt(cameraPosition)` would do the opposite and show the picture mirrored.
 *
 * **Straight above or below.** With the camera on the node's vertical the look direction is
 * parallel to world `+Y`, the `right` vector is a cross product of two parallel vectors, and the
 * whole basis is `NaN` — the node vanishes. Inside [VERTICAL_LIMIT] of the pole the top edge is
 * aimed along world `Z` instead, on the side that continues the orientation of a camera that came
 * over the top from the default `+Z` viewing side.
 */
internal fun cameraFacingQuaternion(nodePosition: Position, cameraPosition: Position): Quaternion? {
    val toNode = nodePosition - cameraPosition
    val lengthSq = toNode.x * toNode.x + toNode.y * toNode.y + toNode.z * toNode.z
    // `lengthSq > epsilon` rejects the zero vector AND any NaN component (a comparison with NaN
    // is always false), which is what would otherwise reach `normalize(0, 0, 0)`.
    if (!(lengthSq > MIN_FACING_DISTANCE_SQ)) return null
    val vertical = toNode.y / sqrt(lengthSq)
    val up = when {
        // Camera below, looking up: the top of the quad goes toward +Z.
        vertical > VERTICAL_LIMIT -> Direction(z = 1.0f)
        // Camera above, looking down: toward -Z.
        vertical < -VERTICAL_LIMIT -> Direction(z = -1.0f)
        else -> Direction(y = 1.0f)
    }
    return lookTowards(eye = nodePosition, forward = toNode, up = up).toQuaternion()
}

/**
 * Whether a quad oriented for [applied] is no longer facing a camera at [cameraPosition].
 *
 * The orientation is a function of three things, and each of them moves on its own:
 * - the **camera** position — the user orbits;
 * - the node's own **world position** — the node, or anything above it, is translated;
 * - the **parent's world orientation** — the orientation is stored relative to the parent, so a
 *   turntable spinning underneath carries the quad round with it even though neither the camera
 *   nor the quad's position changed. Before #4387 only the camera was watched, and a label under a
 *   rotating or moving parent kept the angle it had the last time the camera moved.
 *
 * The whole render-on-demand behaviour turns on this predicate, in both directions:
 * - `false` skips the transform write, and the transform write is a *push* source — writing an
 *   identical quaternion every tick is what used to keep every scene with a billboard in it awake;
 * - `false` also lets `isFrameActive` report idle, and `true` keeps the loop running until the
 *   orientation has caught up with a camera position the caller can only publish one frame late.
 *
 * A `null` [cameraPosition] means no provider was given: the node never re-orients, so it never
 * needs a frame. A `null` [applied] means it has never been oriented, which always needs one.
 */
internal fun cameraFacingIsStale(
    cameraPosition: Position?,
    nodePosition: Position,
    parentQuaternion: Quaternion?,
    applied: AppliedCameraFacing?
): Boolean {
    if (cameraPosition == null) return false
    if (applied == null) return true
    return cameraMovedSinceApplied(cameraPosition, applied.camera) ||
        cameraMovedSinceApplied(nodePosition, applied.node) ||
        orientationChanged(parentQuaternion, applied.parentQuaternion)
}

/**
 * Whether [camPos] is somewhere a billboard oriented for [applied] is not yet facing: further than
 * 1 mm from it. The position half of [cameraFacingIsStale], applied to the camera and to the node.
 *
 * A `null` camera means no provider was given: the node never re-orients, so it never needs a frame.
 * A `null` [applied] means it has never been oriented, which always needs one.
 */
internal fun cameraMovedSinceApplied(camPos: Position?, applied: Position?): Boolean {
    if (camPos == null) return false
    if (applied == null) return true
    val dx = camPos.x - applied.x
    val dy = camPos.y - applied.y
    val dz = camPos.z - applied.z
    return dx * dx + dy * dy + dz * dz > CAMERA_EPSILON_SQ
}

/**
 * Whether two orientations differ by more than [ORIENTATION_EPSILON_SQ]. `q` and `-q` are the same
 * rotation, so the nearer of the two is what counts; gaining or losing a parent always counts.
 */
private fun orientationChanged(current: Quaternion?, applied: Quaternion?): Boolean {
    if (current == null || applied == null) return current != applied
    var same = 0.0f
    var opposite = 0.0f
    for (i in 0..3) {
        val difference = current[i] - applied[i]
        val sum = current[i] + applied[i]
        same += difference * difference
        opposite += sum * sum
    }
    return minOf(same, opposite) > ORIENTATION_EPSILON_SQ
}

/**
 * Squared world-space distance below which a position counts as not having moved: 1 mm, two orders
 * of magnitude under what re-orienting a quad can show.
 */
private const val CAMERA_EPSILON_SQ = 1e-6f

/**
 * Squared quaternion distance below which a parent counts as not having turned: about 0.01°, far
 * above the float noise of re-deriving an unchanged orientation and far below anything visible.
 */
private const val ORIENTATION_EPSILON_SQ = 1e-8f

/** Squared camera-to-node distance under which there is no direction to face (1 µm). */
private const val MIN_FACING_DISTANCE_SQ = 1e-12f

/**
 * How close to straight up or down (as the `y` of the unit look direction) the camera may get
 * before world `+Y` stops being usable as the up vector — about 0.08° from the pole.
 */
private const val VERTICAL_LIMIT = 1.0f - 1e-6f
