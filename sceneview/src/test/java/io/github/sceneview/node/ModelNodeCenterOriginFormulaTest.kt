package io.github.sceneview.node

import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression pins for the [ModelNode.centerOrigin] formula (#2622).
 *
 * The old implementation was `position += origin * size`: sign-inverted (bottom-aligning shifted
 * the model *down*), double magnitude (`size` is the full extent, the anchor offset scales with
 * the half extent), and it ignored the AABB [ModelNode.center] entirely — so `origin = (0,0,0)`
 * ("center the model") was a silent no-op and every alignment was wrong for assets whose bounding
 * box is not authored centered on their pivot.
 *
 * These tests drive the production [centerOriginTranslation] helper directly (the pure math
 * `ModelNode.centerOrigin` applies) — constructing a real `ModelNode` needs a Filament `Engine`,
 * which is unavailable on the JVM, so the formula is extracted and pinned here, mirroring the
 * `resolveModelNodePosition` precedent of `ModelNodeCenterOriginTest`.
 *
 * The fixture AABB is deliberately **non-centered** — `min = (0,0,0)`, `max = (2,4,2)`, i.e.
 * `center = (1,2,1)` — because `center != 0` is the discriminant between the correct formula and
 * the old one (which never consulted `center`). `scale = 0.5` mimics `scaleToUnitCube(2f)`
 * (`maxExtent = 4`). The invariant asserted for every origin: the AABB point selected by the
 * normalized origin, once scaled and translated, lands exactly on the node origin.
 */
class ModelNodeCenterOriginFormulaTest {

    /** AABB center of the non-centered fixture (`min=(0,0,0)`, `max=(2,4,2)`). */
    private val center = Float3(1f, 2f, 1f)
    private val halfExtent = Float3(1f, 2f, 1f)

    /** `scaleToUnitCube(2f)` on a `maxExtent = 4` asset → uniform 0.5. */
    private val scale = Scale(0.5f)

    private fun assertPosition(
        message: String,
        expectedX: Float,
        expectedY: Float,
        expectedZ: Float,
        actual: Position
    ) {
        assertEquals("$message (x)", expectedX, actual.x, 1e-6f)
        assertEquals("$message (y)", expectedY, actual.y, 1e-6f)
        assertEquals("$message (z)", expectedZ, actual.z, 1e-6f)
    }

    /** The scaled-parent-space location of the AABB point selected by [origin], after translating. */
    private fun anchorAfterTranslation(origin: Position, translation: Position): Position =
        (center + origin * halfExtent) * scale + translation

    /**
     * `origin = (0,0,0)` must center the bounding box on the node origin. Under the old formula
     * this was a **no-op** (`0 * size == 0`) — the off-center pivot leaked through untouched.
     */
    @Test
    fun centerOriginCentersANonCenteredBoundingBox() {
        val origin = Position(0f, 0f, 0f)
        val translation = centerOriginTranslation(center, halfExtent, scale, origin)
        // -(center) * scale: pull the scaled AABB center back onto the origin.
        assertPosition("translation must cancel the scaled AABB center", -0.5f, -1f, -0.5f, translation)
        assertPosition(
            "the scaled AABB center must land on the node origin",
            0f, 0f, 0f, anchorAfterTranslation(origin, translation)
        )
    }

    /**
     * `origin = (0,-1,0)` (the KDoc "bottom aligned" idiom) must put the **bottom** of the AABB at
     * `y = 0` — the model sits on the node origin — and keep it horizontally centered. The old
     * formula translated by `origin * size = (0,-2,0)`: a full scaled height *downwards* (sign
     * inverted, ×2 magnitude), leaving the model entirely below the origin and off-center in x/z.
     */
    @Test
    fun bottomOriginSitsTheModelOnTheNodeOrigin() {
        val origin = Position(0f, -1f, 0f)
        val translation = centerOriginTranslation(center, halfExtent, scale, origin)
        assertPosition("translation must lift the bottom-center anchor", -0.5f, 0f, -0.5f, translation)
        assertPosition(
            "the scaled bottom-center of the AABB must land on the node origin",
            0f, 0f, 0f, anchorAfterTranslation(origin, translation)
        )
        // Bottom face at y = 0: scaled AABB min y (= (center.y - halfExtent.y) * scale) + t.y.
        assertEquals(
            "the AABB bottom face must rest exactly on y = 0",
            0f, (center.y - halfExtent.y) * scale.y + translation.y, 1e-6f
        )
    }

    /**
     * `origin = (0,1,0)` must hang the model from the node origin — AABB **top** face at `y = 0`.
     */
    @Test
    fun topOriginHangsTheModelFromTheNodeOrigin() {
        val origin = Position(0f, 1f, 0f)
        val translation = centerOriginTranslation(center, halfExtent, scale, origin)
        assertPosition("translation must pull the top-center anchor down", -0.5f, -2f, -0.5f, translation)
        assertPosition(
            "the scaled top-center of the AABB must land on the node origin",
            0f, 0f, 0f, anchorAfterTranslation(origin, translation)
        )
        // Top face at y = 0: scaled AABB max y (= (center.y + halfExtent.y) * scale) + t.y.
        assertEquals(
            "the AABB top face must hang exactly from y = 0",
            0f, (center.y + halfExtent.y) * scale.y + translation.y, 1e-6f
        )
    }
}
