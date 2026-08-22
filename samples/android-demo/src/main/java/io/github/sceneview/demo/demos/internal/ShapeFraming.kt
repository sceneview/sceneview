package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import io.github.sceneview.math.Position2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Outlines + framing arithmetic for the *Shape Extrude* sub-mode of
 * [io.github.sceneview.demo.demos.CustomGeometryDemo].
 *
 * The sub-mode used to pair `eyePosition = (0, 0, 1.5)` with `targetPosition = (0, 0, -1)`
 * and a shape authored at `z = -1` — which reads as "a camera 2.5 m from the subject". It is
 * not: the orbit distance is the **length** of `eyePosition`, because `SceneView`'s
 * default `autoCenterContent = true` moves the shape onto the world origin and Filament takes
 * the vector verbatim as the eye (see `rememberCameraManipulator`'s KDoc, #2930, and
 * [GeometryLayout]'s orbit-distance note, #2873). The camera was therefore 1.5 m out — 40 %
 * closer than intended — and a 1 m-wide outline on a portrait frame that is only ~0.68 m wide
 * at that distance was clipped on both sides
 * ([#2937](https://github.com/sceneview/sceneview/issues/2937)).
 *
 * Same cure as #2923: the distance is a named constant, [orbitHomeOffset] returns a vector
 * whose length *is* that distance, and `ShapeFramingTest` asserts the widest outline clears a
 * phone-portrait frame through the same frustum relation [GeometryLayout] uses
 * (`halfHeight = distance · 12 / focalLength`). The outlines themselves live here too, so the
 * test measures the vertices the demo actually extrudes rather than a copy of them.
 *
 * **Precondition:** the sub-mode keeps `autoCenterContent` at its default. With it off the
 * distance would become `|eyePosition − contentCentre|` and [CAMERA_DISTANCE] would need
 * re-deriving.
 */
internal object ShapeFraming {

    /** Depth the shape is authored at, and the camera's orbit target. */
    const val TARGET_Z = -1f

    /**
     * Camera-to-shape distance, in metres.
     *
     * Chosen so the widest outline fills ~73 % of the frame width at
     * [GeometryLayout.PHONE_PORTRAIT_ASPECT] — readable at thumbnail size, with a visible margin
     * on every side — and still clears the frame at [GeometryLayout.NARROWEST_EXPECTED_ASPECT].
     * `ShapeFramingTest` pins the margin this value actually delivers.
     */
    const val CAMERA_DISTANCE = 3f

    /** Triangle outline: apex up, 1 m wide, 0.8 m tall. */
    val trianglePath: List<Position2> = listOf(
        Position2(0f, 0.5f),
        Position2(-0.5f, -0.3f),
        Position2(0.5f, -0.3f),
    )

    /** Five-point star outline, outer radius 0.5 m, inner radius 0.2 m, one point up. */
    val starPath: List<Position2> = buildList {
        val outerR = 0.5f
        val innerR = 0.2f
        for (i in 0 until 10) {
            val angle = (i * 36f - 90f) * (PI.toFloat() / 180f)
            val r = if (i % 2 == 0) outerR else innerR
            add(Position2(cos(angle) * r, sin(angle) * r))
        }
    }

    /** Regular hexagon outline, circumradius 0.4 m, one vertex on +X. */
    val hexagonPath: List<Position2> = buildList {
        val r = 0.4f
        for (i in 0 until 6) {
            val angle = (i * 60f) * (PI.toFloat() / 180f)
            add(Position2(cos(angle) * r, sin(angle) * r))
        }
    }

    /** Every outline the sub-mode offers, keyed by its chip label. */
    val paths: Map<String, List<Position2>> = mapOf(
        "Triangle" to trianglePath,
        "Star" to starPath,
        "Hexagon" to hexagonPath,
    )

    /**
     * Orbit offset to pass as `eyePosition`, for a camera [distance] metres from the
     * shape: a vector of length exactly [distance], straight down the view axis. The shape is a
     * flat extrusion facing +Z, so unlike [GeometryLayout.orbitHomeOffset] there is no elevation
     * — a tilt would only foreshorten it.
     *
     * @param distance Camera-to-shape distance in metres. Must be `> 0`.
     */
    fun orbitHomeOffset(distance: Float): Position {
        require(distance > 0f) { "distance must be > 0, was $distance" }
        return Position(x = 0f, y = 0f, z = distance)
    }

    /**
     * Widest half-extent of an [outline] about its bounding-box centre, in metres. The shape
     * does not spin, so this is a plain vertex maximum — and it is taken about the box centre,
     * not the authored origin, because that is where `autoCenterContent` puts it.
     */
    fun halfWidth(outline: List<Position2>): Float = halfExtent(outline.map { it.x })

    /** Tallest half-extent of an [outline] about its bounding-box centre — see [halfWidth]. */
    fun halfHeight(outline: List<Position2>): Float = halfExtent(outline.map { it.y })

    /** Widest half-extent across every outline in [paths]. */
    val halfWidth: Float get() = paths.values.maxOf(::halfWidth)

    /** Tallest half-extent across every outline in [paths]. */
    val halfHeight: Float get() = paths.values.maxOf(::halfHeight)

    /**
     * Fraction of the frame **width** the widest outline occupies at [distance] on an [aspect]
     * viewport — `1.0` touches both edges, above `1.0` is clipped.
     */
    fun horizontalFillRatio(distance: Float, aspect: Float): Float =
        halfWidth / GeometryLayout.frameHalfWidth(distance, aspect)

    /** Fraction of the frame **height** the tallest outline occupies at [distance]. */
    fun verticalFillRatio(distance: Float): Float =
        halfHeight / GeometryLayout.frameHalfHeight(distance)

    private fun halfExtent(coordinates: List<Float>): Float {
        require(coordinates.isNotEmpty()) { "an outline needs at least one vertex" }
        return abs(coordinates.max() - coordinates.min()) * 0.5f
    }
}
