package io.github.sceneview.material

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which authored strength an emissive material is scaled from, and when it is set again (#4950). */
class EmissiveScaleTest {

    @Test
    fun `a model declaring no strength was authored at 1`() {
        assertEquals(1f, EmissiveScale.authoredEmissiveStrength("anything", null))
        assertEquals(1f, EmissiveScale.authoredEmissiveStrength(null, null))
    }

    @Test
    fun `a material is found again by name`() {
        val strengths = mapOf("fire" to 6f, "body" to 1f)

        assertEquals(6f, EmissiveScale.authoredEmissiveStrength("fire", strengths))
        assertEquals(1f, EmissiveScale.authoredEmissiveStrength("body", strengths))
    }

    @Test
    fun `an unknown, missing or ambiguous name leaves the material alone`() {
        val strengths = mapOf("a" to Float.NaN, "b" to 2f)

        assertNull(EmissiveScale.authoredEmissiveStrength("a", strengths))
        assertNull(EmissiveScale.authoredEmissiveStrength("c", strengths))
        assertNull(EmissiveScale.authoredEmissiveStrength(null, strengths))
    }

    @Test
    fun `a node is set again only when the scale moves by more than 1 percent`() {
        assertTrue(EmissiveScale.needsUpdate(null, 0.5f))
        assertFalse(EmissiveScale.needsUpdate(0.5f, 0.5f))
        assertFalse(EmissiveScale.needsUpdate(0.5f, 0.504f))
        assertTrue(EmissiveScale.needsUpdate(0.5f, 0.51f))
        assertTrue(EmissiveScale.needsUpdate(0.2f, 1f))
    }
}
