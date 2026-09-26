package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRequestGenerationTest {
    @Test fun `reset or dismissal rejects a late successful native result`() {
        val requests = CloudRequestGeneration()
        val hosting = requests.current
        assertTrue(requests.accepts(hosting))
        requests.invalidate()
        assertFalse(requests.accepts(hosting))
        val resolving = requests.current
        assertTrue(requests.accepts(resolving))
        requests.invalidate()
        assertFalse(requests.accepts(resolving))
    }
}
