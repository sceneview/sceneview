package io.github.sceneview.ar.depth

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length

/**
 * Kotlin mirror of the occlusion shaping in `camera_stream_depth.mat` and
 * `camera_stream_person_occlusion.mat` (`occluderDistance`), so its thresholds are tested on the
 * JVM. The materials are the source of truth at runtime; `DepthOcclusionShapingTest` fails if
 * a constant here and its GLSL twin drift apart.
 *
 * For a real surface measured at `depth` metres, the camera quad writes a distance into the
 * z-buffer; a virtual fragment farther than that is hidden. `threshold` is the shader's stable
 * per-pixel value in `[0, 1)` that dithers the far fade; the tolerance itself is a hard limit.
 *
 * Rule 4, contact grounding: where the pixel's ray meets a placed object's contact plane within
 * its contact disc, measured depth up to [GROUND_BAND] nearer than that plane counts as the plane.
 * The floor's depth noise right under an object no longer cuts its contact shadow or its
 * placement ring, and a real object in front still occludes once it is nearer than the plane by
 * [GROUND_BAND] plus the [tolerance].
 */
internal object DepthOcclusionShaping {
    const val TOL_BASE = 0.03f
    const val TOL_LINEAR = 0.02f
    const val TOL_QUAD = 0.01f
    const val FAR_FADE_START = 5f
    const val FAR_FADE_END = 8f
    const val MAX_VALID_DEPTH = 60f
    const val NO_DEPTH_DISTANCE = 1000f

    /** Rule 4: how much nearer than the contact plane measured depth may be and still count as it. */
    const val GROUND_BAND = 0.10f

    /** Rule 4: beyond the contact radius the band fades to zero over this fraction of it. */
    const val GROUND_FADE = 0.5f

    /** How much nearer than a virtual surface the real one must be to hide it, in metres. */
    fun tolerance(depth: Float): Float = TOL_BASE + depth * (TOL_LINEAR + TOL_QUAD * depth)

    /**
     * Rule 4's band, in metres, for a pixel whose ray meets the contact plane [distance] metres
     * from the contact centre, the contact disc being [radius] metres: [GROUND_BAND] inside the
     * disc, fading to `0` at `(1 + GROUND_FADE) × radius`.
     */
    fun groundBand(distance: Float, radius: Float): Float {
        val r = maxOf(radius, MIN_GROUND_RADIUS)
        return GROUND_BAND * (1f - ((distance - r) / (r * GROUND_FADE)).coerceIn(0f, 1f))
    }

    /**
     * The shader's `groundSurface` for the view-space [ray] (any point on the pixel's ray from
     * the camera at the origin): `x` = the distance along the view axis to the contact plane
     * through [contact] facing [normal], `y` = its band — `0` when [enabled] is `0` or the ray
     * meets the plane behind the camera.
     */
    fun groundSurface(ray: Float3, contact: Float3, normal: Float3, radius: Float, enabled: Float): Float2 {
        val facing = dot(normal, ray)
        val along = dot(normal, contact) / (if (kotlin.math.abs(facing) > 1e-6f) facing else 1e-6f)
        val hit = ray * along
        val band = groundBand(length(hit - contact), radius) * enabled * (if (along >= 0f) 1f else 0f)
        return Float2(-hit.z, band)
    }

    /**
     * The distance written for a texel at [depth] metres (`0` or > 60 m = no depth), the
     * contact plane being [ground] metres away along the same ray with a rule 4 band of [band]
     * metres (`0`, the default, outside every contact disc).
     */
    fun occluderDistance(depth: Float, threshold: Float, ground: Float = depth, band: Float = 0f): Float {
        val valid = depth > MIN_VALID_DEPTH && depth <= MAX_VALID_DEPTH
        val inRange = threshold >= smoothstep(FAR_FADE_START, FAR_FADE_END, depth)
        return if (valid && inRange) {
            val surface = maxOf(depth, minOf(ground, depth + band))
            surface + tolerance(depth)
        } else {
            NO_DEPTH_DISTANCE
        }
    }

    /** Whether a virtual fragment at [virtualDepth] metres is hidden by a texel at [depth]. */
    fun hides(
        depth: Float,
        virtualDepth: Float,
        threshold: Float,
        ground: Float = depth,
        band: Float = 0f,
    ): Boolean = virtualDepth > occluderDistance(depth, threshold, ground, band)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val MIN_VALID_DEPTH = 0.001f
    private const val MIN_GROUND_RADIUS = 1e-4f
}
