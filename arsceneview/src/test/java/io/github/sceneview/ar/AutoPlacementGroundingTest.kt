package io.github.sceneview.ar

import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.toQuaternion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPlacementGroundingTest {
    private fun corners(rotation: Rotation): List<Position> = buildList {
        val q = rotation.toQuaternion()
        for (x in listOf(-1f, 1f)) for (y in listOf(-2f, 2f)) for (z in listOf(-3f, 3f)) {
            add(q * Position(x + 4f, y + 2f, z - 1f))
        }
    }

    @Test fun `rotated and off-center bounds have their lowest corner on surface`() {
        val points = corners(Rotation(x = -90f))
        val offset = automaticPlacementOffset(points, wall = false)
        assertEquals(0f, points.minOf { (it + offset).y }, 0.00001f)
        assertTrue(points.all { (it + offset).y >= -0.00001f })
        assertEquals(0f, points.minOf { (it + offset).x } + points.maxOf { (it + offset).x }, 0.00001f)
    }

    @Test fun `wall back and bottom lie at the contact pivot`() {
        // Wall anchor +Y faces the viewer, -Z points up; authored +Z is model front.
        val points = corners(Rotation(x = -90f))
        val offset = automaticPlacementOffset(points, wall = true)
        assertEquals(0f, points.minOf { (it + offset).y }, 0.00001f)
        assertEquals(0f, points.maxOf { (it + offset).z }, 0.00001f)
    }
}
