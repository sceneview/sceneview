package io.github.sceneview.math

/**
 * Computes the `centerOrigin` translation: the offset that moves the point of a model's
 * bounding box selected by [origin] (normalized AABB coordinates, `-1..1` per axis, `0` = AABB
 * center) onto the node's local origin.
 *
 * The anchor point in unscaled model space is `center + origin * halfExtent`; negating it and
 * applying the node [scale] yields the parent-space translation that cancels it out. Using the
 * AABB [center] (not just the extents) makes the alignment correct for assets whose bounding
 * box is not authored centered on their pivot.
 *
 * **Single cross-platform source of truth (issue #2763).** Both `sceneview` (Android,
 * `ModelNode.centerOrigin`) and `sceneview-web` (Kotlin/JS, `ModelNode.centerOrigin`) call this
 * exact function — since `sceneview-core` compiles to both the `android` (JVM) and `js` KMP
 * targets, the two platforms share one code path and cannot numerically diverge. The
 * `centerOriginGoldenVectors` table in `sceneview-core`'s `commonTest` pins this formula on both
 * targets from a single Kotlin source. `SceneViewSwift` (RealityKit) has no dependency on this
 * KMP module and reimplements the same math natively in `ModelNode.centerOriginTranslation(
 * center:extents:origin:)` — its `ModelNodeTests.swift` duplicates the identical numeric table
 * so a divergence there is caught too.
 */
fun centerOriginTranslation(
    center: Position,
    halfExtent: Size,
    scale: Scale,
    origin: Position
): Position = -(center + origin * halfExtent) * scale
