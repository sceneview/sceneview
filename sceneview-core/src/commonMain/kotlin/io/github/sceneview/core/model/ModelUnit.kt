package io.github.sceneview.core.model

import io.github.sceneview.core.threemf.ThreeMfUnit

/** Units for unitless meshes. 3MF keeps its declared unit; glTF/GLB always use metres. */
enum class ModelUnit(val meters: Float) {
    Meters(1f), Centimeters(0.01f), Millimeters(0.001f), Inches(0.0254f);

    internal fun toThreeMfUnit(): ThreeMfUnit = when (this) {
        Meters -> ThreeMfUnit.METER
        Centimeters -> ThreeMfUnit.CENTIMETER
        Millimeters -> ThreeMfUnit.MILLIMETER
        Inches -> ThreeMfUnit.INCH
    }
}
