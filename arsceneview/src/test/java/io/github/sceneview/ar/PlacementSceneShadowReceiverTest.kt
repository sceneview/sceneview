package io.github.sceneview.ar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the #2657 single-shadow-receiver invariant of [PlacementScene].
 *
 * ## The (latent) bug
 *
 * `PlacementScene(groundShadows = true)` used to spawn a
 * [io.github.sceneview.ar.ARSceneScope.ShadowReceiverPlane] on every detected plane regardless of
 * whether the plane grid was rendering. The V1 plane renderer attaches its OWN shadow receiver
 * (`plane_renderer_shadow.filamat`, a `shadowMultiplier` quad lifted to `y = 0.005`) to every
 * tracked plane, and `ShadowReceiverPlane` adds a SECOND `shadowMultiplier` quad
 * (`shadow_receiver.filamat`, also lifted to `y += 0.005`) on the same plane. For a horizontal
 * floor the two are exactly coplanar → z-fight, and wherever a shadow falls the plane darkens
 * twice (0.4 × 0.4 ≈ 0.16 → near-black). The default flags dodged the worst of it
 * (`fadePlaneOnFirstPlacement = true` removes the grid — and its receiver — after the first
 * placement), but `planeRenderer = true` + `fadePlaneOnFirstPlacement = false` +
 * `groundShadows = true` stacked both receivers for the whole session — the same mechanism the
 * demo-level #2657 fix (`TapToPlaceState.shouldRenderPlaneGrid` /
 * `shouldCatchGroundShadows`) killed in `TapToPlaceArSession`.
 *
 * ## The invariant (pinned here)
 *
 * The visible/Filament-bound rendering is device territory, but the gating decision is a pure
 * function of the composable's flags plus `placedCount`, so it is pinned headlessly: for **every**
 * flag combination and placement count, the plane grid (which carries the V1 shadow receiver) and
 * the `ShadowReceiverPlane` catcher are **mutually exclusive** — a plane is therefore never
 * covered by two coplanar `shadowMultiplier` receivers at once.
 *
 * @see shouldRenderPlaneGrid
 * @see shouldCatchGroundShadows
 */
class PlacementSceneShadowReceiverTest {

    private val booleans = listOf(false, true)

    @Test
    fun `grid and shadow catcher are never both live in any flag combination`() {
        // THE #2657 invariant, exhaustively: never two coplanar shadowMultiplier receivers on
        // the same plane, whatever the caller passes.
        for (planeRenderer in booleans) {
            for (fade in booleans) {
                for (groundShadows in booleans) {
                    for (placedCount in 0..25) {
                        assertFalse(
                            "planeRenderer=$planeRenderer fadePlaneOnFirstPlacement=$fade " +
                                "groundShadows=$groundShadows placedCount=$placedCount stacks " +
                                "two shadow receivers on the same plane (grid's V1 receiver + " +
                                "ShadowReceiverPlane) — the #2657 double-darken/z-fight",
                            shouldRenderPlaneGrid(planeRenderer, fade, placedCount) &&
                                shouldCatchGroundShadows(
                                    groundShadows, planeRenderer, fade, placedCount
                                ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `default flags scan with the grid then hand over to the catcher after placement`() {
        // planeRenderer=true, fadePlaneOnFirstPlacement=true, groundShadows=true (the documented
        // contact-shadow setup): grid guides discovery, then recedes and the catcher takes over.
        assertTrue(
            "plane grid must render while scanning",
            shouldRenderPlaneGrid(true, true, 0),
        )
        assertFalse(
            "no ShadowReceiverPlane while the grid (and its V1 receiver) shows",
            shouldCatchGroundShadows(true, true, true, 0),
        )
        assertFalse(
            "plane grid must recede after placement",
            shouldRenderPlaneGrid(true, true, 1),
        )
        assertTrue(
            "ShadowReceiverPlane grounds the placed model once the grid is gone",
            shouldCatchGroundShadows(true, true, true, 1),
        )
    }

    @Test
    fun `permanent grid keeps its own receiver as the single shadow receiver`() {
        // The previously latent footgun: planeRenderer=true + fadePlaneOnFirstPlacement=false +
        // groundShadows=true. The grid never recedes, so its built-in V1 receiver serves the
        // contact shadows and the dedicated catcher must never spawn — at any count.
        for (placedCount in 0..25) {
            assertTrue(
                "grid stays for the whole session when fade is off",
                shouldRenderPlaneGrid(true, false, placedCount),
            )
            assertFalse(
                "placedCount=$placedCount must not add a second coplanar receiver (#2657)",
                shouldCatchGroundShadows(true, true, false, placedCount),
            )
        }
    }

    @Test
    fun `without the grid the catcher is live from the start`() {
        // planeRenderer=false: no V1 receiver exists, so groundShadows spawns the catcher
        // immediately — before and after placement.
        for (placedCount in 0..25) {
            assertFalse(shouldRenderPlaneGrid(false, true, placedCount))
            assertTrue(
                "the catcher is the single receiver when the grid is disabled",
                shouldCatchGroundShadows(true, false, true, placedCount),
            )
        }
    }

    @Test
    fun `groundShadows off never spawns a catcher`() {
        for (planeRenderer in booleans) {
            for (fade in booleans) {
                for (placedCount in 0..25) {
                    assertFalse(
                        "groundShadows=false must never attach a ShadowReceiverPlane",
                        shouldCatchGroundShadows(false, planeRenderer, fade, placedCount),
                    )
                }
            }
        }
    }
}
