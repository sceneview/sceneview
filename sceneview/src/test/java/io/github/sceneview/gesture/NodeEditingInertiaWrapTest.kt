package io.github.sceneview.gesture

import io.github.sceneview.gesture.NodeEditingInertia.Companion.wrapRadians
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Pure-JVM tests for [NodeEditingInertia.wrapRadians]: the twist velocity is integrated from
 * `atan2` angle steps, and a step that crosses the ±π seam must read as the small turn it is,
 * not as a full revolution that would launch the node spinning on release.
 */
class NodeEditingInertiaWrapTest {

    private val pi = PI.toFloat()
    private val eps = 1e-5f

    @Test
    fun `small steps are unchanged`() {
        assertEquals(0f, wrapRadians(0f), eps)
        assertEquals(0.1f, wrapRadians(0.1f), eps)
        assertEquals(-0.1f, wrapRadians(-0.1f), eps)
    }

    @Test
    fun `a step across the seam reads as the short turn`() {
        // atan2 jumps from just under +π to just over −π: the finger turned by +0.02 rad.
        val step = (-pi + 0.01f) - (pi - 0.01f)
        assertEquals(0.02f, wrapRadians(step), 1e-4f)
        // And the other way round.
        assertEquals(-0.02f, wrapRadians(-step), 1e-4f)
    }

    @Test
    fun `whole turns are removed`() {
        assertEquals(0.3f, wrapRadians(0.3f + 4 * pi), 1e-4f)
        assertEquals(-0.3f, wrapRadians(-0.3f - 6 * pi), 1e-4f)
    }

    @Test
    fun `result stays in the half-open range minus pi exclusive to pi inclusive`() {
        assertEquals(pi, wrapRadians(pi), eps)
        assertEquals(pi, wrapRadians(-pi), eps)
        var a = -10f
        while (a <= 10f) {
            val w = wrapRadians(a)
            assertTrue("wrap($a) = $w", w > -pi - eps && w <= pi + eps)
            a += 0.37f
        }
    }
}
