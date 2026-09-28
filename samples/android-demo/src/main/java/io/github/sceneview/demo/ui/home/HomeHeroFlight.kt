package io.github.sceneview.demo.ui.home

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
    /** The fox running down the valley floor — the subject the scroll glides onto. */
    val foxX: Float,
    val foxY: Float,
    val foxZ: Float,
    /** The eased glide actually applied, 0 = the flight's own gaze, 1 = framed on the fox. */
    val glide: Float,
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

/** Where the fox runs, relative to the flight's sway line and the camera plane. */
internal const val HERO_FOX_OFFSET_X = -0.55f
internal const val HERO_FOX_Z = -3.4f

/**
 * Where the camera sits relative to the fox once the glide lands: level with it, on its
 * left — the side the low sun rakes in from — so it reads lit, in profile, running
 * across the frame with the right-hand ridges behind it. From behind it was a dark
 * silhouette against the sunset.
 */
private const val GLIDE_EYE_DX = -3.0f
private const val GLIDE_EYE_DY = 0.6f
private const val GLIDE_EYE_DZ = 0f

/**
 * Where the glide aims, relative to the fox's feet. Above its middle, so the fox lands
 * low in the frame — where the band's visible window has moved to by then — and ahead
 * of it along the run, which puts the fox right of centre, clear of the band's
 * left-aligned title, subtitle and "Open" pill.
 */
private const val GLIDE_AIM_DY = 0.4f
private const val GLIDE_AIM_DZ = -0.25f

/**
 * The flight at [seconds] since the stage first rendered.
 *
 * The camera has exactly two inputs, time and [glide], and no memory: the pose is
 * recomputed from scratch every frame, so nothing can drift and scrolling back to the
 * top always lands on the very gaze the flight would have had without the scroll.
 *
 * @param period         Terrain period, so the strip offset wraps where the tiling does.
 * @param glide          How far the page has scrolled the band away, 0 → 1. The camera
 *                       glides from the flight's gaze (and the helmet) onto the fox running
 *                       on the valley floor. Ignored under reduced motion.
 * @param entranceStart  Flight time when the helmet became textured, or null while it has
 *                       not — until then the helmet is scaled away, whatever [motion] says.
 * @param terrainStart   Flight time of the terrain's first frame, or null before it: the
 *                       valley rises into place from below over [HERO_TERRAIN_RISE_SECONDS].
 * @param motion         False under reduced motion: the flight holds its opening frame and
 *                       the helmet, once textured, is simply there, no entrance to play.
 * @param intro          False once the opening has played in this process: coming back to
 *                       the screen shows the helmet and the valley in place instead of
 *                       zooming the helmet in again (#3993).
 */
internal fun heroFlightPose(
    seconds: Double,
    period: Float,
    glide: Float = 0f,
    entranceStart: Double? = null,
    terrainStart: Double? = null,
    motion: Boolean = true,
    intro: Boolean = true,
): HeroFlightPose {
    val t = if (motion) seconds.toFloat() else 0f
    val instant = !motion || !intro
    val sway = (2f * PI.toFloat() / HERO_SWAY_PERIOD)
    val eyeX = sin(t * sway) * 0.7f
    val eyeY = HERO_EYE_HEIGHT + sin(t * 0.37f) * 0.08f
    // Bank into the turn: the roll is the sway's derivative, scaled to a few degrees.
    val roll = -cos(t * sway) * 2.6f
    val yaw = sin(t * sway) * 0.03f
    val pitch = -0.055f
    val entrance = when {
        entranceStart == null -> 0f
        instant -> 1f
        else -> easeOutCubic(
            ((seconds - entranceStart) / HERO_ENTRANCE_SECONDS).toFloat().coerceIn(0f, 1f),
        )
    }
    val rise = when {
        terrainStart == null -> 0f
        instant -> 1f
        else -> easeOutCubic(
            ((seconds - terrainStart) / HERO_TERRAIN_RISE_SECONDS).toFloat().coerceIn(0f, 1f),
        )
    }
    val terrainOffsetZ = ((t * HERO_FLIGHT_SPEED) % period + period) % period
    // The fox rides the flight's sway line like the helmet does, but on the ground: its
    // feet follow the valley floor as the strip slides under it.
    val foxX = eyeX * 0.6f + HERO_FOX_OFFSET_X
    val foxY = heroTerrainHeight(foxX, HERO_FOX_Z - terrainOffsetZ, period) -
        HERO_TERRAIN_RISE_UNITS * (1f - rise)
    val flightTargetX = eyeX + yaw * 10f
    val flightTargetY = eyeY + pitch * 10f
    val flightTargetZ = -10f
    val g = if (motion) smoothstep01(glide) else 0f
    return HeroFlightPose(
        terrainOffsetZ = terrainOffsetZ,
        eyeX = lerp(eyeX, foxX + GLIDE_EYE_DX, g),
        eyeY = lerp(eyeY, foxY + GLIDE_EYE_DY, g),
        eyeZ = lerp(0f, HERO_FOX_Z + GLIDE_EYE_DZ, g),
        targetX = lerp(flightTargetX, foxX, g),
        targetY = lerp(flightTargetY, foxY + GLIDE_AIM_DY, g),
        targetZ = lerp(flightTargetZ, HERO_FOX_Z + GLIDE_AIM_DZ, g),
        // The glide levels the wings: the landing frame is steady, not banked.
        rollDegrees = roll * (1f - g),
        // The helmet rides with the flight, front-right, and bobs on its own beat — it
        // does not follow the glide, which is how the camera leaves it behind.
        helmetX = eyeX + 0.85f,
        helmetY = eyeY - 0.2f + sin(t * 0.8f + 1f) * 0.05f,
        helmetZ = -4.2f,
        helmetYawDegrees = -28f + t * HERO_HELMET_DEGREES_PER_SECOND,
        helmetEntrance = entrance,
        terrainRise = rise,
        foxX = foxX,
        foxY = foxY,
        foxZ = HERO_FOX_Z,
        glide = g,
    )
}

/** How far below the valley the terrain starts, rising into place on its first frame. */
internal const val HERO_TERRAIN_RISE_UNITS = 2.5f

/**
 * The flight time and whether the opening has played, kept for the process.
 *
 * Navigation disposes the home screen — and with it the engine, the model and the
 * clock — every time a demo opens or another tab shows. Without this, every return
 * restarted the flight at zero and zoomed the helmet in again (#3993). Process death
 * resets it, so a cold start still gets its opening.
 */
internal object HeroFlightMemory {
    var seconds: Double = 0.0
    var introPlayed: Boolean = false
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun smoothstep01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOutCubic(x: Float): Float {
    val inv = 1f - x
    return 1f - inv * inv * inv
}
