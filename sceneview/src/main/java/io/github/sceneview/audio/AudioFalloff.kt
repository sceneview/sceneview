package io.github.sceneview.audio

/**
 * Distance-attenuation model applied to a [SpatialAudioNode].
 *
 * The library evaluates the model on every frame against the current camera-to-source distance
 * and feeds the resulting linear gain into the audio backend (Android `Spatializer` /
 * `AudioTrack`, or the `MediaPlayer` fallback's `setVolume(left, right)`).
 *
 * The three variants mirror the standard Web Audio `PannerNode.distanceModel` settings — see the
 * Web Audio spec (https://webaudio.github.io/web-audio-api/#distance-effects) — so behaviour is
 * consistent across Android / iOS / Web.
 */
sealed interface AudioFalloff {

    /**
     * Inverse distance attenuation:
     *
     * `gain = refDistance / (refDistance + rolloffFactor * (clampedDistance - refDistance))`
     *
     * where `clampedDistance = min(distance, maxDistance)`. Below [refDistance] the gain is
     * `1.0`; above [maxDistance] the gain is clamped at the value computed at [maxDistance].
     * This is the WebAudio `"inverse"` model — physically the most realistic of the three.
     *
     * @param refDistance   Distance (m) at which gain is `1.0`. Default `1f`.
     * @param maxDistance   Distance (m) beyond which the gain stops decreasing further.
     * @param rolloffFactor How quickly gain drops once past [refDistance]. `1f` matches the
     *                      physical inverse-square style curve; higher values fall off faster.
     */
    data class Inverse(
        val refDistance: Float = 1f,
        val maxDistance: Float = 100f,
        val rolloffFactor: Float = 1f
    ) : AudioFalloff

    /**
     * Linear distance attenuation:
     *
     * `gain = 1 - (clamp(distance, refDistance, maxDistance) - refDistance) / (maxDistance - refDistance)`
     *
     * Reaches `0.0` exactly at [maxDistance]. Equivalent to the WebAudio `"linear"` model.
     *
     * @param refDistance Distance (m) at which gain is `1.0`. Default `1f`.
     * @param maxDistance Distance (m) at which gain hits `0.0`. Default `100f`.
     */
    data class Linear(
        val refDistance: Float = 1f,
        val maxDistance: Float = 100f
    ) : AudioFalloff

    /**
     * Disable distance attenuation. Always returns `1.0` regardless of source-listener distance.
     * Useful for ambient or non-positional cues.
     */
    object None : AudioFalloff

    companion object {
        /**
         * Computes the linear gain (`0f..1f`) for [falloff] at the given source-listener
         * [distance] (meters). Internal helper used by every backend so the curves match
         * across Android Spatializer, MediaPlayer L/R pan, iOS PHASE, and Web PannerNode.
         */
        fun gainFor(falloff: AudioFalloff, distance: Float): Float {
            // Safety: NaN / negative distances become 0 — keeps the gain in `[0, 1]`
            // even if a malformed frame produces garbage coordinates (Float3 NaN
            // cascades through every quaternion / matrix op upstream and would
            // otherwise nuke the audio bus volume too).
            val d = if (distance.isFinite() && distance >= 0f) distance else 0f
            return when (falloff) {
                is None -> 1f
                is Inverse -> {
                    val clamped = minOf(d, falloff.maxDistance)
                    // Below refDistance everything is at unity — same convention as
                    // OpenAL / WebAudio inverse model.
                    if (clamped <= falloff.refDistance) 1f
                    else {
                        val ref = falloff.refDistance.coerceAtLeast(1e-4f)
                        val gain = ref / (ref + falloff.rolloffFactor *
                                (clamped - ref))
                        gain.coerceIn(0f, 1f)
                    }
                }
                is Linear -> {
                    val ref = falloff.refDistance
                    val max = falloff.maxDistance
                    if (max <= ref) {
                        // Degenerate range — fall back to step at refDistance so the
                        // demo doesn't divide by zero.
                        if (d < ref) 1f else 0f
                    } else {
                        val clamped = d.coerceIn(ref, max)
                        (1f - (clamped - ref) / (max - ref)).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }
}
