package io.github.sceneview.demo.demos.internal

import io.github.sceneview.Aabb
import io.github.sceneview.CameraFit
import io.github.sceneview.fitCameraToBounds
import io.github.sceneview.math.Position
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The primitives [io.github.sceneview.demo.demos.GeometryDemo] shows, in display order — one per
 * built-in geometry composable of `SceneScope` (`CubeNode`, `SphereNode`, `CylinderNode`,
 * `ConeNode`, `TorusNode`, `CapsuleNode`, `PlaneNode`).
 */
internal enum class GeometryShape { Cube, Sphere, Cylinder, Cone, Torus, Capsule, Plane }

/**
 * Layout + framing arithmetic for [io.github.sceneview.demo.demos.GeometryDemo], and the frustum
 * model the other procedural demos (`TorusKnot`, `LinesPathsScene`) check their own framing
 * against.
 *
 * ### Layout: a slot per shape, whatever is hidden
 *
 * Every shape owns a slot of an [Arrangement]; hiding a shape empties its slot and nothing else
 * moves. The cluster's extents — and therefore the camera — depend on the arrangement only,
 * never on what is visible, so a chip tap can never reframe the scene. That is also why the demo
 * runs with `autoCenterContent = false`: the library would otherwise re-centre the survivors on
 * every toggle.
 *
 * The arrangement follows the **free band** of the viewport — what is left between the title row
 * and the bottom chrome — not the window: a staggered 2-3-2 block in a portrait band, a single
 * row in the shallow band a landscape phone leaves. [arrangementFor] picks whichever draws the
 * shapes largest.
 *
 * ### Frustum model
 *
 * `SceneView`'s camera is configured through [io.github.sceneview.node.CameraNode.focalLength]
 * (28 mm by default), which Filament's `setLensProjection` resolves against a full-frame
 * **24 mm sensor height** — see [io.github.sceneview.verticalFovDegreesForFocalLength]. The
 * visible half-height at distance `d` is therefore `d · 12 / focalLength`, and the half-width is
 * that times the viewport aspect. The vertical field of view is the fixed one: a landscape
 * window sees the same height and more width — which is why the Geometry demo itself does not
 * keep 28 mm on a band much wider than it is tall, see [focalLengthMm].
 *
 * ### Orbit distance
 *
 * The camera is built with `createDefaultCameraManipulator(eyePosition, targetPosition)` from
 * [framing], the cluster is centred on the world origin and `autoCenterContent` is off, so the
 * orbit distance is exactly `|eye − target|`. With `autoCenterContent = true` the library moves
 * the content onto the origin and the distance becomes the *length* of the eye vector whatever
 * the target says (#2873, #2930) — the trap the demos citing this note have to account for.
 */
internal object GeometryLayout {

    // ── Frustum model (shared with TorusKnot and LinesPathsScene) ────────────────────────────────

    /** `CameraNode`'s default focal length, in millimetres. */
    const val FOCAL_LENGTH_MM = 28f

    /** Half of Filament's 24 mm full-frame sensor height. */
    private const val SENSOR_HALF_HEIGHT_MM = 12f

    /** Width / height of the Play Store phone capture viewport (1440 × 2706, top bar excluded). */
    const val PHONE_PORTRAIT_ASPECT = 0.532f

    /** The narrowest viewport a procedural demo is expected to meet — a tall phone, chrome on. */
    const val NARROWEST_EXPECTED_ASPECT = 0.45f

    /** Visible half-height of the frame, in metres, at [distance] from the camera. */
    fun frameHalfHeight(distance: Float, focalLengthMm: Float = FOCAL_LENGTH_MM): Float {
        require(distance > 0f) { "distance must be > 0, was $distance" }
        require(focalLengthMm > 0f) { "focalLengthMm must be > 0, was $focalLengthMm" }
        return distance * SENSOR_HALF_HEIGHT_MM / focalLengthMm
    }

    /** Visible half-width of the frame, in metres, at [distance] for a viewport of [aspect]. */
    fun frameHalfWidth(distance: Float, aspect: Float, focalLengthMm: Float = FOCAL_LENGTH_MM): Float {
        require(aspect > 0f) { "aspect must be > 0, was $aspect" }
        return frameHalfHeight(distance, focalLengthMm) * aspect
    }

    // ── Shapes ───────────────────────────────────────────────────────────────────────────────────

    /** Cube edge, in metres. */
    const val CUBE_EDGE = 0.22f

    const val SPHERE_RADIUS = 0.14f

    const val CYLINDER_RADIUS = 0.10f
    const val CYLINDER_HEIGHT = 0.26f

    const val CONE_RADIUS = 0.13f
    const val CONE_HEIGHT = 0.26f

    /** Ring radius (centre of the tube) and tube radius. */
    const val TORUS_MAJOR_RADIUS = 0.105f
    const val TORUS_MINOR_RADIUS = 0.042f

    /** Capsule radius and the height of its cylindrical section (the caps add `2 · radius`). */
    const val CAPSULE_RADIUS = 0.08f
    const val CAPSULE_HEIGHT = 0.13f

    /** Edge of the square plane. */
    const val PLANE_EDGE = 0.24f

    /** How far the shapes lean toward the camera before they spin, in degrees. */
    const val TILT_DEGREES = 20f

    /** The torus lies flat by construction; this stands it up so its hole can face the camera. */
    const val TORUS_TILT_DEGREES = 65f

    /**
     * Radius of the sphere a shape stays inside whatever its rotation — what the slot has to
     * clear, since every shape but the sphere tumbles.
     */
    fun boundingRadius(shape: GeometryShape): Float = when (shape) {
        GeometryShape.Cube -> CUBE_EDGE * sqrt(3f) / 2f
        GeometryShape.Sphere -> SPHERE_RADIUS
        GeometryShape.Cylinder -> hypot(CYLINDER_RADIUS, CYLINDER_HEIGHT / 2f)
        GeometryShape.Cone -> hypot(CONE_RADIUS, CONE_HEIGHT / 2f)
        GeometryShape.Torus -> TORUS_MAJOR_RADIUS + TORUS_MINOR_RADIUS
        GeometryShape.Capsule -> CAPSULE_HEIGHT / 2f + CAPSULE_RADIUS
        GeometryShape.Plane -> PLANE_EDGE * sqrt(2f) / 2f
    }

    // ── Arrangement ──────────────────────────────────────────────────────────────────────────────

    /** Centre-to-centre distance between two neighbouring slots, in metres. */
    const val CELL = 0.42f

    /** Distance between two rows: slots are staggered, so neighbours stay [CELL] apart. */
    val ROW_PITCH: Float = CELL * sin(Math.toRadians(60.0)).toFloat()

    /** Depth of the cluster: the largest bounding sphere, since the slots share one plane. */
    val DEPTH: Float = 2f * GeometryShape.entries.maxOf(::boundingRadius)

    /**
     * How the seven slots are spread, as the number of slots per row from the top. Rows are
     * centred, so rows of different lengths stagger on their own.
     */
    enum class Arrangement(val rows: List<Int>) {
        /** 2-3-2: the densest block, for a band that is taller than it is wide. */
        Honeycomb(listOf(2, 3, 2)),

        /** 4-3: for a band about twice as wide as it is tall — a tablet, a foldable. */
        TwoRows(listOf(4, 3)),

        /** One row: for the shallow band a landscape phone leaves. */
        SingleRow(listOf(7));

        /** Width of the block, from the edge of the first tumbling shape to the edge of the last. */
        val extentX: Float get() = (rows.max() - 1) * CELL + DEPTH

        /** Height of the block, counted the same way: the gap between slots is not framed. */
        val extentY: Float get() = (rows.size - 1) * ROW_PITCH + DEPTH
    }

    /** Share of the free band's width the block may span. */
    const val HORIZONTAL_FILL = 0.94f

    /** Share of the free band's height the block may span. */
    const val VERTICAL_FILL = 0.90f

    /**
     * The arrangement that draws the shapes largest in a free band of [aspect] (width / height):
     * the one the camera can stand closest to.
     */
    fun arrangementFor(aspect: Float): Arrangement =
        Arrangement.entries.minBy { framing(it, aspect).distance }

    /** World position of [shape]'s slot in [arrangement]. The block is centred on the origin. */
    fun position(shape: GeometryShape, arrangement: Arrangement): Position {
        var index = shape.ordinal
        arrangement.rows.forEachIndexed { row, count ->
            if (index < count) {
                val x = (index - (count - 1) / 2f) * CELL
                val y = ((arrangement.rows.size - 1) / 2f - row) * ROW_PITCH
                return Position(x, y, 0f)
            }
            index -= count
        }
        error("$arrangement has no slot for $shape")
    }

    // ── Camera ───────────────────────────────────────────────────────────────────────────────────

    /** How far above the horizon the camera sits, in degrees: enough to show the top faces. */
    const val CAMERA_PITCH_DEGREES = 8f

    /**
     * The lens for a free band of [aspect] (width / height), in millimetres: the default 28 mm
     * while the band is taller than it is wide, and longer in proportion once it is wider.
     *
     * `contentPadding` makes the lens's vertical field span the band, so the horizontal one is
     * that times the band's aspect. A landscape phone leaves a band six times wider than it is
     * tall: at 28 mm that is a 140° horizontal field from half a metre away, and the shapes at
     * the ends of the row are drawn twice as wide as they are tall. Lengthening the lens by the
     * aspect keeps the 28 mm field on the band's *longer* side, whichever it is, so no shape is
     * ever further off axis than the top of a portrait frame.
     */
    fun focalLengthMm(aspect: Float): Float = FOCAL_LENGTH_MM * max(1f, usable(aspect))

    /**
     * The seven slots of [arrangement] as one box, each counted to the edge of the sphere its
     * shape tumbles in. It does not depend on what is shown: the camera frames the slots.
     */
    fun bounds(arrangement: Arrangement): Aabb = Aabb(
        center = Position(0f, 0f, 0f),
        halfExtent = Position(arrangement.extentX / 2f, arrangement.extentY / 2f, DEPTH / 2f),
    )

    /**
     * The opening shot: [arrangement] seen from [CAMERA_PITCH_DEGREES] above the horizon through
     * the [focalLengthMm] lens, and spanning [HORIZONTAL_FILL] × [VERTICAL_FILL] of a free band of
     * [aspect] (width / height).
     *
     * The SDK's [fitCameraToBounds] does the fit, corner by corner, so it holds for the surfaces
     * nearest the camera and not just for the plane through the centres. The band itself is
     * handed to `SceneView(contentPadding = …)`: the camera projects into it, and this only has
     * to fill it.
     *
     * @param distance Overrides the fitted distance, keeping the direction and the target — the
     *                 `?cameraDistance=` deep-link parameter QA flows use.
     */
    fun framing(arrangement: Arrangement, aspect: Float, distance: Float? = null): CameraFit {
        val pitch = Math.toRadians(CAMERA_PITCH_DEGREES.toDouble())
        val towardsBlock = Position(0f, -sin(pitch).toFloat(), -cos(pitch).toFloat())
        val fit = checkNotNull(
            fitCameraToBounds(
                bounds = bounds(arrangement),
                direction = towardsBlock,
                verticalFovDegrees = verticalFovDegreesForFocalLength(focalLengthMm(aspect).toDouble()),
                aspect = usable(aspect).toDouble(),
                widthFill = HORIZONTAL_FILL,
                heightFill = VERTICAL_FILL,
            )
        ) { "the block of slots has a volume to frame" }
        return if (distance == null) fit else fit.copy(eye = fit.target - towardsBlock * distance)
    }

    /** [aspect], or a square band while the layout has not measured one yet. */
    private fun usable(aspect: Float): Float = if (aspect.isFinite() && aspect > 0f) aspect else 1f

    private fun hypot(a: Float, b: Float): Float = sqrt(a * a + b * b)
}
