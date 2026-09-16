package io.github.sceneview.physics

import io.github.sceneview.math.Position
import kotlin.math.abs

/**
 * Pure, platform-independent rigid-body physics state.
 *
 * Uses simple Euler integration — no external physics library dependencies.
 * Supports gravity, floor collision with restitution (bounce), and sleep detection.
 *
 * Physics coordinate system: +Y is up, gravity pulls in -Y direction.
 *
 * @param position Current world-space position.
 * @param velocity Current linear velocity in m/s (world space).
 * @param restitution Coefficient of restitution [0..1]. 0 = inelastic, 1 = perfectly elastic.
 * @param floorY World-space Y coordinate of the floor plane.
 * @param radius Collision radius in meters (bottom of sphere = position.y - radius).
 * @param isAsleep True once the body has come to rest.
 * @param gravity Gravitational acceleration vector in m/s². Defaults to `(0, [GRAVITY], 0)` —
 *                straight down. Tilt it to simulate a sloped surface without moving the floor
 *                plane: express the slope's rotation in the floor's own frame and rotate this
 *                vector by its inverse, and a resting body accelerates along the slope while the
 *                `floorY` clamp keeps it on the plane (#3621). The magnitude is used as given, so
 *                a zero vector means weightlessness.
 */
data class PhysicsState(
    val position: Position = Position(),
    val velocity: Position = Position(),
    val restitution: Float = 0.6f,
    val floorY: Float = 0f,
    val radius: Float = 0f,
    val isAsleep: Boolean = false,
    val gravity: Position = Position(0f, GRAVITY, 0f)
) {
    /**
     * Binary-compatibility shim for the pre-`gravity` constructor descriptor
     * `(Float3, Float3, F, F, F, Z)V` and its default-mask synthetic. Hidden from source
     * resolution, so Kotlin callers always bind to the primary constructor; it exists only so
     * code compiled against 4.36.0 and earlier keeps linking (CONTRIBUTING.md — a removed or
     * retyped public symbol is a breaking change).
     *
     * `isAsleep` is deliberately the one parameter without a default: a fully defaulted
     * secondary constructor would generate a second `<init>()V` and clash with the primary's.
     */
    @Deprecated(
        "Binary-compatibility overload. Use the primary constructor, which takes `gravity`.",
        level = DeprecationLevel.HIDDEN
    )
    constructor(
        position: Position = Position(),
        velocity: Position = Position(),
        restitution: Float = 0.6f,
        floorY: Float = 0f,
        radius: Float = 0f,
        isAsleep: Boolean
    ) : this(position, velocity, restitution, floorY, radius, isAsleep, Position(0f, GRAVITY, 0f))

    /**
     * Binary-compatibility shim for the pre-`gravity` `copy` descriptor. Same rationale as the
     * secondary constructor above; hidden from source so `copy(...)` always means the generated
     * seven-parameter one. Carries [gravity] over, so an old-descriptor `copy` no longer silently
     * resets a tilted gravity to straight down.
     */
    @Deprecated(
        "Binary-compatibility overload. Use copy(), which also takes `gravity`.",
        level = DeprecationLevel.HIDDEN
    )
    fun copy(
        position: Position = this.position,
        velocity: Position = this.velocity,
        restitution: Float = this.restitution,
        floorY: Float = this.floorY,
        radius: Float = this.radius,
        isAsleep: Boolean
    ): PhysicsState = copy(
        position = position,
        velocity = velocity,
        restitution = restitution,
        floorY = floorY,
        radius = radius,
        isAsleep = isAsleep,
        gravity = gravity
    )
}

/** Gravitational acceleration in m/s² (downward along -Y). */
const val GRAVITY = -9.8f

/** Velocities below this threshold are zeroed to stop micro-bouncing. */
const val SLEEP_THRESHOLD = 0.05f

/**
 * True when [PhysicsState.gravity] has no horizontal component, i.e. the body can legitimately
 * come to rest on a horizontal floor. A tilted gravity keeps pushing a contacting body sideways,
 * so sleep detection is suppressed in that case.
 */
internal val PhysicsState.isGravityVertical: Boolean
    get() = gravity.x == 0f && gravity.z == 0f

/**
 * Advance the physics simulation by [deltaSeconds].
 *
 * Pure function — no mutation, no side effects. Returns a new [PhysicsState].
 *
 * @param state Current physics state.
 * @param deltaSeconds Time step in seconds (clamped to 0..0.05 internally to avoid instability).
 * @return Updated physics state after one integration step.
 */
fun simulateStep(state: PhysicsState, deltaSeconds: Float): PhysicsState {
    if (state.isAsleep) return state

    val dt = deltaSeconds.coerceIn(0f, 0.05f)

    // Apply the gravity vector. It is vertical by default, but a tilted vector is what makes a
    // body slide along a sloped floor (#3621) — hence all three axes, not just Y.
    val newVelocity = Position(
        x = state.velocity.x + state.gravity.x * dt,
        y = state.velocity.y + state.gravity.y * dt,
        z = state.velocity.z + state.gravity.z * dt
    )

    // Integrate position
    var newPosition = Position(
        x = state.position.x + newVelocity.x * dt,
        y = state.position.y + newVelocity.y * dt,
        z = state.position.z + newVelocity.z * dt
    )

    // Floor collision
    val contactY = state.floorY + state.radius
    if (newPosition.y < contactY) {
        newPosition = Position(newPosition.x, contactY, newPosition.z)
        val reboundVy = -newVelocity.y * state.restitution

        // Sleep when rebound speed is negligible — but only under a vertical gravity. Under a
        // tilted vector the body is still being accelerated along the plane, so falling asleep on
        // first contact would freeze it halfway down the slope forever.
        return if (state.isGravityVertical && abs(reboundVy) < SLEEP_THRESHOLD) {
            state.copy(
                position = newPosition,
                velocity = Position(newVelocity.x, 0f, newVelocity.z),
                isAsleep = true
            )
        } else {
            state.copy(
                position = newPosition,
                velocity = Position(newVelocity.x, reboundVy, newVelocity.z)
            )
        }
    }

    return state.copy(position = newPosition, velocity = newVelocity)
}

/**
 * Variant of [simulateStep] that consults a dynamic floor source — typically the depth-mesh
 * collider in `arsceneview/` (#1713) — so virtual bodies bounce off real-world geometry.
 *
 * The lookup is invoked once per step at the **projected** XZ centre (after gravity + velocity
 * integration, before the floor clamp). When it returns `null`, the simulation falls back to the
 * configured [PhysicsState.floorY] plane. When it returns a value, that value replaces the static
 * floor for this step only — so a body resting on a real desk can fall off and bounce off the
 * real floor below the next time the desk's triangles get edge-culled.
 *
 * To keep the body alive on a wobbly real-world mesh, sleep detection is **disabled** whenever
 * [floorLookup] returns a non-null value at the contact point. A 5 Hz mesh refresh combined with
 * sleep detection can otherwise freeze a body mid-air the moment its supporting triangles drop
 * out for one rebuild.
 *
 * Pure function — no mutation, no side effects. Returns a new [PhysicsState].
 *
 * @param state Current physics state.
 * @param deltaSeconds Time step in seconds (clamped to 0..0.05 internally to avoid instability).
 * @param floorLookup Returns the world-space Y of the surface under the projected (x, y, z), or
 *                    `null` to use the static floor.
 * @return Updated physics state after one integration step.
 */
fun simulateStep(
    state: PhysicsState,
    deltaSeconds: Float,
    floorLookup: (x: Float, y: Float, z: Float, radius: Float) -> Float?,
): PhysicsState {
    if (state.isAsleep) return state

    val dt = deltaSeconds.coerceIn(0f, 0.05f)

    val newVelocity = Position(
        x = state.velocity.x + state.gravity.x * dt,
        y = state.velocity.y + state.gravity.y * dt,
        z = state.velocity.z + state.gravity.z * dt
    )

    var newPosition = Position(
        x = state.position.x + newVelocity.x * dt,
        y = state.position.y + newVelocity.y * dt,
        z = state.position.z + newVelocity.z * dt
    )

    val dynamicSurface = floorLookup(newPosition.x, newPosition.y, newPosition.z, state.radius)
    val surfaceY = dynamicSurface ?: state.floorY
    val contactY = surfaceY + state.radius

    if (newPosition.y < contactY) {
        newPosition = Position(newPosition.x, contactY, newPosition.z)
        val reboundVy = -newVelocity.y * state.restitution

        // Disable sleep when we're resting on a depth-driven surface: a 5 Hz mesh refresh can
        // momentarily edge-cull the supporting triangles and an asleep body would freeze in
        // mid-air. Only let the body sleep on the static plane.
        val canSleep = dynamicSurface == null && state.isGravityVertical
        return if (canSleep && abs(reboundVy) < SLEEP_THRESHOLD) {
            state.copy(
                position = newPosition,
                velocity = Position(newVelocity.x, 0f, newVelocity.z),
                isAsleep = true
            )
        } else {
            state.copy(
                position = newPosition,
                velocity = Position(newVelocity.x, reboundVy, newVelocity.z)
            )
        }
    }

    return state.copy(position = newPosition, velocity = newVelocity)
}

/**
 * Apply an instantaneous impulse to the physics state.
 *
 * @param state Current physics state.
 * @param impulse Velocity change in m/s (world space).
 * @return Updated state with modified velocity, woken up if sleeping.
 */
fun applyImpulse(state: PhysicsState, impulse: Position): PhysicsState {
    return state.copy(
        velocity = Position(
            x = state.velocity.x + impulse.x,
            y = state.velocity.y + impulse.y,
            z = state.velocity.z + impulse.z
        ),
        isAsleep = false
    )
}
