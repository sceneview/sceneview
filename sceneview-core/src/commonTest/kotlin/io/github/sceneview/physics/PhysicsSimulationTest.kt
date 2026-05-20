package io.github.sceneview.physics

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhysicsSimulationTest {

    private val epsilon = 0.001f

    // --- simulateStep ---

    @Test
    fun sleepingBodyDoesNotMove() {
        val state = PhysicsState(
            position = Position(1f, 5f, 0f),
            velocity = Position(0f, -2f, 0f),
            isAsleep = true
        )
        val result = simulateStep(state, 0.016f)
        assertEquals(state, result)
    }

    @Test
    fun gravityAcceleratesDownward() {
        val state = PhysicsState(
            position = Position(0f, 10f, 0f),
            velocity = Position(0f, 0f, 0f)
        )
        val result = simulateStep(state, 0.016f)
        // Velocity should be negative (downward)
        assertTrue(result.velocity.y < 0f, "Gravity should pull velocity downward")
        // Position should decrease
        assertTrue(result.position.y < 10f, "Position should decrease under gravity")
    }

    @Test
    fun gravityAppliedCorrectly() {
        val state = PhysicsState(
            position = Position(0f, 10f, 0f),
            velocity = Position(0f, 0f, 0f)
        )
        val dt = 0.01f
        val result = simulateStep(state, dt)
        val expectedVy = GRAVITY * dt
        assertEquals(expectedVy, result.velocity.y, epsilon)
    }

    @Test
    fun horizontalVelocityPreserved() {
        val state = PhysicsState(
            position = Position(0f, 10f, 0f),
            velocity = Position(3f, 0f, -2f)
        )
        val dt = 0.01f
        val result = simulateStep(state, dt)
        assertEquals(3f, result.velocity.x, epsilon)
        assertEquals(-2f, result.velocity.z, epsilon)
    }

    @Test
    fun positionIntegratesVelocity() {
        val state = PhysicsState(
            position = Position(0f, 10f, 0f),
            velocity = Position(5f, 0f, -3f)
        )
        val dt = 0.02f
        val result = simulateStep(state, dt)
        assertEquals(5f * dt, result.position.x, epsilon)
        assertEquals(-3f * dt, result.position.z, epsilon)
    }

    @Test
    fun floorCollisionBounces() {
        val state = PhysicsState(
            position = Position(0f, 0.01f, 0f),
            velocity = Position(0f, -5f, 0f),
            restitution = 0.8f,
            floorY = 0f,
            radius = 0f
        )
        val result = simulateStep(state, 0.1f)
        // Should bounce: velocity.y should be positive
        assertTrue(result.velocity.y > 0f, "Should bounce off floor")
        // Position should be at floor
        assertEquals(0f, result.position.y, epsilon)
    }

    @Test
    fun floorCollisionRespectsRestitution() {
        val state = PhysicsState(
            position = Position(0f, 0.001f, 0f),
            velocity = Position(0f, -10f, 0f),
            restitution = 0.5f,
            floorY = 0f,
            radius = 0f
        )
        val result = simulateStep(state, 0.01f)
        // After gravity: vy ≈ -10 + GRAVITY*0.01 ≈ -10.098
        // Rebound: vy = 10.098 * 0.5 ≈ 5.049
        assertTrue(result.velocity.y > 0f)
        // Should be roughly half the incoming speed
        assertTrue(result.velocity.y < 6f, "Restitution 0.5 should halve bounce speed")
        assertTrue(result.velocity.y > 4f, "Rebound should be significant")
    }

    @Test
    fun zeroRestitutionNoRebound() {
        val state = PhysicsState(
            position = Position(0f, 0.001f, 0f),
            velocity = Position(0f, -10f, 0f),
            restitution = 0f,
            floorY = 0f,
            radius = 0f
        )
        val result = simulateStep(state, 0.01f)
        // Zero restitution → rebound = 0 → should sleep
        assertTrue(result.isAsleep, "Zero restitution should put body to sleep")
        assertEquals(0f, result.velocity.y, epsilon)
    }

    @Test
    fun sleepOnNegligibleBounce() {
        // Position at floor with tiny downward velocity.
        // After gravity: newVy = -0.02 + (-9.8 * 0.001) ≈ -0.03
        // newPos.y = -0.001 + (-0.03 * 0.001) < 0 → floor collision
        // reboundVy = 0.03 * 0.3 = 0.009 < SLEEP_THRESHOLD(0.05) → sleep
        val state = PhysicsState(
            position = Position(0f, -0.001f, 0f),
            velocity = Position(0f, -0.02f, 0f),
            restitution = 0.3f,
            floorY = 0f,
            radius = 0f
        )
        val result = simulateStep(state, 0.001f)
        assertTrue(result.isAsleep, "Negligible bounce should trigger sleep")
    }

    @Test
    fun radiusAffectsContactPoint() {
        val radius = 0.5f
        val state = PhysicsState(
            position = Position(0f, 0.4f, 0f), // Below contact point (0 + 0.5 = 0.5)
            velocity = Position(0f, -1f, 0f),
            restitution = 0.8f,
            floorY = 0f,
            radius = radius
        )
        val result = simulateStep(state, 0.01f)
        // Contact at floorY + radius = 0.5
        assertEquals(radius, result.position.y, epsilon)
    }

    @Test
    fun floorYOffsetWorks() {
        val state = PhysicsState(
            position = Position(0f, -0.9f, 0f),
            velocity = Position(0f, -2f, 0f),
            restitution = 0.7f,
            floorY = -1f,
            radius = 0f
        )
        val result = simulateStep(state, 0.1f)
        assertEquals(-1f, result.position.y, epsilon)
    }

    @Test
    fun deltaTimeClampedToMax() {
        val state = PhysicsState(
            position = Position(0f, 100f, 0f),
            velocity = Position(0f, 0f, 0f)
        )
        // Large dt should be clamped to 0.05
        val resultLarge = simulateStep(state, 1.0f)
        val resultClamped = simulateStep(state, 0.05f)
        assertEquals(resultClamped.velocity.y, resultLarge.velocity.y, epsilon)
        assertEquals(resultClamped.position.y, resultLarge.position.y, epsilon)
    }

    @Test
    fun negativeDeltaTimeClampedToZero() {
        val state = PhysicsState(
            position = Position(0f, 10f, 0f),
            velocity = Position(0f, -5f, 0f)
        )
        val result = simulateStep(state, -1f)
        // dt clamped to 0 → no change
        assertEquals(state.position, result.position)
    }

    @Test
    fun freeFallNoFloorCollision() {
        val state = PhysicsState(
            position = Position(0f, 100f, 0f),
            velocity = Position(0f, 0f, 0f),
            floorY = 0f
        )
        val result = simulateStep(state, 0.016f)
        assertFalse(result.isAsleep)
        assertTrue(result.position.y > 99f)
    }

    // --- applyImpulse ---

    @Test
    fun impulseAddsVelocity() {
        val state = PhysicsState(
            velocity = Position(1f, 2f, 3f)
        )
        val result = applyImpulse(state, Position(10f, 20f, 30f))
        assertEquals(11f, result.velocity.x, epsilon)
        assertEquals(22f, result.velocity.y, epsilon)
        assertEquals(33f, result.velocity.z, epsilon)
    }

    @Test
    fun impulseWakesUpSleepingBody() {
        val state = PhysicsState(
            position = Position(0f, 0f, 0f),
            velocity = Position(0f, 0f, 0f),
            isAsleep = true
        )
        val result = applyImpulse(state, Position(0f, 5f, 0f))
        assertFalse(result.isAsleep, "Impulse should wake up sleeping body")
        assertEquals(5f, result.velocity.y, epsilon)
    }

    @Test
    fun impulsePreservesPosition() {
        val state = PhysicsState(
            position = Position(5f, 10f, 15f)
        )
        val result = applyImpulse(state, Position(1f, 1f, 1f))
        assertEquals(state.position, result.position)
    }

    @Test
    fun zeroImpulseStillWakes() {
        val state = PhysicsState(isAsleep = true)
        val result = applyImpulse(state, Position(0f, 0f, 0f))
        assertFalse(result.isAsleep, "Even zero impulse should wake body")
    }

    // --- simulateStep with dynamic floor lookup (#1713) ---

    @Test
    fun dynamicFloorRaisesContactSurface() {
        // Static floor at y = 0, dynamic depth-mesh surface reports y = 0.5 → the body must
        // come to rest at y = 0.5 + radius, not on the static floor below.
        val state = PhysicsState(
            position = Position(0f, 0.6f, 0f),
            velocity = Position(0f, -5f, 0f),
            radius = 0.1f,
            floorY = 0f,
            restitution = 0f, // inelastic so the bounce reads cleanly
        )
        val result = simulateStep(state, 0.05f) { _, _, _, _ -> 0.5f }
        // The body lands on the dynamic surface (0.5) offset by its radius (0.1).
        assertEquals(0.6f, result.position.y, epsilon)
    }

    @Test
    fun dynamicFloorNullFallsBackToStaticFloor() {
        // Depth lookup returns null → static floorY = 0 takes effect.
        val state = PhysicsState(
            position = Position(0f, 0.05f, 0f),
            velocity = Position(0f, -5f, 0f),
            radius = 0.1f,
            floorY = 0f,
            restitution = 0f,
        )
        val result = simulateStep(state, 0.05f) { _, _, _, _ -> null }
        // Falls back to static floor at y = 0 + radius (0.1).
        assertEquals(0.1f, result.position.y, epsilon)
    }

    @Test
    fun dynamicFloorDisablesSleepEvenAtNegligibleRebound() {
        // Velocity is tiny and dt is short enough that gravity's contribution stays under the
        // sleep threshold: the body WOULD sleep on a static floor (no depth source) — pinned by
        // [dynamicFloorAllowsSleepWhenLookupReturnsNull] — but with a depth surface, the
        // simulation MUST keep it awake so the next mesh rebuild can re-evaluate the contact.
        val state = PhysicsState(
            position = Position(0f, 0.099f, 0f),
            velocity = Position(0f, -0.02f, 0f),
            radius = 0.1f,
            restitution = 0.5f,
        )
        // dt small enough that gravity adds only ~0.01 to velocity → |reboundVy| stays under
        // SLEEP_THRESHOLD = 0.05.
        val result = simulateStep(state, 0.001f) { _, _, _, _ -> 0f }
        assertFalse(result.isAsleep, "Bodies on a depth surface must not sleep")
    }

    @Test
    fun dynamicFloorAllowsSleepWhenLookupReturnsNull() {
        // Same setup as above, but no depth surface → sleep still kicks in via the static floor.
        val state = PhysicsState(
            position = Position(0f, 0.099f, 0f),
            velocity = Position(0f, -0.02f, 0f),
            radius = 0.1f,
            restitution = 0.5f,
        )
        val result = simulateStep(state, 0.001f) { _, _, _, _ -> null }
        assertTrue(result.isAsleep, "Static-floor sleep behaviour is preserved")
    }

    @Test
    fun dynamicFloorLookupReceivesProjectedPosition() {
        // Pin that the lookup is invoked with the post-integration XYZ + the body's radius,
        // not the pre-integration values — Depth Lab's "Collider" feeds the projected XZ so
        // a fast-moving body queries the surface where it will land.
        var seenX: Float? = null
        var seenZ: Float? = null
        var seenR: Float? = null
        val state = PhysicsState(
            position = Position(1f, 1f, 2f),
            velocity = Position(10f, 0f, 20f),
            radius = 0.25f,
        )
        simulateStep(state, 0.01f) { x, _, z, r ->
            seenX = x
            seenZ = z
            seenR = r
            null
        }
        // After integration: x = 1 + 10*0.01 = 1.10; z = 2 + 20*0.01 = 2.20
        assertEquals(1.1f, seenX!!, epsilon)
        assertEquals(2.2f, seenZ!!, epsilon)
        assertEquals(0.25f, seenR!!, epsilon)
    }

    @Test
    fun dynamicFloorSleepingBodyStaysAsleep() {
        // The depth lookup variant must respect the same isAsleep guard as the static one —
        // PhysicsBody's lifecycle can put a body to sleep then later attach a depth collider.
        val state = PhysicsState(
            position = Position(0f, 0f, 0f),
            velocity = Position(0f, 0f, 0f),
            isAsleep = true,
        )
        val result = simulateStep(state, 0.01f) { _, _, _, _ -> 5f }
        assertEquals(state, result)
    }

    // --- PhysicsState defaults ---

    @Test
    fun defaultStateValues() {
        val state = PhysicsState()
        assertEquals(Position(), state.position)
        assertEquals(Position(), state.velocity)
        assertEquals(0.6f, state.restitution, epsilon)
        assertEquals(0f, state.floorY, epsilon)
        assertEquals(0f, state.radius, epsilon)
        assertFalse(state.isAsleep)
    }
}
