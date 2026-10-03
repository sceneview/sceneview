package io.github.sceneview.demo.demos

/**
 * Buckets a 0..1 ML Kit confidence into a stable percentage step so the label-bitmap cache
 * key changes only every [step] percent. Without bucketing, every sub-percent confidence
 * jitter between detector passes would invalidate the cache and re-rasterise the bitmap.
 */
internal fun confidenceBucketPercent(confidence: Float, step: Int = 5): Int {
    val pct = (confidence.coerceIn(0f, 1f) * 100f).toInt()
    return (pct / step) * step
}

/**
 * The confidence a label should show, given the one it already shows ([shownPercent], `null`
 * for a new label).
 *
 * Bucketing alone still flips the text whenever the confidence hovers around a bucket edge
 * (79 % / 80 % / 79 % …), and a new text is a new bitmap, which rebuilds the label node: the
 * label blinks several times per second. The shown bucket therefore holds until the confidence
 * has left it by more than half a [step]; only a real change redraws the label.
 */
internal fun stickyConfidencePercent(shownPercent: Int?, confidence: Float, step: Int = 5): Int {
    val bucket = confidenceBucketPercent(confidence, step)
    if (shownPercent == null) return bucket
    val percent = confidence.coerceIn(0f, 1f) * 100f
    val margin = step / 2f
    val holds = percent >= shownPercent - margin && percent < shownPercent + step + margin
    return if (holds) shownPercent else bucket
}

/** What a hit-test result lies on, reduced to what label placement decides on. */
internal enum class LabelSurface {
    /** A point of the depth map: the visible surface itself, object included. */
    Depth,

    /** A plane hit inside the detected polygon — not the infinite extension ARCore also reports. */
    PlaneInsidePolygon,

    /** A tracked feature point near the ray. */
    FeaturePoint,

    /** Anything else: a plane outside its polygon, a subsumed plane, a trackable not tracking. */
    Other,
}

/** One hit-test result along a label ray. */
internal data class LabelHitCandidate(val surface: LabelSurface, val distanceMeters: Float)

/** Which of the two rays cast per detection a hit belongs to. */
internal enum class LabelRay {
    /** Through the centre of the bounding box. */
    Centre,

    /** Through the bottom-centre of the bounding box, where the object rests on its support. */
    Base,
}

/** The hit a label is anchored to: [index] in the hit list of [ray]. */
internal data class LabelHitChoice(val ray: LabelRay, val index: Int)

/**
 * Picks the surface a label is anchored on.
 *
 * The ray through the centre of a bounding box crosses the object, but planes are the floor,
 * the table and the walls: the first *plane* that ray meets is the one **behind** the object.
 * A label anchored there looks right from the detection viewpoint only and slides off the
 * object as soon as the phone moves sideways. So, in order:
 *
 *  1. the depth map along the centre ray — the object's own surface;
 *  2. a plane, inside its polygon, along the base ray — the support the object stands on,
 *     directly under it;
 *  3. the depth map along the base ray;
 *  4. a feature point along the centre ray, then along the base ray.
 *
 * A plane along the centre ray is never used, and neither is a plane hit outside its polygon:
 * no label is better than a label on the wrong surface. Within a step the nearest hit wins,
 * whatever order ARCore lists them in; hits outside [USABLE_HIT_RANGE_METERS] are ignored.
 */
internal fun chooseLabelHit(
    centreHits: List<LabelHitCandidate>,
    baseHits: List<LabelHitCandidate>,
): LabelHitChoice? {
    fun nearest(ray: LabelRay, surface: LabelSurface): LabelHitChoice? {
        val hits = if (ray == LabelRay.Centre) centreHits else baseHits
        return hits.withIndex()
            .filter { (_, hit) ->
                hit.surface == surface && hit.distanceMeters in USABLE_HIT_RANGE_METERS
            }
            .minByOrNull { (_, hit) -> hit.distanceMeters }
            ?.let { LabelHitChoice(ray, it.index) }
    }
    return nearest(LabelRay.Centre, LabelSurface.Depth)
        ?: nearest(LabelRay.Base, LabelSurface.PlaneInsidePolygon)
        ?: nearest(LabelRay.Base, LabelSurface.Depth)
        ?: nearest(LabelRay.Centre, LabelSurface.FeaturePoint)
        ?: nearest(LabelRay.Base, LabelSurface.FeaturePoint)
}

/** Closer is inside the phone's minimum focus distance, farther is beyond useful tracking. */
internal val USABLE_HIT_RANGE_METERS = 0.1f..5.0f

/**
 * `true` when a fresh hit lands within [toleranceMeters] of the anchor a label already uses.
 *
 * Hit-tests of a still object jitter by a few centimetres between detector passes. Re-anchoring
 * on every pass would rebuild the label node several times per second and make it shimmer, so a
 * label keeps its anchor until the object has really moved.
 */
internal fun isWithinReanchorTolerance(
    anchorTranslation: FloatArray,
    hitTranslation: FloatArray,
    toleranceMeters: Float = 0.05f,
): Boolean {
    val dx = hitTranslation[0] - anchorTranslation[0]
    val dy = hitTranslation[1] - anchorTranslation[1]
    val dz = hitTranslation[2] - anchorTranslation[2]
    return dx * dx + dy * dy + dz * dz <= toleranceMeters * toleranceMeters
}
