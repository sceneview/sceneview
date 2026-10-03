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

/** Returns the nearest valid camera-to-surface hit, independent of ARCore list ordering. */
internal fun nearestUsableHitIndex(distancesMeters: List<Float>): Int? = distancesMeters
    .withIndex()
    .filter { (_, distance) -> distance in 0.1f..5.0f }
    .minByOrNull { (_, distance) -> distance }
    ?.index

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
