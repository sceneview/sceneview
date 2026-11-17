package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterPrewarmTest {
    private val all: (FeelMaterial) -> Boolean = { true }

    /** Runs idle frames until the scheduler stops, returning the order of warm draws. */
    private fun MatterPrewarm.drain(available: (FeelMaterial) -> Boolean = all, frames: Int = 200): List<FeelMaterial> {
        val drawn = mutableListOf<FeelMaterial>()
        repeat(frames) {
            next(listOf(FeelMaterial.MemoryFoam), available, idle = true)?.let { drawn += it }
        }
        return drawn
    }

    @Test fun warmsEveryOtherMaterialInOrderThenStops() {
        val prewarm = MatterPrewarm()
        val drawn = prewarm.drain()
        // Each material is drawn for DRAW_FRAMES consecutive frames, the shown one never.
        val order = drawn.distinct()
        assertEquals(FeelMaterial.entries - FeelMaterial.MemoryFoam, order)
        assertEquals(order.size * MatterPrewarm.DRAW_FRAMES, drawn.size)
        assertTrue(prewarm.done)
        assertFalse(prewarm.needsFrames(all))
        assertNull(prewarm.next(emptyList(), all, idle = true))
    }

    @Test fun waitsGapFramesBetweenDraws() {
        val prewarm = MatterPrewarm()
        val frames = (0 until 40).map { prewarm.next(listOf(FeelMaterial.MemoryFoam), all, idle = true) }
        val first = frames.indexOfFirst { it != null }
        assertEquals(MatterPrewarm.GAP_FRAMES, first)
        val second = frames.indexOfFirst { it != null && it != frames[first] }
        assertEquals(first + MatterPrewarm.DRAW_FRAMES + MatterPrewarm.GAP_FRAMES, second)
    }

    @Test fun neverDrawsDuringAPressOrASwitch() {
        val prewarm = MatterPrewarm()
        repeat(50) { assertNull(prewarm.next(listOf(FeelMaterial.MemoryFoam), all, idle = false)) }
        assertTrue(prewarm.needsFrames(all))
    }

    @Test fun anInterruptedDrawStopsAtOnceAndIsRetried() {
        val prewarm = MatterPrewarm()
        var target: FeelMaterial? = null
        while (target == null) target = prewarm.next(listOf(FeelMaterial.MemoryFoam), all, idle = true)
        // A press lands on the next frame: nothing hidden is drawn under it.
        assertNull(prewarm.next(listOf(FeelMaterial.MemoryFoam), all, idle = false))
        assertEquals(target, prewarm.drain().first())
    }

    @Test fun materialsAlreadyOnStageCountAsWarmed() {
        val prewarm = MatterPrewarm()
        prewarm.next(listOf(FeelMaterial.MemoryFoam, FeelMaterial.Gel), all, idle = false)
        assertFalse(FeelMaterial.Gel in prewarm.drain())
    }

    @Test fun onlyAvailableSamplesAreDrawnAndLateOnesAreStillWarmed() {
        val prewarm = MatterPrewarm()
        var ready = setOf(FeelMaterial.Gel)
        assertEquals(listOf(FeelMaterial.Gel), prewarm.drain({ it in ready }).distinct())
        assertFalse(prewarm.needsFrames { it in ready })
        assertFalse(prewarm.done)
        ready = FeelMaterial.entries.toSet()
        assertTrue(prewarm.needsFrames { it in ready })
        assertEquals(FeelMaterial.entries - FeelMaterial.MemoryFoam - FeelMaterial.Gel,
            prewarm.drain({ it in ready }).distinct())
        assertTrue(prewarm.done)
    }
}
