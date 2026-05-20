package io.github.sceneview.ar.physics

import io.github.sceneview.ar.node.DepthMeshNode
import io.github.sceneview.ar.node.DepthMeshSnapshot
import io.github.sceneview.math.Position
import io.github.sceneview.node.FloorProvider

/**
 * A static physics collider built from the live ARCore environment depth mesh, so virtual rigid
 * bodies (e.g. those driven by [io.github.sceneview.node.PhysicsBody]) can bounce off the real
 * floor, table or wall — [arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab)'s
 * "Collider" scene, ported to SceneView.
 *
 * Thin wrapper over a [DepthMeshNode] (#1739): every time the mesh is rebuilt, the collider
 * captures the new vertex/index data and resolves it to world space once so per-body queries are
 * cheap point-vs-triangle math. Edge-discontinuity culling is inherited from the underlying mesh —
 * the collider does not stretch "curtain" surfaces across depth jumps.
 *
 * ### Usage
 *
 * ```kotlin
 * ARSceneView(
 *     sessionConfiguration = { _, config ->
 *         config.depthMode = Config.DepthMode.AUTOMATIC
 *     }
 * ) {
 *     val collider = rememberDepthCollider(refreshIntervalMs = 200L)
 *
 *     // Optional — narrow the per-frame collider region to the area near your bodies for ~10×
 *     // less per-body work when the mesh is dense but the bodies are clustered.
 *     collider.setBodiesRegion(
 *         centres = floatArrayOf(sphereNode.worldPosition.x, sphereNode.worldPosition.y, sphereNode.worldPosition.z),
 *         padding = 0.3f,
 *     )
 *
 *     PhysicsNode(
 *         node = sphereNode,
 *         restitution = 0.7f,
 *         depthCollider = collider,
 *         radius = 0.08f,
 *     )
 * }
 * ```
 *
 * ### Threading
 *
 * Snapshot capture runs on the AR frame thread (the same thread that calls
 * [DepthMeshNode.update]). Per-body queries from [floorYAt] are intended to be called from
 * [io.github.sceneview.node.Node.onFrame] callbacks, which also run on the render thread, so the
 * shared snapshot is accessed without extra synchronisation. **Do not** call [floorYAt] from a
 * background coroutine — the snapshot may be swapped mid-read.
 *
 * @param mesh  The underlying [DepthMeshNode]. The collider installs its own [DepthMeshNode.onMeshRebuilt]
 *              listener, but **forwards** the previous one so the caller's listener still fires.
 */
class DepthCollider private constructor(
    internal val mesh: DepthMeshNode?,
    private val previousListener: ((DepthMeshSnapshot) -> Unit)?,
) : FloorProvider {

    /**
     * Primary constructor — wires the collider to a live [DepthMeshNode], chaining any existing
     * [DepthMeshNode.onMeshRebuilt] listener so the caller's hook still fires.
     *
     * @param mesh  The underlying [DepthMeshNode]. The collider installs its own listener and
     *              forwards the previous one.
     */
    constructor(mesh: DepthMeshNode) : this(mesh = mesh, previousListener = mesh.onMeshRebuilt) {
        mesh.onMeshRebuilt = { snapshot ->
            ingestSnapshot(snapshot)
            previousListener?.invoke(snapshot)
        }
    }

    /**
     * Most-recent world-space snapshot — the geometry per-body queries are tested against.
     *
     * Replaced atomically (via the [worldSnapshotRef] reassignment) every time the underlying
     * [DepthMeshNode] rebuilds. `null` until the first rebuild lands.
     */
    @Volatile
    private var worldSnapshotRef: WorldSpaceSnapshot? = null

    /**
     * Region-near-bodies window. When non-null, the per-frame triangle list is culled to this AABB
     * before the per-body inside-triangle test runs — major speedup when the mesh is dense (full
     * frame, ~1800 triangles after stride-4 + edge culling) and the bodies are clustered.
     */
    @Volatile
    private var bodiesRegion: RegionAabb? = null

    /** Wall-clock timestamp of the latest rebuild ingest — exposed for telemetry / tests. */
    @Volatile
    var lastRebuildTimestampMs: Long = Long.MIN_VALUE
        private set

    /**
     * Updates [bodiesRegion] from a flat-packed array of sphere centres. Pass an empty array (or
     * `null`) to disable region culling — the collider then tests every triangle.
     *
     * Costless if [centres] is empty. Call once per frame from the scene's `onFrame` block; the
     * cost is `O(bodies)` and only the per-rebuild ingest pays the cull cost.
     *
     * @param centres  Sphere centres in world space (flat-packed `[x0,y0,z0, ...]`). `null` to
     *                 disable region culling entirely.
     * @param padding  Per-axis padding in metres. Use roughly each body's collision radius plus a
     *                 fudge for the maximum frame-to-frame travel.
     */
    fun setBodiesRegion(centres: FloatArray?, padding: Float) {
        bodiesRegion = if (centres == null || centres.isEmpty()) {
            null
        } else {
            computeBodiesRegion(centres, padding)
        }
    }

    /**
     * Looks up the world-space Y of the highest depth-mesh triangle directly under the sphere
     * centred at ([x], [y], [z]) with collision radius [radius]. Returns `null` when no triangle
     * covers that XZ (the body is over a depth hole, the mesh isn't ready yet, or the body is
     * outside the depth FOV).
     *
     * Callers (e.g. [io.github.sceneview.node.PhysicsBody]) should treat `null` as "fall through
     * — use the configured static `floorY`".
     *
     * Pure read of [worldSnapshotRef]; no allocation. Safe to call every frame from the render
     * thread.
     */
    override fun floorYAt(x: Float, y: Float, z: Float, radius: Float): Float? {
        val snapshot = worldSnapshotRef ?: return null
        if (snapshot.indices.isEmpty()) return null
        return nearestSurfaceYBelow(
            positionsWorld = snapshot.positions,
            indices = snapshot.indices,
            x = x,
            y = y,
            z = z,
            radius = radius,
        )
    }

    /**
     * Convenience overload — same as [floorYAt] with a single [Position].
     */
    fun floorYAt(position: Position, radius: Float): Float? =
        floorYAt(position.x, position.y, position.z, radius)

    /**
     * Number of triangles currently in the live collider snapshot — `0` when no rebuild has landed
     * yet. Exposed for telemetry / debug overlay; do **not** rely on it in physics math.
     */
    val triangleCount: Int get() = (worldSnapshotRef?.indices?.size ?: 0) / 3

    /**
     * Drops the active snapshot and the [DepthMeshNode] listener hook. After calling this, the
     * collider is inert: [floorYAt] always returns `null`. The owning [DepthMeshNode] is **not**
     * destroyed — its lifecycle is the caller's.
     */
    fun destroy() {
        mesh?.onMeshRebuilt = previousListener
        worldSnapshotRef = null
        bodiesRegion = null
    }

    /**
     * Ingests a freshly-built [DepthMeshSnapshot]: resolves vertices to world space and optionally
     * culls indices to the active [bodiesRegion]. Called on the AR frame thread.
     *
     * Defensive copy of [DepthMeshSnapshot.positions] / [DepthMeshSnapshot.indices]: the mesh node
     * may reuse the arrays on the next rebuild, so we don't keep references to its buffers.
     */
    internal fun ingestSnapshot(snapshot: DepthMeshSnapshot) {
        val worldPositions = transformPositionsToWorld(
            positionsCameraSpace = snapshot.positions,
            worldTransform = snapshot.worldTransform,
        )
        val activeRegion = bodiesRegion
        val culledIndices = if (activeRegion != null) {
            cullIndicesToRegion(worldPositions, snapshot.indices, activeRegion)
        } else {
            snapshot.indices.copyOf()
        }
        worldSnapshotRef = WorldSpaceSnapshot(
            positions = worldPositions,
            indices = culledIndices,
            timestampMs = snapshot.timestampMs,
        )
        lastRebuildTimestampMs = snapshot.timestampMs
    }

    companion object {
        /**
         * Test-only factory — creates a [DepthCollider] that is **not** wired to any live
         * [DepthMeshNode]. Tests drive [ingestSnapshot] directly. Not part of the public API.
         */
        internal fun forTesting(): DepthCollider =
            DepthCollider(mesh = null, previousListener = null)
    }

    /**
     * Snapshot of the depth mesh resolved into world space — cached so per-frame, per-body
     * lookups are a tight loop over a `FloatArray` + `IntArray` rather than re-running the
     * camera-space → world-space transform every query.
     */
    internal data class WorldSpaceSnapshot(
        val positions: FloatArray,
        val indices: IntArray,
        val timestampMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WorldSpaceSnapshot) return false
            return timestampMs == other.timestampMs &&
                positions.contentEquals(other.positions) &&
                indices.contentEquals(other.indices)
        }

        override fun hashCode(): Int {
            var result = positions.contentHashCode()
            result = 31 * result + indices.contentHashCode()
            result = 31 * result + timestampMs.hashCode()
            return result
        }
    }
}
