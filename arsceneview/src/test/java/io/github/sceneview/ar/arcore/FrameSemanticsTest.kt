package io.github.sceneview.ar.arcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** JVM-reachable contract for the ARCore semantic labels exposed by the SDK. */
class FrameSemanticsTest {

    @Test
    fun `the twelve ARCore SemanticLabel ordinal values exist in the SDK`() {
        val labels = com.google.ar.core.SemanticLabel.values()
        assertEquals(
            "ARCore declares 12 outdoor SemanticLabel classes — if this count changes, " +
                "update llms.txt and the ARSceneSemanticsDemo HUD.",
            12,
            labels.size,
        )
        assertNotNull(labels.firstOrNull { it.name == "SKY" })
        assertNotNull(labels.firstOrNull { it.name == "TERRAIN" })
        assertNotNull(labels.firstOrNull { it.name == "PERSON" })
        assertNotNull(labels.firstOrNull { it.name == "UNLABELED" })
    }

    @Test
    fun `ARCore numbers UNLABELED first, then the eleven classes`() {
        // The byte in the semantic image is this ordinal. Everything that reads the raster —
        // `PersonMask`, the `semantics_overlay` material, the demo's overlay and legend — is
        // written against this order; all of them once assumed SKY came first (#4036).
        assertEquals(
            listOf(
                "UNLABELED", "SKY", "BUILDING", "TREE", "ROAD", "SIDEWALK", "TERRAIN",
                "STRUCTURE", "OBJECT", "VEHICLE", "PERSON", "WATER",
            ),
            com.google.ar.core.SemanticLabel.values().map { it.name },
        )
    }
}
