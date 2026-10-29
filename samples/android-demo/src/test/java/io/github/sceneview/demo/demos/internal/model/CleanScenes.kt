package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunTsdf
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** An axis-aligned box from ([x0], [y0], [z0]) to ([x1], [y1], [z1]). */
internal data class Box(val x0: Float, val y0: Float, val z0: Float, val x1: Float, val y1: Float, val z1: Float) {
    /** Signed distance, positive outside. */
    fun sdf(x: Float, y: Float, z: Float): Float {
        val dx = max(x0 - x, x - x1)
        val dy = max(y0 - y, y - y1)
        val dz = max(z0 - z, z - z1)
        val outside = sqrt(max(dx, 0f).let { it * it } + max(dy, 0f).let { it * it } + max(dz, 0f).let { it * it })
        return outside + min(max(dx, max(dy, dz)), 0f)
    }

    /** The six faces' planes, outward normals. */
    fun planes(): List<Plane> = listOf(
        Plane(-1f, 0f, 0f, -x0), Plane(1f, 0f, 0f, x1),
        Plane(0f, -1f, 0f, -y0), Plane(0f, 1f, 0f, y1),
        Plane(0f, 0f, -1f, -z0), Plane(0f, 0f, 1f, z1),
    )
}

/*
 * Synthetic scans for the clean-model tests: a signed distance field, plus the defects a depth
 * scan adds — a centimetre ripple on every surface and per-voxel noise — fused into the real
 * TSDF and meshed by the real marching cubes.
 */
internal object CleanScenes {
    /** A low-frequency ripple, [amplitude] metres: the "wavy wall" of a drifting depth sensor. */
    fun ripple(x: Float, y: Float, z: Float, amplitude: Float): Float =
        amplitude * (sin(x * 9.1f + 0.7f) * sin(z * 7.3f) + 0.6f * sin(y * 11.7f + x * 3.1f))

    /**
     * Fuses [sdf] (positive in free space) over the box [min]..[max] into a TSDF, with
     * [rippleM] of ripple and [noiseM] of per-voxel noise, coloured by [color].
     */
    fun fuse(
        bounds: Box,
        rippleM: Float,
        noiseM: Float,
        seed: Int = 7,
        color: (Float, Float, Float) -> Int = { _, _, _ -> GREY },
        sdf: (Float, Float, Float) -> Float,
    ): RerunTsdf {
        val tsdf = RerunTsdf()
        val voxel = tsdf.voxelM
        val band = tsdf.truncationM * 1.5f
        val random = Random(seed)
        for (gz in floor(bounds.z0 / voxel).toInt()..floor(bounds.z1 / voxel).toInt() + 1) {
            for (gy in floor(bounds.y0 / voxel).toInt()..floor(bounds.y1 / voxel).toInt() + 1) {
                for (gx in floor(bounds.x0 / voxel).toInt()..floor(bounds.x1 / voxel).toInt() + 1) {
                    val x = gx * voxel
                    val y = gy * voxel
                    val z = gz * voxel
                    val clean = sdf(x, y, z)
                    if (abs(clean) > band) continue
                    val d = clean + ripple(x, y, z, rippleM) + (random.nextFloat() * 2f - 1f) * noiseM
                    tsdf.update(gx, gy, gz, d, 1f, color(x, y, z))
                }
            }
        }
        return tsdf
    }

    fun mesh(tsdf: RerunTsdf): WorkMesh =
        CleanGeometry.of(RerunMarchingCubes.extractWhole(tsdf, minWeight = 0.5f).mesh)

    /**
     * Flatness: the RMS distance of each surface's vertices to that surface's own best-fit plane
     * (the true orientation, the mean offset), over the vertices within [nearM] of one of
     * [planes] whose normal agrees with it, [edgeM] clear of the edges. The synthetic ripple's
     * average over a face shifts the face a little; that is position, not flatness.
     */
    fun flatnessRms(mesh: WorkMesh, planes: List<Plane>, nearM: Float = 0.05f, edgeM: Float = 0.12f): Float {
        val p = mesh.positions
        val normals = vertexNormals(p, mesh.indices)
        val sum = DoubleArray(planes.size)
        val sumSq = DoubleArray(planes.size)
        val count = IntArray(planes.size)
        for (v in 0 until mesh.vertexCount) {
            val x = p[v * 3]
            val y = p[v * 3 + 1]
            val z = p[v * 3 + 2]
            val distances = planes.map { abs(it.distance(x, y, z)) }
            val order = distances.indices.sortedBy { distances[it] }
            if (distances[order[0]] > nearM || distances[order[1]] < edgeM) continue
            val plane = planes[order[0]]
            // A vertex of something standing on the plane — a cabinet's side by a wall — is not the plane's.
            val along = normals[v * 3] * plane.nx + normals[v * 3 + 1] * plane.ny + normals[v * 3 + 2] * plane.nz
            if (abs(along) < 0.7f) continue
            val d = plane.distance(x, y, z).toDouble()
            sum[order[0]] += d
            sumSq[order[0]] += d * d
            count[order[0]]++
        }
        var residual = 0.0
        var total = 0
        for (i in planes.indices) {
            if (count[i] == 0) continue
            val mean = sum[i] / count[i]
            residual += sumSq[i] - count[i] * mean * mean
            total += count[i]
        }
        return if (total == 0) Float.NaN else sqrt(residual / total).toFloat()
    }

    /**
     * The share of the area within [nearM] of [planes] (and [edgeM] clear of their edges) whose
     * triangles face against their plane: folds flattened into the plane, the dark specks of a
     * bad flatten.
     */
    fun flippedShare(mesh: WorkMesh, planes: List<Plane>, nearM: Float = 0.01f, edgeM: Float = 0.12f): Float {
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        var flipped = 0.0
        var total = 0.0
        for (t in 0 until faces.count) {
            val x = faces.centroids[t * 3]
            val y = faces.centroids[t * 3 + 1]
            val z = faces.centroids[t * 3 + 2]
            val distances = planes.map { abs(it.distance(x, y, z)) }
            val order = distances.indices.sortedBy { distances[it] }
            if (distances[order[0]] > nearM || distances[order[1]] < edgeM) continue
            val plane = planes[order[0]]
            total += faces.areas[t]
            val along = faces.normals[t * 3] * plane.nx + faces.normals[t * 3 + 1] * plane.ny + faces.normals[t * 3 + 2] * plane.nz
            if (along < -0.5f) flipped += faces.areas[t]
        }
        return if (total == 0.0) 0f else (flipped / total).toFloat()
    }

    /** Edges with no triangle across, and edges more than two triangles share: both 0 when watertight. */
    fun openEdges(mesh: WorkMesh): Int = MeshTopology(mesh.vertexCount, mesh.indices).borderEdges()

    const val GREY = 0xFFB4AFA5.toInt()
}
