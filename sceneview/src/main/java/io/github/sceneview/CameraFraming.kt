package io.github.sceneview

import com.google.android.filament.Box
import io.github.sceneview.math.Position
import io.github.sceneview.math.toPosition
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.Node
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Library-level **auto-fit camera framing** — given a model / content subtree and the active
 * camera, compute the orbit distance at which the content's bounding sphere exactly fills the
 * viewport (with a configurable padding margin), regardless of the model's intrinsic size
 * ([#1439]).
 *
 * This is the Android counterpart of the iOS demo's height-adaptive framing. Where
 * [SceneAutoCenter] answers *where* content sits (it translates the content centroid onto the
 * orbit pivot), this answers *how far* the camera must be so a 5 cm bee and a 5 m crate both read
 * as "comfortably framed" without per-demo `scaleToUnits` tuning.
 *
 * The geometry is intentionally split from any Filament / Compose plumbing so it can be unit
 * tested in pure JVM — see `CameraFramingTest`. The two entry points are:
 *
 * - [fitDistanceForBounds] — pure trigonometry: bounds + FOV + aspect → distance. Testable.
 * - [CameraNode.frameToContent] / [CameraNode.frameToBounds] — convenience extensions that read
 *   the camera's own projection and apply the result. Must run on the main (render) thread.
 *
 * @see SceneAutoCenter
 * @see io.github.sceneview.SceneView
 */

/**
 * Default fraction of "extra" framing margin around the content. `0.15` means the content's
 * bounding sphere occupies ~87% of the smaller viewport dimension, leaving a comfortable 13%
 * breathing room — matching the iOS demo's default framing tightness.
 */
const val DEFAULT_FRAMING_PADDING: Float = 0.15f

/**
 * The 35 mm-equivalent sensor height (in millimetres) Filament assumes when a camera is
 * configured via [CameraNode.focalLength]. Filament's `setLensProjection` derives the vertical
 * field-of-view from `focalLength` against this full-frame 24 mm sensor height.
 */
private const val FILAMENT_SENSOR_HEIGHT_MM: Double = 24.0

/**
 * Converts a lens [focalLength] (millimetres, the unit [CameraNode.focalLength] uses) into the
 * vertical field-of-view in **degrees**, using Filament's full-frame 24 mm sensor model.
 *
 * `vfov = 2 · atan(sensorHeight / (2 · focalLength))`
 *
 * @param focalLength Lens focal length in millimetres. Must be `> 0`.
 * @return Vertical field-of-view in degrees, in the open interval `(0, 180)`.
 */
fun verticalFovDegreesForFocalLength(focalLength: Double): Double {
    require(focalLength > 0.0) { "focalLength must be > 0, was $focalLength" }
    val vfovRadians = 2.0 * atan(FILAMENT_SENSOR_HEIGHT_MM / (2.0 * focalLength))
    return Math.toDegrees(vfovRadians)
}

/**
 * Converts a Filament [Box] — the type [io.github.sceneview.model.Model.getBoundingBox] and
 * [io.github.sceneview.node.RenderableNode.axisAlignedBoundingBox] return — into the centre /
 * half-extent [Aabb] the framing math operates on.
 *
 * Use this to compute a fit distance directly from a freshly loaded model's intrinsic bounds
 * (`modelInstance.model.boundingBox.toAabb()`) without first attaching it to the scene graph.
 */
fun Box.toAabb(): Aabb = Aabb(
    center = center.toPosition(),
    halfExtent = halfExtent.toPosition()
)

/**
 * Computes the distance from the content centroid at which a camera with the given projection
 * frames [bounds] so it just fits the viewport, with [padding] breathing room on the tighter axis.
 *
 * The content is fitted by its **bounding sphere** (radius = half the AABB's space diagonal), so
 * the framing is invariant of the camera's orbit yaw / pitch — the model never clips when the
 * camera rotates around it. The required distance is taken as the larger of the vertical-fit and
 * horizontal-fit distances so the content fits on **both** axes:
 *
 * - vertical:   `d = r / sin(vfov / 2)`
 * - horizontal: `d = r / sin(hfov / 2)`, where `hfov` is derived from `vfov` and `aspect`.
 *
 * A degenerate / empty [bounds] (not-yet-loaded async model) yields `0` so callers can detect
 * "nothing to frame yet" and defer.
 *
 * @param bounds                Content bounding box, in the space the camera orbits.
 * @param verticalFovDegrees    Camera vertical field-of-view in degrees, `(0, 180)`.
 * @param aspect                Viewport aspect ratio `width / height`. Must be `> 0`.
 * @param padding               Extra framing margin as a fraction of the bounding-sphere radius.
 *                              Default [DEFAULT_FRAMING_PADDING]. Clamped to `>= 0`.
 * @return The orbit distance (world units) from the content centroid, or `0` when [bounds] is
 *         empty / degenerate.
 */
fun fitDistanceForBounds(
    bounds: Aabb,
    verticalFovDegrees: Double,
    aspect: Double,
    padding: Float = DEFAULT_FRAMING_PADDING
): Float {
    if (bounds.isEmpty) return 0f
    val safeAspect = if (aspect.isFinite() && aspect > 0.0) aspect else 1.0
    val extents = bounds.extents
    // Bounding-sphere radius — half the AABB space diagonal. Yaw / pitch-invariant.
    val radius = 0.5f * sqrt(
        extents.x * extents.x + extents.y * extents.y + extents.z * extents.z
    )
    if (!radius.isFinite() || radius <= 0f) return 0f
    val paddedRadius = radius * (1f + max(0f, padding))

    val vfovRad = Math.toRadians(verticalFovDegrees.coerceIn(1.0, 179.0))
    val halfVfov = vfovRad / 2.0
    // Horizontal FOV from the vertical FOV and the viewport aspect.
    val halfHfov = atan(tan(halfVfov) * safeAspect)

    val distanceForVertical = paddedRadius / kotlin.math.sin(halfVfov).toFloat()
    val distanceForHorizontal = paddedRadius / kotlin.math.sin(halfHfov).toFloat()
    return max(distanceForVertical, distanceForHorizontal)
}

/**
 * Computes the auto-fit orbit distance for an arbitrary content subtree against this camera's
 * current projection.
 *
 * Walks [content] with [computeContentBounds] (so it includes the renderable child nodes a
 * `ModelNode` exposes for its meshes), then feeds the union AABB plus this camera's
 * focal-length-derived vertical FOV and viewport aspect into [fitDistanceForBounds].
 *
 * **Threading:** reads Filament `RenderableManager` / `TransformManager` state and the camera's
 * viewport — must run on the main (render) thread. `SceneView`'s frame loop satisfies this.
 *
 * @param content    Subtree whose bounds drive the framing — typically a `ModelNode` or the
 *                   `SceneView` content root.
 * @param relativeTo Node whose local space the bounds are measured in (the orbit space).
 *                   Defaults to [content] itself.
 * @param padding    Extra framing margin — see [fitDistanceForBounds].
 * @return The orbit distance from the content centroid, or `0` when the content has no measurable
 *         bounds yet (async load not finished).
 */
fun CameraNode.fitDistanceForContent(
    content: Node,
    relativeTo: Node = content,
    padding: Float = DEFAULT_FRAMING_PADDING
): Float {
    val bounds = computeContentBounds(content, relativeTo = relativeTo)
    return fitDistanceForBounds(
        bounds = bounds,
        verticalFovDegrees = verticalFovDegreesForFocalLength(focalLength),
        aspect = getViewPortAspect(),
        padding = padding
    )
}

/**
 * Positions this camera so [bounds] fills the viewport, looking at the content from [direction].
 *
 * The camera is moved to `bounds.center - direction · fitDistance` and oriented to look at the
 * content centroid. [direction] is the unit vector **from the camera towards the content**; the
 * default `(0, 0, -1)` is the canonical "look down −Z" front view.
 *
 * No-op when [bounds] is empty / degenerate (returns `false`), so callers can run this every
 * frame until the async model load populates and frame exactly once.
 *
 * **Threading:** writes a Filament transform — must run on the main (render) thread.
 *
 * @param bounds    Content bounding box, in this camera's parent space.
 * @param direction Unit look direction from camera to content. Default front view `(0, 0, -1)`.
 * @param padding   Extra framing margin — see [fitDistanceForBounds].
 * @return `true` if the camera was repositioned, `false` when [bounds] was empty.
 */
fun CameraNode.frameToBounds(
    bounds: Aabb,
    direction: Position = Position(0f, 0f, -1f),
    padding: Float = DEFAULT_FRAMING_PADDING
): Boolean {
    val distance = fitDistanceForBounds(
        bounds = bounds,
        verticalFovDegrees = verticalFovDegreesForFocalLength(focalLength),
        aspect = getViewPortAspect(),
        padding = padding
    )
    if (distance <= 0f) return false
    val normalized = normalizeOrDefault(direction)
    val eye = bounds.center - normalized * distance
    worldPosition = eye
    lookAt(bounds.center)
    return true
}

/**
 * Positions this camera so the content subtree rooted at [content] fills the viewport.
 *
 * Convenience wrapper around [frameToBounds] that first measures [content] with
 * [computeContentBounds]. Use this to one-shot frame a freshly loaded `ModelNode`:
 *
 * ```kotlin
 * SceneView(
 *     cameraNode = cameraNode,
 *     onFrame = {
 *         if (cameraNode.frameToContent(modelNode)) framed = true
 *     }
 * ) {
 *     ModelNode(modelInstance = instance)
 * }
 * ```
 *
 * **Threading:** reads/writes Filament state — must run on the main (render) thread.
 *
 * @param content    Subtree to frame — typically the model's `ModelNode`.
 * @param relativeTo Node whose local space the bounds are measured in. Defaults to [content].
 * @param direction  Unit look direction from camera to content. Default front view `(0, 0, -1)`.
 * @param padding    Extra framing margin — see [fitDistanceForBounds].
 * @return `true` if the camera was repositioned, `false` when [content] had no bounds yet.
 */
fun CameraNode.frameToContent(
    content: Node,
    relativeTo: Node = content,
    direction: Position = Position(0f, 0f, -1f),
    padding: Float = DEFAULT_FRAMING_PADDING
): Boolean = frameToBounds(
    bounds = computeContentBounds(content, relativeTo = relativeTo),
    direction = direction,
    padding = padding
)

/** Returns [v] normalized to unit length, or `(0, 0, -1)` when [v] is zero / non-finite. */
private fun normalizeOrDefault(v: Position): Position {
    val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    return if (length.isFinite() && length > 1e-6f) v * (1f / length) else Position(0f, 0f, -1f)
}

/**
 * Mutable holder for the library-level auto-fit framing pass. Lives in a Compose `remember` so the
 * gate state survives recomposition — the framing analogue of [SceneAutoCenterState].
 *
 * Drives the [io.github.sceneview.SceneView] `autoFitContent` parameter: each frame the content's
 * union bounds materially change the camera is moved so the content fills the viewport, then the
 * pass latches once the union diagonal has settled so the user's subsequent zoom / pan is never
 * fought.
 *
 * Backed by a [FramingGate]: the pass re-runs whenever an async model grows the content's union
 * bounds, so a model that finishes loading after a sibling already framed still triggers a
 * re-frame (#1596 / #1540) — it is not frozen on a stale first-frame latch.
 */
class SceneAutoFitState {
    private val gate = FramingGate()

    /** `true` once the union diagonal has settled and the auto-fit pass has latched. */
    val didFit: Boolean get() = gate.latched

    /**
     * The orbit distance the last [maybeFit] framing computed. `0` until the content has been
     * framed. Camera manipulators can read this to seed their orbit radius.
     */
    var fitDistance: Float = 0f
        private set

    /**
     * Runs the auto-fit pass against [contentRoot] for [cameraNode]. No-op once the gate has
     * latched on a settled union, or while the content bounds are still empty / degenerate (async
     * loads not finished). On a frame where the union diagonal materially changed the camera is
     * repositioned via [CameraNode.frameToBounds] and the computed [fitDistance] is recorded; the
     * gate latches once that diagonal settles across consecutive frames.
     *
     * **Threading:** must run on the main thread — reads and writes Filament state.
     *
     * @param cameraNode   Camera to reposition.
     * @param contentRoot  Subtree whose bounds drive the framing.
     * @param padding      Extra framing margin — see [fitDistanceForBounds].
     * @return `true` if this call performed the framing.
     */
    fun maybeFit(
        cameraNode: CameraNode,
        contentRoot: Node,
        padding: Float = DEFAULT_FRAMING_PADDING
    ): Boolean = maybeFit(cameraNode, listOf(contentRoot), padding)

    /**
     * Runs the auto-fit pass against the union bounds of every node in [contentRoots]. Used when
     * `SceneView`'s content is registered directly with the node manager (no intermediate
     * content-root node, i.e. `autoCenterContent = false`). Each root's bounds are measured in
     * its own local space and unioned in world space via the root's world transform.
     *
     * **Threading:** must run on the main thread — reads and writes Filament state.
     */
    fun maybeFit(
        cameraNode: CameraNode,
        contentRoots: List<Node>,
        padding: Float = DEFAULT_FRAMING_PADDING
    ): Boolean {
        if (contentRoots.isEmpty()) return false
        if (!gate.shouldRun(hasContent = true)) return false
        // Measure each root's subtree against a shared reference: the first root. Single-root is
        // the common case (a SceneView content-root node); multi-root unions correctly because
        // `computeContentBounds` re-expresses each subtree in the reference's local space.
        val reference = contentRoots.first()
        val bounds = contentRoots
            .map { computeContentBounds(it, relativeTo = reference) }
            .union()
        if (bounds.isEmpty) return false
        val distance = fitDistanceForBounds(
            bounds = bounds,
            verticalFovDegrees = verticalFovDegreesForFocalLength(cameraNode.focalLength),
            aspect = cameraNode.getViewPortAspect(),
            padding = padding
        )
        if (distance <= 0f) return false
        val diagonal = bounds.diagonal
        val framed = gate.shouldFrame(diagonal)
        if (framed) {
            if (!cameraNode.frameToBounds(bounds, padding = padding)) return false
            fitDistance = distance
        }
        gate.recordFraming(diagonal)
        return framed
    }

    /**
     * Resets the gate so the next frame re-runs the auto-fit pass from scratch. Call this when the
     * scene content is *replaced* so a shrinking union still re-frames — a growing union already
     * re-frames automatically via the diagonal-stability gate.
     */
    fun reset() {
        gate.reset()
        fitDistance = 0f
    }
}
