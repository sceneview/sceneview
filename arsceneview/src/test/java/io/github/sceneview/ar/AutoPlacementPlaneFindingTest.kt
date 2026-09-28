package io.github.sceneview.ar

import com.google.ar.core.Config
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The plane finding each automatic-placement flow asks ARCore for (#4070). The wall flow must
 * request vertical planes, or no wall can ever be found; the surface flow stays horizontal.
 */
class AutoPlacementPlaneFindingTest {
    @Test fun `wall flow asks ARCore for vertical planes`() {
        assertEquals(
            Config.PlaneFindingMode.VERTICAL,
            autoPlacementPlaneFindingMode(PlacementSurface.WALL),
        )
    }

    @Test fun `surface flow asks ARCore for horizontal planes only`() {
        assertEquals(
            Config.PlaneFindingMode.HORIZONTAL,
            autoPlacementPlaneFindingMode(PlacementSurface.SURFACE),
        )
    }
}
