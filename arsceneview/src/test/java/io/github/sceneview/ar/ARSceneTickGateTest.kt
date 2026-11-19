package io.github.sceneview.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ARSceneTickGateTest {
    @Test
    fun `an absent session blocks scene work`() {
        assertFalse(canTickARScene(sessionIsClosed = null))
    }

    @Test
    fun `a closed session blocks scene work`() {
        assertFalse(canTickARScene(sessionIsClosed = true))
    }

    @Test
    fun `an open session permits scene work even while paused after a resume failure`() {
        assertTrue(canTickARScene(sessionIsClosed = false))
    }
}
