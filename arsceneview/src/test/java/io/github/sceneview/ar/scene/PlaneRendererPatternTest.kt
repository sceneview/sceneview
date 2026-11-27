package io.github.sceneview.ar.scene

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaneRendererPatternTest {
    private val normals = listOf(
        Float3(0f, 1f, 0f), Float3(0f, -1f, 0f), Float3(1f, 0f, 0f),
        Float3(0f, 0f, 1f), Float3(1f, 0f, 1f), Float3(0.3f, 1f, 0.2f),
        Float3(1f, 0.2f, 1f),
    )

    @Test fun `surface axes have unit length and are tangent on floors walls and tilted planes`() {
        for (normal in normals) {
            val (u, v) = PlanePattern.basis(normal)
            assertEquals(1f, length(u), 1e-6f)
            assertEquals(1f, length(v), 1e-6f)
            assertEquals(0f, dot(u, v), 1e-6f)
            assertEquals(0f, dot(u, normalize(normal)), 1e-6f)
            assertEquals(0f, dot(v, normalize(normal)), 1e-6f)
        }
    }

    @Test fun `adjacent cells are ten centimetres apart on every surface orientation`() {
        for (normal in normals) {
            val (u, v) = PlanePattern.basis(normal)
            for (origin in listOf(Float3(0f), Float3(12.3f, -2f, 8.1f))) {
                for (axis in listOf(u, v)) {
                    val next = origin + axis * PlanePattern.SPACING_METRES
                    assertEquals(0.10f, length(next - origin), 2e-6f)
                    assertEquals(1f, dot(next - origin, axis) * PlanePattern.MARKS_PER_METRE, 2e-5f)
                }
            }
        }
    }

    @Test fun `dot radius is nine millimetres regardless of lattice orientation`() {
        for (normal in normals) {
            val (u, v) = PlanePattern.basis(normal)
            val diagonal = normalize(u + v) * PlanePattern.DOT_RADIUS_METRES
            assertEquals(0.009f, length(diagonal), 1e-6f)
            assertEquals(0.09f, length(diagonal) * PlanePattern.MARKS_PER_METRE, 1e-6f)
        }
    }
}
