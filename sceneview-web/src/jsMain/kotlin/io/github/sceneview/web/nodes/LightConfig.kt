package io.github.sceneview.web.nodes

/**
 * Light configuration for SceneView web.
 *
 * ```kotlin
 * light {
 *     directional()
 *     intensity(10_000.0)
 *     color(1.0f, 1.0f, 1.0f)
 *     direction(0.0f, -1.0f, -0.5f)
 * }
 * ```
 */
class LightConfig {
    var type = LightType.DIRECTIONAL; private set
    // Default key-light intensity, in lux — mirrors Android's
    // DEFAULT_MAIN_LIGHT_INTENSITY (10_000). Read under the physically-based
    // default camera exposure; a "full noon sun" 100_000 over-exposes.
    var intensity = 10_000.0; private set
    var colorR = 1.0f; private set
    var colorG = 1.0f; private set
    var colorB = 1.0f; private set
    var directionX = 0.0f; private set
    var directionY = -1.0f; private set
    var directionZ = -0.5f; private set
    var positionX = 0.0f; private set
    var positionY = 3.0f; private set
    var positionZ = 0.0f; private set

    fun directional() { type = LightType.DIRECTIONAL }
    fun point() { type = LightType.POINT }
    fun spot() { type = LightType.SPOT }

    fun intensity(value: Double) { intensity = value }

    fun color(r: Float, g: Float, b: Float) {
        colorR = r; colorG = g; colorB = b
    }

    fun direction(x: Float, y: Float, z: Float) {
        directionX = x; directionY = y; directionZ = z
    }

    fun position(x: Float, y: Float, z: Float) {
        positionX = x; positionY = y; positionZ = z
    }
}

enum class LightType {
    DIRECTIONAL,
    POINT,
    SPOT
}
