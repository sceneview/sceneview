package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier

/**
 * The floor ([FloorArea]) after each stage of the finish, the stages run here as [CleanModel]
 * runs them — the surface brought down for cleaning, each cleaning step, the simplification —
 * and the finished model's last: where a floor the scan had opens.
 */
internal object FinishStages {
    /** One stage's floor, at the raw floor's height, and the share of the raw floor it leaves open. */
    class Floor(val stage: String, val floor: FloorArea, val opened: Float, val triangles: Int)

    /** [raw]'s floor after each stage, then [finished]'s; the simplification is [textured]'s. */
    fun floors(raw: RerunMesh, textured: Boolean, finished: ModelMesh): List<Floor> {
        val rawFloor = FloorArea.of(raw.positions, raw.indices)
        val floors = mutableListOf(Floor("raw", rawFloor, 0f, raw.triangleCount))
        fun note(stage: String, positions: FloatArray, indices: IntArray) {
            val floor = FloorArea.of(positions, indices, rawFloor.height)
            floors += Floor(stage, floor, floor.openedOn(rawFloor), indices.size / 3)
        }
        var mesh = prepared(raw)
        note("prepared", mesh.positions, mesh.indices)
        var (kept, _) = MeshCleanup.removeSmallPieces(mesh)
        mesh = kept
        note("small pieces", mesh.positions, mesh.indices)
        mesh = MeshCleanup.removeDoubledSurfaces(mesh).first
        note("doubled surfaces", mesh.positions, mesh.indices)
        MeshCleanup.smooth(mesh)
        note("smoothing", mesh.positions, mesh.indices)
        val flattening = PlaneFlattening.flatten(mesh)
        note("planes", mesh.positions, mesh.indices)
        val filled = MeshCleanup.fillSmallHoles(mesh, facePlane = flattening.facePlane, planes = flattening.planes).first
        mesh = MeshCleanup.removeCovered(filled, firstNew = mesh.triangleCount).first
        note("small holes", mesh.positions, mesh.indices)
        val simplified = simplified(mesh, textured, flattening.noiseM, flattening.planes.isEmpty())
        note("simplification", simplified.positions, simplified.indices)
        note("finished", finished.positions, finished.texturedIndices + finished.plainIndices)
        return floors
    }

    /** As [CleanModel]'s preparation: over [CleanModel.CLEAN_INPUT_TRIANGLES], simplified on its shape alone down to it. */
    private fun prepared(raw: RerunMesh): WorkMesh {
        if (raw.triangleCount <= CleanModel.CLEAN_INPUT_TRIANGLES) return CleanGeometry.of(raw)
        val out = RerunMeshSimplifier.simplify(
            RerunMesh(raw.positions, raw.positions, IntArray(raw.vertexCount), raw.indices),
            CleanModel.CLEAN_INPUT_TRIANGLES,
        )
        return WorkMesh(out.positions, out.colors, out.indices)
    }

    /** As [CleanModel]'s first simplification, to its budget: on the shape alone when [textured], colour edges kept otherwise. */
    private fun simplified(clean: WorkMesh, textured: Boolean, noiseM: Float, noPlanes: Boolean): RerunMesh {
        val mesh = RerunMesh(clean.positions, vertexNormals(clean.positions, clean.indices), clean.colors, clean.indices)
        if (!textured) return RerunMeshSimplifier.simplify(mesh, RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES, collapseBorders = true)
        val ceiling = if (noPlanes) {
            CleanModel.MAX_TEXTURED_ERROR_CEILING_M
        } else {
            (NOISE_CEILING_FACTOR * noiseM).coerceIn(CleanModel.MAX_TEXTURED_ERROR_M, CleanModel.MAX_TEXTURED_ERROR_CEILING_M)
        }
        return RerunMeshSimplifier.simplify(
            RerunMesh(clean.positions, mesh.normals, IntArray(clean.vertexCount), clean.indices),
            CleanModel.TEXTURED_TARGET_TRIANGLES,
            maxEdgeM = CleanModel.MAX_TEXTURED_EDGE_M,
            maxErrorM = CleanModel.MAX_TEXTURED_ERROR_M,
            maxErrorCeilingM = ceiling,
            belowTarget = true,
            collapseBorders = true,
        )
    }

    private const val NOISE_CEILING_FACTOR = 2f
}
