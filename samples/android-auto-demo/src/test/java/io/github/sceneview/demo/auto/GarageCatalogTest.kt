package io.github.sceneview.demo.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalog and `stageGarageAssets` (build.gradle) name the same files twice. A path in the
 * catalog that is not staged is a car that never loads — and a cover that never lifts.
 */
class GarageCatalogTest {

    /** Gradle runs unit tests from the module directory. */
    private val staged = File("build/generated/garageAssets")

    @Test
    fun `every car is staged`() {
        GarageCatalog.cars.forEach { car ->
            assertTrue("${car.assetPath} is not staged", File(staged, car.assetPath).isFile)
        }
    }

    @Test
    fun `every environment is staged`() {
        GarageCatalog.lightings.forEach { lighting ->
            assertTrue("${lighting.hdrPath} is not staged", File(staged, lighting.hdrPath).isFile)
        }
    }

    @Test
    fun `nothing is staged that the catalog does not use`() {
        val used = (GarageCatalog.cars.map { it.assetPath } + GarageCatalog.lightings.map { it.hdrPath }).toSet()
        val shipped = staged.walkTopDown().filter { it.isFile }.map { it.relativeTo(staged).path }.toSet()
        assertEquals(used, shipped)
    }

    @Test
    fun `a car's body is never longer than its model`() {
        GarageCatalog.cars.forEach { car ->
            assertTrue("${car.label}: bodyLength over length", car.bodyLength <= car.length)
        }
    }

    @Test
    fun `one mood switches the headlights on`() {
        assertTrue(GarageCatalog.lightings.any { it.headlights })
    }

    @Test
    fun `every car carries its credit`() {
        GarageCatalog.cars.forEach { car ->
            assertTrue("${car.label} has no credit", car.credit.isNotBlank())
        }
    }
}
