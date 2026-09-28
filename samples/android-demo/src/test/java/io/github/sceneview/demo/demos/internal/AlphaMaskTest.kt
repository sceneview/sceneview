package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlphaMaskTest {

    @Test fun `a cutoff of 1 is lowered one 8-bit step, so fully opaque texels draw`() {
        // The Fantasy Butterfly's file: MASK, alphaCutoff 1.0 (#4103).
        assertEquals(254f / 255f, drawableMaskThreshold(1f), 1e-6f)
        assertTrue(drawableMaskThreshold(1f) < 1f)
    }

    @Test fun `ordinary cutoffs are kept as authored`() {
        assertEquals(0.5f, drawableMaskThreshold(0.5f), 0f)
        assertEquals(0.9f, drawableMaskThreshold(0.9f), 0f)
        assertEquals(MAX_MASK_THRESHOLD, drawableMaskThreshold(MAX_MASK_THRESHOLD), 0f)
    }
}
