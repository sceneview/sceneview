package io.github.sceneview

import com.google.android.filament.Camera
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Camera-feel preset for the cinematic turntable — a slow, eased orbit around auto-framed content
 * that mimics the "hero shot" of product photography and animation cinema.
 *
 * The maths driving a profile live in [cinematicAzimuth], [cinematicElevation],
 * [cinematicCameraEye] and [cinematicDistance] — all pure functions, so a profile can be unit
 * tested and previewed without a renderer. Three ready-made profiles are provided as companion
 * constants; build a custom one by copying and tweaking.
 *
 * @property secondsPerRevolution Time for one full 360° orbit at cruising speed.
 * @property elevationDegrees     Base camera elevation above the horizon. Positive looks slightly
 *                                *down* at the subject — the "presented" product look.
 * @property elevationBobDegrees  Amplitude of a gentle sinusoidal rise/fall added to the
 *                                elevation; `0` keeps the orbit perfectly flat.
 * @property bobSecondsPerCycle   Period of the elevation bob.
 * @property fovDegrees           Vertical field of view. Narrower (a longer lens) flattens
 *                                perspective distortion — the flattering cinematic look.
 * @property easeInSeconds        Ramp-up time: azimuth speed accelerates linearly from rest to
 *                                cruising speed over this window, then stays constant. `0`
 *                                starts at full speed.
 * @property frameMargin          Multiplier on the fitted camera distance — `1.0` frames the
 *                                content edge-to-edge, `>1` leaves breathing room around it.
 */
data class CinematicCameraProfile(
    val secondsPerRevolution: Float,
    val elevationDegrees: Float,
    val elevationBobDegrees: Float,
    val bobSecondsPerCycle: Float,
    val fovDegrees: Float,
    val easeInSeconds: Float,
    val frameMargin: Float
) {
    companion object {
        /** Reveal-grade orbit: brisk, slight downward tilt, long-lens look. */
        val HeroProduct = CinematicCameraProfile(
            secondsPerRevolution = 10f,
            elevationDegrees = 18f,
            elevationBobDegrees = 0f,
            bobSecondsPerCycle = 8f,
            fovDegrees = 30f,
            easeInSeconds = 1.2f,
            frameMargin = 1.25f
        )

        /** Contemplative orbit: slow, very long lens, a soft vertical bob for life. */
        val SlowCinematic = CinematicCameraProfile(
            secondsPerRevolution = 18f,
            elevationDegrees = 17f,
            elevationBobDegrees = 5f,
            bobSecondsPerCycle = 12f,
            fovDegrees = 28f,
            easeInSeconds = 1.5f,
            frameMargin = 1.33f
        )

        /** Sober web-viewer spin: very slow, near eye-level, standard lens. */
        val NeutralWeb = CinematicCameraProfile(
            secondsPerRevolution = 24f,
            elevationDegrees = 10f,
            elevationBobDegrees = 0f,
            bobSecondsPerCycle = 8f,
            fovDegrees = 45f,
            easeInSeconds = 0f,
            frameMargin = 1.4f
        )

        /**
         * The recommended default — [SlowCinematic]. A slow, contemplative orbit with a long
         * lens and a soft vertical bob reads as the most "cinematic" of the presets, so it is
         * what [applyCinematicOrbit] uses unless a profile is passed explicitly.
         */
        val Default = SlowCinematic
    }
}

/**
 * Azimuth angle, in radians, the orbit has reached after [timeSeconds].
 *
 * Speed ramps linearly from rest to the cruising rate (`2π / secondsPerRevolution`) over
 * [CinematicCameraProfile.easeInSeconds], then stays constant — so the orbit starts gently
 * instead of snapping into motion. The result grows without bound; callers needing a wrapped
 * angle can take it modulo `2π`.
 */
fun cinematicAzimuth(timeSeconds: Float, profile: CinematicCameraProfile): Float {
    val t = timeSeconds.coerceAtLeast(0f)
    val cruiseSpeed = (2.0 * PI / profile.secondsPerRevolution).toFloat() // rad/s
    val ease = profile.easeInSeconds
    return if (ease > 0f && t < ease) {
        // Area under a 0→cruiseSpeed ramp: ½·cruiseSpeed·t²/ease.
        cruiseSpeed * t * t / (2f * ease)
    } else if (ease > 0f) {
        // Constant cruise, offset by the half-revolution "lost" during the ramp.
        cruiseSpeed * (t - ease / 2f)
    } else {
        cruiseSpeed * t
    }
}

/**
 * Camera elevation above the horizon, in radians, at [timeSeconds] — the base
 * [CinematicCameraProfile.elevationDegrees] plus the optional sinusoidal bob.
 */
fun cinematicElevation(timeSeconds: Float, profile: CinematicCameraProfile): Float {
    val bob = if (profile.elevationBobDegrees != 0f && profile.bobSecondsPerCycle > 0f) {
        profile.elevationBobDegrees *
            sin(2.0 * PI * timeSeconds / profile.bobSecondsPerCycle).toFloat()
    } else {
        0f
    }
    // Clamp short of the poles: at ±90° the orbit basis degenerates (cos → 0) and the camera
    // would tip over the subject. The built-in profiles stay well inside this; the clamp only
    // guards a custom profile whose elevation + bob would otherwise cross a pole.
    val degrees = (profile.elevationDegrees + bob).coerceIn(-89f, 89f)
    return degrees * (PI / 180.0).toFloat()
}

/**
 * World-space camera eye position at [timeSeconds] for an orbit of radius [distance] around the
 * origin — the point auto-centred content sits at (see `SceneView(autoCenterContent = …)`).
 *
 * Pair with [cinematicDistance] to derive [distance] from the content size, and aim the camera at
 * `Position(0f, 0f, 0f)` each frame.
 */
fun cinematicCameraEye(
    timeSeconds: Float,
    profile: CinematicCameraProfile,
    distance: Float
): Position {
    val azimuth = cinematicAzimuth(timeSeconds, profile)
    val elevation = cinematicElevation(timeSeconds, profile)
    val horizontal = distance * cos(elevation)
    return Position(
        x = horizontal * sin(azimuth),
        y = distance * sin(elevation),
        z = horizontal * cos(azimuth)
    )
}

/**
 * Orbit radius that frames a subject of the given [contentRadius] (bounding-sphere radius, in
 * metres) within the profile's vertical field of view, with [CinematicCameraProfile.frameMargin]
 * breathing room.
 *
 * `distance = contentRadius / tan(fov / 2) · frameMargin` — the standard "fit a sphere to the
 * frustum" relation. A model whose largest extent is `n` metres has a bounding-sphere radius of
 * roughly `n / 2`, which is a sound estimate for [contentRadius].
 */
fun cinematicDistance(contentRadius: Float, profile: CinematicCameraProfile): Float {
    val halfFovRad = (profile.fovDegrees / 2.0 * PI / 180.0)
    return (contentRadius / tan(halfFovRad)).toFloat() * profile.frameMargin
}

/**
 * Drives [cameraNode] for one frame of the cinematic turntable: positions the camera on the
 * eased orbit at [timeSeconds], aims it at the origin, and applies the profile's field of view.
 *
 * Call this every frame from `SceneView(onFrame = …)` with `cameraManipulator = null` so the
 * turntable owns the camera, and `autoCenterContent = true` so the content centroid sits on the
 * origin the orbit looks at. Pass the subject's bounding-sphere radius as [contentRadius] (the
 * default `0.5f` suits a model about 1 m across). [contentRadius] is a bounding-sphere radius, so
 * the framing is independent of the orbit elevation; [CinematicCameraProfile.frameMargin] absorbs
 * the slack for non-spherical subjects.
 *
 * **Threading:** writes Filament camera state — call it on the main thread (the `SceneView`
 * frame loop already satisfies this).
 *
 * @param cameraNode    the active [CameraNode] (pass it explicitly to `SceneView`).
 * @param timeSeconds   elapsed time since the orbit started.
 * @param profile       the camera feel — defaults to [CinematicCameraProfile.Default].
 * @param contentRadius bounding-sphere radius of the subject, in metres.
 */
fun applyCinematicOrbit(
    cameraNode: CameraNode,
    timeSeconds: Float,
    profile: CinematicCameraProfile = CinematicCameraProfile.Default,
    contentRadius: Float = 0.5f
) {
    val distance = cinematicDistance(contentRadius, profile)
    cameraNode.position = cinematicCameraEye(timeSeconds, profile, distance)
    cameraNode.lookAt(Position(0f, 0f, 0f), smooth = false)
    // Explicit VERTICAL axis: cinematicDistance frames against the vertical FOV, so the
    // projection must use the same axis regardless of viewport aspect.
    cameraNode.setProjection(profile.fovDegrees.toDouble(), direction = Camera.Fov.VERTICAL)
}
