package io.github.sceneview

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression pins for the silent-no-op `centerOrigin` bug in the [SceneScope.ModelNode] composable.
 *
 * `ModelNode`'s constructor bakes `centerOrigin` into the node as a translation
 * (`position += origin * size`). The composable captures that baked offset once and composes the
 * declarative `position` param **additively** on top of it via [resolveModelNodePosition]. Before
 * the fix the composable assigned `node.position = position` (default `(0,0,0)`) on creation and on
 * every recomposition, which overwrote — and so silently discarded — any non-zero `centerOrigin`
 * (e.g. `Position(0,-1,0)` to bottom-align). The node ended up at the origin instead. Discovered
 * while fixing the Materials → Occlusion demo (#2304).
 *
 * These tests drive the production helper directly rather than re-implementing the contract. The
 * `ModelNode` composable itself cannot be exercised under `createComposeRule` — Compose's test
 * dispatcher trips Filament's thread-adoption check (see `RenderQualityComposeTest` for the full
 * rationale), so the additive-composition logic is extracted into [resolveModelNodePosition] and
 * pinned here, mirroring the JVM-simulator precedent of `RenderQualityLaunchedEffectTest`.
 *
 * `Position` (kotlin-math `Float3`) uses reference equality, so every assertion is component-wise.
 */
class ModelNodeCenterOriginTest {

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

    /**
     * The core regression: a non-zero `centerOrigin` offset MUST survive when the `position` param
     * is left at its default `(0,0,0)`. `(0, -0.5, 0)` here is a representative non-zero bottom-align
     * offset — the actual value the constructor bakes is `origin * size` (full bounding-box extent),
     * but the helper under test is agnostic to its provenance, so any non-zero offset exercises the
     * regression. Pre-fix the composable returned `position` (`(0,0,0)`) — the silent no-op that
     * rendered the model at the origin instead of bottom-aligned.
     */
    @Test
    fun centerOriginOffsetSurvivesWhenPositionIsDefault() {
        val bottomAlignOffset = Position(0f, -0.5f, 0f)
        // Position(x = 0f) is the composable's own default — kotlin-math's named-arg ctor leaves
        // y/z at 0, so this is (0, 0, 0).
        val resolved = resolveModelNodePosition(bottomAlignOffset, Position(x = 0f))
        assertPosition(
            "centerOrigin bottom-align offset must survive a default position",
            0f, -0.5f, 0f, resolved
        )
    }

    /**
     * `centerOrigin` and an explicit `position` compose additively — e.g. bottom-align a model
     * *and* place it at a point. Pre-fix only `position` reached the node; the centerOrigin offset
     * was lost.
     */
    @Test
    fun positionComposesAdditivelyWithCenterOrigin() {
        val bottomAlignOffset = Position(0f, -0.5f, 0f)
        val resolved = resolveModelNodePosition(bottomAlignOffset, Position(2f, 0f, -3f))
        assertPosition(
            "position must add on top of the centerOrigin offset",
            2f, -0.5f, -3f, resolved
        )
    }

    /**
     * No `centerOrigin` (offset == 0, i.e. `centerOrigin = null` or `Position(0,0,0)`) → the node
     * sits exactly at `position`. This is the unchanged, already-correct path, pinned so the fix
     * cannot regress the common case.
     */
    @Test
    fun zeroCenterOriginIsPositionPassthrough() {
        val resolved = resolveModelNodePosition(Position(x = 0f), Position(1f, 2f, 3f))
        assertPosition(
            "a zero centerOrigin offset must be a position passthrough",
            1f, 2f, 3f, resolved
        )
    }
}
