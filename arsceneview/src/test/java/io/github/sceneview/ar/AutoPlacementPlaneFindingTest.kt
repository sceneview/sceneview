package io.github.sceneview.ar

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plane finding and depth each automatic-placement flow asks ARCore for (#4070). The wall
 * flow needs vertical planes, the floor (a wall is inferred from the floor's edge) and the depth
 * API (a plain wall yields depth hits but no plane); the surface flow stays horizontal, no depth.
 */
class AutoPlacementPlaneFindingTest {
    @Test fun `wall flow asks ARCore for vertical planes and the floor`() {
        assertEquals(
            Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,
            autoPlacementPlaneFindingMode(PlacementSurface.WALL),
        )
    }

    @Test fun `surface flow asks ARCore for horizontal planes only`() {
        assertEquals(
            Config.PlaneFindingMode.HORIZONTAL,
            autoPlacementPlaneFindingMode(PlacementSurface.SURFACE),
        )
    }

    @Test fun `wall flow turns the depth API on where supported`() {
        assertEquals(Config.DepthMode.AUTOMATIC, autoPlacementDepthMode(PlacementSurface.WALL))
    }

    @Test fun `surface flow keeps depth off`() {
        assertEquals(Config.DepthMode.DISABLED, autoPlacementDepthMode(PlacementSurface.SURFACE))
    }
}
