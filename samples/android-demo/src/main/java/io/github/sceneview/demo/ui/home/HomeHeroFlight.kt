package io.github.sceneview.demo.ui.home

import io.github.sceneview.utils.GlRenderer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The home hero's flight, as pure functions of time (#3948).
 *
 * Everything the stage writes to Filament per frame comes out of [heroFlightPose]:
 * where the terrain strip has slid to, where the camera is and how it banks, where the
 * helmet rides and how far its entrance has played. The stage owns a [HeroClock] that
 * only advances on presented frames, so a scroll, a page swipe, a background trip or a
 * parked loop leave `seconds` exactly where they were — coming back resumes the flight
 * from the same frame, which is the contract #3949 asks for.
 *
 * See `HomeHeroFlightTest`.
 */

/** Rendering tier. Picked once from `ActivityManager.isLowRamDevice`. */
internal data class HeroTier(
    val terrain: HeroTerrainSpec,
    /** HDR IBL, bloom + lens flare, fog and TAA — everything the Performance preset drops. */
    val cinematic: Boolean,
) {
    companion object {
        fun forDevice(lowRam: Boolean): HeroTier =
            if (lowRam) HeroTier(HeroTerrainSpec.Light, cinematic = false)
            else HeroTier(HeroTerrainSpec.Full, cinematic = true)
    }
}

/**
 * What fills the hero stage: the flight, or its picture.
 *
 * Decided once, from the GL implementation, *before* an engine exists. Filament reports a
 * shader program it cannot link as a panic that aborts the process, so on a renderer the
 * flight does not survive, the only safe handling is not to start it (#4411).
 */
internal enum class HeroSurface {
    /** The flight, rendered by SceneView. */
    Live,

    /** One frame of the flight, as a bundled picture: no engine is created. */
    Still;

    companion object {
        /**
         * [Still] on the legacy guest SwiftShader ([GlRenderer.isLegacySwiftShader]), where
         * the app aborted in Filament a second after launch on every start, and when
         * [renderer] is null — no OpenGL ES 3 context at all, so no engine either. [Live]
         * everywhere else, hardware or not: the emulator's own SwiftShader modes fly it.
         */
        fun forRenderer(renderer: GlRenderer?): HeroSurface =
            if (renderer == null || renderer.isLegacySwiftShader) Still else Live
    }
}

/**
 * A clock that only ticks while frames are presented and motion is wanted.
 *
 * [frame] returns the flight time to draw. The first frame after a pause is given no
 * delta at all (`previousNanos` is null), and no single frame is ever handed more than
 * [MAX_FRAME_SECONDS]: a stall in the compositor must not teleport the terrain.
 */
internal class HeroClock(initialSeconds: Double = 0.0) {
    var seconds: Double = initialSeconds
        private set
    private var previousNanos: Long? = null

    fun pause() {
        previousNanos = null
    }

    fun frame(nanos: Long, moving: Boolean): Double {
        if (!moving) {
            pause()
            return seconds
        }
        previousNanos?.let { previous ->
            seconds += ((nanos - previous) / 1_000_000_000.0).coerceIn(0.0, MAX_FRAME_SECONDS)
        }
        previousNanos = nanos
        return seconds
    }

    companion object {
        const val MAX_FRAME_SECONDS = 0.1
    }
}

/** One frame of the flight. Positions are world units, angles degrees. */
internal data class HeroFlightPose(
    /** Z offset of the terrain strip, in `[0, period)`. */
    val terrainOffsetZ: Float,
    val eyeX: Float,
    val eyeY: Float,
    val eyeZ: Float,
    /** Where the camera looks. */
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float,
    /** Bank, positive = right wing down. */
    val rollDegrees: Float,
    val helmetX: Float,
    val helmetY: Float,
    val helmetZ: Float,
    val helmetYawDegrees: Float,
    /** 0 → 1 over [HERO_ENTRANCE_SECONDS] from the helmet's first textured frame. */
    val helmetEntrance: Float,
    /** 0 → 1 over [HERO_TERRAIN_RISE_SECONDS] from the terrain's first frame. */
    val terrainRise: Float,
)

/** Forward speed of the flight, world units per second. */
internal const val HERO_FLIGHT_SPEED = 5.5f

/** Sway period of the camera's lazy S-curve, seconds. */
internal const val HERO_SWAY_PERIOD = 27f

internal const val HERO_HELMET_DEGREES_PER_SECOND = 9f

internal const val HERO_ENTRANCE_SECONDS = 0.9f

internal const val HERO_TERRAIN_RISE_SECONDS = 1.1f

/** Cruise altitude, world units above the valley floor at height ≈ −0.35. */
internal const val HERO_EYE_HEIGHT = 2.3f

/**
 * The flight at [seconds] since the stage first rendered.
 *
 * @param period         Terrain period, so the strip offset wraps where the tiling does.
 * @param tiltX          Smoothed device tilt, −1 → 1, left → right; steers the gaze.
 * @param tiltY          Smoothed device tilt, −1 → 1, back → forward; pitches the gaze.
 * @param entranceStart  Flight time when the helmet became textured, or null while it has
 *                       not — until then the helmet is scaled away, whatever [motion] says.
 * @param terrainStart   Flight time of the terrain's first frame, or null before it: the
 *                       valley rises into place from below over [HERO_TERRAIN_RISE_SECONDS].
 * @param motion         False under reduced motion: the flight holds its opening frame and
 *                       the helmet, once textured, is simply there, no entrance to play.
 */
internal fun heroFlightPose(
    seconds: Double,
    period: Float,
    tiltX: Float = 0f,
    tiltY: Float = 0f,
    entranceStart: Double? = null,
    terrainStart: Double? = null,
    motion: Boolean = true,
): HeroFlightPose {
    val t = if (motion) seconds.toFloat() else 0f
    val sway = (2f * PI.toFloat() / HERO_SWAY_PERIOD)
    val eyeX = sin(t * sway) * 0.7f
    val eyeY = HERO_EYE_HEIGHT + sin(t * 0.37f) * 0.08f
    // Bank into the turn: the roll is the sway's derivative, scaled to a few degrees.
    val roll = -cos(t * sway) * 2.6f + tiltX * 1.5f
    val yaw = tiltX * 0.09f + sin(t * sway) * 0.03f
    val pitch = -0.055f + tiltY * 0.04f
    val entrance = when {
        entranceStart == null -> 0f
        !motion -> 1f
        else -> easeOutCubic(
            ((seconds - entranceStart) / HERO_ENTRANCE_SECONDS).toFloat().coerceIn(0f, 1f),
        )
    }
    val rise = when {
        terrainStart == null -> 0f
        !motion -> 1f
        else -> easeOutCubic(
            ((seconds - terrainStart) / HERO_TERRAIN_RISE_SECONDS).toFloat().coerceIn(0f, 1f),
        )
    }
    return HeroFlightPose(
        terrainOffsetZ = ((t * HERO_FLIGHT_SPEED) % period + period) % period,
        eyeX = eyeX,
        eyeY = eyeY,
        eyeZ = 0f,
        targetX = eyeX + yaw * 10f,
        targetY = eyeY + pitch * 10f,
        targetZ = -10f,
        rollDegrees = roll,
        // The helmet rides with the camera, front-right, and bobs on its own beat.
        helmetX = eyeX + 0.85f,
        helmetY = eyeY - 0.2f + sin(t * 0.8f + 1f) * 0.05f,
        helmetZ = -4.2f,
        helmetYawDegrees = -28f + t * HERO_HELMET_DEGREES_PER_SECOND,
        helmetEntrance = entrance,
        terrainRise = rise,
    )
}

private fun easeOutCubic(x: Float): Float {
    val inv = 1f - x
    return 1f - inv * inv * inv
}

/**
 * Device tilt, smoothed and re-centred.
 *
 * Raw gravity is fed by the sensor at whatever cadence it likes; [update] runs once per
 * presented frame with the elapsed time. Two low-pass filters: a quick one (τ ≈ 0.15 s)
 * that follows the hand, and a slow one (τ ≈ 4 s) that learns the resting posture. The
 * output is their difference, so a phone held at any angle settles at zero and only a
 * *change* of tilt steers the gaze — nobody reads the home screen holding it flat.
 */
internal class HeroTilt(
    private val fastTau: Float = 0.15f,
    private val slowTau: Float = 4f,
    private val gain: Float = 0.25f,
) {
    private var rawX = 0f
    private var rawY = 0f
    private var fastX = 0f
    private var fastY = 0f
    private var slowX = 0f
    private var slowY = 0f
    private var primed = false

    /** −1 → 1, left → right. */
    var x: Float = 0f
        private set

    /** −1 → 1, back → forward. */
    var y: Float = 0f
        private set

    /**
     * Gravity vector from `Sensor.TYPE_GRAVITY`, any thread; only the latest is kept. The
     * component along the phone's long axis (Y) carries no lean, so it is not needed.
     */
    fun feed(gravityX: Float, gravityZ: Float) {
        // In portrait, gravity along +X means the phone leans left, along +Z it lies flat.
        rawX = (-gravityX / EARTH_GRAVITY).coerceIn(-1f, 1f)
        rawY = (gravityZ / EARTH_GRAVITY).coerceIn(-1f, 1f)
    }

    fun update(deltaSeconds: Float) {
        if (!primed) {
            fastX = rawX; fastY = rawY; slowX = rawX; slowY = rawY
            primed = true
        }
        val dt = deltaSeconds.coerceIn(0f, 0.25f)
        val kf = 1f - kotlin.math.exp(-dt / fastTau)
        val ks = 1f - kotlin.math.exp(-dt / slowTau)
        fastX += (rawX - fastX) * kf
        fastY += (rawY - fastY) * kf
        slowX += (fastX - slowX) * ks
        slowY += (fastY - slowY) * ks
        x = ((fastX - slowX) * gain / SETTLE_RANGE).coerceIn(-1f, 1f)
        y = ((fastY - slowY) * gain / SETTLE_RANGE).coerceIn(-1f, 1f)
    }

    fun reset() {
        primed = false
        x = 0f
        y = 0f
    }

    private companion object {
        const val EARTH_GRAVITY = 9.80665f
        /** Tilt (as a fraction of g) that maps to full deflection, before [gain]. */
        const val SETTLE_RANGE = 0.12f
    }
}
