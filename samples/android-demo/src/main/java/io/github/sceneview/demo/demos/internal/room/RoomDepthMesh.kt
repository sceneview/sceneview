package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.PoseContinuity
import io.github.sceneview.demo.demos.internal.MeshExtraction
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.model.CleanGeometry
import io.github.sceneview.demo.demos.internal.model.MeshCleanup
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.sqrt

/**
 * A scan's surface fused again from its depth recording ([ScanDepthFormat], `capture-depth.bin`):
 * every frame the pose guard integrated, through a TSDF ([RerunTsdf]) tuned for what stands in a
 * room — 2 cm voxels, a band that widens with the depth, near views weighing more — and marching
 * cubes ([RerunMarchingCubes]), its small floating pieces dropped. Continuous surfaces where the
 * surfel cloud only has points: the shell's furniture ([RoomShellBuilder]) is cut from it.
 *
 * Pure Kotlin, no Filament: blocking CPU and file work, off the main thread.
 */
internal object RoomDepthMesh {
    /** How the depth is fused and meshed; the defaults are the ones tuned on real scans. */
    data class Settings(
        val voxelM: Float = VOXEL_M,
        val truncationM: Float = TRUNCATION_M,
        val truncationShare: Float = TRUNCATION_SHARE,
        val minConfidence: Int = MIN_CONFIDENCE,
        val farM: Float = FAR_M,
        val minWeight: Float = MIN_WEIGHT,
        val weakestCorner: Float = WEAKEST_CORNER,
        val minComponentTriangles: Int = MIN_COMPONENT_TRIANGLES,
        val maxBytes: Long = MAX_BYTES,
        val maxTriangles: Int = MAX_TRIANGLES,
        /** Holes whose rim spans at most this are closed ([MeshCleanup.fillSmallHoles]); 0 leaves them. */
        val holeSpanM: Float = HOLE_SPAN_M,
    )

    /** The fused surface and what it cost. */
    data class Result(
        val mesh: RerunMesh,
        val frames: Int,
        val fused: Int,
        val blocks: Int,
        val bytes: Long,
        /** Blocks the byte cap turned away: the surface is missing what they held. */
        val droppedBlocks: Int,
        val rawTriangles: Int,
        val droppedComponents: Int,
        val capped: Boolean,
        /** Small holes closed after meshing. */
        val holesClosed: Int,
        val fuseMs: Long,
        val meshMs: Long,
    )

    /**
     * [depth]'s surface. A frame is fused when [integrates] says so for its time, or, without a
     * verdict, when the phone's pose guard did not hold it back — and, with a [guard], when that
     * guard, handed every recorded pose in turn, lets it through too: a slip the phone missed is
     * still kept out. `null` when [depth] is not a depth recording, or none of its frames is
     * fused. [progress] (0–1) may throw to stop it.
     */
    fun fuse(
        depth: File,
        settings: Settings = Settings(),
        integrates: Map<Long, Boolean>? = null,
        guard: PoseContinuity? = null,
        progress: (Float) -> Unit = {},
    ): Result? {
        // Only extracted arrays and scalar statistics cross this call boundary. The TSDF and
        // its block index are no longer retained when hole closing allocates its topology.
        val raw = extract(depth, settings, integrates, guard, progress) ?: return null
        val started = System.nanoTime()
        val (mesh, holes) = if (settings.holeSpanM > 0f) {
            closeHoles(raw.mesh, settings.holeSpanM) { progress(MESH_END + (1f - MESH_END) * it) }
        } else {
            raw.mesh to 0
        }
        progress(1f)
        return raw.copy(mesh = mesh, holesClosed = holes, meshMs = raw.meshMs + since(started))
    }

    private fun extract(
        depth: File,
        settings: Settings,
        integrates: Map<Long, Boolean>?,
        guard: PoseContinuity?,
        progress: (Float) -> Unit,
    ): Result? {
        val started = System.nanoTime()
        val total = depth.length().coerceAtLeast(1L)
        val tsdf = RerunTsdf(settings.voxelM, settings.truncationM, settings.maxBytes)
        var count = 0
        var fused = 0
        val file = CountingInput(BufferedInputStream(depth.inputStream(), STREAM_BUFFER))
        file.use { input ->
            ScanDepthFormat.readHeader(input) ?: return null
            while (true) {
                val recorded = ScanDepthFormat.readFrame(input) ?: break
                count++
                // Every pose goes through the guard, held ones included: it follows the walk.
                val guarded = guard?.step(recorded.nanos, recorded.display)?.integrates ?: true
                if (!(integrates?.get(recorded.nanos) ?: !recorded.rejected) || !guarded) {
                    progress(FUSE_SHARE * (file.read.toFloat() / total).coerceAtMost(1f))
                    continue
                }
                tsdf.integrate(
                    recorded.frame,
                    minConfidence = settings.minConfidence,
                    farM = settings.farM,
                    truncationShare = settings.truncationShare,
                    distanceWeighted = true,
                )
                fused++
                progress(FUSE_SHARE * (file.read.toFloat() / total).coerceAtMost(1f))
            }
        }
        if (fused == 0) return null
        val fuseMs = since(started)
        progress(FUSE_SHARE)
        val meshStarted = System.nanoTime()
        val extraction: MeshExtraction = RerunMarchingCubes.extractWhole(
            tsdf,
            minWeight = settings.minWeight,
            maxTriangles = settings.maxTriangles,
            minComponentTriangles = settings.minComponentTriangles,
            progress = { progress(FUSE_SHARE + (MESH_END - FUSE_SHARE) * it) },
            weakestCorner = settings.weakestCorner,
        )
        return Result(
            mesh = extraction.mesh,
            holesClosed = 0,
            frames = count,
            fused = fused,
            blocks = tsdf.blockCount,
            bytes = tsdf.bytes,
            droppedBlocks = tsdf.droppedBlocks,
            rawTriangles = extraction.rawTriangles,
            droppedComponents = extraction.droppedComponents,
            capped = extraction.capped,
            fuseMs = fuseMs,
            meshMs = since(meshStarted),
        )
    }

    /** The surface of the session in [dir], when it keeps a depth recording; `null` otherwise. */
    fun fuseSession(dir: File, settings: Settings = Settings(), progress: (Float) -> Unit = {}): Result? =
        File(dir, ScanDepthFormat.FILE).takeIf { it.isFile }?.let { fuse(it, settings, progress = progress) }

    /**
     * [mesh] with its holes of a rim under [spanM] closed by [MeshCleanup.fillSmallHoles]: a
     * vertex it adds takes the normal of the triangles round it, the others keep theirs.
     */
    private fun closeHoles(mesh: RerunMesh, spanM: Float, progress: (Float) -> Unit): Pair<RerunMesh, Int> {
        val (filled, holes) = MeshCleanup.fillSmallHoles(CleanGeometry.consuming(mesh), maxSpanM = spanM, progress = { progress(it * NORMALS_START) })
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
        return RerunMesh(filled.positions, normals, filled.colors, filled.indices) to holes
    }

    /** The bytes read so far through it: how far into the recording the fusion is. */
    private class CountingInput(input: InputStream) : FilterInputStream(input) {
        var read = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) read++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
    }

    private fun since(start: Long) = (System.nanoTime() - start) / NANOS_PER_MS

    /** 2 cm: a speaker's edge, a plant's leaves, a TV unit's shelf, at 160 × 90 depth. */
    const val VOXEL_M = 0.02f

    /** The band round the surface: at least 6 cm, 3 % of the depth further than 2 m. */
    const val TRUNCATION_M = 0.06f
    const val TRUNCATION_SHARE = 0.03f

    /** ARCore's confidence, 0–255: the lower half is guessed depth. */
    const val MIN_CONFIDENCE = 128
    const val FAR_M = 5f

    /**
     * How far a room's fused surface reaches when it stands as no room ([depthModelOf]): past
     * 4 m ARCore's raw depth on a phone is a lace of sure-looking pixels, and what it builds there
     * is the shreds a short walk's result was made of. Nothing is drawn further than this from
     * where the camera really was.
     */
    const val RELIABLE_FAR_M = 4f

    /** A cell is meshed when one corner was seen by about half a near, sure view or more… */
    const val MIN_WEIGHT = 0.5f

    /** …and every other corner at least a little: the edge of a surface closes. */
    const val WEAKEST_CORNER = 0.05f

    /** Floating pieces smaller than this are noise: about 400 vertices. */
    const val MIN_COMPONENT_TRIANGLES = 700

    /**
     * Holes up to 15 cm across are closed: the lace a far wall's depth leaves, a pinhole in the
     * floor. A doorway, the gap between two speakers, a part nobody scanned stay open.
     */
    const val HOLE_SPAN_M = 0.15f

    /** 48 MB, as the live TSDF ([RerunTsdf.MAX_BYTES]): ~7 900 blocks of 16 cm. */
    const val MAX_BYTES = RerunTsdf.MAX_BYTES

    /** A room's furniture and shell at 2 cm is a few hundred thousand triangles; past this the cells coarsen. */
    const val MAX_TRIANGLES = 600_000

    private const val FUSE_SHARE = 0.65f
    private const val MESH_END = 0.88f
    private const val NORMALS_START = 0.9f
    private const val PROGRESS_TRIANGLES = 1024
    private const val STREAM_BUFFER = 1 shl 16
    private const val NANOS_PER_MS = 1_000_000L
}
