package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.sqrt

/**
 * Layout + framing arithmetic for [io.github.sceneview.demo.demos.GeometryDemo].
 *
 * The demo used to lay its four primitives out on a **single horizontal line** spanning
 * ~1.45 m, viewed from a camera that was ~1.2 m away — so the group was more than twice as
 * wide as the frame and a primitive was cut off at an edge no matter what
 * ([#2873](https://github.com/sceneview/sceneview/issues/2873)). A clipped primitive on the
 * store page of a *rendering engine* reads as a rendering bug, which is why the `geometry`
 * id was dropped from the v2 Play Store screenshot set rather than shipped.
 *
 * Two things were wrong, and both are fixed here:
 *
 *  1. **The row was too wide.** Four primitives side by side need horizontal room, which is
 *     exactly what a phone-portrait viewport has least of. They now sit in a **2 × 2
 *     cluster**: two columns instead of four, spending the viewport's abundant height
 *     instead of its scarce width. That roughly halves the horizontal footprint.
 *  2. **The camera was ~2× closer than the code claimed.** See the orbit-distance note
 *     below — the old framing comment asserted "a comfortable 2.7 m" for a camera that
 *     measured 1.2 m away.
 *
 * ### Orbit distance is the LENGTH of `orbitHomePosition` (measured, #2873)
 *
 * `rememberCameraManipulator(orbitHomePosition, targetPosition)`'s KDoc used to document that
 * parameter as "the camera's world position to return to on double-tap", which reads as "the
 * distance is `|orbitHomePosition − targetPosition|`". Measured on-device it is **not**: the
 * resulting orbit distance is `|orbitHomePosition|`.
 *
 * The cause is *not* that the value is an offset from the target. Filament's
 * `OrbitManipulator` assigns it verbatim as the eye (`mEye = mProps.orbitHomePosition`) and
 * never re-bases it on `targetPosition`. What actually happens is that `SceneView`'s default
 * `autoCenterContent = true` translates this demo's nodes so their bounding-box centre lands
 * on the **world origin** — the authored `z = -1.5` does not survive, so the eye's distance to
 * the subject is simply its own length. `targetPosition` sets the orbit pivot and the initial
 * look-at, nothing more. See `rememberCameraManipulator`'s KDoc (#2930) for the canonical
 * statement. Every doc example targets the origin, where the two readings coincide, which is
 * why the difference goes unnoticed until a demo targets something else.
 *
 * **That precondition is load-bearing here:** with `autoCenterContent = false` the distance
 * becomes `|orbitHomePosition − contentCentre|` again and every constant below would need
 * re-deriving. This demo leaves it at the default.
 *
 * The old `orbitHomePosition = (0, 0.2, 1.2)` / `targetPosition = (0, 0, -1.5)` therefore
 * put the camera 1.22 m from the primitives, not the 2.7 m its comment claimed. Verified by
 * projecting known geometry back through the frustum at four distances, and independently on
 * the `shape` demo (whose triangle is clipped by the same misreading).
 *
 * [orbitHomeOffset] encodes the measured semantics: it returns a vector whose **length is
 * the requested distance**, so the number that reaches Filament means what its name says. It
 * is named for the direction it encodes — Filament does not treat it as an offset.
 *
 * ### Frustum model
 *
 * `SceneView`'s camera is configured through [io.github.sceneview.node.CameraNode.focalLength]
 * (28 mm by default), which Filament's `setLensProjection` resolves against a full-frame
 * **24 mm sensor height** — see [io.github.sceneview.verticalFovDegreesForFocalLength]. The
 * visible half-height at a distance `d` is therefore exactly `d · 12 / focalLength`, and the
 * half-width is that scaled by the viewport aspect (`width / height`). No trigonometry, and
 * no approximation: this is the relation Filament itself implements, and it reproduced the
 * on-device pixel measurements to within 1.5 %.
 *
 * Everything the framing depends on lives here as a named constant so the fit is
 * *arithmetic*, not a number someone eyeballed once — `GeometryLayoutTest` asserts the
 * cluster clears the frame with margin. Move a primitive and the test tells you whether it
 * still fits.
 */
internal object GeometryLayout {

    /** Filament's full-frame sensor **half**-height, in millimetres. */
    private const val SENSOR_HALF_HEIGHT_MM = 12f

    /** Ratio of a square's half-diagonal to its half-edge — what a Y-spun cube sweeps. */
    private val SQRT_2 = sqrt(2f)

    /** Default lens of [io.github.sceneview.node.CameraNode], in millimetres. */
    const val FOCAL_LENGTH_MM = 28f

    /**
     * Viewport aspect ratio (`width / height`) the framing is tuned for: the demo's 3D
     * surface on a 1080 × 2400 phone in portrait, which measures 1080 × 2031 once the top
     * app bar is taken out. **Measured**, not assumed — derived by projecting known geometry
     * back through the frustum on the QA emulator (#2873).
     */
    const val PHONE_PORTRAIT_ASPECT = 0.532f

    /**
     * Narrowest aspect ratio the demo is expected to survive: a 20:9 phone whose 3D surface
     * somehow spans the **whole** screen. No real viewport is this narrow — the top app bar
     * always shortens it, which makes the aspect *larger* — so this is a deliberate
     * worst-case floor for the fit test, not a configuration that ships.
     */
    const val NARROWEST_EXPECTED_ASPECT = 0.45f

    /** Depth the whole cluster sits at, and the camera's orbit target. */
    const val TARGET_Z = -1.5f

    /** Horizontal offset of each of the two columns from the cluster centre, in metres. */
    const val COLUMN_X = 0.26f

    /** Vertical offset of each of the two rows from the cluster centre, in metres. */
    const val ROW_Y = 0.32f

    /** Cube edge length, in metres. */
    const val CUBE_EDGE = 0.18f

    /** Sphere radius, in metres. */
    const val SPHERE_RADIUS = 0.13f

    /** Cylinder radius, in metres. */
    const val CYLINDER_RADIUS = 0.1f

    /** Cylinder height, in metres. */
    const val CYLINDER_HEIGHT = 0.25f

    /** Plane edge length, in metres — a flat XY quad, so this is both its width and height. */
    const val PLANE_EDGE = 0.32f

    /**
     * Permanent tilt of the plane about X, in degrees.
     *
     * The plane is the one primitive in the cluster with **zero thickness**
     * (`size.z == 0`). Sharing the cluster's Y spin, its normal swept the whole
     * circle, so twice per revolution it went exactly edge-on to the camera and
     * the quad vanished — a demo whose job is to show a primitive spent half its
     * time showing nothing. The plane therefore spins about **Z** (its own
     * normal, which the rotation leaves pointing at the camera) and carries this
     * fixed X tilt instead.
     *
     * The tilt is what keeps it from reading as a flat sticker: the normal ends
     * up at `(±sin·sin s, −sin·cos s, cos)` for a tilt angle of this size, so the
     * face is always within this many degrees of the camera — never edge-on, and
     * never perfectly flat-on either, which is what gives the panel its shading.
     * 20° is the smallest angle that still reads as depth at Play Store thumbnail
     * size; past ~35° the quad starts to foreshorten enough to look narrower than
     * the cube beside it. #3237
     */
    const val PLANE_TILT_DEGREES = 20f

    /**
     * Camera-to-cluster distance, in metres, when no `camera_distance` override is supplied.
     *
     * Chosen so the cluster fills ~71 % of the frame width at [PHONE_PORTRAIT_ASPECT] — a
     * subject that reads at Play Store thumbnail size while keeping a visible margin on every
     * side, and one that still clears the frame at [NARROWEST_EXPECTED_ASPECT]. Smaller
     * values fill more and clip sooner; `GeometryLayoutTest` pins the margin this value
     * actually delivers.
     */
    const val CAMERA_DISTANCE = 2.6f

    /**
     * Sine of the camera's elevation above the cluster centre — a ~4° downward tilt that
     * keeps the primitives reading as solids rather than as flat silhouettes. Expressed as a
     * fraction of the distance so the tilt is preserved when `camera_distance` reframes the
     * scene.
     */
    private const val ELEVATION_RATIO = 0.07f

    /**
     * Orbit offset to pass as `orbitHomePosition`, for a camera [distance] metres from the
     * cluster: a vector of length exactly [distance], lifted [ELEVATION_RATIO] above the
     * view axis.
     *
     * Returning a vector of the requested length is what makes the distance honest, given
     * the `|orbitHomePosition|` semantics documented above — Filament uses it as the eye
     * directly, and `autoCenterContent` has already put the cluster on the origin.
     *
     * @param distance Camera-to-cluster distance in metres. Must be `> 0`.
     */
    fun orbitHomeOffset(distance: Float): Position {
        require(distance > 0f) { "distance must be > 0, was $distance" }
        return Position(
            x = 0f,
            y = distance * ELEVATION_RATIO,
            z = distance * sqrt(1f - ELEVATION_RATIO * ELEVATION_RATIO),
        )
    }

    /**
     * Widest half-extent of the four-primitive cluster about `x = 0`, in metres.
     *
     * Every primitive spins about its **Y** axis, so the swept horizontal half-extent is the
     * largest horizontal distance any vertex reaches from its own centre over a full
     * revolution: the cube's half-**diagonal** (not its half-edge), the sphere's radius, the
     * cylinder's radius, and the plane's half-edge.
     */
    val halfWidth: Float
        get() = COLUMN_X + maxOf(
            CUBE_EDGE * 0.5f * SQRT_2,
            SPHERE_RADIUS,
            CYLINDER_RADIUS,
            PLANE_EDGE * 0.5f,
        )

    /**
     * Tallest half-extent of the cluster about `y = 0`, in metres. Spin is about Y, so
     * vertical extents are rotation-invariant and these are plain half-heights.
     */
    val halfHeight: Float
        get() = ROW_Y + maxOf(
            CUBE_EDGE * 0.5f,
            SPHERE_RADIUS,
            CYLINDER_HEIGHT * 0.5f,
            PLANE_EDGE * 0.5f,
        )

    /**
     * Half-height of the visible frame, in metres, at [distance] metres from the camera.
     *
     * @param distance       Camera-to-subject distance in metres. Must be `> 0`.
     * @param focalLengthMm  Lens focal length in millimetres. Must be `> 0`.
     */
    fun frameHalfHeight(distance: Float, focalLengthMm: Float = FOCAL_LENGTH_MM): Float {
        require(distance > 0f) { "distance must be > 0, was $distance" }
        require(focalLengthMm > 0f) { "focalLengthMm must be > 0, was $focalLengthMm" }
        return distance * SENSOR_HALF_HEIGHT_MM / focalLengthMm
    }

    /**
     * Half-width of the visible frame, in metres, at [distance] metres from the camera.
     *
     * @param distance       Camera-to-subject distance in metres. Must be `> 0`.
     * @param aspect         Viewport aspect ratio, `width / height`. Must be `> 0`.
     * @param focalLengthMm  Lens focal length in millimetres. Must be `> 0`.
     */
    fun frameHalfWidth(
        distance: Float,
        aspect: Float,
        focalLengthMm: Float = FOCAL_LENGTH_MM,
    ): Float {
        require(aspect > 0f) { "aspect must be > 0, was $aspect" }
        return frameHalfHeight(distance, focalLengthMm) * aspect
    }

    /**
     * Fraction of the frame **width** the cluster occupies at [distance] on an [aspect]
     * viewport — `1.0` means it exactly touches both edges, above `1.0` means it is clipped,
     * and `1 - value` is the total margin left over.
     *
     * @param distance Camera-to-subject distance in metres. Must be `> 0`.
     * @param aspect   Viewport aspect ratio, `width / height`. Must be `> 0`.
     */
    fun horizontalFillRatio(distance: Float, aspect: Float): Float =
        halfWidth / frameHalfWidth(distance, aspect)

    /**
     * Fraction of the frame **height** the cluster occupies at [distance]. Independent of the
     * aspect ratio: the lens fixes the vertical field of view, and the viewport width is what
     * varies around it.
     *
     * @param distance Camera-to-subject distance in metres. Must be `> 0`.
     */
    fun verticalFillRatio(distance: Float): Float = halfHeight / frameHalfHeight(distance)
}
