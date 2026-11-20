package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.PoseContinuity
import io.github.sceneview.demo.demos.internal.RecordedDepthFrame
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
import java.io.IOException
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.floor
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
        /** The floor ARCore found under the fused frames (their median), `null` when it found none. */
        val floorPlaneY: Float? = null,
        /** Frames the phone fused that the [fuse] guard kept out. */
        val guardHeld: Int = 0,
        /** Walk segments a slip with no end split the recording into; only the largest is the surface. */
        val segments: Int = 1,
        /** The segment fused as the surface, from 0: the one whose depth reached the most cells. */
        val chosenSegment: Int = 0,
        /** Each segment's coverage: the [COVERAGE_CELL_M] cells its depth reached. */
        val segmentCells: List<Int> = emptyList(),
        /** Each segment's frames the phone and the guard let through. */
        val segmentFrames: List<Int> = emptyList(),
        val fuseMs: Long,
        val meshMs: Long,
        val floorTrace: FloorTrace? = null,
    )

    data class FloorTrace(
        val floorY: Float,
        val stride: Int,
        val capped: Boolean,
        val widerDroppedBlocks: Int?,
        val widerTruncationM2: Float?,
        val widerTruncationWeightM2: Float?,
        val cells: RerunMarchingCubes.FloorCells,
        val componentsM2: Float,
        val classification: FloorClassification,
        val observedVoxels: Int,
        val widerBandOnlyVoxels: Int?,
    )

    data class FloorClassification(
        val nearFloorM2: Float,
        val heightBandM2: Float,
        val classifiedM2: Float,
        val heightTriangles: Int,
        val normalTriangles: Int,
        val classifiedTriangles: Int,
    )

    /**
     * [depth]'s surface. A frame is fused when [integrates] says so for its time, or, without a
     * verdict, when the phone's pose guard did not hold it back — and, with a [guard], when that
     * guard, handed every recorded pose in turn, lets it through too: a slip the phone missed is
     * still kept out. `null` when [depth] is not a depth recording, or none of its frames is
     * fused. [progress] (0–1) may throw to stop it. With [traceFloor], measure the floor at
     * [floorY] or the floor found in the fused segment and try a wider band on a separate TSDF.
     */
    fun fuse(
        depth: File,
        settings: Settings = Settings(),
        integrates: Map<Long, Boolean>? = null,
        guard: PoseContinuity? = null,
        traceFloor: Boolean = false,
        floorY: Float? = null,
        progress: (Float) -> Unit = {},
    ): Result? {
        val probe = if (traceFloor) FloorProbe(floorY) else null
        // The nominal TSDF is released before the diagnostic replay or hole closing allocates.
        val raw = extract(depth, settings, integrates, guard, probe, progress) ?: return null
        val replay = probe?.replay
        val wider = replay?.measureWider(depth, settings) { progress(MESH_END) }
        val started = System.nanoTime()
        val (mesh, holes) = if (settings.holeSpanM > 0f) {
            closeHoles(raw.mesh, settings.holeSpanM) { progress(MESH_END + (1f - MESH_END) * it) }
        } else {
            raw.mesh to 0
        }
        progress(1f)
        val meshMs = raw.meshMs + since(started)
        val floorTrace = replay?.finish(wider, mesh)
        return raw.copy(mesh = mesh, holesClosed = holes, meshMs = meshMs, floorTrace = floorTrace)
    }

    private fun extract(
        depth: File,
        settings: Settings,
        integrates: Map<Long, Boolean>?,
        guard: PoseContinuity?,
        probe: FloorProbe?,
        progress: (Float) -> Unit,
    ): Result? {
        val started = System.nanoTime()
        // The walk in segments: a slip the guard sees no end of ([PoseContinuity.isStuck]) starts a
        // new one, never fused over the world it slipped from (a ghost room). The segment that saw
        // the most of the place ([coverage]: distinct 10 cm cells its depth reached, not how many
        // frames it holds) is the surface. One TSDF at a time, in the one byte budget: the first
        // segment is fused as it is read; another that covers more is fused again on its own.
        val plan = ArrayList<Int>()
        var segment = 0
        val reached = arrayListOf(HashSet<Long>())
        var count = 0
        var guardHeld = 0
        var fusedFirst = 0
        val firstFloors = ArrayList<Float>()
        var tsdf: RerunTsdf? = RerunTsdf(settings.voxelM, settings.truncationM, settings.maxBytes)
        read(depth) { recorded, share ->
            count++
            // Every pose goes through the guard, held ones included: it follows the walk.
            var guarded = guard?.step(recorded.nanos, recorded.display)?.integrates ?: true
            if (guard != null && guard.isStuck) {
                guard.reset()
                guarded = guard.step(recorded.nanos, recorded.display).integrates
                if (reached[segment].isNotEmpty()) {
                    segment++
                    reached += HashSet<Long>()
                }
            }
            val phone = integrates?.get(recorded.nanos) ?: !recorded.rejected
            if (phone && !guarded) guardHeld++
            if (phone && guarded) {
                plan += segment
                coverage(recorded.frame, settings, reached[segment])
                if (segment == 0) {
                    tsdf?.integrateRoomFrame(recorded.frame, settings)
                    fusedFirst++
                    recorded.floorPlaneY?.let { firstFloors += it }
                }
            } else {
                plan += NOT_FUSED
            }
            progress(FIRST_PASS_SHARE * share)
        } ?: return null
        val chosen = reached.indices.maxBy { reached[it].size }
        var fused = fusedFirst
        var floors: List<Float> = firstFloors
        if (chosen != 0) {
            // The first segment's TSDF goes before the chosen one is fused: never two at once.
            tsdf = null
            val again = RerunTsdf(settings.voxelM, settings.truncationM, settings.maxBytes)
            val againFloors = ArrayList<Float>()
            var index = 0
            fused = 0
            read(depth) { recorded, share ->
                if (plan[index++] == chosen) {
                    again.integrateRoomFrame(recorded.frame, settings)
                    fused++
                    recorded.floorPlaneY?.let { againFloors += it }
                }
                progress(FIRST_PASS_SHARE + (FUSE_SHARE - FIRST_PASS_SHARE) * share)
            }
            tsdf = again
            floors = againFloors
        }
        val segments = reached.size
        val segmentFrames = IntArray(segments)
        for (s in plan) if (s >= 0) segmentFrames[s]++
        val surface = tsdf ?: return null
        if (fused == 0) return null
        val fuseMs = since(started)
        progress(FUSE_SHARE)
        val meshStarted = System.nanoTime()
        val extraction: MeshExtraction = RerunMarchingCubes.extractWhole(
            surface,
            minWeight = settings.minWeight,
            maxTriangles = settings.maxTriangles,
            minComponentTriangles = settings.minComponentTriangles,
            progress = { progress(FUSE_SHARE + (MESH_END - FUSE_SHARE) * it) },
            weakestCorner = settings.weakestCorner,
        )
        val floorPlaneY = floors.sorted().getOrNull(floors.size / 2)
        val meshMs = since(meshStarted)
        if (probe != null) {
            val y = probe.hint ?: floorPlaneY ?: ScanFloorSlab.floorOf(extraction.mesh)
            if (y != null && y.isFinite()) {
                val area = FloorArea(y)
                val voxels = HashMap<Long, LongArray>()
                var observed = 0
                floorVoxelLayers(surface, y) { key, layer, mask ->
                    voxels.getOrPut(key) { LongArray(RerunTsdf.BLOCK) }[layer] = mask
                    observed += mask.countOneBits()
                }
                probe.replay = FloorReplay(
                    area, plan, chosen, extraction.stride, extraction.capped,
                    measureFloorCells(surface, settings, extraction.stride, area, extraction.cappedAt),
                    area.classify(extraction.mesh).classifiedM2,
                    voxels, observed,
                )
            }
        }
        return Result(
            mesh = extraction.mesh,
            holesClosed = 0,
            floorPlaneY = floorPlaneY,
            guardHeld = guardHeld,
            segments = segments,
            chosenSegment = chosen,
            segmentCells = reached.map { it.size },
            segmentFrames = segmentFrames.toList(),
            frames = count,
            fused = fused,
            blocks = surface.blockCount,
            bytes = surface.bytes,
            droppedBlocks = surface.droppedBlocks,
            rawTriangles = extraction.rawTriangles,
            droppedComponents = extraction.droppedComponents,
            capped = extraction.capped,
            fuseMs = fuseMs,
            meshMs = meshMs,
        )
    }

    private fun RerunTsdf.integrateRoomFrame(frame: DepthFrame, settings: Settings) {
        integrate(
            frame,
            minConfidence = settings.minConfidence,
            farM = settings.farM,
            truncationShare = settings.truncationShare,
            distanceWeighted = true,
        )
    }

    private class FloorProbe(val hint: Float?) {
        var replay: FloorReplay? = null
    }

    private data class WiderBand(
        val droppedBlocks: Int,
        val observedM2: Float,
        val weightM2: Float,
        val newVoxels: Int,
    )

    /** The selected frames and a compact snapshot of the nominal floor band, with no TSDF retained. */
    private class FloorReplay(
        val area: FloorArea,
        val plan: List<Int>,
        val chosen: Int,
        val stride: Int,
        val capped: Boolean,
        val cells: RerunMarchingCubes.FloorCells,
        val componentsM2: Float,
        val voxels: Map<Long, LongArray>,
        val observedVoxels: Int,
    ) {
        fun measureWider(depth: File, settings: Settings, checkActive: () -> Unit): WiderBand? {
            val wider = fuseWider(depth, settings, checkActive) ?: return null
            val widerCells = measureFloorCells(wider, settings, stride, area)
            var newVoxels = 0
            floorVoxelLayers(wider, area.y) { key, layer, mask ->
                val nominal = voxels[key]?.get(layer) ?: 0L
                newVoxels += (mask and nominal.inv()).countOneBits()
            }
            return WiderBand(wider.droppedBlocks, widerCells.observedM2, widerCells.weightM2, newVoxels)
        }

        fun finish(wider: WiderBand?, mesh: RerunMesh) = FloorTrace(
            area.y, stride, capped, wider?.droppedBlocks, wider?.observedM2, wider?.weightM2,
            cells, componentsM2, area.classify(mesh), observedVoxels, wider?.newVoxels,
        )

        private fun fuseWider(depth: File, settings: Settings, checkActive: () -> Unit): RerunTsdf? {
            val wide = settings.copy(truncationM = settings.truncationM * 2f, truncationShare = settings.truncationShare * 2f)
            val tsdf = RerunTsdf(wide.voxelM, wide.truncationM, wide.maxBytes)
            var index = 0
            try {
                read(depth) { recorded, _ ->
                    if (plan.getOrNull(index++) == chosen) tsdf.integrateRoomFrame(recorded.frame, wide)
                    checkActive()
                } ?: return null
            } catch (_: IOException) {
                // The result came from the first read. A failed diagnostic reread leaves it usable.
                return null
            }
            return tsdf.takeIf { index == plan.size }
        }
    }

    private fun measureFloorCells(tsdf: RerunTsdf, settings: Settings, stride: Int, area: FloorArea, cappedAt: Long = Long.MAX_VALUE) =
        RerunMarchingCubes.measureFloorCells(
            tsdf, stride, area.y, ScanFloorSlab.FLOOR_BAND_M, settings.minWeight, settings.weakestCorner, cappedAt, area::classified,
        )

    /** One 8 × 8 mask per observed voxel layer in the slab's height band. */
    private inline fun floorVoxelLayers(tsdf: RerunTsdf, y: Float, layer: (Long, Int, Long) -> Unit) {
        val coord = IntArray(3)
        val side = RerunTsdf.BLOCK
        for (block in 0 until tsdf.blockCount) {
            tsdf.blockCoord(block, coord)
            val key = RerunTsdf.blockKey(coord[0], coord[1], coord[2])
            val weights = tsdf.weight[block]
            for (ly in 0 until side) {
                if (abs((coord[1] * side + ly) * tsdf.voxelM - y) > ScanFloorSlab.FLOOR_BAND_M) continue
                var mask = 0L
                for (lz in 0 until side) {
                    for (lx in 0 until side) {
                        if (weights[(lz * side + ly) * side + lx] > 0f) mask = mask or (1L shl (lz * side + lx))
                    }
                }
                if (mask != 0L) layer(key, ly, mask)
            }
        }
    }

    private class FloorArea(val y: Float) {
        private val band = ScanFloorSlab.FLOOR_BAND_M

        /** XZ projected m². Same all-vertices height and two-sided face normal as ScanFloorSlab.isFloor. */
        fun classified(p: FloatArray, a: Int, b: Int, c: Int): Float =
            if (heightError(p, a, b, c) <= band) projected(p, a, b, c, classifyNormal = true) else 0f

        fun classify(mesh: RerunMesh): FloorClassification {
            val p = mesh.positions
            val ix = mesh.indices
            var near = 0.0
            var inBand = 0.0
            var accepted = 0.0
            var heightTriangles = 0
            var normalTriangles = 0
            var classifiedTriangles = 0
            for (t in ix.indices step 3) {
                val a = ix[t]
                val b = ix[t + 1]
                val c = ix[t + 2]
                val error = heightError(p, a, b, c)
                if (error > band * 2f) continue
                val area = projected(p, a, b, c)
                near += area
                if (error > band) {
                    heightTriangles++
                } else {
                    inBand += area
                    val classified = projected(p, a, b, c, classifyNormal = true)
                    if (classified > 0f) {
                        accepted += classified
                        classifiedTriangles++
                    } else {
                        normalTriangles++
                    }
                }
            }
            return FloorClassification(near.toFloat(), inBand.toFloat(), accepted.toFloat(), heightTriangles, normalTriangles, classifiedTriangles)
        }

        private fun heightError(p: FloatArray, a: Int, b: Int, c: Int) =
            maxOf(abs(p[a * 3 + 1] - y), abs(p[b * 3 + 1] - y), abs(p[c * 3 + 1] - y))

        private fun projected(p: FloatArray, a: Int, b: Int, c: Int, classifyNormal: Boolean = false): Float {
            val ux = p[b * 3] - p[a * 3]
            val uz = p[b * 3 + 2] - p[a * 3 + 2]
            val vx = p[c * 3] - p[a * 3]
            val vz = p[c * 3 + 2] - p[a * 3 + 2]
            val ny = abs(uz * vx - ux * vz)
            if (classifyNormal) {
                val uy = p[b * 3 + 1] - p[a * 3 + 1]
                val vy = p[c * 3 + 1] - p[a * 3 + 1]
                val nx = uy * vz - uz * vy
                val nz = ux * vy - uy * vx
                val length = sqrt(nx * nx + ny * ny + nz * nz)
                if (!(length > 0f && ny / length >= 0.8f)) return 0f
            }
            return ny * 0.5f
        }
    }

    /**
     * Every frame of [depth] in turn, with the share of the file read so far; `null` when it is
     * not a depth recording.
     */
    private inline fun read(depth: File, frame: (RecordedDepthFrame, Float) -> Unit): Unit? {
        val total = depth.length().coerceAtLeast(1L)
        CountingInput(BufferedInputStream(depth.inputStream(), STREAM_BUFFER)).use { input ->
            ScanDepthFormat.readHeader(input) ?: return null
            while (true) {
                val recorded = ScanDepthFormat.readFrame(input) ?: break
                frame(recorded, (input.read.toFloat() / total).coerceAtMost(1f))
            }
        }
        return Unit
    }

    /**
     * The [COVERAGE_CELL_M] cells [frame]'s sure depth reaches (one pixel in [COVERAGE_STRIDE] each
     * way), added to [cells]: how much of the place a segment saw, whatever its frame count.
     */
    internal fun coverage(frame: DepthFrame, settings: Settings, cells: MutableSet<Long>) {
        val pose = frame.pose
        for (py in 0 until frame.height step COVERAGE_STRIDE) {
            for (px in 0 until frame.width step COVERAGE_STRIDE) {
                val i = py * frame.width + px
                val z = (frame.depthMm[i].toInt() and 0xFFFF) / MM_PER_M
                if (z <= 0f || z > settings.farM) continue
                val c = frame.confidence?.let { it[i].toInt() and 0xFF } ?: FULL_CONFIDENCE
                if (c < settings.minConfidence) continue
                val w = pose.rotate((px - frame.cx) / frame.fx * z, -(py - frame.cy) / frame.fy * z, -z)
                val x = floor((pose.x + w.x) / COVERAGE_CELL_M).toLong()
                val y = floor((pose.y + w.y) / COVERAGE_CELL_M).toLong()
                val zc = floor((pose.z + w.z) / COVERAGE_CELL_M).toLong()
                cells += ((x and CELL_MASK) shl 42) or ((y and CELL_MASK) shl 21) or (zc and CELL_MASK)
            }
        }
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

    /**
     * The pose guard a recording is fused through again, off the phone. Its depth frames come
     * 100 ms or more apart, not every camera frame: the jump back from a slip carries that much
     * real motion with it, so it is let to undo the slip within 30 cm and 25°, not the phone's
     * 20 cm and 10°.
     */
    fun replayGuard(): PoseContinuity = PoseContinuity(recoverMetres = REPLAY_RECOVER_M, recoverDegrees = REPLAY_RECOVER_DEG)

    private const val REPLAY_RECOVER_M = 0.3f

    /** A frame left out of every segment ([extract]'s plan). */
    private const val NOT_FUSED = -1

    /** The part of the fusion's share the first read takes; a second, for another segment, the rest. */
    private const val FIRST_PASS_SHARE = 0.6f

    /** The coverage grid a segment is judged on: 10 cm cells, one depth pixel in four each way. */
    private const val COVERAGE_CELL_M = 0.1f
    private const val COVERAGE_STRIDE = 4
    private const val CELL_MASK = (1L shl 21) - 1
    private const val MM_PER_M = 1000f
    private const val FULL_CONFIDENCE = 255
    private const val REPLAY_RECOVER_DEG = 25f

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
