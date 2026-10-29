package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import kotlin.math.floor

/** What the finished model's build did and how long each stage took, for the log, tests and corpus. */
internal data class CleanModelStats(
    val rawTriangles: Int,
    /** The triangles cleaning took: [rawTriangles], or [CleanModel.CLEAN_INPUT_TRIANGLES] for a bigger surface. */
    val cleanInputTriangles: Int,
    val geometry: CleanGeometryStats,
    /** The budget the simplification aimed at; it may stop under it (flat walls) or over it (colour edges). */
    val simplifyTarget: Int,
    val simplifiedTriangles: Int,
    val finalTriangles: Int,
    val finalVertices: Int,
    val texture: PhotoTextureStats?,
    /** The textured share of a texture dropped for covering too little of the model, `null` if none was. */
    val droppedTextureShare: Float?,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val glbBytes: Int,
    /** The most the finished `.glb` weighs, [CleanModel.MAX_GLB_BYTES]. */
    val glbLimitBytes: Int,
    /** How many times the atlas was halved to keep the `.glb` within [glbLimitBytes]. */
    val atlasHalvings: Int,
    /** Whether the texture was dropped, then the budget lowered, to keep within [glbLimitBytes]. */
    val glbGuarded: Boolean,
    /**
     * The stage the heap ran out in, `null` if none did: the result is then the raw surface's own
     * `.glb` ([rawKept]), and the stats stop at that stage.
     */
    val outOfMemoryIn: CleanModel.Stage?,
    val prepareMs: Long,
    val cleanMs: Long,
    val simplifyMs: Long,
    val textureMs: Long,
    val writeMs: Long,
) {
    val totalMs: Long get() = prepareMs + cleanMs + simplifyMs + textureMs + writeMs

    /** The finish ran out of memory ([outOfMemoryIn]): the raw surface's `.glb` is the result. */
    val rawKept: Boolean get() = outOfMemoryIn != null
}

/**
 * The finished model's [glb], and the [model] it was written from — `null` when the finish ran
 * out of memory ([CleanModelStats.rawKept]): [glb] is then the raw surface's own.
 */
internal class CleanModelResult(val glb: ByteArray, val model: ModelMesh?, val stats: CleanModelStats)

/**
 * A raw surface handed to [CleanModel.build] whole: the finish takes [mesh] from it once and holds
 * it no longer than it needs it. A caller that keeps no other reference lets the finish free the
 * surface's arrays once they are prepared, before the cleaning's peak.
 */
internal class RawSurface(mesh: RerunMesh) {
    val triangleCount: Int = mesh.triangleCount
    val vertexCount: Int = mesh.vertexCount
    private var held: RerunMesh? = mesh

    /** The surface, while nothing took it. */
    val mesh: RerunMesh get() = checkNotNull(held) { "the raw surface was taken" }

    /** The surface, which this holder lets go. */
    fun take(): RerunMesh = mesh.also { held = null }
}

/**
 * The finished model of a scan, from the marching cubes' raw surface: brought within what
 * cleaning takes ([CLEAN_INPUT_TRIANGLES]), cleaned ([CleanGeometry]), simplified to a phone's
 * budget, textured from the key photos when the recording has them and they cover the model
 * ([PhotoTexturing]), its normals split at creases ([ModelAssembly]), and written as a GLB
 * ([ModelGlb]). Blocking CPU work, for a worker thread; [progress] (0–1) may throw to stop it.
 *
 * The finished `.glb` weighs at most [MAX_GLB_BYTES]: over it, a textured model's faces are cut
 * down to [MIN_GUARD_TEXTURED_TRIANGLES], then its atlas halved, then — last — its texture
 * dropped and the budget halved down to [MIN_GUARD_TRIANGLES]. A finish the heap cannot
 * hold gives the raw surface back — its own `.glb`, no [CleanModelResult.model] — and says in
 * which [Stage] it ran out ([CleanModelStats.outOfMemoryIn]): a phone too small for the scan still
 * keeps it as scanned. With `consume`, the raw surface's arrays are worked in instead of copied —
 * for a caller that already wrote its `.glb`, passed as `rawGlb`, and is done with them. Handed
 * over as a [RawSurface], they are also let go once prepared.
 *
 * A scan meshed to more than [maxRawTriangles] for the heap would not finish in it: the caller
 * caps the extraction there.
 */
internal object CleanModel {
    /** The finish's stages, in order: what [CleanModelStats.outOfMemoryIn] names. */
    enum class Stage { Prepare, Clean, Simplify, Texture, Write }

    @Suppress("LongParameterList")
    fun build(
        raw: RerunMesh,
        photos: ModelPhotos?,
        encoder: AtlasEncoder?,
        targetTriangles: Int = RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES,
        consume: Boolean = false,
        rawGlb: ByteArray? = null,
        progress: (Float) -> Unit = {},
    ): CleanModelResult {
        require(!consume || rawGlb != null) { "a consumed raw surface needs its .glb written first" }
        return finishOrRaw(RawSurface(raw), photos, encoder, targetTriangles, consume, progress) { rawGlb ?: RerunMeshGlb.write(raw) }
    }

    /**
     * [raw] finished in its own arrays, which it lets go once prepared: the caller holds nothing
     * else of the surface, only its [rawGlb], written first — the result if the heap runs out.
     */
    fun build(
        raw: RawSurface,
        rawGlb: ByteArray,
        photos: ModelPhotos?,
        encoder: AtlasEncoder?,
        targetTriangles: Int = RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES,
        progress: (Float) -> Unit = {},
    ): CleanModelResult = finishOrRaw(raw, photos, encoder, targetTriangles, consume = true, progress) { rawGlb }

    /**
     * The most raw triangles a scan may mesh to for its finish to fit in a heap of [heapBytes]
     * (`Runtime.maxMemory()`, on a phone its growth limit), within [RerunMarchingCubes.MAX_TRIANGLES]:
     * [HELD_BYTES], then [BYTES_PER_RAW_TRIANGLE] for each raw triangle. A Pixel 4a's 192 MB
     * meshes about 645k triangles, the longest recordings and then some; 263 MB and more, the
     * extraction's million.
     */
    fun maxRawTriangles(heapBytes: Long): Int =
        ((heapBytes - HELD_BYTES) / BYTES_PER_RAW_TRIANGLE)
            .coerceIn(MIN_RAW_TRIANGLES.toLong(), RerunMarchingCubes.MAX_TRIANGLES.toLong()).toInt()

    @Suppress("LongParameterList")
    private fun finishOrRaw(
        raw: RawSurface,
        photos: ModelPhotos?,
        encoder: AtlasEncoder?,
        targetTriangles: Int,
        consume: Boolean,
        progress: (Float) -> Unit,
        rawGlb: () -> ByteArray,
    ): CleanModelResult {
        require(raw.triangleCount > 0) { "a model needs triangles" }
        val rawTriangles = raw.triangleCount
        val rawVertices = raw.vertexCount
        val reached = Reached()
        // Out of memory, what the finish held goes with its frame: the raw surface's .glb fits again.
        val finished = runCatching { finish(raw, photos, encoder, targetTriangles, consume, reached, progress) }
        val failure = finished.exceptionOrNull()
        if (failure != null && failure !is OutOfMemoryError) throw failure
        return finished.getOrNull() ?: rawSurface(rawGlb(), rawTriangles, rawVertices, reached)
    }

    /**
     * No local here holds what a later stage no longer needs — the raw surface, the cleaning's
     * input, a texture dropped — so the heap's peak stays that of the stage at work.
     */
    @Suppress("LongMethod", "LongParameterList")
    private fun finish(
        raw: RawSurface,
        photos: ModelPhotos?,
        encoder: AtlasEncoder?,
        targetTriangles: Int,
        consume: Boolean,
        reached: Reached,
        progress: (Float) -> Unit,
    ): CleanModelResult {
        val rawTriangles = raw.triangleCount
        val (clean, geometry) = CleanGeometry.clean(prepared(raw, consume, reached, progress)) {
            progress(PREPARE_SHARE + it * CLEAN_SHARE)
        }
        reached.geometry = geometry
        reached.lap(Stage.Simplify)
        val textured = photos != null && photos.photos.isNotEmpty() && encoder != null
        val simplifyTarget = if (textured) minOf(targetTriangles, TEXTURED_TARGET_TRIANGLES) else targetTriangles
        val ceiling = errorCeiling(geometry)
        val shape = simplify(clean, textured, simplifyTarget, ceiling) { progress(BEFORE_SIMPLIFY + it * SIMPLIFY_SHARE) }
        reached.lap(Stage.Texture)
        // A shape simplified for a texture keeps only coarse colours where the photos did not
        // reach: the photos texture what they saw, the vertex colours the rest. Photos that
        // failed to decode, wrong poses, a corner of the room seen — under [MIN_TEXTURED_SHARE],
        // the colours are simplified again, edges kept, as without photos.
        val textures = if (textured) textures(shape, photos!!) { progress(BEFORE_TEXTURE + it * TEXTURE_SHARE) } else null
        val covered = textures?.covering != null
        var texture = textures?.covering
        var simplified = if (textured && !covered) {
            reached.lap(Stage.Simplify)
            simplify(clean, textured = false, targetTriangles, ceiling) { progress(BEFORE_TEXTURE + it * TEXTURE_SHARE) }
                .also { reached.lap(Stage.Texture) }
        } else {
            shape
        }
        var model: ModelMesh? = ModelAssembly.assemble(simplified, texture)
        reached.lap(Stage.Write)
        var glb = ModelGlb.write(checkNotNull(model), encoder)
        val guarded = glb.size > MAX_GLB_BYTES
        // Within the cap, the model gives up first what shows least. A textured one sheds faces
        // past its budget, down to [MIN_GUARD_TEXTURED_TRIANGLES]: its shape simplified again,
        // the error no longer capped, and textured again. Then its atlas's resolution. Only then
        // its photo texture, for the vertex colours, its faces halved as they must. Each step
        // reports, so a finish left meanwhile stops there.
        var cut = true
        while (glb.size > MAX_GLB_BYTES && texture != null && cut && simplified.triangleCount > MIN_GUARD_TEXTURED_TRIANGLES) {
            progress(BEFORE_WRITE)
            val before = simplified.triangleCount
            val target = maxOf(MIN_GUARD_TEXTURED_TRIANGLES, (before * GUARD_FILL * MAX_GLB_BYTES / glb.size).toInt())
            // The model, its atlas and its file go before the coarser shape and its texture are made.
            model = null
            texture = null
            glb = NO_BYTES
            simplified = simplify(clean, textured = true, target, ceiling = Float.POSITIVE_INFINITY) { progress(BEFORE_WRITE) }
            cut = simplified.triangleCount < before
            texture = textures(simplified, photos!!) { progress(BEFORE_WRITE) }.covering
            model = ModelAssembly.assemble(simplified, texture)
            glb = ModelGlb.write(model, encoder)
        }
        var halvings = 0
        while (glb.size > MAX_GLB_BYTES && texture != null && halvings < MAX_ATLAS_HALVINGS) {
            progress(BEFORE_WRITE)
            texture = texture.halved()
            model = checkNotNull(model).withTexture(texture)
            glb = ModelGlb.write(model, encoder)
            halvings++
        }
        var guardTarget = minOf(targetTriangles, simplified.triangleCount)
        while (glb.size > MAX_GLB_BYTES && (texture != null || guardTarget >= MIN_GUARD_TRIANGLES)) {
            progress(BEFORE_WRITE)
            if (texture == null) guardTarget /= 2
            texture = null
            simplified = simplify(clean, textured = false, guardTarget, ceiling) { progress(BEFORE_WRITE) }
            model = ModelAssembly.assemble(simplified, null)
            glb = ModelGlb.write(model, encoder)
        }
        reached.lap(Stage.Write)
        progress(1f)
        val finished = checkNotNull(model)
        return CleanModelResult(
            glb,
            finished,
            CleanModelStats(
                rawTriangles = rawTriangles,
                cleanInputTriangles = reached.cleanInputTriangles,
                geometry = geometry,
                simplifyTarget = if (texture != null) simplifyTarget else targetTriangles,
                simplifiedTriangles = simplified.triangleCount,
                finalTriangles = finished.triangleCount,
                finalVertices = finished.vertexCount,
                texture = texture?.stats,
                droppedTextureShare = textures?.share?.takeIf { texture == null },
                atlasWidth = finished.texture?.width ?: 0,
                atlasHeight = finished.texture?.height ?: 0,
                glbBytes = glb.size,
                glbLimitBytes = MAX_GLB_BYTES,
                atlasHalvings = halvings,
                glbGuarded = guarded,
                outOfMemoryIn = null,
                prepareMs = reached.ms(Stage.Prepare),
                cleanMs = reached.ms(Stage.Clean),
                simplifyMs = reached.ms(Stage.Simplify),
                textureMs = reached.ms(Stage.Texture),
                writeMs = reached.ms(Stage.Write),
            ),
        )
    }

    /** [raw] prepared for cleaning, the stage's figures noted: the frame that took it is gone by the cleaning. */
    private fun prepared(raw: RawSurface, consume: Boolean, reached: Reached, progress: (Float) -> Unit): WorkMesh {
        val input = prepare(raw, consume) { progress(it * PREPARE_SHARE) }
        reached.cleanInputTriangles = input.triangleCount
        reached.lap(Stage.Clean)
        return input
    }

    /**
     * The photos' texture of [shape] when it covers [MIN_TEXTURED_SHARE] of it, the rest left to
     * its vertex colours, and the share it covered: one that covers less goes with this frame,
     * before the colours are simplified again.
     */
    private class Textures(val covering: PhotoTexture?, val share: Float?)

    private fun textures(shape: WorkMesh, photos: ModelPhotos, progress: (Float) -> Unit): Textures {
        val tried = PhotoTexturing.texture(shape, photos, progress)
        val share = tried?.stats?.texturedShare
        return Textures(tried.takeIf { share != null && share >= MIN_TEXTURED_SHARE }, share)
    }

    /** The raw surface as the result, its [glb] written before the finish ran out of memory. */
    private fun rawSurface(glb: ByteArray, triangles: Int, vertices: Int, reached: Reached): CleanModelResult {
        val stage = reached.stage
        reached.lap(stage)
        return CleanModelResult(
            glb,
            null,
            CleanModelStats(
                rawTriangles = triangles,
                cleanInputTriangles = reached.cleanInputTriangles,
                geometry = reached.geometry ?: CleanGeometryStats(0, 0, 0, 0, 0, 0, 0, 0f, 0),
                simplifyTarget = 0,
                simplifiedTriangles = 0,
                finalTriangles = triangles,
                finalVertices = vertices,
                texture = null,
                droppedTextureShare = null,
                atlasWidth = 0,
                atlasHeight = 0,
                glbBytes = glb.size,
                glbLimitBytes = MAX_GLB_BYTES,
                atlasHalvings = 0,
                glbGuarded = false,
                outOfMemoryIn = stage,
                prepareMs = reached.ms(Stage.Prepare),
                cleanMs = reached.ms(Stage.Clean),
                simplifyMs = reached.ms(Stage.Simplify),
                textureMs = reached.ms(Stage.Texture),
                writeMs = reached.ms(Stage.Write),
            ),
        )
    }

    /** How far a finish got — its stage, what it measured so far, each stage's time — for when it runs out of memory. */
    private class Reached {
        var stage = Stage.Prepare
            private set
        var cleanInputTriangles = 0
        var geometry: CleanGeometryStats? = null
        private val times = LongArray(Stage.entries.size)
        private var mark = System.nanoTime()

        /** Counts the time since the last lap to the current stage's, then moves on to [next]. */
        fun lap(next: Stage) {
            val now = System.nanoTime()
            times[stage.ordinal] += (now - mark) / NANOS_PER_MS
            mark = now
            stage = next
        }

        fun ms(stage: Stage): Long = times[stage.ordinal]
    }

    /**
     * How far the textured simplification may go past [MAX_TEXTURED_ERROR_M] when that cap holds
     * it over budget: twice the depth noise the flat faces measured ([CleanGeometryStats.noiseM]),
     * within [MAX_TEXTURED_ERROR_CEILING_M]. A scan whose walls wander by a centimetre loses
     * nothing real to a collapse of two; a clean synthetic one stays at the cap.
     */
    private fun errorCeiling(geometry: CleanGeometryStats): Float =
        if (geometry.planes == 0) {
            MAX_TEXTURED_ERROR_CEILING_M
        } else {
            (NOISE_CEILING_FACTOR * geometry.noiseM).coerceIn(MAX_TEXTURED_ERROR_M, MAX_TEXTURED_ERROR_CEILING_M)
        }

    /**
     * [raw] as cleaning takes it, taken from its holder. A surface over [CLEAN_INPUT_TRIANGLES] —
     * a long scan of a big room, up to the cap the heap allows ([maxRawTriangles]) — is first
     * simplified on its shape alone down to it (the flat runs go first; no colour edge holds a
     * collapse back, so the budget is reached), its colours then averaged back from the raw
     * vertices around each one. Cleaning a million triangles would take a phone's whole heap; the
     * simplifier's workspace ([RerunMeshSimplifier.workspaceBytes]) is the smaller peak, and the
     * raw normals, which it does not read, are let go before it.
     */
    private fun prepare(raw: RawSurface, consume: Boolean, progress: (Float) -> Unit): WorkMesh {
        if (raw.triangleCount <= CLEAN_INPUT_TRIANGLES) return if (consume) CleanGeometry.consuming(raw.take()) else CleanGeometry.of(raw.take())
        val source = arraysOf(raw)
        val grey = IntArray(source.colors.size) { FLAT_COLOR }
        // The simplifier reads no normals: the positions stand in for them.
        val out = RerunMeshSimplifier.simplify(
            RerunMesh(source.positions, source.positions, grey, source.indices),
            CLEAN_INPUT_TRIANGLES,
            consume = consume,
            progress = progress,
        )
        val colors = averageColors(source.positions, source.colors, out.positions, PREPARE_RADIUS_M)
        return WorkMesh(out.positions, colors, out.indices)
    }

    /** [raw]'s positions, colours and indices, taken in a frame of their own: its normals go with it. */
    private fun arraysOf(raw: RawSurface): WorkMesh = raw.take().let { WorkMesh(it.positions, it.colors, it.indices) }

    /**
     * [clean] within [target] triangles. A model the photos will texture is simplified on its shape
     * alone — the texture carries the colour, so a plain wall comes down to a few large faces —
     * every collapse within [MAX_TEXTURED_ERROR_M] — past the budget too when it starts under
     * it, so a small scan still sheds its flat runs — then on to the budget
     * within [ceiling] when the noise held it back, and no edge longer than
     * [MAX_TEXTURED_EDGE_M], so each face still fits in one photo; its vertices then take the
     * mean colour of the cleaned vertices around them, for the faces no photo covers.
     * Without photos, colour edges are kept, as the vertex colours are all there is.
     */
    private fun simplify(clean: WorkMesh, textured: Boolean, target: Int, ceiling: Float, progress: (Float) -> Unit): WorkMesh {
        val normals = vertexNormals(clean.positions, clean.indices)
        if (!textured) {
            val out = RerunMeshSimplifier.simplify(
                RerunMesh(clean.positions, normals, clean.colors, clean.indices),
                target,
                collapseBorders = true,
                progress = progress,
            )
            return WorkMesh(out.positions, out.colors, out.indices)
        }
        val grey = IntArray(clean.vertexCount) { FLAT_COLOR }
        val out = RerunMeshSimplifier.simplify(
            RerunMesh(clean.positions, normals, grey, clean.indices),
            target,
            maxEdgeM = MAX_TEXTURED_EDGE_M,
            maxErrorM = MAX_TEXTURED_ERROR_M,
            maxErrorCeilingM = ceiling,
            belowTarget = true,
            collapseBorders = true,
            progress = progress,
        )
        return WorkMesh(out.positions, averageColors(clean.positions, clean.colors, out.positions, CELL_M), out.indices)
    }

    /**
     * For each of [positions], the mean colour of the [sourcePositions] within [radius] of it —
     * the nearest one's where none is that close, within a cell or two — from a hashed grid of
     * [CELL_M] cells. [radius] is at most [CELL_M].
     */
    private fun averageColors(sourcePositions: FloatArray, sourceColors: IntArray, positions: FloatArray, radius: Float): IntArray {
        val cells = HashMap<Long, IntList>()
        val p = sourcePositions
        for (v in 0 until sourceColors.size) {
            cells.getOrPut(cell(p[v * 3], p[v * 3 + 1], p[v * 3 + 2])) { IntList(4) }.add(v)
        }
        val radiusSq = radius * radius
        return IntArray(positions.size / 3) { i ->
            val x = positions[i * 3]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            val cx = floor(x / CELL_M).toLong()
            val cy = floor(y / CELL_M).toLong()
            val cz = floor(z / CELL_M).toLong()
            var nearest = -1
            var nearestD = Float.MAX_VALUE
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (dz in -1..1) for (dy in -1..1) for (dx in -1..1) {
                val list = cells[key(cx + dx, cy + dy, cz + dz)] ?: continue
                for (j in 0 until list.size) {
                    val v = list[j]
                    val ex = p[v * 3] - x
                    val ey = p[v * 3 + 1] - y
                    val ez = p[v * 3 + 2] - z
                    val d = ex * ex + ey * ey + ez * ez
                    if (d < nearestD) {
                        nearestD = d
                        nearest = v
                    }
                    if (d <= radiusSq) {
                        val c = sourceColors[v]
                        r += c shr 16 and 0xFF
                        g += c shr 8 and 0xFF
                        b += c and 0xFF
                        count++
                    }
                }
            }
            when {
                count > 0 -> PhotoTexturing.rgb(r / count, g / count, b / count)
                nearest >= 0 -> sourceColors[nearest]
                else -> FLAT_COLOR
            }
        }
    }

    private fun cell(x: Float, y: Float, z: Float): Long =
        key(floor(x / CELL_M).toLong(), floor(y / CELL_M).toLong(), floor(z / CELL_M).toLong())

    private fun key(x: Long, y: Long, z: Long): Long =
        ((x and KEY_MASK) shl (2 * KEY_BITS)) or ((y and KEY_MASK) shl KEY_BITS) or (z and KEY_MASK)

    /** A face this long still fits in a photo taken a metre and a half away. */
    const val MAX_TEXTURED_EDGE_M = 0.45f

    /** A few millimetres: under the depth's own noise, so no shape a scan really saw is lost. */
    const val MAX_TEXTURED_ERROR_M = 0.004f

    /** The furthest a scan's noise lets [MAX_TEXTURED_ERROR_M] grow: still under a skirting board. */
    const val MAX_TEXTURED_ERROR_CEILING_M = 0.02f
    private const val NOISE_CEILING_FACTOR = 2f

    /** An atlas halved twice is a quarter as fine: past that, the vertex colours are the lighter model. */
    private const val MAX_ATLAS_HALVINGS = 2

    /** The fewest triangles the size guard brings a model down to: well under [MAX_GLB_BYTES] untextured. */
    const val MIN_GUARD_TRIANGLES = 2_000

    /**
     * The fewest faces the size guard cuts a textured model to before it halves the atlas: a
     * little over [TEXTURED_TARGET_TRIANGLES], so a room keeps the shape it was simplified to
     * and a big scan held over its budget by its noise sheds that first.
     */
    const val MIN_GUARD_TEXTURED_TRIANGLES = 60_000

    /** The share of the cap a textured model's faces are cut to fill, its size taken as theirs. */
    private const val GUARD_FILL = 0.85
    private val NO_BYTES = ByteArray(0)

    /**
     * The most a finished `.glb` weighs: 8 MB. A room textured as the finish makes it — a
     * 2048-texel atlas, [TEXTURED_TARGET_TRIANGLES] faces — weighs about 2.5 MB, so the cap leaves
     * an atlas that compresses badly three times that room before it is halved. It bounds what a
     * share sends and a kept scan stores, and the renderer's copy stays small next to a phone's
     * heap. Not the raw surface's own weight: a small room's raw surface weighs less than its
     * atlas alone, and the texture is what the finish is for.
     */
    const val MAX_GLB_BYTES = 8 * 1024 * 1024

    /**
     * A textured room needs fewer faces than a vertex-coloured one: the photos carry the detail,
     * and [MAX_TEXTURED_ERROR_M] stops the simplification before a curve flattens.
     */
    const val TEXTURED_TARGET_TRIANGLES = 50_000

    /**
     * The most triangles cleaning is given, under [CleanGeometry.MAX_TRIANGLES]: a synthetic
     * room's 394k and the recorded corpus's scans pass as they are; a fifteen-minute scan near the
     * extraction's million is simplified down to it first.
     */
    const val CLEAN_INPUT_TRIANGLES = 400_000

    /**
     * [maxRawTriangles]' line, fitted to the smallest heaps a JVM finished 750k, 800k, 900k and a
     * million raw triangles in — 192, 208, 224 and 240 MB, the live peak after full collections
     * plus the raw `.glb` shown meanwhile, with the app's 32 MB and the recording's dense cloud
     * held: about 48 MB plus 201 bytes a triangle. This line asks 15 to 22 MB more of each.
     */
    private const val HELD_BYTES = 64L * 1024 * 1024

    /** Per raw triangle: the simplification to [CLEAN_INPUT_TRIANGLES], the surface, its `.glb` kept and shown. */
    private const val BYTES_PER_RAW_TRIANGLE = 208L

    /** A heap smaller than any phone's still meshes a room: [RerunMarchingCubes.extractWhole]'s coarser cells take it. */
    private const val MIN_RAW_TRIANGLES = 100_000

    /**
     * Under this share textured, a texture is dropped for the vertex-coloured model. Above it, the
     * photos texture what they saw and the vertex colours the rest: a room photographed along two
     * of its walls is a third textured, a big room walked once under half — both better for the
     * photos where they reach. Under a fifth, they saw a corner, or their poses are off: the
     * edges an untextured simplification keeps in its colours are worth more.
     */
    const val MIN_TEXTURED_SHARE = 0.2f
    private const val FLAT_COLOR = 0xFFB4AFA5.toInt()
    private const val CELL_M = 0.04f
    private const val KEY_BITS = 21
    private const val KEY_MASK = (1L shl KEY_BITS) - 1
    private const val PREPARE_RADIUS_M = 0.02f
    private const val PREPARE_SHARE = 0.1f
    private const val CLEAN_SHARE = 0.3f
    private const val SIMPLIFY_SHARE = 0.15f
    private const val TEXTURE_SHARE = 0.4f
    private const val BEFORE_SIMPLIFY = PREPARE_SHARE + CLEAN_SHARE
    private const val BEFORE_TEXTURE = BEFORE_SIMPLIFY + SIMPLIFY_SHARE
    private const val BEFORE_WRITE = BEFORE_TEXTURE + TEXTURE_SHARE
    private const val NANOS_PER_MS = 1_000_000L
}
