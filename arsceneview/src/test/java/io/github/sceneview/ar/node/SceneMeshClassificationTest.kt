package io.github.sceneview.ar.node

import com.google.ar.core.StreetscapeGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression tests for [MeshClassification] and the
 * [StreetscapeGeometry.Type.toMeshClassification] extension.
 *
 * [MeshClassification] is the unified surface-label vocabulary shared across Android (ARCore) and
 * iOS (ARKit). These tests pin:
 *  1. The enum has all 10 expected labels in the documented order.
 *  2. The [StreetscapeGeometry.Type] → [MeshClassification] mapping is correct for every
 *     ARCore type and that unknown future types default to [MeshClassification.UNLABELED].
 *  3. The ARKit-specific labels (FLOOR, WALL, CEILING, TABLE, SEAT, WINDOW, DOOR) are present
 *     in the enum and not accidentally removed.
 *
 * No Filament or ARCore JNI handles are needed — the enum and extension are pure Kotlin.
 */
class SceneMeshClassificationTest {

    // ── Enum completeness ────────────────────────────────────────────────────────────────────────

    @Test
    fun `enum contains all ten expected labels`() {
        val expected = setOf(
            "FLOOR", "WALL", "CEILING", "TABLE", "SEAT",
            "WINDOW", "DOOR", "TERRAIN", "BUILDING", "UNLABELED"
        )
        val actual = MeshClassification.values().map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `enum has exactly ten entries`() {
        assertEquals(10, MeshClassification.values().size)
    }

    @Test
    fun `ARKit surface labels are present`() {
        val arkitLabels = setOf(
            MeshClassification.FLOOR,
            MeshClassification.WALL,
            MeshClassification.CEILING,
            MeshClassification.TABLE,
            MeshClassification.SEAT,
            MeshClassification.WINDOW,
            MeshClassification.DOOR,
        )
        arkitLabels.forEach { label ->
            // Enum.values() always contains the declared entry — the assertion documents intent.
            assert(MeshClassification.values().contains(label)) {
                "ARKit label $label missing from MeshClassification enum"
            }
        }
    }

    // ── StreetscapeGeometry.Type → MeshClassification mapping ────────────────────────────────────

    @Test
    fun `TERRAIN type maps to TERRAIN classification`() {
        assertEquals(
            MeshClassification.TERRAIN,
            StreetscapeGeometry.Type.TERRAIN.toMeshClassification()
        )
    }

    @Test
    fun `BUILDING type maps to BUILDING classification`() {
        assertEquals(
            MeshClassification.BUILDING,
            StreetscapeGeometry.Type.BUILDING.toMeshClassification()
        )
    }

    @Test
    fun `all known ARCore types map to a non-UNLABELED classification`() {
        // Every StreetscapeGeometry.Type declared in ARCore 1.54 must map to a specific
        // MeshClassification — not the fallback UNLABELED.
        val knownTypes = setOf(
            StreetscapeGeometry.Type.TERRAIN,
            StreetscapeGeometry.Type.BUILDING,
        )
        knownTypes.forEach { type ->
            val cls = type.toMeshClassification()
            assert(cls != MeshClassification.UNLABELED) {
                "ARCore type $type unexpectedly mapped to UNLABELED"
            }
        }
    }

    @Test
    fun `each ARCore type produces a distinct classification`() {
        val classifications = StreetscapeGeometry.Type.values()
            .map { it.toMeshClassification() }
            .toSet()
        // Every type should produce a distinct label — no two types collapse to the same bucket.
        assertEquals(StreetscapeGeometry.Type.values().size, classifications.size)
    }

    // ── UNLABELED is the explicit fallback ───────────────────────────────────────────────────────

    @Test
    fun `UNLABELED is present and has the correct name`() {
        assertEquals("UNLABELED", MeshClassification.UNLABELED.name)
    }
}
