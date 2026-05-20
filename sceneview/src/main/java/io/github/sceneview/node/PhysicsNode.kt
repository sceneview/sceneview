package io.github.sceneview.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.github.sceneview.math.Position
import io.github.sceneview.utils.intervalSeconds

/**
 * Looks up the world-space Y of a "floor" surface directly under a sphere centred at the given
 * world position with the given collision radius. Returns `null` when no surface is available
 * (e.g. the depth mesh has not been built yet, the body is over a depth hole, or the body is
 * outside the device's depth FOV).
 *
 * Implemented by [io.github.sceneview.ar.physics.DepthCollider] (#1713). Kept in `sceneview/` so
 * the AR collider can be injected into [PhysicsBody] without `arsceneview/` depending on
 * `sceneview/`'s internals.
 *
 * The contract: the implementation is **called on the render thread once per frame, per body**.
 * Implementations must be safe to call from there without extra synchronisation and should not
 * allocate.
 */
fun interface FloorProvider {
    /**
     * @param x       Sphere centre X in world space.
     * @param y       Sphere centre Y in world space.
     * @param z       Sphere centre Z in world space.
     * @param radius  Collision radius of the body in metres.
     * @return        World-space Y of the highest surface under ([x],[z]), or `null` to fall
     *                through to the body's static `floorY`.
     */
    fun floorYAt(x: Float, y: Float, z: Float, radius: Float): Float?
}

/**
 * Rigid-body physics state attached to a [Node].
 *
 * Uses a simple Euler integration step executed every frame via the node's [Node.onFrame]
 * callback. There are no external library dependencies — gravity and floor collision are
 * handled with pure Kotlin arithmetic.
 *
 * Physics coordinate system matches SceneView / Filament:
 *   +Y is up, gravity pulls in the -Y direction.
 *
 * @param node          The node whose [Node.position] is driven by the simulation.
 * @param restitution   Coefficient of restitution in [0, 1]. 0 = fully inelastic,
 *                      1 = perfectly elastic. Applied on each floor bounce.
 * @param floorY        World-space Y coordinate of the floor plane used when [floorProvider] is
 *                      `null` or returns `null` for this body. Default 0.0 (scene origin).
 * @param radius        Collision radius of the object in meters. Used to offset the floor
 *                      contact point so the surface of the sphere sits on the floor, not
 *                      its centre.
 * @param initialVelocity   Initial linear velocity in m/s (world space).
 * @param floorProvider Optional dynamic floor source — typically an
 *                      [io.github.sceneview.ar.physics.DepthCollider] (#1713) so the body bounces
 *                      off the **real** floor / table / wall instead of a static plane. When
 *                      `null` or when the provider returns `null` at the body's current XZ, the
 *                      simulation falls back to the static [floorY] plane.
 */
class PhysicsBody(
    val node: Node,
    val restitution: Float = 0.6f,
    val floorY: Float = 0f,
    val radius: Float = 0f,
    initialVelocity: Position = Position(0f, 0f, 0f),
    var floorProvider: FloorProvider? = null,
) {
    /**
     * Mass in kg.
     *
     * The current Euler integration only applies gravity, which is an acceleration and is
     * therefore mass-independent (all bodies fall at the same rate). `mass` only becomes
     * meaningful once a force/impulse API exists (`acceleration = force / mass`). Until
     * then this property is a no-op and is kept solely so callers can read back the value
     * they configured.
     */
    @Deprecated(
        "Mass is currently a no-op: the Euler integration applies only gravity, which is " +
            "mass-independent. It will gain an effect once a force/impulse API lands.",
        level = DeprecationLevel.WARNING
    )
    var mass: Float = 1f
        private set

    @Deprecated(
        "The 'mass' parameter is currently a no-op (the Euler integration applies only " +
            "gravity, which is mass-independent). Use the constructor without 'mass'.",
        ReplaceWith("PhysicsBody(node, restitution, floorY, radius, initialVelocity)"),
        DeprecationLevel.WARNING
    )
    constructor(
        node: Node,
        mass: Float,
        restitution: Float = 0.6f,
        floorY: Float = 0f,
        radius: Float = 0f,
        initialVelocity: Position = Position(0f, 0f, 0f)
    ) : this(node, restitution, floorY, radius, initialVelocity) {
        @Suppress("DEPRECATION")
        this.mass = mass
    }

    companion object {
        const val GRAVITY = -9.8f   // m/s² downward (-Y)

        /** Velocities below this threshold are zeroed to stop micro-bouncing. */
        const val SLEEP_THRESHOLD = 0.05f
    }

    /** Current linear velocity in m/s (world space). */
    var velocity: Position = initialVelocity

    /** True once the body has come to rest on the floor. */
    var isAsleep: Boolean = false
        private set

    /**
     * Advance the simulation by [frameTimeNanos] nanoseconds from [prevFrameTimeNanos].
     *
     * Call this from a [Node.onFrame] lambda or from a [Scene] `onFrame` block.
     */
    fun step(frameTimeNanos: Long, prevFrameTimeNanos: Long?) {
        if (isAsleep) return

        val dt = frameTimeNanos.intervalSeconds(prevFrameTimeNanos).toFloat()
        // Clamp dt to avoid huge jumps after e.g. a GC pause or first frame.
        val safeDt = dt.coerceIn(0f, 0.05f)

        // Apply gravity to vertical velocity.
        velocity = Position(
            x = velocity.x,
            y = velocity.y + GRAVITY * safeDt,
            z = velocity.z
        )

        // Integrate position.
        val pos = node.position
        var newPos = Position(
            x = pos.x + velocity.x * safeDt,
            y = pos.y + velocity.y * safeDt,
            z = pos.z + velocity.z * safeDt
        )

        // Floor collision: the bottom of the sphere is at (centre.y - radius). When a dynamic
        // floor provider is attached (typically a depth-mesh collider, #1713), ask it for the
        // surface Y under the body's current XZ — that's how virtual rigid bodies bounce off the
        // real floor / table / wall in AR. Falls back to the static `floorY` plane when the
        // provider has no answer (mesh not ready, body over a depth hole, etc.).
        val surfaceY = floorProvider?.floorYAt(newPos.x, newPos.y, newPos.z, radius) ?: floorY
        val contactY = surfaceY + radius
        if (newPos.y < contactY) {
            newPos = Position(newPos.x, contactY, newPos.z)
            val reboundVy = -velocity.y * restitution
            velocity = Position(velocity.x, reboundVy, velocity.z)

            // Put the body to sleep when the rebound speed is negligible. Only allow sleep on
            // the **static** floor: the depth mesh refreshes 5× a second and an "asleep" body
            // on a wobbly mesh would freeze in mid-air the moment its supporting triangles get
            // edge-culled. A body resting on real geometry stays awake and re-evaluates.
            if (kotlin.math.abs(reboundVy) < SLEEP_THRESHOLD && floorProvider == null) {
                velocity = Position(velocity.x, 0f, velocity.z)
                isAsleep = true
            }
        }

        node.position = newPos
    }
}

// ── Composable DSL helper ─────────────────────────────────────────────────────────────────────────

/**
 * Attaches a [PhysicsBody] to [node] and steps the simulation each frame via [Node.onFrame].
 *
 * This is a pure-Kotlin, no-library physics integration intended as a lightweight prototype.
 * It supports:
 * - Gravity (9.8 m/s²) along -Y
 * - Bouncy floor collision with a configurable coefficient of restitution
 * - Sleep detection to halt integration once the body comes to rest
 * - Optional dynamic floor lookup via [floorProvider] — used by the #1713 depth collider so the
 *   body bounces off the real floor / table / wall instead of a static plane
 *
 * Usage inside a `SceneView { }` block:
 * ```kotlin
 * SceneView(...) {
 *     val sphereNode = remember(engine) { SphereNode(engine, radius = 0.15f) }
 *     PhysicsNode(
 *         node = sphereNode,
 *         restitution = 0.7f,
 *         linearVelocity = Position(x = 0.5f, y = 2f, z = 0f)
 *     )
 * }
 * ```
 *
 * In AR (`ARSceneView { }`), pair it with a depth collider to bounce off the real world:
 * ```kotlin
 * ARSceneView(
 *     sessionConfiguration = { _, config -> config.depthMode = Config.DepthMode.AUTOMATIC }
 * ) {
 *     val depthCollider = rememberDepthCollider(refreshIntervalMs = 200L)
 *     PhysicsNode(
 *         node = ballNode,
 *         restitution = 0.7f,
 *         radius = 0.05f,
 *         floorProvider = depthCollider,
 *     )
 * }
 * ```
 *
 * @param node           The [Node] to animate. Must already be added to the scene by the caller.
 * @param restitution    Bounciness in [0, 1].
 * @param linearVelocity Initial velocity in m/s.
 * @param floorY         World Y of the static floor plane (default 0). Used when [floorProvider]
 *                       is `null` or returns `null` at the body's XZ.
 * @param radius         Collision radius in metres — offsets the contact point so the sphere
 *                       surface, not its centre, lands on the floor.
 * @param floorProvider  Optional dynamic floor source. Typically a
 *                       [io.github.sceneview.ar.physics.DepthCollider] (#1713). `null` keeps the
 *                       legacy static-plane behaviour.
 */
@Composable
fun PhysicsNode(
    node: Node,
    restitution: Float = 0.6f,
    linearVelocity: Position = Position(0f, 0f, 0f),
    floorY: Float = 0f,
    radius: Float = 0f,
    floorProvider: FloorProvider? = null,
) {
    val body = remember(node) {
        PhysicsBody(
            node = node,
            restitution = restitution,
            floorY = floorY,
            radius = radius,
            initialVelocity = linearVelocity,
            floorProvider = floorProvider,
        )
    }
    // Keep the floor provider live: callers usually pass `rememberDepthCollider(...)`, which is
    // a stable instance, but allow swapping it out across recompositions (e.g. to A/B between
    // depth-driven and static floor) without recreating the body.
    body.floorProvider = floorProvider

    DisposableEffect(node) {
        // Save the caller's existing onFrame so PhysicsNode does not clobber it.
        val previousOnFrame = node.onFrame
        var prevFrameTime: Long? = null
        node.onFrame = { frameTimeNanos ->
            body.step(frameTimeNanos, prevFrameTime)
            prevFrameTime = frameTimeNanos
            // Chain-call any caller-provided callback (runs synchronously on the
            // render/main thread, like onFrame itself).
            previousOnFrame?.invoke(frameTimeNanos)
        }
        onDispose {
            // Restore exactly what was there before instead of nulling unconditionally.
            node.onFrame = previousOnFrame
        }
    }
}

/**
 * Deprecated overload kept for source compatibility — the `mass` parameter is a no-op.
 *
 * The Euler integration applies only gravity, which is an acceleration and therefore
 * mass-independent. `mass` will gain an effect once a force/impulse API lands. Until then,
 * use the [PhysicsNode] overload without `mass`.
 */
@Deprecated(
    "The 'mass' parameter is currently a no-op (the Euler integration applies only gravity, " +
        "which is mass-independent). Use the PhysicsNode overload without 'mass'.",
    ReplaceWith("PhysicsNode(node, restitution, linearVelocity, floorY, radius)"),
    DeprecationLevel.WARNING
)
@Composable
fun PhysicsNode(
    node: Node,
    @Suppress("UNUSED_PARAMETER") mass: Float,
    restitution: Float = 0.6f,
    linearVelocity: Position = Position(0f, 0f, 0f),
    floorY: Float = 0f,
    radius: Float = 0f
) = PhysicsNode(
    node = node,
    restitution = restitution,
    linearVelocity = linearVelocity,
    floorY = floorY,
    radius = radius
)
