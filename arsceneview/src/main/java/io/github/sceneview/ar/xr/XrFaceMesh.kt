package io.github.sceneview.ar.xr

import io.github.sceneview.math.Position
import kotlin.math.sqrt

/**
 * Pure-Kotlin face-mesh geometry and pose math for [XrFaceNode].
 *
 * This file deliberately contains **no Android, Filament or `androidx.xr.arcore`
 * references** — every symbol operates on SceneView's own [Position] (a
 * `Float3`) and plain primitives. That keeps the mesh-buffer adapter
 * unit-testable on a stock JVM with no XR runtime on the classpath: the JVM
 * tests in `XrFaceMeshTest` exercise it directly.
 *
 * The node wrapper ([XrFaceNode]) is the only place that touches the upstream
 * preview `Face` / `FaceState` / `Pose` types — it reads the per-frame vertex,
 * normal and index buffers reflectively and hands the flat float / int arrays
 * to the functions here to validate, measure and centre the mesh.
 *
 * Preview status: the buffer layout mirrors `androidx.xr.arcore.Face`
 * (`1.0.0-alpha14`); the upstream is alpha and may change the vertex stride or
 * the index winding. See
 * [arsceneview/docs/JETPACK-XR-INTEGRATION.md](https://github.com/sceneview/sceneview/blob/main/arsceneview/docs/JETPACK-XR-INTEGRATION.md).
 */

/**
 * Stable identifiers for the named face *anchor regions* SceneView's face
 * renderer attaches geometry to.
 *
 * These mirror the anchor regions the upstream `androidx.xr.arcore.Face` State
 * exposes alongside the dense mesh — `FaceMeshRegion.NOSE_TIP`,
 * `FOREHEAD_LEFT`, `FOREHEAD_RIGHT` — plus a [CENTER] entry sourced from the
 * State's `meshCenterPose`. They are the headset equivalent of the
 * front-camera regions `com.google.ar.core.AugmentedFace.RegionType` carries
 * on phones. The dense mesh drives skin overlays; these named regions are the
 * natural anchors for attaching a discrete model (glasses on the [NOSE_TIP],
 * a hat above the forehead).
 *
 * The [ordinal] of each entry is also its index in the contiguous region-pose
 * array produced by [XrFaceMesh.regionCount], so callers can use a flat
 * `Array<Position?>` keyed by `region.ordinal` instead of a map.
 *
 * Each entry's [upstreamName] is the matching upstream `FaceMeshRegion`
 * constant name (or `null` for [CENTER], which is read from `meshCenterPose`
 * rather than the region-pose map) — [XrFaceNode] uses it to match poses by
 * name so the mapping survives the alpha SDK renaming a region.
 *
 * @property upstreamName Name of the upstream `FaceMeshRegion` constant this
 *                        region maps to, or `null` when it is sourced from
 *                        the State's `meshCenterPose` instead.
 */
enum class XrFaceRegion(val upstreamName: String?) {
    /** Centre of the face — read from the State's `meshCenterPose`. */
    CENTER(null),

    /** Tip of the nose — upstream `FaceMeshRegion.NOSE_TIP`. */
    NOSE_TIP("NOSE_TIP"),

    /** Left side of the forehead — upstream `FaceMeshRegion.FOREHEAD_LEFT`. */
    FOREHEAD_LEFT("FOREHEAD_LEFT"),

    /** Right side of the forehead — upstream `FaceMeshRegion.FOREHEAD_RIGHT`. */
    FOREHEAD_RIGHT("FOREHEAD_RIGHT"),
}

/**
 * A face-mesh snapshot decoded from the upstream preview buffers: a flat
 * vertex array, an optional flat normal array and a triangle index array.
 *
 * Holds only primitive arrays and [Position] — no `androidx.xr.arcore` types —
 * so [XrFaceNode] decodes the alpha SDK buffers into this and every downstream
 * consumer (geometry upload, the JVM tests) works on a runtime-free value.
 *
 * @property vertices Flat XYZ vertex positions: `vertices[3*i .. 3*i+2]` is
 *                    vertex `i`. `size` is a multiple of [VERTEX_STRIDE].
 * @property normals  Flat XYZ per-vertex normals with the same layout as
 *                    [vertices], or `null` when the upstream frame carried no
 *                    normals (an unlit overlay does not need them).
 * @property indices  Triangle indices into [vertices] — `size` is a multiple
 *                    of 3, each run of 3 forming one triangle.
 */
data class XrFaceMeshData(
    val vertices: FloatArray,
    val normals: FloatArray?,
    val indices: IntArray,
) {
    /** Number of vertices — `vertices.size / `[XrFaceMesh.VERTEX_STRIDE]. */
    val vertexCount: Int get() = vertices.size / XrFaceMesh.VERTEX_STRIDE

    /** Number of triangles — `indices.size / 3`. */
    val triangleCount: Int get() = indices.size / XrFaceMesh.INDICES_PER_TRIANGLE

    // Array properties need structural equals/hashCode — the generated ones
    // compare by reference, which would make two equal meshes unequal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is XrFaceMeshData) return false
        if (!vertices.contentEquals(other.vertices)) return false
        if (normals == null) {
            if (other.normals != null) return false
        } else if (other.normals == null || !normals.contentEquals(other.normals)) {
            return false
        }
        return indices.contentEquals(other.indices)
    }

    override fun hashCode(): Int {
        var result = vertices.contentHashCode()
        result = 31 * result + (normals?.contentHashCode() ?: 0)
        result = 31 * result + indices.contentHashCode()
        return result
    }
}

/**
 * Pure-logic helpers describing the face mesh: buffer-layout constants, mesh
 * validation, and the small amount of vector math [XrFaceNode] needs to lay a
 * face overlay out.
 *
 * Stateless `object` — everything here is referentially transparent and
 * trivially unit-testable.
 */
object XrFaceMesh {

    /** Floats per vertex in a flat vertex / normal buffer — X, Y, Z. */
    const val VERTEX_STRIDE: Int = 3

    /** Index count per triangle. */
    const val INDICES_PER_TRIANGLE: Int = 3

    /** Number of named anchor regions — `XrFaceRegion.entries.size`. */
    val regionCount: Int = XrFaceRegion.entries.size

    /**
     * Validates that a decoded [mesh] has a self-consistent buffer layout —
     * the gate [XrFaceNode] applies before uploading anything to Filament so a
     * malformed alpha-SDK frame is dropped rather than crashing the renderer.
     *
     * A mesh is valid when:
     *  - the vertex array length is a multiple of [VERTEX_STRIDE];
     *  - the normal array, when present, has exactly the same length as the
     *    vertex array (one normal per vertex);
     *  - the index array length is a multiple of [INDICES_PER_TRIANGLE];
     *  - every index is within `0 until vertexCount`.
     *
     * An empty mesh (no vertices, no indices) is valid — it simply means the
     * face is not currently tracked.
     */
    fun isValid(mesh: XrFaceMeshData): Boolean {
        if (mesh.vertices.size % VERTEX_STRIDE != 0) return false
        val normals = mesh.normals
        if (normals != null && normals.size != mesh.vertices.size) return false
        if (mesh.indices.size % INDICES_PER_TRIANGLE != 0) return false
        val vertexCount = mesh.vertexCount
        for (index in mesh.indices) {
            if (index < 0 || index >= vertexCount) return false
        }
        return true
    }

    /**
     * Reads vertex [index] out of a decoded [mesh] as a [Position].
     *
     * Returns `null` when [index] is out of `0 until `[XrFaceMeshData.vertexCount]
     * so a caller iterating a stale index never produces a phantom vertex.
     */
    fun vertexAt(mesh: XrFaceMeshData, index: Int): Position? {
        if (index < 0 || index >= mesh.vertexCount) return null
        val base = index * VERTEX_STRIDE
        return Position(
            x = mesh.vertices[base],
            y = mesh.vertices[base + 1],
            z = mesh.vertices[base + 2],
        )
    }

    /**
     * The axis-aligned centroid of every vertex in [mesh] — the average of all
     * vertex positions. This is the natural local anchor for a face overlay's
     * geometry and a cheap proxy for the face centre.
     *
     * Returns `null` for an empty mesh (no vertices — face untracked).
     */
    fun centroid(mesh: XrFaceMeshData): Position? {
        val count = mesh.vertexCount
        if (count == 0) return null
        var sx = 0f
        var sy = 0f
        var sz = 0f
        for (i in 0 until count) {
            val base = i * VERTEX_STRIDE
            sx += mesh.vertices[base]
            sy += mesh.vertices[base + 1]
            sz += mesh.vertices[base + 2]
        }
        val inv = 1f / count
        return Position(x = sx * inv, y = sy * inv, z = sz * inv)
    }

    /**
     * The bounding-box extent of [mesh] — the per-axis span between the
     * minimum and maximum vertex coordinates. A fully tracked adult face is
     * roughly `0.12f..0.20f` meters wide; a near-zero extent means almost
     * nothing is tracked.
     *
     * Returns `Position(0f, 0f, 0f)` for an empty mesh.
     */
    fun extent(mesh: XrFaceMeshData): Position {
        val count = mesh.vertexCount
        if (count == 0) return Position(0f, 0f, 0f)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val base = i * VERTEX_STRIDE
            val x = mesh.vertices[base]
            val y = mesh.vertices[base + 1]
            val z = mesh.vertices[base + 2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
        }
        return Position(x = maxX - minX, y = maxY - minY, z = maxZ - minZ)
    }

    /**
     * Euclidean distance between two region poses, in meters.
     *
     * Returns `0f` when either endpoint is `null` (region not currently
     * tracked) so callers can skip a degenerate measurement.
     */
    fun regionDistance(from: Position?, to: Position?): Float {
        if (from == null || to == null) return 0f
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Linearly interpolates between two positions by [t].
     *
     * Used to smooth a region's rendered position across frames: feeding the
     * previous rendered position and the freshly tracked position with a small
     * `t` (e.g. `0.5f`) damps the per-frame jitter the preview tracker emits.
     *
     * [t] is clamped to `0f..1f`; `t = 0f` returns [from], `t = 1f` returns [to].
     * Returns `null` when either endpoint is `null` so an untracked region
     * never produces a phantom interpolated position.
     */
    fun lerp(from: Position?, to: Position?, t: Float): Position? {
        if (from == null || to == null) return null
        val k = t.coerceIn(0f, 1f)
        return Position(
            x = from.x + (to.x - from.x) * k,
            y = from.y + (to.y - from.y) * k,
            z = from.z + (to.z - from.z) * k,
        )
    }

    /**
     * Counts how many of the [regionCount] slots are currently tracked
     * (non-`null`). `0` means the face is fully untracked; [regionCount] means
     * every named region has a pose this frame.
     *
     * [regions] is indexed by [XrFaceRegion.ordinal].
     */
    fun trackedRegionCount(regions: Array<Position?>): Int =
        regions.count { it != null }
}
