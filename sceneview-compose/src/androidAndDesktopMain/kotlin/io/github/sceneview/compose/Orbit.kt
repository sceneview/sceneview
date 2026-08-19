package io.github.sceneview.compose

import dev.romainguy.kotlin.math.Float3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val ORBIT_DEGREES_PER_PIXEL = 0.3f

private const val DEG_TO_RAD = (PI / 180.0).toFloat()

/**
 * Eye position for a spherical orbit around [target].
 *
 * [azimuthDegrees] is yaw about +Y, [elevationDegrees] pitch about the local X,
 * both in degrees. Matches the mapping [CameraState] documents.
 */
internal fun orbitEyePosition(
    target: Float3,
    distance: Float,
    azimuthDegrees: Float,
    elevationDegrees: Float,
): Float3 {
    val azimuthRad = azimuthDegrees * DEG_TO_RAD
    val elevationRad = elevationDegrees * DEG_TO_RAD
    val horizontal = distance * cos(elevationRad)
    return Float3(
        x = target.x + horizontal * sin(azimuthRad),
        y = target.y + distance * sin(elevationRad),
        z = target.z + horizontal * cos(azimuthRad),
    )
}
