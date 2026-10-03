package io.github.sceneview

import com.google.android.filament.Box
import io.github.sceneview.math.Position
import io.github.sceneview.math.toPosition
import io.github.sceneview.node.CameraNode
import io.github.sceneview.node.Node
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
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
 * Default camera elevation (degrees above the content centroid) the framing math assumes when a
 * caller does not derive one from a look direction. `0` means the camera sits level with the
 * centroid — the pose [frameToBounds]'s default `(0, 0, -1)` direction produces.
 */
const val DEFAULT_FRAMING_ELEVATION_DEGREES: Double = 0.0

/**
 * Computes the distance from the content centroid at which a camera with the given projection
 * frames [bounds] so it just fits the viewport, with [padding] breathing room.
 *
 * ### What is fitted (#3426 — the Android half of the iOS #3383 / PR #3395 fix)
 *
 * This used to fit the content's **bounding sphere** — half the AABB's *space diagonal* — against
 * each FOV axis and take the larger distance. That charged every subject twice over:
 *
 * 1. The subject was billed for a diagonal it does not occupy. A 1 m cube got a radius of 0.866 m
 *    for 0.5 m of half-height.
 * 2. Each axis was billed the *other* axis's distance. On a portrait viewport the horizontal FOV
 *    is the narrow one, so a subject bound purely by its **height** was pushed back by a **width**
 *    constraint it never hits — a portrait viewport could never be filled. That is the "subject
 *    starts far too small" every non-AR sample showed (#3426).
 *
 * The fit is now **per FOV axis**, in closed form. A point `v` relative to the target is inside
 * the frustum at distance `d` iff `d >= v·back + |v·axis| / tanAxis` on *both* the horizontal and
 * the vertical axis. Maximising the right-hand side over the subject is a support-function
 * evaluation at `w = back ± axis / tanAxis`, since `|x| = max(x, −x)`.
 *
 * ### Azimuth invariance is kept, deliberately
 *
 * An auto-rotating model must not clip when it turns broadside, so the fit does **not** specialise
 * on the current yaw. Instead of the box it frames the box's **sweep about world Y** — a cylinder
 * of radius `hypot(halfX, halfZ)` and half-height `halfY`, whose support is
 * `halfY·|w.y| + R·hypot(w.x, w.z)`. That sweep is fitted *exactly*, not bounded, so this is the
 * tightest azimuth-independent distance that exists. The result is never larger than the old
 * bounding-sphere distance, so nothing is framed further away than before.
 *
 * Unlike the sphere fit, the result depends on [elevationDegrees]: a camera looking down at a
 * subject sees a shorter projected height than one level with it.
 *
 * A degenerate / empty [bounds] (not-yet-loaded async model) yields `0` so callers can detect
 * "nothing to frame yet" and defer.
 *
 * @param bounds                Content bounding box, in the space the camera orbits.
 * @param verticalFovDegrees    Camera vertical field-of-view in degrees, `(0, 180)`.
 * @param aspect                Viewport aspect ratio `width / height`. Must be `> 0`.
 * @param padding               Extra framing margin as a *fraction* of the fitted distance.
 *                              Default [DEFAULT_FRAMING_PADDING]. Clamped to `>= 0`.
 * @param elevationDegrees      Camera elevation above the content centroid, in degrees. `0` is
 *                              level with the centroid, `90` straight down. [frameToBounds]
 *                              derives it from its look direction.
 * @param azimuthInvariant      `true` (default) frames the Y-sweep, so the subject never clips at
 *                              any orbit yaw — required for auto-rotating or user-orbited scenes.
 *                              `false` frames the raw box at azimuth 0: strictly tighter, and
 *                              correct for a scene the camera views head-on and does not orbit.
 * @return The orbit distance (world units) from the content centroid, or `0` when [bounds] is
 *         empty / degenerate.
 */
fun fitDistanceForBounds(
    bounds: Aabb,
    verticalFovDegrees: Double,
    aspect: Double,
    padding: Float = DEFAULT_FRAMING_PADDING,
    elevationDegrees: Double = DEFAULT_FRAMING_ELEVATION_DEGREES,
    azimuthInvariant: Boolean = true
): Float {
    if (bounds.isEmpty) return 0f
    val safeAspect = if (aspect.isFinite() && aspect > 0.0) aspect else 1.0
    val half = bounds.halfExtent
    val halfX = abs(half.x)
    val halfY = abs(half.y)
    val halfZ = abs(half.z)
    if (!halfX.isFinite() || !halfY.isFinite() || !halfZ.isFinite()) return 0f
    if (halfX <= 0f && halfY <= 0f && halfZ <= 0f) return 0f

    // Radius of the box's sweep about world Y — a cylinder. Sweeping is what makes the fit
    // azimuth-invariant, so an auto-rotating model never clips as it turns broadside.
    val sweptRadius = sqrt(halfX * halfX + halfZ * halfZ)

    val vfovRad = Math.toRadians(verticalFovDegrees.coerceIn(1.0, 179.0))
    val tanY = tan(vfovRad / 2.0)
    val tanX = tanY * safeAspect
    if (tanY <= 0.0 || tanX <= 0.0) return 0f

    // Camera basis at azimuth 0 — for the swept fit azimuth is irrelevant, so this loses no
    // generality, and it avoids the `cross(forward, worldUp)` that degenerates at elevation ±90°:
    //   back = (0, s, c)    right = (1, 0, 0)    up = (0, c, -s)
    val elevationRad = Math.toRadians(
        if (elevationDegrees.isFinite()) elevationDegrees else DEFAULT_FRAMING_ELEVATION_DEGREES
    )
    val s = sin(elevationRad).toFloat()
    val c = cos(elevationRad).toFloat()

    // Support of the subject along `w`, in the horizontal plane. The swept cylinder answers
    // `R·hypot(w.x, w.z)`; the raw box — correct when the camera stays at azimuth 0 — answers
    // `halfX·|w.x| + halfZ·|w.z|`, which is strictly tighter and is what a static, non-orbiting
    // scene should pay.
    fun planarSupport(wx: Float, wz: Float): Float = if (azimuthInvariant) {
        sweptRadius * sqrt(wx * wx + wz * wz)
    } else {
        halfX * abs(wx) + halfZ * abs(wz)
    }

    // Horizontal: w = (±1/tanX, s, c) — both signs give the same support.
    val inverseTanX = (1.0 / tanX).toFloat()
    val horizontal = halfY * abs(s) + planarSupport(inverseTanX, c)
    // Vertical: w = (0, s ± c/tanY, c ∓ s/tanY) — the two signs differ.
    val inverseTanY = (1.0 / tanY).toFloat()
    val verticalUpper = halfY * abs(s + c * inverseTanY) + planarSupport(0f, c - s * inverseTanY)
    val verticalLower = halfY * abs(s - c * inverseTanY) + planarSupport(0f, c + s * inverseTanY)

    val fitted = max(horizontal, max(verticalUpper, verticalLower)) * (1f + max(0f, padding))
    return if (fitted.isFinite() && fitted > 0f) fitted else 0f
}

/**
 * Camera elevation, in degrees above the content centroid, implied by a look [direction] pointing
 * **from the camera towards the content**. A camera above the subject looks down, so its direction
 * has a negative `y` and the elevation is positive.
 *
 * Returns [DEFAULT_FRAMING_ELEVATION_DEGREES] for a zero / non-finite direction.
 */
internal fun elevationDegreesForDirection(direction: Position): Double {
    val length =
        sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
    if (!length.isFinite() || length <= 1e-6f) return DEFAULT_FRAMING_ELEVATION_DEGREES
    return Math.toDegrees(asin((-direction.y / length).toDouble().coerceIn(-1.0, 1.0)))
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
    val normalized = normalizeOrDefault(direction)
    val distance = fitDistanceForBounds(
        bounds = bounds,
        verticalFovDegrees = verticalFovDegreesForFocalLength(focalLength),
        aspect = getViewPortAspect(),
        padding = padding,
        // The projected height of a subject shrinks as the camera rises, so the fit has to know
        // the pose it is fitting for (#3426). Derived from the caller's look direction.
        elevationDegrees = elevationDegreesForDirection(normalized)
    )
    if (distance <= 0f) return false
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

/**
 * Default share of the visible area a subject framed by [fitCameraToBounds] occupies on the axis
 * that binds: `0.8` leaves a tenth of the area free on each side.
 */
const val DEFAULT_FRAMING_FILL: Float = 0.8f

/**
 * A camera pose that frames a bounding box — the result of [fitCameraToBounds].
 *
 * @property eye    Where the camera stands.
 * @property target The point it looks at: on the optical axis, at the depth of the box's centre.
 *                  It is what an orbit should turn around — pass it as `targetPosition`, and
 *                  [eye] as `orbitHomePosition`, to `rememberCameraManipulator`.
 */
data class CameraFit(val eye: Position, val target: Position) {
    /** Distance from [eye] to [target]: the orbit radius. */
    val distance: Float
        get() {
            val d = eye - target
            return sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
        }
}

/**
 * Frames an arbitrary box **under a given angle**: where a camera looking along [direction] has to
 * stand for [bounds] to fill the view, centred.
 *
 * Where [fitDistanceForBounds] answers "how far" for a subject that may turn — it frames the box's
 * sweep about Y, on purpose — this fits the box as it is seen from [direction], corner by corner,
 * and also answers "aimed where". After a perspective projection the centre of a box is not the
 * centre of its picture, so the fit moves the eye sideways and vertically as well as back:
 *
 * - on the axis that binds, the projected box spans exactly `±widthFill` (or `±heightFill`) of the
 *   half-view — both edges touch, the picture is centred by construction;
 * - on the other axis it is centred too, and spans less.
 *
 * The box needs no model behind it. A scene made of many nodes — a board, a room, a solar
 * system — declares the volume worth framing as an [Aabb] and gets the same treatment as a single
 * mesh measured by [computeContentBounds].
 *
 * To frame inside the part of a view a panel leaves free, pass the **visible area's** aspect
 * ratio — which is what [CameraNode.fitToBounds] does once a `contentPadding` is set. The padding
 * centres the projection on that area; this fills it.
 *
 * The camera is assumed upright (no roll, world `+Y` up), as every orbit manipulator and
 * `lookAt` produce. Looking straight down or straight up, "right" is world `+X`.
 *
 * @param bounds             The box to frame, in world space (or in the space the camera lives in).
 * @param direction          Look direction, **from the camera towards the subject**. Need not be
 *                           normalised. Default: the front view, `(0, 0, -1)`.
 * @param verticalFovDegrees Vertical field of view, `(0, 180)`. See
 *                           [verticalFovDegreesForFocalLength].
 * @param aspect             Width / height of the area to frame in.
 * @param widthFill          Share of the half-width the box may span, `(0, 1]`.
 * @param heightFill         Share of the half-height the box may span, `(0, 1]`.
 * @return The pose, or `null` when [bounds] is empty / non-finite — nothing to frame yet.
 */
fun fitCameraToBounds(
    bounds: Aabb,
    direction: Position = Position(0f, 0f, -1f),
    verticalFovDegrees: Double,
    aspect: Double,
    widthFill: Float = DEFAULT_FRAMING_FILL,
    heightFill: Float = DEFAULT_FRAMING_FILL
): CameraFit? {
    if (bounds.isEmpty) return null
    val safeAspect = if (aspect.isFinite() && aspect > 0.0) aspect else 1.0
    val tanY = tan(Math.toRadians(verticalFovDegrees.coerceIn(1.0, 179.0)) / 2.0)
    val slopeX = tanY * safeAspect * widthFill.toDouble().coerceIn(MIN_FRAMING_FILL, 1.0)
    val slopeY = tanY * heightFill.toDouble().coerceIn(MIN_FRAMING_FILL, 1.0)

    // Camera basis. `back` points from the subject to the camera.
    val forward = normalizeOrDefault(direction)
    val back = doubleArrayOf(-forward.x.toDouble(), -forward.y.toDouble(), -forward.z.toDouble())
    // right = worldUp × back. Degenerate when looking along Y: fall back on world +X.
    var right = doubleArrayOf(back[2], 0.0, -back[0])
    val rightLength = sqrt(right[0] * right[0] + right[2] * right[2])
    right = if (rightLength > 1e-6) {
        doubleArrayOf(right[0] / rightLength, 0.0, right[2] / rightLength)
    } else {
        doubleArrayOf(1.0, 0.0, 0.0)
    }
    val up = doubleArrayOf(
        back[1] * right[2] - back[2] * right[1],
        back[2] * right[0] - back[0] * right[2],
        back[0] * right[1] - back[1] * right[0]
    )

    // The eight corners, in the camera's basis: (along right, along up, along back).
    val lowest = bounds.min
    val highest = bounds.max
    val xs = DoubleArray(8)
    val ys = DoubleArray(8)
    val zs = DoubleArray(8)
    for (i in 0 until 8) {
        val x = (if (i and 1 == 0) lowest.x else highest.x).toDouble()
        val y = (if (i and 2 == 0) lowest.y else highest.y).toDouble()
        val z = (if (i and 4 == 0) lowest.z else highest.z).toDouble()
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        xs[i] = x * right[0] + y * right[1] + z * right[2]
        ys[i] = x * up[0] + y * up[1] + z * up[2]
        zs[i] = x * back[0] + y * back[1] + z * back[2]
    }

    // On each axis, the eye whose two frustum planes of half-slope `slope` both touch the box.
    val tightX = tangentEye(xs, zs, slopeX)
    val tightY = tangentEye(ys, zs, slopeY)
    // The deeper of the two is the fit; the other axis re-centres at that depth.
    val depth = max(tightX.depth, tightY.depth)
    val eyeX = if (tightX.depth >= depth) tightX.offset else centredOffset(xs, zs, slopeX, depth)
    val eyeY = if (tightY.depth >= depth) tightY.offset else centredOffset(ys, zs, slopeY, depth)

    val centre = bounds.center
    val centreDepth =
        depth - (centre.x * back[0] + centre.y * back[1] + centre.z * back[2])
    if (!depth.isFinite() || !centreDepth.isFinite() || centreDepth <= 0.0) return null

    fun world(axis: Int) = eyeX * right[axis] + eyeY * up[axis] + depth * back[axis]
    val eye = Position(world(0).toFloat(), world(1).toFloat(), world(2).toFloat())
    val target = Position(
        (world(0) - back[0] * centreDepth).toFloat(),
        (world(1) - back[1] * centreDepth).toFloat(),
        (world(2) - back[2] * centreDepth).toFloat()
    )
    return CameraFit(eye = eye, target = target)
}

/**
 * [fitCameraToBounds] for this camera: its lens, the aspect ratio of the area it projects into —
 * the viewport minus [CameraNode.contentPadding] — and, by default, the direction it is looking
 * in now. Nothing is written: apply the result with [frame], or hand it to a camera manipulator.
 *
 * **Threading:** reads the camera's viewport and transform — main (render) thread.
 */
fun CameraNode.fitToBounds(
    bounds: Aabb,
    direction: Position = worldQuaternion * Position(0f, 0f, -1f),
    widthFill: Float = DEFAULT_FRAMING_FILL,
    heightFill: Float = DEFAULT_FRAMING_FILL
): CameraFit? = fitCameraToBounds(
    bounds = bounds,
    direction = direction,
    verticalFovDegrees = verticalFovDegreesForFocalLength(focalLength),
    aspect = getViewPortAspect(),
    widthFill = widthFill,
    heightFill = heightFill
)

/**
 * Moves this camera to [fit] and aims it at the fit's target.
 *
 * For a camera no manipulator drives. With an orbit manipulator installed, seed the manipulator
 * with [CameraFit.eye] / [CameraFit.target] instead — it owns the pose and would overwrite this.
 *
 * **Threading:** writes a Filament transform — main (render) thread.
 */
fun CameraNode.frame(fit: CameraFit) {
    worldPosition = fit.eye
    lookAt(fit.target)
}

/** Smallest fill [fitCameraToBounds] honours: below it the fit distance runs away. */
private const val MIN_FRAMING_FILL = 0.01

/** An eye coordinate along one screen axis ([offset]) and how far back it stands ([depth]). */
private class AxisFit(val depth: Double, val offset: Double)

/**
 * The eye, on one screen axis, whose two frustum planes of half-slope [slope] are both tangent to
 * the corners. A corner `(p, z)` is inside iff `|p − e| <= slope · (depth − z)`, i.e.
 * `e + slope·depth >= p + slope·z` and `−e + slope·depth >= −p + slope·z`; taking both with
 * equality at their tightest corner gives the two unknowns in closed form.
 */
private fun tangentEye(ps: DoubleArray, zs: DoubleArray, slope: Double): AxisFit {
    var high = Double.NEGATIVE_INFINITY
    var low = Double.NEGATIVE_INFINITY
    for (i in ps.indices) {
        high = max(high, ps[i] + slope * zs[i])
        low = max(low, -ps[i] + slope * zs[i])
    }
    return AxisFit(depth = (high + low) / (2.0 * slope), offset = (high - low) / 2.0)
}

/**
 * The eye coordinate that centres the corners' projection on an axis that does *not* bind: the
 * narrower slope whose tangent eye stands at exactly [depth]. [tangentEye]'s depth only grows as
 * the slope shrinks, so a bisection finds it.
 */
private fun centredOffset(ps: DoubleArray, zs: DoubleArray, slope: Double, depth: Double): Double {
    var wide = slope
    var narrow = slope
    // Find a slope narrow enough to stand at least `depth` back. A flat axis never gets there —
    // every slope is then equally centred, and the loop simply runs out.
    var guard = 0
    while (tangentEye(ps, zs, narrow).depth < depth && guard < CENTRING_STEPS) {
        wide = narrow
        narrow /= 2.0
        guard++
    }
    repeat(CENTRING_STEPS) {
        val middle = (wide + narrow) / 2.0
        if (tangentEye(ps, zs, middle).depth < depth) wide = middle else narrow = middle
    }
    return tangentEye(ps, zs, (wide + narrow) / 2.0).offset
}

private const val CENTRING_STEPS = 60

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
     * `true` while the pass still needs presented frames to converge — see [FramingGate.isPending].
     * `SceneView` reads this, **not** `!`[didFit], as its "framing pending" render source, and only
     * when the pass is actually wired: `autoFitContent = true` **and** no camera manipulator.
     * With a manipulator the pass never runs at all, so `!didFit` would have been permanently true
     * and would have held the loop at full cadence forever — with `rememberCameraManipulator()`
     * being the default, that is every scene that ever set `autoFitContent = true`.
     */
    val isFramingPending: Boolean get() = gate.isPending

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
        // No content to frame yet. See [FramingGate.isPending]: this is a wait on a push source
        // (the DSL node sync invalidates and resets the gate when content arrives), never a reason
        // to hold the render loop awake.
        if (contentRoots.isEmpty()) {
            gate.recordNoContent()
            return false
        }
        if (!gate.shouldRun(hasContent = true)) return false
        // Measure each root's subtree against a shared reference: the first root. Single-root is
        // the common case (a SceneView content-root node); multi-root unions correctly because
        // `computeContentBounds` re-expresses each subtree in the reference's local space.
        val reference = contentRoots.first()
        val bounds = contentRoots
            .map { computeContentBounds(it, relativeTo = reference) }
            .union()
        if (bounds.isEmpty) {
            gate.recordNoContent()
            return false
        }
        val distance = fitDistanceForBounds(
            bounds = bounds,
            verticalFovDegrees = verticalFovDegreesForFocalLength(cameraNode.focalLength),
            aspect = cameraNode.getViewPortAspect(),
            padding = padding
        )
        if (distance <= 0f) {
            gate.recordNoContent()
            return false
        }
        val diagonal = bounds.diagonal
        val framed = gate.shouldFrame(diagonal)
        if (framed) {
            if (!cameraNode.frameToBounds(bounds, padding = padding)) {
                gate.recordNoContent()
                return false
            }
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
