package io.github.sceneview.demo.demos.internal.measure

import kotlin.math.ceil

enum class ObjectHitType { PLANE, DEPTH, FEATURE }
data class ObjectTap(val point: BoxPoint, val hitType: ObjectHitType, val cameraDistanceM: Double)
data class DimensionHalfWidths(val lengthCm: Double, val widthCm: Double, val heightCm: Double) {
    val displayEachCm: Int get() = ceil(maxOf(lengthCm, widthCm, heightCm)).toInt()
}

/** Heuristic half-width, NOT a confidence interval or a calibrated accuracy claim. */
object MeasureUncertainty {
    fun estimate(type: ObjectHitType, cameraDistanceM: Double, baseCm: Double = 1.0): Double {
        require(cameraDistanceM.isFinite() && cameraDistanceM >= 0 && baseCm.isFinite() && baseCm >= 1.0)
        val distancePenalty = when {
            cameraDistanceM < 0.5 -> (0.5 - cameraDistanceM) * 8
            cameraDistanceM > 0.6 -> (cameraDistanceM - 0.6) * 4
            else -> 0.0
        }
        val sourcePenalty = when (type) { ObjectHitType.PLANE -> 0.0; ObjectHitType.DEPTH -> 0.6; ObjectHitType.FEATURE -> 1.2 }
        return ceil(baseCm + distancePenalty + sourcePenalty)
    }

    /** Include the support in every dimension; use the worst contributing tap conservatively. */
    fun estimate(support: ObjectTap, first: ObjectTap, second: ObjectTap, across: ObjectTap, top: ObjectTap): DimensionHalfWidths {
        fun worst(vararg taps: ObjectTap) = taps.maxOf { estimate(it.hitType, it.cameraDistanceM) }
        return DimensionHalfWidths(worst(support, first, second), worst(support, first, second, across), worst(support, top))
    }
}
