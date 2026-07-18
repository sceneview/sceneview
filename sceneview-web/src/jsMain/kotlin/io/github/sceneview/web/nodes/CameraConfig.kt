package io.github.sceneview.web.nodes

import io.github.sceneview.web.bindings.Camera
import io.github.sceneview.web.bindings.float3

/**
 * Camera configuration for SceneView web.
 *
 * ```kotlin
 * camera {
 *     eye(0.0, 1.5, 5.0)
 *     target(0.0, 0.0, 0.0)
 *     up(0.0, 1.0, 0.0)
 *     fov(45.0)
 *     near(0.1)
 *     far(1000.0)
 * }
 * ```
 */
class CameraConfig {
    var eyeX = 0.0; private set
    var eyeY = 1.5; private set
    var eyeZ = 5.0; private set
    var targetX = 0.0; private set
    var targetY = 0.0; private set
    var targetZ = 0.0; private set
    var upX = 0.0; private set
    var upY = 1.0; private set
    var upZ = 0.0; private set
    var fovDegrees = 45.0; private set
    var nearPlane = 0.1; private set
    var farPlane = 1000.0; private set
    // Exposure defaults — physically-based, mirroring Android's SceneFactories
    // default camera (f/12, 1/200s, ISO 200; "neutral, less photographic").
    // Filament's camera is PHOTOMETRIC: pairing a relative/direct exposure ≈ 1.0
    // (model-viewer style) with lux light intensities over-exposes bright albedos
    // to a clipped white blob — the shared /view Duck bug. A relative exposure is
    // opt-in via exposure(value). See Android `utils/Camera.kt` for the same trap.
    var aperture = 12.0; private set
    var shutterSpeed = 1.0 / 200.0; private set
    var sensitivity = 200.0; private set

    fun eye(x: Double, y: Double, z: Double) {
        eyeX = x; eyeY = y; eyeZ = z
    }

    fun target(x: Double, y: Double, z: Double) {
        targetX = x; targetY = y; targetZ = z
    }

    fun up(x: Double, y: Double, z: Double) {
        upX = x; upY = y; upZ = z
    }

    fun fov(degrees: Double) { fovDegrees = degrees }
    fun near(value: Double) { nearPlane = value }
    fun far(value: Double) { farPlane = value }

    /**
     * Set a physically-based (photometric) exposure — aperture (f-stop),
     * shutter speed (seconds) and ISO sensitivity, mirroring Android's
     * `SceneView` camera. This is the ONLY exposure path: Filament.js'
     * `Camera.setExposureDirect` (relative/model-viewer-style exposure) blows
     * bright albedos out to a clipped white blob for any reasonable value, so
     * the DSL exposes only the correct photometric form.
     */
    fun exposure(aperture: Double, shutterSpeed: Double, sensitivity: Double) {
        this.aperture = aperture
        this.shutterSpeed = shutterSpeed
        this.sensitivity = sensitivity
    }

    /** Apply this config to a Filament.js Camera using float3 arrays for lookAt. */
    fun applyTo(camera: Camera) {
        camera.lookAt(
            float3(eyeX, eyeY, eyeZ),
            float3(targetX, targetY, targetZ),
            float3(upX, upY, upZ)
        )
        camera.setExposure(aperture, shutterSpeed, sensitivity)
    }
}
