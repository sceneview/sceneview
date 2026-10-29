package io.github.sceneview.node

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.translation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure math behind [ModelNode.worldSize] and [ModelNode.worldBoundingBox]: a
 * real `ModelNode` needs a Filament engine, unavailable on the JVM.
 */
class ModelWorldBoundsTest {

    private fun assertFloat3(message: String, expected: Float3, actual: Float3, delta: Float = 1e-4f) {
        assertEquals("$message.x", expected.x, actual.x, delta)
        assertEquals("$message.y", expected.y, actual.y, delta)
        assertEquals("$message.z", expected.z, actual.z, delta)
    }

    @Test fun `identity keeps the authored box`() {
        val (min, max) = worldAxisAlignedBounds(Float3(0f, 0.5f, 0f), Float3(0.5f), Mat4.identity())
        assertFloat3("min", Float3(-0.5f, 0f, -0.5f), min)
        assertFloat3("max", Float3(0.5f, 1f, 0.5f), max)
    }

    @Test fun `rotation and translation move and reshape the box`() {
        // 2 x 1 x 0.5 box, quarter turn about Y, then 3 m along X.
        val transform = translation(Float3(3f, 0f, 0f)) * rotation(Float3(0f, 1f, 0f), 90f)
        val (min, max) = worldAxisAlignedBounds(Float3(0f), Float3(1f, 0.5f, 0.25f), transform)
        assertFloat3("min", Float3(2.75f, -0.5f, -1f), min)
        assertFloat3("max", Float3(3.25f, 0.5f, 1f), max)
    }

    @Test fun `world scale multiplies the authored extents`() {
        // A 1 m cube under a parent at 2x and a child at 0.5x stays 1 m.
        assertFloat3("size", Float3(1f), modelWorldSize(Float3(0.5f), Float3(2f) * Float3(0.5f)))
        // Non-uniform: 1 m cube scaled (1, 3, 1).
        assertFloat3("size", Float3(1f, 3f, 1f), modelWorldSize(Float3(0.5f), Float3(1f, 3f, 1f)))
    }

    @Test fun `scaled transform scales the bounds`() {
        val (min, max) = worldAxisAlignedBounds(Float3(0f), Float3(0.5f), scale(Float3(2f)))
        assertFloat3("min", Float3(-1f), min)
        assertFloat3("max", Float3(1f), max)
    }
}
