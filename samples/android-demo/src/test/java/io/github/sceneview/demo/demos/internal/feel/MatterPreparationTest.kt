package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest

class MatterPreparationTest {
    @Test fun coldSelectionDoesNotWaitForAnotherAtlasAndDuplicateRequestsShareTheirWork() = runTest {
        val preparation = MatterPreparation()
        val atlas = CompletableDeferred<Unit>()
        val marble = preparation.request(this, FeelMaterial.Marble) { atlas.await() }
        val duplicate = preparation.request(this, FeelMaterial.Marble) { error("Duplicate upload") }
        assertSame(marble, duplicate)
        val steel = preparation.request(this, FeelMaterial.BrushedSteel) { }
        steel.await()
        assertTrue(steel.isCompleted)
        assertFalse(marble.isCompleted)
        atlas.complete(Unit)
        marble.await()
        preparation.prepared(FeelMaterial.MemoryFoam)
        assertEquals(FeelMaterial.Gel, preparation.next(FeelMaterial.Marble, revealed = true))
    }

    @Test fun onlyFoamCanPrepareBeforeRevealEvenIfAnotherMaterialIsRequested() {
        val preparation = MatterPreparation()
        assertEquals(FeelMaterial.MemoryFoam, preparation.next(FeelMaterial.Leather, revealed = false))
        preparation.prepared(FeelMaterial.MemoryFoam)
        assertNull(preparation.next(FeelMaterial.Leather, revealed = false))
        assertEquals(FeelMaterial.Leather, preparation.next(FeelMaterial.Leather, revealed = true))
    }

    @Test fun selectionOvertakesBackgroundQueueAndEachMaterialIsPreparedOnce() {
        val preparation = MatterPreparation()
        preparation.prepared(FeelMaterial.MemoryFoam)
        val warmed = mutableListOf<FeelMaterial>()
        for (selection in listOf(FeelMaterial.MemoryFoam, FeelMaterial.BrushedSteel,
            FeelMaterial.Velvet, FeelMaterial.Marble, FeelMaterial.MemoryFoam)) {
            val next = checkNotNull(preparation.next(selection, revealed = true))
            warmed += next
            preparation.prepared(next)
        }
        assertEquals(listOf(FeelMaterial.Gel, FeelMaterial.BrushedSteel, FeelMaterial.Velvet,
            FeelMaterial.Marble, FeelMaterial.Leather), warmed)
        assertNull(preparation.next(FeelMaterial.Gel, revealed = true))
    }
}
