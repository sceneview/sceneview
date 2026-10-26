package io.github.sceneview.node

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import kotlin.math.max
import kotlin.math.min

/**
 * World-space axis-aligned bounds of the authored box ([center], [halfExtent]) once its eight
 * corners are moved by [transform]. Returns `min` to `max`.
 *
 * Pure math behind [ModelNode.worldBoundingBox], kept out of the node so it is testable on
 * the JVM.
 */
internal fun worldAxisAlignedBounds(
    center: Float3,
    halfExtent: Float3,
    transform: Mat4,
): Pair<Float3, Float3> {
    var lo = Float3(Float.POSITIVE_INFINITY)
    var hi = Float3(Float.NEGATIVE_INFINITY)
    for (x in CORNER_SIGNS) for (y in CORNER_SIGNS) for (z in CORNER_SIGNS) {
        val corner = transform * Float4(
            center.x + x * halfExtent.x,
            center.y + y * halfExtent.y,
            center.z + z * halfExtent.z,
            1f,
        )
        lo = Float3(min(lo.x, corner.x), min(lo.y, corner.y), min(lo.z, corner.z))
        hi = Float3(max(hi.x, corner.x), max(hi.y, corner.y), max(hi.z, corner.z))
    }
    return lo to hi
}

/** The authored box's full extents multiplied by the node's [worldScale]: width, height, depth. */
internal fun modelWorldSize(halfExtent: Float3, worldScale: Float3): Float3 =
    halfExtent * 2f * worldScale

private val CORNER_SIGNS = floatArrayOf(-1f, 1f)
