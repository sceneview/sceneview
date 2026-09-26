package io.github.sceneview.web

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the clear-colour math behind `setBackgroundColor` (#3879). The on-screen exactness
 * itself needs the real WASM renderer and is asserted in the web-demo
 * `kotlin-bundle.spec.ts`; this covers what reaches `Renderer.setClearOptions`.
 */
class BackgroundColorTest {

    private fun assertColor(expected: DoubleArray, actual: DoubleArray) {
        assertEquals(4, actual.size)
        for (i in 0 until 4) assertEquals(expected[i], actual[i], 1e-9, "component $i")
    }

    @Test
    fun opaqueColourPassesThroughUnchanged() {
        // No sRGB-to-linear conversion: the canvas clear is a raw framebuffer value.
        assertColor(
            doubleArrayOf(0xEE / 255.0, 0xF0 / 255.0, 0xF3 / 255.0, 1.0),
            BackgroundColor.clearColor(0xEE / 255.0, 0xF0 / 255.0, 0xF3 / 255.0, 1.0),
        )
        assertColor(doubleArrayOf(1.0, 1.0, 1.0, 1.0), BackgroundColor.clearColor(1.0, 1.0, 1.0))
    }

    @Test
    fun translucentColourIsPremultiplied() {
        // An unpremultiplied (1, 1, 1, 0) would composite as additive white.
        assertColor(doubleArrayOf(0.0, 0.0, 0.0, 0.0), BackgroundColor.clearColor(1.0, 1.0, 1.0, 0.0))
        assertColor(doubleArrayOf(0.5, 0.25, 0.0, 0.5), BackgroundColor.clearColor(1.0, 0.5, 0.0, 0.5))
    }

    @Test
    fun componentsAreClampedAndNonFiniteFallsBack() {
        assertColor(doubleArrayOf(1.0, 0.0, 0.5, 1.0), BackgroundColor.clearColor(3.0, -1.0, 0.5, 7.0))
        // A NaN colour channel reads as 0, a NaN alpha as opaque.
        assertColor(doubleArrayOf(0.0, 0.2, 0.4, 1.0), BackgroundColor.clearColor(Double.NaN, 0.2, 0.4, Double.NaN))
    }

    @Test
    fun defaultKeepsTheHistoricalSlate() {
        // #333443 is what the viewer displayed before #3879 (the tone-mapped 0.05/0.05/0.07).
        assertColor(doubleArrayOf(0x33 / 255.0, 0x34 / 255.0, 0x43 / 255.0, 1.0), BackgroundColor.DEFAULT)
    }
}
