package io.github.sceneview.demo.auto

import org.junit.Assert.assertEquals
import org.junit.Test

class GarageSelectionTest {

    private val cars = listOf(
        Car("Two finishes", "a.glb", "credit", paints = listOf(VariantPaint("One", "one"), VariantPaint("Two", "two"))),
        Car("No finish", "b.glb", "credit"),
    )
    private val lightings = listOf(
        Lighting("Day", "day.hdr", keyIntensity = 1f, keyKelvin = 6_500f),
        Lighting("Night", "night.hdr", keyIntensity = 1f, keyKelvin = 6_500f),
    )

    @Test
    fun `the next car wraps around and starts in its first finish`() {
        val second = GarageSelection(car = 0, paint = 1).nextCar(cars)
        assertEquals(GarageSelection(car = 1, paint = 0), second)
        assertEquals(GarageSelection(car = 0, paint = 0), second.nextCar(cars))
    }

    @Test
    fun `the next finish wraps around`() {
        val last = GarageSelection(car = 0, paint = 0).nextPaint(cars)
        assertEquals(1, last.paint)
        assertEquals(0, last.nextPaint(cars).paint)
    }

    @Test
    fun `a car without finishes keeps its selection`() {
        val selection = GarageSelection(car = 1)
        assertEquals(selection, selection.nextPaint(cars))
    }

    @Test
    fun `switching the car keeps the lighting`() {
        assertEquals(1, GarageSelection(lighting = 1).nextCar(cars).lighting)
    }

    @Test
    fun `the next lighting wraps around`() {
        assertEquals(0, GarageSelection(lighting = 1).nextLighting(lightings).lighting)
    }

    @Test
    fun `a selection saved by another catalog falls back instead of crashing`() {
        fun sanitized(car: Int, paint: Int, lighting: Int = 0) =
            GarageSelection(car, paint, lighting).sanitized(cars, lightings)
        assertEquals(GarageSelection(), sanitized(car = 7, paint = 3, lighting = 9))
        assertEquals(GarageSelection(car = 0, paint = 0), sanitized(car = 0, paint = 5))
        assertEquals(GarageSelection(car = 1, paint = 0), sanitized(car = 1, paint = 1))
    }

    @Test
    fun `a valid selection is left alone`() {
        val selection = GarageSelection(car = 0, paint = 1, lighting = 1)
        assertEquals(selection, selection.sanitized(cars, lightings))
    }
}
