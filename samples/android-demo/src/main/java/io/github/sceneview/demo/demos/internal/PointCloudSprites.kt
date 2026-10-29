package io.github.sceneview.demo.demos.internal

import kotlin.math.sqrt

/**
 * Turns a [FeaturePointAccumulator] into camera-facing sprites for the Point Cloud demo (#4624).
 *
 * `POINTS` primitives draw one pixel wide on mobile GPUs — invisible on a phone. Each point is
 * instead a four-vertex quad in the demo's `cosmos_dust.mat` layout (alpha-blended, the vertex
 * stage pushes the corners along the camera's right/up axes):
 *
 * - position — float3, the point (all four corners carry it),
 * - color    — float4, linear rgb and the opacity at the centre,
 * - custom0  — float4 (corner.x, corner.y, radius in metres, unused).
 *
 * The radius is chosen per point so the dot keeps a constant size **on screen**:
 * `radiusMetres = radiusPx × viewDepth / focalPx`. It is recomputed on every rebuild, so a point
 * stays the same size as the user walks towards or away from it, including off the view axis.
 *
 * Colour carries meaning (DESIGN.md "Only the present glows"): a point ARCore saw this instant
 * is [Palette.fresh]; as it ages it settles into the confidence ramp [Palette.low] →
 * [Palette.high], and its opacity eases from 1 down to [HISTORY_ALPHA] so the history reads
 * behind what the camera sees now.
 */
internal object PointCloudSprites {

    /** Floats per sprite vertex: position (3), colour (4), custom0 (4). */
    const val STRIDE: Int = 11

    /** Vertices and indices per point. */
    const val VERTICES_PER_POINT: Int = 4
    const val INDICES_PER_POINT: Int = 6

    /** How long a point stays "seen now" before it starts fading into the history ramp. */
    const val FRESH_MS: Long = 400L

    /** How long the fade from the fresh colour into the history ramp takes. */
    const val SETTLE_MS: Long = 1_200L

    /** Opacity the history settles to: present but behind the live points. */
    const val HISTORY_ALPHA: Float = 0.9f

    /** Extra size of a fresh point, so the area being scanned visibly sparkles. */
    const val FRESH_SIZE_BOOST: Float = 0.25f

    /** Linear-space rgb triplets of the ramp. */
    class Palette(val low: FloatArray, val high: FloatArray, val fresh: FloatArray) {
        init {
            require(low.size == 3 && high.size == 3 && fresh.size == 3) { "rgb triplets expected" }
        }
    }

    /** Index pattern for [maxPoints] quads: 0,1,2, 0,2,3 per point. */
    fun indices(maxPoints: Int): IntArray {
        val out = IntArray(maxPoints * INDICES_PER_POINT)
        for (p in 0 until maxPoints) {
            val v = p * VERTICES_PER_POINT
            val o = p * INDICES_PER_POINT
            out[o] = v
            out[o + 1] = v + 1
            out[o + 2] = v + 2
            out[o + 3] = v
            out[o + 4] = v + 2
            out[o + 5] = v + 3
        }
        return out
    }

    /** `0` for a point just seen, `1` once it has fully settled into the history ramp. */
    fun settle(ageMs: Long): Float = when {
        ageMs <= FRESH_MS -> 0f
        ageMs >= FRESH_MS + SETTLE_MS -> 1f
        else -> (ageMs - FRESH_MS).toFloat() / SETTLE_MS
    }

    /**
     * Writes one sprite per point of [cloud] whose confidence is at least [minConfidence] into
     * [out] and returns the number of points written. Points closer than [MIN_DISTANCE_M] to the
     * camera are skipped (they would fill the screen).
     *
     * @param camX camera position, world space.
     * @param camForwardX camera forward unit vector, world space.
     * @param focalPx vertical focal length in pixels (`projection[1][1] × viewportHeight / 2`).
     * @param radiusPx on-screen radius of a fully confident, settled point.
     * @param out at least `cloud.size × VERTICES_PER_POINT × STRIDE` floats.
     */
    @Suppress("LongParameterList")
    fun write(
        cloud: FeaturePointAccumulator,
        minConfidence: Float,
        nowMs: Long,
        camX: Float,
        camY: Float,
        camZ: Float,
        camForwardX: Float,
        camForwardY: Float,
        camForwardZ: Float,
        focalPx: Float,
        radiusPx: Float,
        palette: Palette,
        out: FloatArray,
    ): Int {
        require(focalPx > 0f) { "focalPx must be positive" }
        var written = 0
        for (i in 0 until cloud.size) {
            val confidence = cloud.confidence(i)
            val x = cloud.x(i)
            val y = cloud.y(i)
            val z = cloud.z(i)
            val dx = x - camX
            val dy = y - camY
            val dz = z - camZ
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            if (confidence < minConfidence || distance < MIN_DISTANCE_M) continue

            val s = settle(nowMs - cloud.lastSeenMs(i))
            // Confidence ramp, then the fresh colour on top of it until the point settles.
            val r = lerp(lerp(palette.low[0], palette.high[0], confidence), palette.fresh[0], 1f - s)
            val g = lerp(lerp(palette.low[1], palette.high[1], confidence), palette.fresh[1], 1f - s)
            val b = lerp(lerp(palette.low[2], palette.high[2], confidence), palette.fresh[2], 1f - s)
            val a = lerp(1f, HISTORY_ALPHA, s)
            // Low-confidence points draw a little smaller, fresh ones a little bigger.
            val sizeFactor = (MIN_SIZE_FACTOR + (1f - MIN_SIZE_FACTOR) * confidence) *
                (1f + FRESH_SIZE_BOOST * (1f - s))
            val viewDepth = (dx * camForwardX + dy * camForwardY + dz * camForwardZ)
                .coerceAtLeast(MIN_VIEW_DEPTH_M)
            val radius = radiusPx * sizeFactor * viewDepth / focalPx

            var o = written * VERTICES_PER_POINT * STRIDE
            for (c in 0 until VERTICES_PER_POINT) {
                out[o] = x
                out[o + 1] = y
                out[o + 2] = z
                out[o + 3] = r
                out[o + 4] = g
                out[o + 5] = b
                out[o + 6] = a
                out[o + 7] = CORNER_X[c]
                out[o + 8] = CORNER_Y[c]
                out[o + 9] = radius
                out[o + 10] = 0f
                o += STRIDE
            }
            written++
        }
        return written
    }

    /** sRGB-encoded channel in `[0, 1]` to linear light, for vertex colours. */
    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private const val MIN_DISTANCE_M = 0.05f
    private const val MIN_VIEW_DEPTH_M = 0.001f
    private const val MIN_SIZE_FACTOR = 0.8f
    private val CORNER_X = floatArrayOf(-1f, 1f, 1f, -1f)
    private val CORNER_Y = floatArrayOf(-1f, -1f, 1f, 1f)
}
