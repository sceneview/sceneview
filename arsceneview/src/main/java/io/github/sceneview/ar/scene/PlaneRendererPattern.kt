package io.github.sceneview.ar.scene

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import kotlin.math.abs

/** Default discovery visuals contain marks only, with no fill, dark halo or shadow mesh. */
internal object PlaneVisualDefaults {
    const val SURFACE_ALPHA = 0f
    const val CONTRAST = 0f
    const val SHADOW_RECEIVER = false
}

/** Physical dimensions and orthonormal world-space axes uploaded to plane_renderer.mat. */
internal object PlanePattern {
    const val SPACING_METRES = 0.10f
    const val DOT_RADIUS_METRES = 0.009f
    const val MARKS_PER_METRE = 1f / SPACING_METRES

    data class Basis(val u: Float3, val v: Float3)

    fun basis(normal: Float3): Basis {
        val n = normalize(normal)
        return if (abs(n.y) < 0.7f) {
            val across = normalize(cross(Float3(0f, 1f, 0f), n))
            Basis(across, normalize(cross(n, across)))
        } else {
            // Project X onto the actual surface: world X/Z alone stretches a tilted plane.
            val x = Float3(1f, 0f, 0f)
            val across = normalize(x - n * dot(x, n))
            Basis(across, normalize(cross(across, n)))
        }
    }
}
