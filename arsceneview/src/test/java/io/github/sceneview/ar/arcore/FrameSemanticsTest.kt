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
        // Everything that reads the raster — `PersonMask`, the `semantics_overlay` material, the
        // demo's overlay and legend — is written against this order; all of them once assumed
        // SKY came first (#4036).
        assertEquals(
            listOf(
                "UNLABELED", "SKY", "BUILDING", "TREE", "ROAD", "SIDEWALK", "TERRAIN",
                "STRUCTURE", "OBJECT", "VEHICLE", "PERSON", "WATER",
            ),
            com.google.ar.core.SemanticLabel.values().map { it.name },
        )
    }

    @Test
    fun `the byte ARCore writes for a class is that class's ordinal`() {
        // The semantic image holds ARCore's native code for each class (`ArSemanticLabel` in
        // `arcore_c_api.h`), which the SDK keeps in a package-private `nativeCode` field. The
        // order of the Java constants says nothing about it by itself: this is what lets
        // `SemanticLabel.values()[byte]` and an ordinal-indexed palette decode the raster.
        val nativeCode = com.google.ar.core.SemanticLabel::class.java
            .getDeclaredField("nativeCode")
            .apply { isAccessible = true }
        val labels = com.google.ar.core.SemanticLabel.values()
        assertEquals(12, labels.size)
        labels.forEach { label ->
            assertEquals("native code of ${label.name}", label.ordinal, nativeCode.getInt(label))
        }
    }
}
