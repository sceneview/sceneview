package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A camera pose expressed the way a user reads it — **where am I looking, from which angle, how
 * far away** — instead of the 4×4 matrix the renderer wants.
 *
 * Every camera state in the `camera-gestures` demo is one of these: the resting framing, the pose
 * a drag is producing, the pose a preset flies to, and the numbers the on-screen HUD prints. The
 * conversion to an eye position happens in exactly one place ([CameraRig.eye]), which is what lets
 * the orbit maths, the fly-to interpolation and the readout agree by construction rather than by
 * three independent derivations.
 *
 * @property target           World point the camera looks at and pivots around.
 * @property azimuthDegrees   Angle around world +Y, measured from +Z towards +X. Unbounded — a
 *                            turntable that has spun three times reads 1080°; use
 *                            [CameraRig.normalizeDegrees] before showing it.
 * @property elevationDegrees Angle above the horizon. Positive looks *down* at the target.
 * @property distance         Camera-to-target distance, in metres.
 */
data class OrbitPose(
    val target: Position = Position(0f, 0f, 0f),
    val azimuthDegrees: Float = 0f,
    val elevationDegrees: Float = 0f,
    val distance: Float = 1f,
)

/**
 * What the camera is doing right now, for the one-word badge the HUD shows while it happens.
 *
 * A camera demo whose gestures produce no visible acknowledgement teaches nothing: the user drags,
 * the image moves, and which of the three manipulations they just performed is left to be inferred
 * from the result. Naming the gesture *as it runs* is the difference between a viewport and a
 * lesson.
 */
enum class RigGesture(val label: String) {
    Orbit("Orbit"),
    Pan("Pan"),
    Zoom("Zoom"),
    Fly("Flying"),
}

/**
 * A named camera angle, relative to whatever the camera is currently focused on.
 *
 * Presets are stored as **angles and a distance multiple**, never as absolute eye positions, so the
 * same five chips work on the whole scene and on any single subject: "Top" means 78° above
 * whatever you are looking at, at a distance derived from that subject's own size.
 *
 * @property label         Chip label. One word — the row has to fit five of them on a phone.
 * @property azimuthDegrees   Angle around world +Y for this view.
 * @property elevationDegrees Angle above the horizon for this view.
 * @property distanceScale    Multiple of the focus's auto-fit distance.
 */
enum class CameraView(
    val label: String,
    val azimuthDegrees: Float,
    val elevationDegrees: Float,
    val distanceScale: Float,
) {
    /** The resting three-quarter framing — what the screen opens on and what Recenter returns to. */
    Hero("Hero", azimuthDegrees = 32f, elevationDegrees = 16f, distanceScale = 1f),

    /** Straight on, near eye level. */
    Front("Front", azimuthDegrees = 0f, elevationDegrees = 5f, distanceScale = 0.94f),

    /** Broadside, near eye level. */
    Side("Side", azimuthDegrees = 90f, elevationDegrees = 6f, distanceScale = 0.94f),

    /**
     * Looking down — the plan view that shows the layout rather than the silhouette.
     *
     * Pulled in rather than pushed out, unlike the other wide views: seen from overhead the
     * stage presents its *plan*, which is the one projection where the subjects' height stops
     * contributing to how much frame they fill, so the fit computed for the Hero elevation
     * leaves this view noticeably loose.
     */
    Top("Top", azimuthDegrees = 12f, elevationDegrees = 78f, distanceScale = 0.82f),

    /** Pulled in tight on the focus, from the hero angle. */
    Close("Close", azimuthDegrees = 24f, elevationDegrees = 12f, distanceScale = 0.52f),
}

/**
 * One of the three objects on the stage.
 *
 * The demo needs more than one subject or half of what it shows would have nothing to demonstrate:
 * "tap an object to fly to it" and "presets are relative to what you are looking at" are both
 * statements about *choosing* a subject. Three is the smallest number that makes the choice
 * visible and still frames as a composition rather than a row.
 *
 * @property label      Name shown in the HUD when this subject has focus.
 * @property assetPath  Bundled glTF, relative to `assets/`.
 * @property groundX    World X of the spot the subject stands on.
 * @property groundZ    World Z of the spot the subject stands on.
 * @property extent     Largest world-space dimension after `scaleToUnits`, in metres. Doubles as
 *                      the content extent the per-subject auto-fit distance is computed from.
 * @property yawDegrees Resting yaw, so each subject presents its best side to the Hero angle
 *                      instead of all three facing the same way like stock on a shelf.
 */
enum class RigSubject(
    val label: String,
    val assetPath: String,
    val groundX: Float,
    val groundZ: Float,
    val extent: Float,
    val yawDegrees: Float,
) {
    Helmet(
        label = "Damaged Helmet",
        assetPath = "models/khronos_damaged_helmet.glb",
        groundX = 0f,
        groundZ = 0f,
        extent = 0.58f,
        yawDegrees = -22f,
    ),
    ToyCar(
        label = "Toy Car",
        assetPath = "models/khronos_toy_car.glb",
        groundX = 0.44f,
        groundZ = -0.27f,
        // `scaleToUnits` normalises the LARGEST dimension, and this asset's bounds include
        // the draped cloth the car sits on — so a value that reads as "same size as the
        // helmet" leaves the car itself a third of it. Scaled to what is visible, not to
        // what the bounding box measures.
        extent = 0.62f,
        yawDegrees = 38f,
    ),
    Lantern(
        label = "Lantern",
        assetPath = "models/khronos_lantern.glb",
        groundX = -0.44f,
        groundZ = 0.27f,
        // Tall and thin: normalising its height to the helmet's would leave a sliver. The
        // three subjects are matched by visual mass, not by bounding-box extent.
        extent = 0.80f,
        yawDegrees = -34f,
    ),
}

/**
 * The camera maths of the `camera-gestures` demo, as pure functions.
 *
 * Nothing here touches Filament, Compose or a `MotionEvent`, so the orbit conversion, the shortest
 * -arc interpolation a preset flight uses and the clamps that keep a drag out of the poles are all
 * unit-testable on the JVM (`CameraRigTest`). [StudioCameraManipulator] is the thin, stateful shell
 * that feeds these functions from touch input and the frame loop.
 */
object CameraRig {

    /** Highest the camera may rise. Short of the pole, where `lookAt`'s world-up collapses. */
    const val MAX_ELEVATION_DEGREES: Float = 86f

    /**
     * Lowest the camera may sink. The subjects stand on a floor, so a camera below it sees the
     * underside of a plane and nothing else — the clamp is a framing decision, not a maths one.
     */
    const val MIN_ELEVATION_DEGREES: Float = -12f

    /** Closest a pinch or the distance slider may take the camera, as a multiple of the fit. */
    const val MIN_DISTANCE_SCALE: Float = 0.30f

    /** Furthest a pinch or the distance slider may take the camera, as a multiple of the fit. */
    const val MAX_DISTANCE_SCALE: Float = 2.60f

    /** Degrees of orbit per pixel dragged, at [DEFAULT_SENSITIVITY]. */
    const val ORBIT_DEGREES_PER_PIXEL: Float = 0.24f

    /** Neutral position of the demo's sensitivity slider. */
    const val DEFAULT_SENSITIVITY: Float = 1f

    /** Sensitivity slider bounds. */
    const val MIN_SENSITIVITY: Float = 0.35f
    const val MAX_SENSITIVITY: Float = 2.2f

    /**
     * Fraction of the release velocity that survives each second of coasting.
     *
     * `0.06` reads as a heavy, well-oiled turntable: a flick keeps turning for about a second and
     * settles rather than stopping dead. Applied as `v *= decay^dt`, so the feel is identical at
     * 60 and 120 Hz — a per-frame multiplier would spin twice as long on a 120 Hz panel.
     */
    const val INERTIA_DECAY_PER_SECOND: Float = 0.06f

    /** Below this angular speed the coast is over and the camera is parked (degrees / second). */
    const val INERTIA_STOP_DEGREES_PER_SECOND: Float = 1.2f

    /** Fastest a flick may coast, so a frantic swipe cannot launch the camera into a blur. */
    const val MAX_INERTIA_DEGREES_PER_SECOND: Float = 420f

    /** How far a two-finger pan may drag the pivot from the stage centre, in metres. */
    const val MAX_PAN_RADIUS: Float = 1.4f

    /** Vertical bounds of the pivot under a pan, in metres. */
    const val MIN_TARGET_Y: Float = -0.15f
    const val MAX_TARGET_Y: Float = 1.30f

    /** Duration of a preset / focus flight — `motion-entrance` from `DESIGN.md`. */
    const val FLIGHT_MILLIS: Int = 700

    /** Centre of the three-subject stage — the pivot the Recenter action returns to. */
    val SCENE_TARGET: Position = Position(0f, 0.34f, 0.02f)

    /**
     * World-space extents of the whole stage, feeding its auto-fit distance.
     *
     * The three subjects are spread along the **Hero camera's own right vector**, not along
     * world X and not along its view direction. Spreading them in depth stacks them up the
     * portrait frame, which is what a tall screen wants — but it also puts one subject in front
     * of another and the composition reads as a pile. Spreading them across the view instead
     * keeps all three legible and un-occluded at the resting framing, and the modest depth
     * stagger that comes with it is what stops them looking like stock on a shelf.
     */
    const val SCENE_EXTENT_X: Float = 1.46f
    const val SCENE_EXTENT_Y: Float = 0.86f
    const val SCENE_EXTENT_Z: Float = 1.12f

    /**
     * Fraction of the binding axis the whole stage is framed to fill.
     *
     * Above 1 on purpose. The auto-fit is azimuth-invariant, so it frames the stage's bounding
     * **sphere** — the guarantee that nothing clips as the camera swings broadside. A flat
     * triangle of three subjects standing on a floor fills perhaps two-thirds of that sphere, so
     * fitting the sphere to the frame leaves the content itself small in the middle of it, with
     * the slack showing as empty floor. Per-subject fits keep the stock fill: a single subject
     * roughly *is* its bounding sphere, and there the default already frames it.
     */
    const val STAGE_FILL: Float = 1.24f

    /**
     * Where the camera aims when [subject] has focus: the middle of the subject's own height, not
     * the spot it stands on. Framing an object from its feet puts it in the top half of the frame.
     */
    fun focusTarget(subject: RigSubject): Position =
        Position(subject.groundX, subject.extent * 0.46f, subject.groundZ)

    /** Eye position for [pose] — the single conversion from spherical to world space. */
    fun eye(pose: OrbitPose): Position {
        val azimuth = Math.toRadians(pose.azimuthDegrees.toDouble())
        val elevation = Math.toRadians(pose.elevationDegrees.toDouble())
        val horizontal = pose.distance * cos(elevation)
        return Position(
            x = pose.target.x + (horizontal * sin(azimuth)).toFloat(),
            y = pose.target.y + (pose.distance * sin(elevation)).toFloat(),
            z = pose.target.z + (horizontal * cos(azimuth)).toFloat(),
        )
    }

    /** Inverse of [eye] — the pose that would put the camera at [eye] looking at [target]. */
    fun poseOf(eye: Position, target: Position): OrbitPose {
        val dx = eye.x - target.x
        val dy = eye.y - target.y
        val dz = eye.z - target.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!distance.isFinite() || distance <= 1e-6f) {
            return OrbitPose(target = target, distance = 1f)
        }
        return OrbitPose(
            target = target,
            azimuthDegrees = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat(),
            elevationDegrees = Math.toDegrees(
                asin((dy / distance).toDouble().coerceIn(-1.0, 1.0))
            ).toFloat(),
            distance = distance,
        )
    }

    /** Wraps [degrees] into `(-180, 180]` — the form the HUD prints. */
    fun normalizeDegrees(degrees: Float): Float {
        if (!degrees.isFinite()) return 0f
        var wrapped = degrees % 360f
        if (wrapped > 180f) wrapped -= 360f
        if (wrapped <= -180f) wrapped += 360f
        return wrapped
    }

    /**
     * Signed shortest way round from [from] to [to], in `(-180, 180]`.
     *
     * A flight from 350° to 10° must travel +20°, not -340°: interpolating the raw numbers takes
     * the camera the long way round the subject, which reads as a mistake even though it lands on
     * the right frame.
     */
    fun shortestDelta(from: Float, to: Float): Float = normalizeDegrees(to - from)

    /**
     * The pose [fraction] of the way from [from] to [to].
     *
     * Angles take the shortest arc ([shortestDelta]); the target is a straight line; the **distance
     * is interpolated geometrically** — `d = d0·(d1/d0)^t` — because a dolly that covers equal
     * *ratios* per unit time is what reads as constant speed to the eye. A linear distance lerp
     * from 4 m to 0.5 m appears to accelerate violently at the end, since the last half-metre
     * changes the image far more than the first.
     */
    fun lerp(from: OrbitPose, to: OrbitPose, fraction: Float): OrbitPose {
        val t = fraction.coerceIn(0f, 1f)
        val d0 = from.distance.takeIf { it.isFinite() && it > 1e-4f } ?: to.distance
        val d1 = to.distance.takeIf { it.isFinite() && it > 1e-4f } ?: d0
        return OrbitPose(
            target = Position(
                x = from.target.x + (to.target.x - from.target.x) * t,
                y = from.target.y + (to.target.y - from.target.y) * t,
                z = from.target.z + (to.target.z - from.target.z) * t,
            ),
            azimuthDegrees = from.azimuthDegrees +
                shortestDelta(from.azimuthDegrees, to.azimuthDegrees) * t,
            elevationDegrees = from.elevationDegrees +
                (to.elevationDegrees - from.elevationDegrees) * t,
            distance = (d0 * exp(ln(d1 / d0) * t)).let { if (it.isFinite()) it else d1 },
        )
    }

    /**
     * The pose a [view] of [focusTarget] takes, at [fitDistance] × the view's own multiple.
     *
     * [awayFrom] keeps a flight going the way the user would expect: the preset's azimuth is
     * absolute, but the camera reaches it by the shortest arc from where it already is, so a
     * "Front" tapped from behind the subject swings round the near side.
     */
    fun poseFor(
        view: CameraView,
        focusTarget: Position,
        fitDistance: Float,
        awayFrom: Float = 0f,
    ): OrbitPose = OrbitPose(
        target = focusTarget,
        azimuthDegrees = awayFrom + shortestDelta(awayFrom, view.azimuthDegrees),
        elevationDegrees = view.elevationDegrees,
        distance = fitDistance * view.distanceScale,
    )

    /** Keeps [pose] inside the demo's framing limits, given the focus's [fitDistance]. */
    fun clamp(pose: OrbitPose, fitDistance: Float): OrbitPose {
        val fit = fitDistance.takeIf { it.isFinite() && it > 1e-3f } ?: 1f
        val x = pose.target.x.coerceIn(-MAX_PAN_RADIUS, MAX_PAN_RADIUS)
        val z = pose.target.z.coerceIn(-MAX_PAN_RADIUS, MAX_PAN_RADIUS)
        return pose.copy(
            target = Position(x, pose.target.y.coerceIn(MIN_TARGET_Y, MAX_TARGET_Y), z),
            elevationDegrees = pose.elevationDegrees
                .coerceIn(MIN_ELEVATION_DEGREES, MAX_ELEVATION_DEGREES),
            distance = pose.distance
                .coerceIn(fit * MIN_DISTANCE_SCALE, fit * MAX_DISTANCE_SCALE),
        )
    }

    /**
     * Metres of world movement one screen pixel is worth at [distance], for a camera with
     * [verticalFovDegrees] on a [viewportHeightPx]-tall viewport.
     *
     * This is what makes a two-finger pan feel like dragging the *scene* rather than nudging a
     * value: the point under the fingers stays under the fingers, at any zoom level, because the
     * conversion is derived from the projection instead of tuned as a constant.
     */
    fun worldPerPixel(
        distance: Float,
        verticalFovDegrees: Double,
        viewportHeightPx: Int,
    ): Float {
        if (viewportHeightPx <= 0 || !distance.isFinite() || distance <= 0f) return 0f
        val halfFov = Math.toRadians(verticalFovDegrees / 2.0)
        val worldHeight = 2.0 * distance * kotlin.math.tan(halfFov)
        val perPixel = worldHeight / viewportHeightPx
        return if (perPixel.isFinite()) perPixel.toFloat() else 0f
    }

    /**
     * Applies one second's worth of [INERTIA_DECAY_PER_SECOND] to [velocity] over [deltaSeconds],
     * returning `0` once the coast has fallen under [INERTIA_STOP_DEGREES_PER_SECOND].
     */
    fun decayInertia(velocity: Float, deltaSeconds: Float): Float {
        if (!velocity.isFinite() || deltaSeconds <= 0f) return 0f
        val decayed = velocity * exp(ln(INERTIA_DECAY_PER_SECOND) * deltaSeconds)
        return if (abs(decayed) < INERTIA_STOP_DEGREES_PER_SECOND) 0f else decayed
    }
}
