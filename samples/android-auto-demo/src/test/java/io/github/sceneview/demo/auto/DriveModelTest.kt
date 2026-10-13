package io.github.sceneview.demo.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class DriveModelTest {

    private val arena = 20f
    private val podium = 4f

    private fun model() = DriveModel(arenaRadius = arena, obstacleRadius = podium)

    private fun DriveModel.run(seconds: Float, input: DriveInput) {
        repeat((seconds / STEP).toInt()) { step(STEP, input) }
    }

    @Test
    fun `a parked car stays where it is`() {
        val car = model()
        car.run(5f, DriveInput())
        assertEquals(DriveModel.START_X, car.x, 0f)
        assertEquals(DriveModel.START_Z, car.z, 0f)
        assertEquals(0f, car.speed, 0f)
    }

    @Test
    fun `the throttle drives the car along its nose`() {
        val car = model()
        car.run(1f, DriveInput(throttle = true))
        // The start heading is 90 degrees: the nose points along +X.
        assertTrue(car.x > DriveModel.START_X + 1f)
        assertEquals(DriveModel.START_Z, car.z, 0.01f)
        assertTrue(car.speed > 0f)
    }

    @Test
    fun `the speed never passes the limit`() {
        val car = model()
        repeat(2_000) {
            car.step(STEP, DriveInput(throttle = true, steer = 1))
            assertTrue(car.speed <= DriveModel.MAX_SPEED)
        }
    }

    @Test
    fun `steering right turns the nose clockwise seen from above`() {
        val car = model()
        car.run(1f, DriveInput(throttle = true, steer = 1))
        assertTrue("heading ${car.heading}", car.heading < DriveModel.START_HEADING)
        val other = model()
        other.run(1f, DriveInput(throttle = true, steer = -1))
        assertTrue("heading ${other.heading}", other.heading > DriveModel.START_HEADING)
    }

    @Test
    fun `a car that does not move does not turn`() {
        val car = model()
        car.run(2f, DriveInput(steer = 1))
        assertEquals(DriveModel.START_HEADING, car.heading, 0f)
    }

    @Test
    fun `the brake stops the car and lights the brake lights, then backs it up`() {
        val car = model()
        car.run(1.5f, DriveInput(throttle = true))
        car.step(STEP, DriveInput(brake = true))
        assertTrue(car.braking)
        car.run(1.5f, DriveInput(brake = true))
        assertTrue("speed ${car.speed}", car.speed < 0f)
        assertFalse(car.braking)
        car.run(10f, DriveInput(brake = true))
        assertTrue(car.speed >= -DriveModel.MAX_REVERSE)
    }

    @Test
    fun `a car left alone coasts to a stop`() {
        val car = model()
        car.run(1f, DriveInput(throttle = true))
        car.run(30f, DriveInput())
        assertEquals(0f, car.speed, 0f)
    }

    @Test
    fun `the car never leaves the floor`() {
        val car = model()
        // Flat out in a straight line, then every way a thumb can lean on the pads.
        val inputs = listOf(
            DriveInput(throttle = true),
            DriveInput(throttle = true, steer = 1),
            DriveInput(brake = true, steer = -1),
            DriveInput(throttle = true, steer = -1),
            DriveInput(brake = true),
        )
        inputs.forEach { input ->
            repeat(1_500) {
                car.step(STEP, input)
                val distance = hypot(car.x, car.z)
                assertTrue("left the floor at $distance", distance <= arena + EPSILON)
                assertTrue("on the podium at $distance", distance >= podium - EPSILON)
            }
        }
    }

    @Test
    fun `a wall takes the speed of a car driven straight into it`() {
        val car = model()
        car.run(20f, DriveInput(throttle = true))
        assertEquals(arena, hypot(car.x, car.z), EPSILON)
        assertTrue("speed ${car.speed}", car.speed < 1f)
    }

    @Test
    fun `reset puts the car back on its mark`() {
        val car = model()
        car.run(3f, DriveInput(throttle = true, steer = 1))
        car.reset()
        assertEquals(DriveModel.START_X, car.x, 0f)
        assertEquals(DriveModel.START_Z, car.z, 0f)
        assertEquals(DriveModel.START_HEADING, car.heading, 0f)
        assertEquals(0f, car.speed, 0f)
    }

    @Test
    fun `the mark is on the floor`() {
        val start = hypot(DriveModel.START_X, DriveModel.START_Z)
        assertTrue(start < GarageStage.DRIVE_RADIUS)
        assertTrue(start > GarageStage.PODIUM_RADIUS + GarageStage.PODIUM_CLEARANCE)
        assertTrue(GarageStage.DRIVE_RADIUS < GarageStage.EDGE_LINE_RADIUS)
        assertTrue(GarageStage.EDGE_LINE_RADIUS < GarageStage.PILLAR_RING_RADIUS - GarageStage.PILLAR_SIDE)
    }

    private companion object {
        const val STEP = 1f / 60f
        const val EPSILON = 1e-3f
    }
}
