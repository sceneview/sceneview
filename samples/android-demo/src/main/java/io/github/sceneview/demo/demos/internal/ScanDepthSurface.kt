package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.model.CleanGeometry
import io.github.sceneview.demo.demos.internal.model.MeshCleanup
import kotlin.math.sqrt

/** Live Room v2 (#4996) surface extraction, fed by the live depth TSDF instead of a disk replay.
 * Keeps measured vertex colours, with neutral grey for uncoloured voxels. No planar extension,
 * photo projection or aggressive simplification; only holes with a rim under 15 cm close.
 */
internal object ScanDepthSurface {
    fun extract(tsdf: RerunTsdf, progress: (Float) -> Unit = {}): RerunMesh {
        val extraction = RerunMarchingCubes.extractWhole(
            tsdf, minWeight = 0.5f, weakestCorner = 0.05f,
            minComponentTriangles = 700, maxTriangles = 250_000,
            progress = { progress(it * 0.8f) },
        )
        return closeHoles(extraction.mesh, 0.15f) { progress(0.8f + it * 0.2f) }.first
    }

    private fun closeHoles(mesh: RerunMesh, spanM: Float, progress: (Float) -> Unit): Pair<RerunMesh, Int> {
        val (filled, holes) = MeshCleanup.fillSmallHoles(CleanGeometry.consuming(mesh), maxSpanM = spanM)
        progress(NORMALS_START)
        if (holes == 0) return mesh to 0
        val n = filled.vertexCount
        val normals = mesh.normals.copyOf(n * 3)
        val p = filled.positions
        val ix = filled.indices
        for (t in 0 until filled.triangleCount) {
            if (t % PROGRESS_TRIANGLES == 0) progress(NORMALS_START + (1f - NORMALS_START) * t / filled.triangleCount)
            val a = ix[t * 3]
            val b = ix[t * 3 + 1]
            val c = ix[t * 3 + 2]
            if (a < mesh.vertexCount && b < mesh.vertexCount && c < mesh.vertexCount) continue
            val ux = p[b * 3] - p[a * 3]
            val uy = p[b * 3 + 1] - p[a * 3 + 1]
            val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[a * 3]
            val vy = p[c * 3 + 1] - p[a * 3 + 1]
            val vz = p[c * 3 + 2] - p[a * 3 + 2]
            for (v in intArrayOf(a, b, c)) {
                if (v < mesh.vertexCount) continue
                normals[v * 3] += uy * vz - uz * vy
                normals[v * 3 + 1] += uz * vx - ux * vz
                normals[v * 3 + 2] += ux * vy - uy * vx
            }
        }
        for (v in mesh.vertexCount until n) {
            val l = sqrt(normals[v * 3] * normals[v * 3] + normals[v * 3 + 1] * normals[v * 3 + 1] + normals[v * 3 + 2] * normals[v * 3 + 2])
            if (l > 0f) for (k in 0 until 3) normals[v * 3 + k] /= l else normals[v * 3 + 1] = 1f
        }
        // Hole interiors have no measured colour. Give each patch face its own neutral
        // vertices so colour cannot stretch across the gap or leak into measured neighbours.
        val added = filled.indices.size - mesh.indices.size
        val positions = filled.positions.copyOf((n + added) * 3)
        val patchNormals = normals.copyOf(positions.size)
        val colors = filled.colors.copyOf(n + added)
        for (i in mesh.indices.size until filled.indices.size) {
            val source = filled.indices[i]
            val target = n + i - mesh.indices.size
            for (k in 0..2) {
                positions[target * 3 + k] = filled.positions[source * 3 + k]
                patchNormals[target * 3 + k] = normals[source * 3 + k]
            }
            colors[target] = RerunMarchingCubes.NO_COLOR
            filled.indices[i] = target
        }
        return RerunMesh(positions, patchNormals, colors, filled.indices) to holes
    }

    private const val NORMALS_START = 0.9f
    private const val PROGRESS_TRIANGLES = 1024
}
