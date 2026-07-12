package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the #2657 single-shadow-receiver invariant of
 * [TapToPlaceArSession].
 *
 * ## The bug
 *
 * `TapToPlaceArSession` used to keep `planeRenderer = true` for the whole session AND spawn a
 * [io.github.sceneview.ar.ARSceneScope.ShadowReceiverPlane] on every tracked plane at the same
 * time. The V1 plane renderer attaches its OWN shadow receiver (`plane_renderer_shadow.filamat`,
 * a `shadowMultiplier` quad lifted to `y = 0.005`) to every tracked plane, and
 * `ShadowReceiverPlane` adds a SECOND `shadowMultiplier` quad (`shadow_receiver.filamat`, also
 * lifted to `y += 0.005`) on the same plane. For a horizontal floor the two are exactly coplanar
 * → z-fight, and wherever a shadow falls the plane darkens twice (0.4 × 0.4 ≈ 0.16 → near-black),
 * so the detected plane reads as a hard dark polygon (#2657). The library's own `PlacementScene`
 * avoids this by fading the plane renderer after the first placement
 * (`fadePlaneOnFirstPlacement`); `TapToPlaceArSession` copied the `ShadowReceiverPlane` spawn but
 * not the guard.
 *
 * ## The invariant (pinned here)
 *
 * The visible/Filament-bound rendering is device territory, but the gating decision is a pure
 * function of `placedCount`, so it is pinned headlessly: for **every** placement count, the plane
 * grid (which carries the V1 shadow receiver) and the `ShadowReceiverPlane` catcher are
 * **mutually exclusive** — a plane is therefore never covered by two coplanar `shadowMultiplier`
 * receivers at once.
 *
 * @see shouldRenderPlaneGrid
 * @see shouldCatchGroundShadows
 */
class TapToPlaceShadowReceiverTest {

    @Test
    fun `no model placed shows the grid and attaches no ShadowReceiverPlane`() {
        // Before any placement: the grid (and its V1 shadow receiver) guides discovery; there is
        // nothing to cast a shadow yet, so no ShadowReceiverPlane is spawned.
        assertTrue("plane grid must render while scanning", shouldRenderPlaneGrid(0))
        assertFalse("no ShadowReceiverPlane before a model exists", shouldCatchGroundShadows(0))
    }

    @Test
    fun `after the first placement the grid recedes and the shadow catcher takes over`() {
        // Once a model is placed, the grid (and its V1 shadow receiver) recede and the dedicated
        // ShadowReceiverPlane becomes the single receiver — clean, single-darkened contact shadow.
        assertFalse("plane grid must recede after placement (#2657)", shouldRenderPlaneGrid(1))
        assertTrue("ShadowReceiverPlane grounds the placed model", shouldCatchGroundShadows(1))
    }

    @Test
    fun `grid and shadow catcher are mutually exclusive for every placement count`() {
        // THE #2657 invariant: never two coplanar shadowMultiplier receivers on the same plane.
        // The V1 plane renderer's receiver rides the grid, so "grid on" and "catcher on" must
        // never both be true — for any count, including the multi-model case.
        for (placedCount in 0..25) {
            assertFalse(
                "placedCount=$placedCount stacks two shadow receivers on the same plane " +
                    "(grid's V1 receiver + ShadowReceiverPlane) — the #2657 double-darken/z-fight",
                shouldRenderPlaneGrid(placedCount) && shouldCatchGroundShadows(placedCount),
            )
            // Exactly one shadow-receiver source is active at all times (mutual exclusion is
            // total, not merely non-overlapping): grid-receiver XOR catcher.
            assertTrue(
                "placedCount=$placedCount must have exactly one shadow-receiver source active",
                shouldRenderPlaneGrid(placedCount) != shouldCatchGroundShadows(placedCount),
            )
        }
    }
}
