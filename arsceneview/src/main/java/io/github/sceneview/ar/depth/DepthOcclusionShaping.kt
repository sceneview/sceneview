package io.github.sceneview.ar.depth

/**
 * Kotlin mirror of the occlusion shaping in `camera_stream_depth.mat` and
 * `camera_stream_person_occlusion.mat` (`occluderDistance`), so its thresholds are tested on the
 * JVM. The materials are the source of truth at runtime; `DepthOcclusionShapingTest` fails if
 * a constant here and its GLSL twin drift apart.
 *
 * For a real surface measured at `depth` metres, the camera quad writes a distance into the
 * z-buffer; a virtual fragment farther than that is hidden. `threshold` is the shader's stable
 * per-pixel value in `[0, 1)` that turns each hard limit into a dithered ramp.
 */
internal object DepthOcclusionShaping {
    const val TOL_BASE = 0.03f
    const val TOL_LINEAR = 0.02f
    const val TOL_QUAD = 0.01f
    const val FAR_FADE_START = 5f
    const val FAR_FADE_END = 8f
    const val MAX_VALID_DEPTH = 60f
    const val NO_DEPTH_DISTANCE = 1000f

    /** How much nearer than a virtual surface the real one must be to hide it, in metres. */
    fun tolerance(depth: Float): Float = TOL_BASE + depth * (TOL_LINEAR + TOL_QUAD * depth)

    /** The distance written for a texel at [depth] metres (`0` or > 60 m = no depth). */
    fun occluderDistance(depth: Float, threshold: Float): Float {
        val valid = depth > MIN_VALID_DEPTH && depth <= MAX_VALID_DEPTH
        val inRange = threshold >= smoothstep(FAR_FADE_START, FAR_FADE_END, depth)
        return if (valid && inRange) {
            depth + tolerance(depth) * (HALF + threshold)
        } else {
            NO_DEPTH_DISTANCE
        }
    }

    /** Whether a virtual fragment at [virtualDepth] metres is hidden by a texel at [depth]. */
    fun hides(depth: Float, virtualDepth: Float, threshold: Float): Boolean =
        virtualDepth > occluderDistance(depth, threshold)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val MIN_VALID_DEPTH = 0.001f
    private const val HALF = 0.5f
}
