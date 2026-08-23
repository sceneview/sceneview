package io.github.sceneview.web

/**
 * Pure, renderer-agnostic bounding-box math for the library-level
 * `autoCenterContent` feature (issue #1052 — web port of iOS #1026).
 *
 * Kept free of any Filament.js binding so the centring math can be unit-tested
 * on the JVM-free `jsTest` Karma target without a WebGL context.
 */
internal object ContentCentering {

    /** Smallest mesh extent (in metres) the auto-centre pass accepts as
     * "content has loaded enough to be centred". Below this the bounds are
     * assumed to come from an async load that hasn't populated yet, so the
     * pass is deferred to the next frame. Mirrors iOS `minVisualExtent`. */
    const val MIN_VISUAL_EXTENT: Double = 0.001

    /** An axis-aligned bounding box. `min`/`max` are `[x, y, z]`. */
    data class Aabb(val min: DoubleArray, val max: DoubleArray) {
        override fun equals(other: Any?): Boolean =
            other is Aabb && min.contentEquals(other.min) && max.contentEquals(other.max)

        override fun hashCode(): Int = 31 * min.contentHashCode() + max.contentHashCode()
    }

    /**
     * Uniformly scale [box] by [factor] about the asset-space origin — the
     * point a root-entity uniform scale also pivots around. For a positive
     * [factor], `min*factor` stays the minimum and `max*factor` stays the
     * maximum, so the result is still a well-formed AABB.
     *
     * Used to convert a model's asset-space (unscaled) `getBoundingBox()` into
     * the box the geometry actually renders at once the consumer applied
     * `model { scale(factor) }` (#2432). Feeding the *scaled* box to
     * [centeringOffset] / [fitToBounds] keeps a scaled model centred and
     * correctly framed — the unscaled box would leave it off-centre by
     * `(factor-1)·centroid` and mis-dollied.
     *
     * [factor] `== 1.0` returns an equal box (the common no-scale path);
     * a non-positive [factor] is clamped to a degenerate (zero-extent) box so
     * the auto-centre stability gate simply defers, rather than producing an
     * inverted min/max.
     */
    fun scale(box: Aabb, factor: Double): Aabb {
        if (factor == 1.0) return box
        val f = if (factor > 0.0) factor else 0.0
        return Aabb(
            doubleArrayOf(box.min[0] * f, box.min[1] * f, box.min[2] * f),
            doubleArrayOf(box.max[0] * f, box.max[1] * f, box.max[2] * f),
        )
    }

    /**
     * Compute the union of [boxes]. Returns `null` when the list is empty —
     * there is nothing to centre yet.
     */
    fun union(boxes: List<Aabb>): Aabb? {
        if (boxes.isEmpty()) return null
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (b in boxes) {
            if (b.min[0] < minX) minX = b.min[0]
            if (b.min[1] < minY) minY = b.min[1]
            if (b.min[2] < minZ) minZ = b.min[2]
            if (b.max[0] > maxX) maxX = b.max[0]
            if (b.max[1] > maxY) maxY = b.max[1]
            if (b.max[2] > maxZ) maxZ = b.max[2]
        }
        return Aabb(doubleArrayOf(minX, minY, minZ), doubleArrayOf(maxX, maxY, maxZ))
    }

    /** Geometric centroid `(min + max) / 2` of [box]. */
    fun center(box: Aabb): DoubleArray = doubleArrayOf(
        (box.min[0] + box.max[0]) / 2.0,
        (box.min[1] + box.max[1]) / 2.0,
        (box.min[2] + box.max[2]) / 2.0,
    )

    /** Per-axis extents `(max - min)` of [box], `[ex, ey, ez]`. */
    fun extents(box: Aabb): DoubleArray = doubleArrayOf(
        box.max[0] - box.min[0],
        box.max[1] - box.min[1],
        box.max[2] - box.min[2],
    )

    /**
     * Length of [box]'s space diagonal — the single scalar the auto-centre
     * gate uses to detect "the union grew" when an async model lands and to
     * decide when the framing has settled. Mirrors iOS `simd_length(extents)`.
     * Returns `0.0` for a `null` box (nothing loaded yet).
     */
    fun diagonal(box: Aabb?): Double {
        if (box == null) return 0.0
        val e = extents(box)
        return kotlin.math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2])
    }

    /**
     * Default orbit distance, in bounding-sphere radii, that `fitToModels()`
     * dollies to: `2.5 × radius`, the value the fit has always used.
     */
    const val FIT_DISTANCE_RADII: Double = 2.5

    /** Lower bound of the `fitToModels(margin)` multiplier — same floor as iOS `framingMargin`. */
    const val MIN_FIT_MARGIN: Double = 0.2

    /** Upper bound of the `fitToModels(margin)` multiplier — same ceiling as iOS `framingMargin`. */
    const val MAX_FIT_MARGIN: Double = 10.0

    /**
     * The orbit distance `fitToModels(margin)` dollies to for a content
     * bounding sphere of [radius]: the default `2.5 × radius` scaled by
     * [margin], a *multiplier* in the iOS `framingMargin` convention —
     * `1.0` keeps the historical fit, `< 1` frames tighter, `> 1` leaves more
     * air (#2946). [margin] is clamped to `[MIN_FIT_MARGIN, MAX_FIT_MARGIN]`
     * like iOS; a non-finite margin falls back to `1.0`. Returns `0.0` for a
     * non-positive radius so callers can skip a degenerate fit.
     */
    fun fitDistance(radius: Double, margin: Double = 1.0): Double {
        if (!(radius > 0.0)) return 0.0
        val m = if (margin.isFinite()) margin.coerceIn(MIN_FIT_MARGIN, MAX_FIT_MARGIN) else 1.0
        return radius * FIT_DISTANCE_RADII * m
    }

    /**
     * Whether [box]'s extents are finite and large enough to be considered
     * loaded content worth centring.
     *
     * An empty / degenerate box (e.g. before async resources finish loading)
     * has non-finite or zero extents — both are rejected so the centring pass
     * is deferred to a later frame. Mirrors iOS `refreshContentCentering`.
     */
    fun isStable(box: Aabb): Boolean {
        val ex = box.max[0] - box.min[0]
        val ey = box.max[1] - box.min[1]
        val ez = box.max[2] - box.min[2]
        if (!ex.isFinite() || !ey.isFinite() || !ez.isFinite()) return false
        val extentMax = maxOf(ex, ey, ez)
        return extentMax > MIN_VISUAL_EXTENT
    }

    /**
     * The translation that, applied to content currently centred at the
     * centroid of [box], moves that centroid to the world origin. Equal to
     * `-center(box)`. Returns `null` when [box] is not yet stable.
     */
    fun centeringOffset(box: Aabb?): DoubleArray? {
        if (box == null || !isStable(box)) return null
        val c = center(box)
        // `+ 0.0` normalises a negated zero (`-0.0`) back to `0.0`: an
        // already-centred axis must report a plain `0.0` translation, not the
        // signed-zero that `-(0.0)` produces.
        return doubleArrayOf(-c[0] + 0.0, -c[1] + 0.0, -c[2] + 0.0)
    }
}
