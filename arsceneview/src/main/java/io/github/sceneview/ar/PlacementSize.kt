package io.github.sceneview.ar

import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.math.toQuaternion
import kotlin.math.max
import kotlin.math.min

/**
 * How big [AutoPlacementModel] makes the model at 100 % — the size the pinch's detent, the
 * double-tap and [AutoPlacementState.baseSize] refer to.
 */
sealed interface PlacementSize {

    /**
     * The asset's authored size: glTF is in metres, so a 1.8 m figure stands 1.8 m tall and a
     * sofa takes the room it would take. Nothing is rescaled. Pair it with
     * [AutoPlacementState.allowsScaling] `= false` for objects that must stay at real size, or
     * read [AutoPlacementState.isAtRealWorldSize] to tell the user when they are.
     */
    data object RealWorld : PlacementSize

    /**
     * Scale the model uniformly so its longest authored axis measures [meters] — a preview for
     * assets whose units are unknown or not metres. The fit is on the asset's own axes, before
     * `assetRotation`: [AutoPlacementState.baseSize] reports the axis-aligned box after it, so
     * its largest side equals [meters] only when the rotation is a multiple of 90° (a 1 m cube
     * turned 45° about Y measures about 1.41 × 1 × 1.41 m).
     */
    data class FitInto(val meters: Float) : PlacementSize {
        init {
            require(meters > 0f && meters.isFinite()) { "FitInto needs a positive, finite size, got $meters" }
        }
    }
}

/** The `ModelNode(scaleToUnits = …)` value for this size: `null` keeps authored units. */
internal val PlacementSize.scaleToUnits: Float?
    get() = when (this) {
        PlacementSize.RealWorld -> null
        is PlacementSize.FitInto -> meters
    }

/**
 * Width, height and depth, in metres, of an authored box with [halfExtent] once uniformly
 * scaled by [scale] and turned by [assetRotation] — the axis-aligned box of the model's 100 %
 * size as the user sees it standing on the floor, before any pinch. `null` for an asset with no
 * extent at all (an empty box) or unusable bounds: there is no size to report.
 */
internal fun placementBaseSize(halfExtent: Size, scale: Scale, assetRotation: Rotation): Size? {
    val q = assetRotation.toQuaternion()
    var lo = Position(Float.POSITIVE_INFINITY)
    var hi = Position(Float.NEGATIVE_INFINITY)
    for (x in SIGNS) for (y in SIGNS) for (z in SIGNS) {
        val p = q * (Position(x * halfExtent.x, y * halfExtent.y, z * halfExtent.z) * scale)
        lo = Position(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
        hi = Position(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
    }
    val size = hi - lo
    val usable = size.x.isFinite() && size.y.isFinite() && size.z.isFinite() &&
        (size.x > 0f || size.y > 0f || size.z > 0f)
    return size.takeIf { usable }
}

private val SIGNS = floatArrayOf(-1f, 1f)
