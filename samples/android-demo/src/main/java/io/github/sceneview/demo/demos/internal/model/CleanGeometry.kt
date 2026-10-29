package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.RerunMesh

/** What [CleanGeometry.clean] did to a mesh, for the log and the tests. */
internal data class CleanGeometryStats(
    val trianglesIn: Int,
    val trianglesOut: Int,
    val piecesIn: Int,
    val piecesOut: Int,
    val piecesDropped: Int,
    val doubledTriangles: Int,
    val planes: Int,
    val flatShare: Float,
    val holesFilled: Int,
    val planeList: List<Plane> = emptyList(),
    /** RMS distance (m) of the flat faces' vertices to their planes before the snap: the depth noise measured. */
    val noiseM: Float = 0f,
)

/*
 * The geometry half of the clean model: marching cubes' raw surface in, a surface with no
 * floating debris, no doubled sheets, no ripple, flat walls and closed pin-holes out. Colours ride
 * along per vertex. Off the main thread: a room is a few seconds of work.
 */
internal object CleanGeometry {
    fun of(mesh: RerunMesh): WorkMesh = WorkMesh(mesh.positions.copyOf(), mesh.colors.copyOf(), mesh.indices.copyOf())

    /** [mesh]'s own arrays, which [clean] then works in: for a caller done with [mesh]. */
    fun consuming(mesh: RerunMesh): WorkMesh = WorkMesh(mesh.positions, mesh.colors, mesh.indices)

    /**
     * The most triangles [clean] takes. Each step rebuilds a face table, a topology and a grid
     * over the whole mesh, a few hundred bytes a triangle at peak: past this, a phone's heap is
     * at risk, so [CleanModel] brings a bigger surface down to [CleanModel.CLEAN_INPUT_TRIANGLES]
     * first.
     */
    const val MAX_TRIANGLES = 500_000

    /** [raw] cleaned; [progress] (0–1) is told after each step and pass, and may throw to stop it. */
    fun clean(raw: WorkMesh, progress: (Float) -> Unit = {}): Pair<WorkMesh, CleanGeometryStats> {
        require(raw.triangleCount <= MAX_TRIANGLES) { "${raw.triangleCount} triangles: clean takes $MAX_TRIANGLES" }
        val trianglesIn = raw.triangleCount
        val piecesIn = MeshCleanup.pieces(raw)
        val (pieces, dropped) = MeshCleanup.removeSmallPieces(raw)
        progress(PROGRESS_PIECES)
        // Where the sheet in front has a hole, the hidden sheet stays: it is the only surface there.
        var (mesh, doubled) = MeshCleanup.removeDoubledSurfaces(pieces)
        progress(PROGRESS_DOUBLED)
        MeshCleanup.smooth(mesh) { progress(PROGRESS_DOUBLED) }
        progress(PROGRESS_SMOOTH)
        val flattening = PlaneFlattening.flatten(mesh) { progress(PROGRESS_SMOOTH) }
        progress(PROGRESS_PLANES)
        // The lace a wall's missed depth leaves closes on its plane; a hole at a corner stays small-only.
        val (filled, holes) = MeshCleanup.fillSmallHoles(
            mesh,
            facePlane = flattening.facePlane,
            planes = flattening.planes,
        )
        // What the hidden sheet showed through a hole just closed is behind the patch now.
        val (uncovered, covered) = MeshCleanup.removeCovered(filled, firstNew = mesh.triangleCount)
        mesh = uncovered
        progress(1f)
        val stats = CleanGeometryStats(
            trianglesIn = trianglesIn,
            trianglesOut = mesh.triangleCount,
            piecesIn = piecesIn,
            piecesOut = MeshCleanup.pieces(mesh),
            piecesDropped = dropped,
            doubledTriangles = doubled + covered,
            planes = flattening.planes.size,
            flatShare = if (flattening.totalArea > 0f) flattening.flatArea / flattening.totalArea else 0f,
            holesFilled = holes,
            planeList = flattening.planes,
            noiseM = flattening.noiseM,
        )
        return mesh to stats
    }

    private const val PROGRESS_PIECES = 0.1f
    private const val PROGRESS_DOUBLED = 0.3f
    private const val PROGRESS_SMOOTH = 0.6f
    private const val PROGRESS_PLANES = 0.9f
}
