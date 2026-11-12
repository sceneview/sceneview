package io.github.sceneview.demo.demos.internal.measure

import io.github.sceneview.demo.demos.internal.spatial.DimensionSource
import io.github.sceneview.demo.demos.internal.spatial.SpatialAnswerSpec
import io.github.sceneview.demo.demos.internal.spatial.SpatialObject
import io.github.sceneview.demo.demos.internal.spatial.SpatialPlacement
import kotlinx.serialization.json.*

object MeasureBoxJson {
    fun encode(box: ObjectBox, uncertainty: DimensionHalfWidths): String {
        require(listOf(box.lengthM, box.widthM, box.heightM, uncertainty.lengthCm, uncertainty.widthCm, uncertainty.heightCm)
            .all { it.isFinite() && it > 0 })
        fun dimension(m: Double, cm: Double) = buildJsonObject {
            put("value", m * 1000)
            put("u95", JsonNull)
            put("halfWidthEstimate", cm * 10)
        }
        return buildJsonObject {
            put("schema", "sceneview.measure/1")
            put("units", "mm")
            putJsonObject("dimensions") {
                put("L", dimension(box.lengthM, uncertainty.lengthCm))
                put("W", dimension(box.widthM, uncertainty.widthCm))
                put("H", dimension(box.heightM, uncertainty.heightCm))
            }
            putJsonObject("quality") {
                put("method", "plane-constrained-taps")
                put("uncertainty_status", "uncalibrated")
            }
        }.toString()
    }
}

/** No floor evidence means TABLE. A distinct lower support implies a tabletop. */
fun objectPlacement(supportY: Double, lowestPlaneY: Double?): SpatialPlacement =
    if (lowestPlaneY != null && supportY - lowestPlaneY <= 0.15) SpatialPlacement.FLOOR else SpatialPlacement.TABLE

fun ObjectBox.toSpatialAnswer(placement: SpatialPlacement, label: String) = SpatialAnswerSpec(
    v = 1,
    subject = SpatialObject.Box(listOf(lengthM.toFloat(), heightM.toFloat(), widthM.toFloat())),
    dimensionSource = DimensionSource.MEASURED,
    placement = placement,
    label = label,
)
