package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.MeshExtraction
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

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
    )

    /** The fused surface and what it cost. */
    class Result(
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
        val fuseMs: Long,
        val meshMs: Long,
    )

    /**
     * [depth]'s surface. A frame is fused when [integrates] says so for its time, or, without a
     * verdict, when the phone's pose guard did not hold it back. `null` when [depth] is not a
     * depth recording, or none of its frames is fused. [progress] (0–1) may throw to stop it.
     */
    fun fuse(
        depth: File,
        settings: Settings = Settings(),
        integrates: Map<Long, Boolean>? = null,
        progress: (Float) -> Unit = {},
    ): Result? {
        val started = System.nanoTime()
        val total = depth.length().coerceAtLeast(1L)
        val tsdf = RerunTsdf(settings.voxelM, settings.truncationM, settings.maxBytes)
        var count = 0
        var fused = 0
        val file = CountingInput(depth.inputStream())
        BufferedInputStream(file, STREAM_BUFFER).use { input ->
            ScanDepthFormat.readHeader(input) ?: return null
            while (true) {
                val recorded = ScanDepthFormat.readFrame(input) ?: break
                count++
                if (count % PROGRESS_FRAMES == 0) progress(FUSE_SHARE * (file.read.toFloat() / total).coerceAtMost(1f))
                if (!(integrates?.get(recorded.nanos) ?: !recorded.rejected)) continue
                tsdf.integrate(
                    recorded.frame,
                    minConfidence = settings.minConfidence,
                    farM = settings.farM,
                    truncationShare = settings.truncationShare,
                    distanceWeighted = true,
                )
                fused++
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
            progress = { progress(FUSE_SHARE + (1f - FUSE_SHARE) * it) },
            weakestCorner = settings.weakestCorner,
        )
        return Result(
            mesh = extraction.mesh,
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

    /** A cell is meshed when one corner was seen by about half a near, sure view or more… */
    const val MIN_WEIGHT = 0.5f

    /** …and every other corner at least a little: the edge of a surface closes. */
    const val WEAKEST_CORNER = 0.05f

    /** Floating pieces smaller than this are noise: about 400 vertices. */
    const val MIN_COMPONENT_TRIANGLES = 700

    /** 48 MB, as the live TSDF ([RerunTsdf.MAX_BYTES]): ~7 900 blocks of 16 cm. */
    const val MAX_BYTES = RerunTsdf.MAX_BYTES

    /** A room's furniture and shell at 2 cm is a few hundred thousand triangles; past this the cells coarsen. */
    const val MAX_TRIANGLES = 600_000

    private const val FUSE_SHARE = 0.8f
    private const val PROGRESS_FRAMES = 8
    private const val STREAM_BUFFER = 1 shl 16
    private const val NANOS_PER_MS = 1_000_000L
}
