package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.RerunMesh

/**
 * A scan as big as the longest recordings mesh: the furnished room and the bare one
 * ([RealScanRoom]) side by side, [PITCH_M] apart along x — over 600k raw triangles, past what
 * cleaning takes, so the finish simplifies it first — with [PIECES] small pieces scattered
 * around them, a few metres off, as depth leaves through a window or in a mirror. Its photos
 * ([photos]) see the furnished room only: a little under half the model, as a big room walked once.
 */
internal object BigScanScene {
    /** The bare room's offset along x. */
    const val PITCH_M = 5.6f

    /** How many pieces lie scattered around the rooms. */
    const val PIECES = 14

    /**
     * A dim room's depth: drift and blotches near three times a lit room's ([RealScanRoom.Depth.PHONE]),
     * which the textured simplification cannot flatten within its noise ceiling — a model that
     * stays well over its budget.
     */
    val DIM = RealScanRoom.Depth(
        driftM = 0.025f, blotchM = 0.025f, blotchCellM = 0.04f, whiteM = 0.006f,
        missedShare = 0.33f, missedCellM = 0.08f,
    )

    /** The two rooms' floor, the scattered pieces left out. */
    val ROOMS = Box(RealScanRoom.ROOM.x0, RealScanRoom.ROOM.y0, RealScanRoom.ROOM.z0, RealScanRoom.ROOM.x1 + PITCH_M, RealScanRoom.ROOM.y1, RealScanRoom.ROOM.z1)

    /**
     * The raw surface: both rooms as the marching cubes mesh them from [depth], then the scattered
     * pieces. With [driftedFloor], the furnished room's floor is scanned twice, the second pass
     * drifted below the first ([RealScanRoom.DRIFTED_FLOOR]).
     */
    fun raw(depth: RealScanRoom.Depth = RealScanRoom.Depth.PHONE, driftedFloor: Boolean = false): RerunMesh {
        val furnished = RealScanRoom.raw(furnished = true, depth = depth, driftedFloor = driftedFloor)
        val bare = RealScanRoom.raw(furnished = false, depth = depth)
        val pieces = (0 until PIECES).map { i ->
            val (x, y, z) = SCATTER[i]
            // A patch of the bare room's wall x = -2.5, moved out to where the piece lies.
            val z0 = -1.4f + i * PATCH_STEP_M
            moved(crop(bare, Box(-2.6f, 0.9f, z0, -2.4f, 0.9f + PATCH_M, z0 + PATCH_M)), x + 2.5f, y - 0.9f, z - z0)
        }
        return concat(listOf(furnished, moved(bare, PITCH_M, 0f, 0f)) + pieces)
    }

    /** The furnished room's photos, then every [bareEvery]th of the bare room's, moved with it (0: none). */
    fun photos(bareEvery: Int = 0): ModelPhotos {
        val furnished = RealScanRoom.modelPhotos(furnished = true)
        if (bareEvery <= 0) return furnished
        val bare = RealScanRoom.modelPhotos(furnished = false).photos.filterIndexed { i, _ -> i % bareEvery == 0 }.map { photo ->
            val pose = photo.pose.copy(x = photo.pose.x + PITCH_M)
            ModelPhoto(pose) { photo.load()?.let { BakePhoto(pose, it.width, it.height, it.argb) } }
        }
        return ModelPhotos(furnished.lens, furnished.photos + bare)
    }

    /** [mesh]'s triangles with all three corners in [box], their vertices renumbered. */
    fun crop(mesh: RerunMesh, box: Box): RerunMesh {
        fun inside(v: Int): Boolean {
            val x = mesh.positions[v * 3]
            val y = mesh.positions[v * 3 + 1]
            val z = mesh.positions[v * 3 + 2]
            return x in box.x0..box.x1 && y in box.y0..box.y1 && z in box.z0..box.z1
        }
        val remap = IntArray(mesh.vertexCount) { -1 }
        val kept = ArrayList<Int>()
        var vertices = 0
        for (t in 0 until mesh.triangleCount) {
            val corners = IntArray(3) { mesh.indices[t * 3 + it] }
            if (!corners.all(::inside)) continue
            for (v in corners) {
                if (remap[v] < 0) remap[v] = vertices++
                kept += remap[v]
            }
        }
        val positions = FloatArray(vertices * 3)
        val normals = FloatArray(vertices * 3)
        val colors = IntArray(vertices)
        for (v in 0 until mesh.vertexCount) {
            val to = remap[v]
            if (to < 0) continue
            mesh.positions.copyInto(positions, to * 3, v * 3, v * 3 + 3)
            mesh.normals.copyInto(normals, to * 3, v * 3, v * 3 + 3)
            colors[to] = mesh.colors[v]
        }
        return RerunMesh(positions, normals, colors, kept.toIntArray())
    }

    fun moved(mesh: RerunMesh, dx: Float, dy: Float, dz: Float): RerunMesh {
        val positions = mesh.positions.copyOf()
        for (v in 0 until mesh.vertexCount) {
            positions[v * 3] += dx
            positions[v * 3 + 1] += dy
            positions[v * 3 + 2] += dz
        }
        return RerunMesh(positions, mesh.normals, mesh.colors, mesh.indices)
    }

    fun concat(meshes: List<RerunMesh>): RerunMesh {
        val vertices = meshes.sumOf { it.vertexCount }
        val positions = FloatArray(vertices * 3)
        val normals = FloatArray(vertices * 3)
        val colors = IntArray(vertices)
        val indices = IntArray(meshes.sumOf { it.indices.size })
        var v = 0
        var i = 0
        for (mesh in meshes) {
            mesh.positions.copyInto(positions, v * 3)
            mesh.normals.copyInto(normals, v * 3)
            mesh.colors.copyInto(colors, v)
            for (k in mesh.indices.indices) indices[i + k] = mesh.indices[k] + v
            v += mesh.vertexCount
            i += mesh.indices.size
        }
        return RerunMesh(positions, normals, colors, indices)
    }

    private const val PATCH_M = 0.3f
    private const val PATCH_STEP_M = 0.2f

    /** Where the pieces lie: around both rooms, 1 to 4 m off their walls, from the floor to above the ceiling. */
    private val SCATTER = listOf(
        Triple(-4.5f, 0.5f, -2f), Triple(-6.5f, 1.2f, 1f), Triple(-3.8f, 2.4f, 4.5f), Triple(0f, 1.5f, 6f),
        Triple(3f, 0.3f, 7.5f), Triple(8f, 2f, 5.2f), Triple(10.5f, 1f, 2f), Triple(12f, 1.8f, -1f),
        Triple(9f, 0.6f, -5.5f), Triple(5f, 3.5f, -6.5f), Triple(1f, 1.1f, -4.8f), Triple(-3f, 0.9f, -6f),
        Triple(-5.5f, 3.2f, -4f), Triple(4f, 4.2f, 1f),
    )
}
