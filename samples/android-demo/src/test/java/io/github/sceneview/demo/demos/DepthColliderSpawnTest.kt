package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure-JVM tests for [depthColliderSpawn] — the geometry behind the Depth Collider demo's Drop
 * button (#3217). Pins that a ball always starts in front of the user and is thrown away from
 * them, including the case the bug report describes: the phone aimed steeply at the floor.
 */
class DepthColliderSpawnTest {

    private val origin = floatArrayOf(0f, 1.4f, 0f)

    @Test
    fun `looking straight ahead spawns ahead, slightly up, and throws forward`() {
        val spawn = depthColliderSpawn(
            index = 2, // centre of the first row → no lateral scatter
            cameraPosition = origin,
            cameraForward = floatArrayOf(0f, 0f, -1f),
            cameraUp = floatArrayOf(0f, 1f, 0f),
        )
        assertEquals(0f, spawn.position.x, 1e-5f)
        assertEquals(1.4f + SPAWN_LIFT_M, spawn.position.y, 1e-5f)
        assertEquals(-SPAWN_AHEAD_M, spawn.position.z, 1e-5f)
        assertEquals(-THROW_SPEED_M_S, spawn.velocity.z, 1e-5f)
        assertEquals(0f, spawn.velocity.y, 1e-5f)
    }

    @Test
    fun `aiming at the floor keeps the ball at least half a metre in front horizontally`() {
        // Pitched 75° down, looking towards -Z.
        val pitch = Math.toRadians(75.0)
        val forward = floatArrayOf(0f, -sin(pitch), -cos(pitch))
        val up = floatArrayOf(0f, cos(pitch), -sin(pitch))
        val spawn = depthColliderSpawn(2, origin, forward, up)

        val horizontal = sqrt(spawn.position.x * spawn.position.x + spawn.position.z * spawn.position.z)
        assertTrue("ball spawned at the user's feet: $spawn", horizontal >= SPAWN_MIN_HORIZONTAL_M - 1e-5f)
        assertTrue("ball should be ahead (-Z), was ${spawn.position.z}", spawn.position.z < 0f)
        // Thrown downwards along the view ray, towards the aimed floor, not dropped at rest.
        assertTrue(spawn.velocity.y < 0f)
        assertTrue(spawn.velocity.z < 0f)
    }

    @Test
    fun `looking straight down falls back to the phone's top edge as heading`() {
        // Camera -Z points straight down; the top of the phone points towards -Z world.
        val spawn = depthColliderSpawn(
            index = 2,
            cameraPosition = origin,
            cameraForward = floatArrayOf(0f, -1f, 0f),
            cameraUp = floatArrayOf(0f, 0f, -1f),
        )
        assertEquals(0f, spawn.position.x, 1e-5f)
        assertEquals(-SPAWN_MIN_HORIZONTAL_M, spawn.position.z, 1e-5f)
        assertTrue(spawn.position.y < origin[1]) // below the camera, above the floor
        assertEquals(-THROW_SPEED_M_S, spawn.velocity.y, 1e-5f)
    }

    @Test
    fun `spawn follows the camera heading, not world axes`() {
        // Looking along world +X: the ball must be at +X, and the lateral scatter along ±Z.
        val left = depthColliderSpawn(0, origin, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        val right = depthColliderSpawn(4, origin, floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        assertEquals(SPAWN_AHEAD_M, left.position.x, 1e-5f)
        assertEquals(SPAWN_AHEAD_M, right.position.x, 1e-5f)
        assertEquals(THROW_SPEED_M_S, left.velocity.x, 1e-5f)
        // Scatter is symmetric about the heading.
        assertEquals(-right.position.z, left.position.z, 1e-5f)
        assertTrue(left.position.z != right.position.z)
    }

    @Test
    fun `second row of a Drop 5 is staggered further ahead`() {
        val first = depthColliderSpawn(2, origin, floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f))
        val second = depthColliderSpawn(7, origin, floatArrayOf(0f, 0f, -1f), floatArrayOf(0f, 1f, 0f))
        assertTrue(second.position.z < first.position.z)
    }

    private fun sin(r: Double) = kotlin.math.sin(r).toFloat()
    private fun cos(r: Double) = kotlin.math.cos(r).toFloat()
}
