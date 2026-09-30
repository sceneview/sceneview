package io.github.sceneview.demo.demos.soundgarden

import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.audio.AudioFalloff
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The listener's head for one frame: where it is, which way it faces and where its right ear
 * points. [forward] and [right] are unit vectors **on the horizontal plane** — a head does not
 * roll its ears when the phone tilts, so neither may the mix.
 */
data class ListenerFrame(
    val position: Float3,
    val forward: Float3,
    val right: Float3,
)

/**
 * How one voice must sound this frame, in the terms the mixer consumes directly: a gain and a
 * delay per ear, and the cutoff of the low-pass that darkens a source behind the head.
 *
 * [delayLeftSec] / [delayRightSec] carry the interaural time difference — only the ear
 * further from the source is ever delayed, so at most one of the two is non-zero.
 */
data class VoiceParams(
    val gainLeft: Float,
    val gainRight: Float,
    val delayLeftSec: Float,
    val delayRightSec: Float,
    val cutoffHz: Float,
) {
    companion object {
        /** A voice that has not been placed yet: silent, centred, open filter. */
        val SILENT = VoiceParams(0f, 0f, 0f, 0f, SpatialVoiceMath.FRONT_CUTOFF_HZ)
    }
}

/**
 * The binaural cues of the Sound Garden, as plain functions of two poses — no Android, no audio
 * device, so every number here is unit-tested on the JVM.
 *
 * Four cues, each the cheapest one that carries its information on a phone and a pair of
 * earbuds — the cues a listener actually uses while walking between objects, at a cost a
 * Pixel 4a does not notice, and with no native library in the APK:
 *
 *  - **Level** — equal-power pan from the source's lateral angle, softened by [PAN_WIDTH] so a
 *    source at 90° still leaks into the far ear the way it does around a real head.
 *  - **Time** — interaural time difference from Woodworth's spherical-head formula, at most
 *    [MAX_ITD_SEC] (≈ 0.66 ms). This is what makes left/right sound *placed* rather than
 *    *panned* on headphones.
 *  - **Front / back** — a source behind the head loses its highs (one-pole low-pass from
 *    [FRONT_CUTOFF_HZ] down to [REAR_CUTOFF_HZ]) and 3 dB. Pure panning cannot tell front from
 *    back at all; this is the cue that makes "turn around" audible.
 *  - **Distance** — the SDK's own [AudioFalloff] law, the same curve `SpatialAudioNode` uses on
 *    Android, iOS and Web.
 */
object SpatialVoiceMath {

    /** Average adult head radius (m) — Woodworth's model, the value every ITD table uses. */
    const val HEAD_RADIUS_M = 0.0875f

    /** Speed of sound in air at 20 °C (m/s). */
    const val SPEED_OF_SOUND_M_S = 343f

    /** Largest interaural delay, for a source at exactly 90°: r / c · (π/2 + 1) ≈ 0.656 ms. */
    val MAX_ITD_SEC: Float = HEAD_RADIUS_M / SPEED_OF_SOUND_M_S * (PI.toFloat() / 2f + 1f)

    /** Low-pass cutoff for a source straight ahead — above what the stems contain, i.e. open. */
    const val FRONT_CUTOFF_HZ = 18_000f

    /** Low-pass cutoff for a source straight behind — muffled, still clearly the same part. */
    const val REAR_CUTOFF_HZ = 2_000f

    /** Gain for a source straight behind: −3 dB, the pinna's shadow. */
    const val REAR_GAIN = 0.708f

    /**
     * How far the level pan swings (0 = mono, 1 = hard pan). At 0.8 a source at 90° lands at
     * ≈ −16 dB in the far ear — about what a head does to a mid-range sound — instead of
     * vanishing from it, which on headphones sounds like a broken earbud, not like a direction.
     */
    const val PAN_WIDTH = 0.8f

    /**
     * Builds the levelled listener frame from the camera pose.
     *
     * The facing direction is **not** the camera's forward projected flat: when the phone points
     * at the floor that projection shrinks to nothing and its direction becomes noise, so the
     * mix would spin while the user stands still. Writing the camera's pitch as p,
     * `forward = cos p · F − sin p · Y` and `up = sin p · F + cos p · Y` for the facing
     * direction F; their horizontal parts are `cos p · F` and `sin p · F`, so
     * `cos p · forwardₕ + sin p · upₕ = F` exactly, at every pitch — straight down included,
     * where the top edge of the screen is what points where the user faces.
     *
     * @param cameraForward the direction the camera looks (its −Z axis), unit length.
     * @param cameraUp      the display's up direction (the camera's +Y axis), unit length.
     */
    fun listenerFrame(cameraPosition: Float3, cameraForward: Float3, cameraUp: Float3): ListenerFrame {
        val sinPitch = -cameraForward.y
        val cosPitch = sqrt((1f - sinPitch * sinPitch).coerceAtLeast(0f))
        var fx = cameraForward.x * cosPitch + cameraUp.x * sinPitch
        var fz = cameraForward.z * cosPitch + cameraUp.z * sinPitch
        val length = sqrt(fx * fx + fz * fz)
        if (length < 1e-4f) {
            // Only reachable with a degenerate (non-orthonormal) pose — face −Z like ARCore's origin.
            fx = 0f
            fz = -1f
        } else {
            fx /= length
            fz /= length
        }
        val forward = Float3(fx, 0f, fz)
        // right = forward × worldUp — for forward = −Z that is +X, the ARCore convention.
        val right = Float3(-fz, 0f, fx)
        return ListenerFrame(cameraPosition, forward, right)
    }

    /**
     * Equal-power pan for a lateral position in −1 (left) … +1 (right), after [PAN_WIDTH].
     * Returns `(left, right)` with `left² + right² = 1` everywhere.
     */
    fun panGains(lateral: Float): Pair<Float, Float> {
        val p = (lateral.coerceIn(-1f, 1f) * PAN_WIDTH + 1f) * (PI.toFloat() / 4f)
        return kotlin.math.cos(p) to kotlin.math.sin(p)
    }

    /**
     * Woodworth's interaural time difference for a lateral position in −1 … +1 (the sine of
     * the azimuth). Positive means the source is on the right, so the **left** ear lags.
     */
    fun interauralDelaySec(lateral: Float): Float {
        val theta = asin(lateral.coerceIn(-1f, 1f))
        return HEAD_RADIUS_M / SPEED_OF_SOUND_M_S * (theta + kotlin.math.sin(theta))
    }

    /**
     * Rear low-pass cutoff for a front/back position in −1 (behind) … +1 (ahead). Everything in
     * the front half stays open; behind, the cutoff slides geometrically (so each step sounds
     * like the same amount of darkening) down to [REAR_CUTOFF_HZ].
     */
    fun rearCutoffHz(frontness: Float): Float {
        val behind = (-frontness).coerceIn(0f, 1f)
        return FRONT_CUTOFF_HZ * (REAR_CUTOFF_HZ / FRONT_CUTOFF_HZ).pow(behind)
    }

    /** Rear attenuation for a front/back position in −1 … +1: 1 in front, [REAR_GAIN] behind. */
    fun rearGain(frontness: Float): Float {
        val behind = (-frontness).coerceIn(0f, 1f)
        return 1f - (1f - REAR_GAIN) * behind
    }

    /**
     * Everything the mixer needs for one source heard by [listener].
     *
     * @param level 0 … 1 on top of the spatial gains — the bloom fade-in and the tap-to-mute.
     */
    fun voice(
        listener: ListenerFrame,
        source: Float3,
        falloff: AudioFalloff,
        level: Float = 1f,
    ): VoiceParams {
        val dx = source.x - listener.position.x
        val dy = source.y - listener.position.y
        val dz = source.z - listener.position.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        val loudness = AudioFalloff.gainFor(falloff, distance) * level.coerceIn(0f, 1f)
        if (distance < 1e-3f) {
            // Inside the head: no direction to speak of — centred, open, full level.
            val centre = sqrt(0.5f) * loudness
            return VoiceParams(centre, centre, 0f, 0f, FRONT_CUTOFF_HZ)
        }
        val lateral = (dx * listener.right.x + dy * listener.right.y + dz * listener.right.z) / distance
        val frontness =
            (dx * listener.forward.x + dy * listener.forward.y + dz * listener.forward.z) / distance
        val (panLeft, panRight) = panGains(lateral)
        val gain = loudness * rearGain(frontness)
        val itd = interauralDelaySec(lateral)
        return VoiceParams(
            gainLeft = panLeft * gain,
            gainRight = panRight * gain,
            delayLeftSec = if (itd > 0f) itd else 0f,
            delayRightSec = if (itd < 0f) -itd else 0f,
            cutoffHz = rearCutoffHz(frontness),
        )
    }

}
