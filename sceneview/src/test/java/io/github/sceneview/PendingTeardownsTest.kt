package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order in which the teardowns deferred on one engine run, see [PendingTeardowns]: what waits
 * for the backend (a renderer, a used prefilter) always goes before the engine, exactly once. No
 * engine needed.
 */
class PendingTeardownsTest {

    private val ran = mutableListOf<String>()
    private val teardowns = PendingTeardowns()

    @Test
    fun `what is still waiting runs before the engine goes, oldest first`() {
        teardowns.add { ran += "renderer" }
        teardowns.add { ran += "prefilter" }

        // What safeDestroy() does right before Engine.destroy().
        teardowns.runAll()
        ran += "engine"

        assertEquals(listOf("renderer", "prefilter", "engine"), ran)
        assertEquals(0, teardowns.size)
    }

    @Test
    fun `a teardown runs once, from its own check or from the engine, whichever is first`() {
        val renderer = teardowns.add { ran += "renderer" }
        val prefilter = teardowns.add { ran += "prefilter" }

        // The prefilter's own check finds the backend idle first.
        prefilter.run()
        assertEquals(1, teardowns.size)
        teardowns.runAll()
        // Their checks still fire afterwards: nothing left to do.
        renderer.run()
        prefilter.run()

        assertEquals(listOf("prefilter", "renderer"), ran)
        assertTrue(renderer.isDone && prefilter.isDone)
    }

    @Test
    fun `a teardown added while the others run is run too`() {
        teardowns.add {
            ran += "first"
            teardowns.add { ran += "added meanwhile" }
        }

        teardowns.runAll()

        assertEquals(listOf("first", "added meanwhile"), ran)
        assertEquals(0, teardowns.size)
    }

    @Test
    fun `a teardown that throws does not keep the next ones, nor the engine, from going`() {
        val failures = mutableListOf<Exception>()
        teardowns.onFailure = { failures += it }
        teardowns.add { throw IllegalStateException("renderer") }
        teardowns.add { ran += "prefilter" }

        teardowns.runAll()
        ran += "engine"

        assertEquals(listOf("prefilter", "engine"), ran)
        assertEquals(listOf("renderer"), failures.map { it.message })
    }

    @Test
    fun `nothing waiting, nothing to run`() {
        teardowns.runAll()

        assertEquals(0, teardowns.size)
    }
}
