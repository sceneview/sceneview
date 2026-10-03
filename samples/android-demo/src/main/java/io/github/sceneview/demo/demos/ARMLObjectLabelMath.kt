package io.github.sceneview.demo.demos

import java.util.Locale

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

    /**
     * A horizontal, upward-facing plane hit inside its detected polygon — something an object
     * can stand on, not the infinite extension ARCore also reports.
     */
    SupportPlane,

    /** A tracked feature point near the ray. */
    FeaturePoint,

    /**
     * Anything else: a wall or a ceiling, a plane outside its polygon, a subsumed plane, a
     * trackable not tracking.
     */
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
 *  2. a support plane along the base ray — what the object stands on, directly under it —
 *     when it [is the object's own support][isOwnSupport];
 *  3. the depth map along the base ray;
 *  4. a feature point along the centre ray, then along the base ray.
 *
 * A plane along the centre ray is never used, and neither is a wall nor a plane hit outside
 * its polygon: no label is better than a label on the wrong surface. Within a step the nearest
 * hit wins, whatever order ARCore lists them in; hits outside [USABLE_HIT_RANGE_METERS] are
 * ignored.
 */
internal fun chooseLabelHit(
    centreHits: List<LabelHitCandidate>,
    baseHits: List<LabelHitCandidate>,
): LabelHitChoice? {
    fun nearest(ray: LabelRay, surface: LabelSurface): IndexedValue<LabelHitCandidate>? {
        val hits = if (ray == LabelRay.Centre) centreHits else baseHits
        return hits.withIndex()
            .filter { (_, hit) ->
                hit.surface == surface && hit.distanceMeters in USABLE_HIT_RANGE_METERS
            }
            .minByOrNull { (_, hit) -> hit.distanceMeters }
    }

    fun choice(ray: LabelRay, surface: LabelSurface): LabelHitChoice? =
        nearest(ray, surface)?.let { LabelHitChoice(ray, it.index) }

    // Where the scene itself was seen along either ray: a depth sample or a feature point.
    val sceneDistance = listOf(LabelRay.Centre, LabelRay.Base)
        .flatMap { ray -> listOf(LabelSurface.Depth, LabelSurface.FeaturePoint).map { ray to it } }
        .mapNotNull { (ray, surface) -> nearest(ray, surface)?.value?.distanceMeters }
        .minOrNull()
    val support = nearest(LabelRay.Base, LabelSurface.SupportPlane)
        ?.takeIf { isOwnSupport(it.value.distanceMeters, sceneDistance) }
        ?.let { LabelHitChoice(LabelRay.Base, it.index) }

    return choice(LabelRay.Centre, LabelSurface.Depth)
        ?: support
        ?: choice(LabelRay.Base, LabelSurface.Depth)
        ?: choice(LabelRay.Centre, LabelSurface.FeaturePoint)
        ?: choice(LabelRay.Base, LabelSurface.FeaturePoint)
}

/**
 * `true` when a support plane met at [planeDistanceMeters] along the base ray can be the one
 * the object stands on.
 *
 * The base ray only meets planes ARCore has already detected. With a mug on a table that is
 * not detected yet and a floor that is, the ray goes through the table and lands on the floor
 * one or two metres behind the mug. So the plane is checked against [sceneDistanceMeters], the
 * nearest depth sample or feature point along the two rays: a plane more than
 * [SUPPORT_BEHIND_MARGIN_METERS] beyond it is behind the object, not under it. With nothing to
 * check against (`null`), only a plane within [UNCORROBORATED_SUPPORT_MAX_METERS] is trusted —
 * table-top range, where a floor seen through an undetected table is still farther away.
 */
internal fun isOwnSupport(planeDistanceMeters: Float, sceneDistanceMeters: Float?): Boolean =
    if (sceneDistanceMeters != null) {
        planeDistanceMeters <= sceneDistanceMeters + SUPPORT_BEHIND_MARGIN_METERS
    } else {
        planeDistanceMeters <= UNCORROBORATED_SUPPORT_MAX_METERS
    }

/** Closer is inside the phone's minimum focus distance, farther is beyond useful tracking. */
internal val USABLE_HIT_RANGE_METERS = 0.1f..5.0f

/** How far behind the nearest scene point a support plane may be: about one object deep. */
internal const val SUPPORT_BEHIND_MARGIN_METERS = 0.3f

/** The farthest a support plane is trusted with no depth sample or feature point to check it. */
internal const val UNCORROBORATED_SUPPORT_MAX_METERS = 1.0f

/**
 * One line per detection and per pass for the device check: what each ray met, and the hit the
 * label was anchored on. It answers the question the JVM tests cannot: whether the ray variant
 * of `Frame.hitTest` returns depth points at all.
 */
internal fun labelHitReport(
    label: String,
    centreHits: List<LabelHitCandidate>,
    baseHits: List<LabelHitCandidate>,
    choice: LabelHitChoice?,
): String {
    fun LabelHitCandidate.describe() =
        String.format(Locale.US, "%s %.2f m", surface.name, distanceMeters)

    fun List<LabelHitCandidate>.describe() =
        if (isEmpty()) "none" else joinToString(", ") { it.describe() }

    val chosen = choice?.let {
        val hit = (if (it.ray == LabelRay.Centre) centreHits else baseHits)[it.index]
        "${it.ray.name} ${hit.describe()}"
    } ?: "no label"
    return "hitTest \"$label\": centre ray [${centreHits.describe()}], " +
        "base ray [${baseHits.describe()}] -> $chosen"
}

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
