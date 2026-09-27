package io.github.sceneview.sample.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loading state (#3926) must cover the whole wait, on launch and on every switch, and end
 * exactly when the picked model has been presented once.
 */
class TvLoadingStateTest {

    private val previous = Any()
    private val next = Any()

    @Test
    fun `launch - loading until the instance exists and has been drawn`() {
        assertTrue(isModelLoading(current = null, stale = null, drawn = null))
        assertTrue(isModelLoading(current = next, stale = null, drawn = null))
        assertFalse(isModelLoading(current = next, stale = null, drawn = next))
    }

    @Test
    fun `switch - the previous model's instance does not end the wait`() {
        // rememberModelInstance keeps returning the old instance until the new one is built.
        assertTrue(isModelLoading(current = previous, stale = previous, drawn = previous))
        assertTrue(isModelLoading(current = next, stale = previous, drawn = previous))
        assertFalse(isModelLoading(current = next, stale = previous, drawn = next))
    }
}
