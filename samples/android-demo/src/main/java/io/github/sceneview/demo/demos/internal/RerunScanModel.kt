package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A scan's result: the 3D model the result screen opens on and the export sheet writes first.
 * Kept next to the scan (`model.glb`, then `model.json`, [RerunSessionStore.saveModel]) so the
 * scan reopens on the same model without building it again.
 */
class RerunScanModel(
    val kind: Kind,
    /** The model as one `.glb` ([RerunMeshGlb]), metres, Y up, the scan's world space. */
    val glb: ByteArray,
    /**
     * `[minX, minY, minZ, maxX, maxY, maxZ]` of the geometry, the few vertices far off left out
     * ([RerunResultView.framingBounds]): what the result frames.
     */
    val bounds: FloatArray,
    val triangles: Int,
    val vertices: Int,
    /** The TSDF ran out of memory: part of the space is left out. */
    val budgetReached: Boolean = false,
    /**
     * Built in full. `false` for a fallback shown while the finish failed — the raw surface, or
     * the points when meshing did — which is not kept with its scan ([keptWithScan]): the next
     * open builds the model again. Not written to `model.json`: a model read back was finished,
     * unless it is the raw surface a finish ran out of memory on.
     */
    val finished: Boolean = true,
    /**
     * The heap (`Runtime.maxMemory()`, bytes) the finish ran out of, the raw surface shown in its
     * place: kept with its scan anyway, so the next open in a heap that size shows it at once,
     * and finishes it again behind it only while attempts are left ([retriesFinish]). A bigger
     * heap finishes it again from the start ([parse]).
     */
    val outOfMemoryInHeap: Long? = null,
    /**
     * The finishes that ran out of memory on this scan, this one included: until there are
     * [OUT_OF_MEMORY_ATTEMPTS], the scan reopens on the raw surface and finishes it again
     * ([retriesFinish]).
     */
    val outOfMemoryAttempts: Int = 1,
    /**
     * Read from an earlier app's `model.json` ([parse]): the raw surface it kept as the model.
     * Shown at once as it is, recording readable or not, and finished again from the recording
     * when it reads ([retriesFinish]); not saved over as this app's ([keptWithScan]).
     */
    val earlierFormat: Boolean = false,
) {
    init {
        require(bounds.size == BOUNDS) { "six bounds" }
        require(outOfMemoryInHeap == null || !finished) { "a finished model ran out of memory" }
        require(outOfMemoryAttempts >= 1) { "at least one attempt" }
        require(!earlierFormat || !finished) { "an earlier app's model is its raw surface" }
    }

    /** Kept with its scan ([RerunSessionStore.saveModel]): the finished model, or the raw surface left by [outOfMemoryInHeap]. */
    val keptWithScan: Boolean get() = finished || outOfMemoryInHeap != null

    /**
     * The raw surface a finish ran out of memory on, with attempts left: shown at once when its
     * scan reopens, and finished again behind it ([finishedAgain]). A heap that ran out once can
     * fit the finish the next time — other apps let go of theirs, a recording reads its surfels
     * instead of holding its TSDF — but not every time: past [OUT_OF_MEMORY_ATTEMPTS] the raw
     * surface is the scan's model for good.
     */
    val retriesFinish: Boolean
        get() = earlierFormat || (outOfMemoryInHeap != null && outOfMemoryAttempts < OUT_OF_MEMORY_ATTEMPTS)

    /**
     * What a finish run again over this raw surface ([retriesFinish]) leaves: [built] when it
     * finished; else this surface, one attempt more — with the heap [built] ran out of, when it
     * did. The surface stays the one on screen: the finish was handed its `.glb`, and a surface
     * meshed again need not hold the same triangles.
     */
    fun finishedAgain(built: RerunScanModel?): RerunScanModel {
        if (built != null && built.finished) return built
        val heap = built?.outOfMemoryInHeap
        return RerunScanModel(
            kind = kind,
            glb = glb,
            bounds = bounds,
            triangles = triangles,
            vertices = vertices,
            budgetReached = budgetReached,
            finished = false,
            outOfMemoryInHeap = heap ?: outOfMemoryInHeap,
            outOfMemoryAttempts = outOfMemoryAttempts + 1,
            // Out of memory, it is this app's raw surface kept for it.
            earlierFormat = earlierFormat && heap == null,
        )
    }

    /**
     * [glb] in a native buffer, for the renderer to read; `null` when the heap has no room left
     * for the copy — a big scan's 12 MB, beside its finish — so the model is left unshown instead
     * of taking the app down. [allocate] is the allocation, for a test.
     */
    fun glbBuffer(allocate: (Int) -> ByteBuffer = ByteBuffer::allocateDirect): ByteBuffer? = try {
        allocate(glb.size).order(ByteOrder.nativeOrder()).put(glb).also { it.rewind() }
    } catch (_: OutOfMemoryError) {
        null
    }

    /** What the model is made of. */
    enum class Kind(val wireName: String) {
        /** A solid surface meshed from the fused depth. */
        Mesh("mesh"),

        /** Meshing failed: the scan's coloured points, each a small square. */
        Points("points"),
        ;

        companion object {
            fun of(wireName: String?): Kind? = entries.firstOrNull { it.wireName == wireName }
        }
    }

    /** `model.json`: everything but the `.glb`, pretty-printed. */
    fun infoJson(): String = pretty.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("version", VERSION)
            put("kind", kind.wireName)
            putJsonArray("bounds") { bounds.forEach { add(JsonPrimitive(it)) } }
            put("triangles", triangles)
            put("vertices", vertices)
            put("budgetReached", budgetReached)
            outOfMemoryInHeap?.let {
                put("outOfMemoryInHeap", it)
                put("outOfMemoryAttempts", outOfMemoryAttempts)
            }
        },
    )

    companion object {
        /**
         * `model.json`'s format. 2: the finished model ([io.github.sceneview.demo.demos.internal.model.CleanModel]);
         * a 1 is the raw surface an earlier app kept, read as it is ([earlierFormat]) so the scan
         * opens on it and finishes it again.
         */
        const val VERSION = 2

        /**
         * The finishes a scan runs before running out of memory is final: the first, and two
         * more over the raw surface shown. One more is the cheap test of a heap that was only
         * crowded; a third that still fails says the scan is too big for this phone, and a
         * fourth would only cost the user another wait for the same raw surface.
         */
        const val OUT_OF_MEMORY_ATTEMPTS = 3

        /** An earlier app's `model.json`: its raw surface, kept as the model ([earlierFormat]). */
        private const val EARLIER_VERSION = 1
        private const val BOUNDS = 6
        private val pretty = Json { prettyPrint = true }
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * `null` when [info] is not a whole `model.json`, was written by a newer app than this
         * one ([VERSION]), or [glb] is not a `.glb`, or is a raw surface kept for running out of a
         * smaller [heap] than this one: the scan then builds its model again. An earlier app's
         * raw surface (format 1) reads as it is, to be finished again ([earlierFormat]).
         */
        @Suppress("ReturnCount") // one early return per required key
        fun parse(info: String, glb: ByteArray, heap: Long = Runtime.getRuntime().maxMemory()): RerunScanModel? {
            if (glb.size < GLB_HEADER || !glb.startsWithGlbMagic()) return null
            val root = runCatching { lenient.parseToJsonElement(info) as? JsonObject }.getOrNull() ?: return null
            fun number(key: String) = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }
            val version = number("version")?.intOrNull ?: return null
            val earlier = version == EARLIER_VERSION
            if (version != VERSION && !earlier) return null
            val kind = Kind.of((root["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content) ?: return null
            val bounds = (root["bounds"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
                ?.takeIf { b -> b.size == BOUNDS && b.all { it.isFinite() } }
                ?.toFloatArray() ?: return null
            val outOfMemoryInHeap = root["outOfMemoryInHeap"]?.let { number("outOfMemoryInHeap")?.longOrNull ?: return null }
            if (outOfMemoryInHeap != null && heap > outOfMemoryInHeap) return null
            if (earlier && outOfMemoryInHeap != null) return null
            return RerunScanModel(
                kind = kind,
                glb = glb,
                bounds = bounds,
                triangles = number("triangles")?.intOrNull?.takeIf { it > 0 } ?: return null,
                vertices = number("vertices")?.intOrNull ?: return null,
                budgetReached = number("budgetReached")?.booleanOrNull ?: false,
                finished = outOfMemoryInHeap == null && !earlier,
                outOfMemoryInHeap = outOfMemoryInHeap,
                // Before attempts were counted, a model.json held one finish.
                outOfMemoryAttempts = number("outOfMemoryAttempts")?.intOrNull?.takeIf { it >= 1 } ?: 1,
                earlierFormat = earlier,
            )
        }

        private fun ByteArray.startsWithGlbMagic() =
            this[0] == 'g'.code.toByte() && this[1] == 'l'.code.toByte() &&
                this[2] == 'T'.code.toByte() && this[3] == 'F'.code.toByte()

        private const val GLB_HEADER = 12
    }
}

/**
 * The model the result screen reads its ground off: [model] once [settled], else none yet. A
 * big scan's raw surface is shown while it finishes; decoding its ground then would hold a
 * second copy of its vertices beside the finish, in the heap the finish needs.
 */
internal fun groundSource(model: RerunScanModel?, settled: Boolean): RerunScanModel? = model?.takeIf { settled }

/**
 * The fallback model when meshing fails: the scan's coloured points, each one a small square
 * lying in its surface (the replay's surfels, [DenseSurfels]), in a mesh [RerunMeshGlb] writes.
 */
object RerunPointsMesh {
    /** Points kept at most: 4 vertices each, ~8 MB of `.glb` at the cap. */
    const val MAX_POINTS = 60_000

    /** Grey for a point the camera never coloured. */
    const val UNCOLOURED = 0xFFB4B4B4.toInt()

    /**
     * Two triangles a point, every `count / maxPoints`-th point when [cloud] holds more than
     * [maxPoints]; a kept square grows so the points left out leave no holes.
     */
    @Suppress("LongMethod")
    fun of(cloud: DenseCloud, voxelM: Float = DenseFusion.VOXEL_M, maxPoints: Int = MAX_POINTS): RerunMesh {
        val stride = maxOf(1, (cloud.count + maxPoints - 1) / maxPoints)
        val n = (cloud.count + stride - 1) / stride
        val half = voxelM * DenseSurfels.HALF_SIDE_VOXELS * sqrt(stride.toFloat())
        val positions = FloatArray(n * QUAD * 3)
        val normals = FloatArray(n * QUAD * 3)
        val colors = IntArray(n * QUAD)
        val indices = IntArray(n * QUAD_INDICES)
        val source = cloud.normals
        for (k in 0 until n) {
            val i = k * stride
            val x = cloud.positions[i * 3]
            val y = cloud.positions[i * 3 + 1]
            val z = cloud.positions[i * 3 + 2]
            val nx = source?.get(i * 3) ?: 0f
            val ny = source?.get(i * 3 + 1) ?: 1f
            val nz = source?.get(i * 3 + 2) ?: 0f
            // Two tangents: any vector not parallel to the normal, crossed twice.
            val ax = if (abs(ny) < PARALLEL) 0f else 1f
            val ay = if (abs(ny) < PARALLEL) 1f else 0f
            var tx = ay * nz
            var ty = -ax * nz
            var tz = ax * ny - ay * nx
            val tl = sqrt(tx * tx + ty * ty + tz * tz).takeIf { it > EPSILON } ?: 1f
            tx = tx / tl * half
            ty = ty / tl * half
            tz = tz / tl * half
            val bx = ny * tz - nz * ty
            val by = nz * tx - nx * tz
            val bz = nx * ty - ny * tx
            // `0` is "no colour" (DenseCloud); a coloured point is opaque, black included.
            val color = cloud.colors[i].takeIf { it ushr ALPHA_SHIFT != 0 } ?: UNCOLOURED
            val v = k * QUAD
            corner(positions, v, x - tx - bx, y - ty - by, z - tz - bz)
            corner(positions, v + 1, x + tx - bx, y + ty - by, z + tz - bz)
            corner(positions, v + 2, x + tx + bx, y + ty + by, z + tz + bz)
            corner(positions, v + 3, x - tx + bx, y - ty + by, z - tz + bz)
            for (c in 0 until QUAD) {
                corner(normals, v + c, nx, ny, nz)
                colors[v + c] = color
            }
            val t = k * QUAD_INDICES
            indices[t] = v
            indices[t + 1] = v + 1
            indices[t + 2] = v + 2
            indices[t + 3] = v
            indices[t + 4] = v + 2
            indices[t + 5] = v + 3
        }
        return RerunMesh(positions, normals, colors, indices)
    }

    private fun corner(into: FloatArray, vertex: Int, x: Float, y: Float, z: Float) {
        into[vertex * 3] = x
        into[vertex * 3 + 1] = y
        into[vertex * 3 + 2] = z
    }

    private const val QUAD = 4
    private const val QUAD_INDICES = 6
    private const val PARALLEL = 0.9f
    private const val EPSILON = 1e-6f
    private const val ALPHA_SHIFT = 24
    private const val LOW = 0.01f
    private const val HIGH = 0.99f

    /**
     * The box the result frames a points model by: per axis, from the 1st to the 99th percentile
     * of its vertices. Points are noisy — a few strays far off, which a min/max box would fit
     * whole, leave the room itself small in the middle of the screen. All zero for no vertex.
     */
    fun framingBounds(mesh: RerunMesh, low: Float = LOW, high: Float = HIGH): FloatArray {
        val n = mesh.vertexCount
        val out = FloatArray(BOUNDS)
        if (n == 0) return out
        val axis = FloatArray(n)
        for (a in 0 until 3) {
            for (v in 0 until n) axis[v] = mesh.positions[v * 3 + a]
            axis.sort()
            out[a] = axis[((n - 1) * low).toInt()]
            out[a + 3] = axis[((n - 1) * high + 0.5f).toInt().coerceAtMost(n - 1)]
        }
        return out
    }

    private const val BOUNDS = 6
}

/**
 * The result screen's view of a model: a fixed three-quarter angle, a little higher than the
 * replay's so the eye looks into the space over its near walls, the geometry's box
 * ([framingBounds]) fitted whole inside the area the chrome leaves free. The export thumbnail is
 * drawn from the same view.
 */
object RerunResultView {
    const val AZIMUTH = ArDebugFraming.HOME_AZIMUTH
    const val ELEVATION = 40f

    /** The pose that opens [bounds] on a view of [aspect] whose chrome covers [insets]: in its [area]. */
    fun home(bounds: FloatArray, verticalFovDegrees: Double, aspect: Float, insets: FramingInsets): OrbitPose =
        ArDebugFraming.fit(
            bounds = bounds,
            azimuthDegrees = AZIMUTH,
            elevationDegrees = ELEVATION,
            verticalFovDegrees = verticalFovDegrees,
            aspect = aspect,
            insets = area(aspect, insets),
        )

    /**
     * The box the result frames [mesh] by: per axis, from its vertices' [FRAMING_TAIL] quantile to
     * their 1 - [FRAMING_TAIL]. A raw surface still has the pieces depth left beyond the walls —
     * through a window, in a mirror — that the finish removes: fitted whole, they leave the room
     * itself small in the middle of the screen. The finished model is framed the same way, so
     * shown in the raw surface's framing, which it replaces in place, it fills it as much.
     */
    fun framingBounds(mesh: RerunMesh): FloatArray =
        RerunPointsMesh.framingBounds(mesh, FRAMING_TAIL, 1f - FRAMING_TAIL)

    /**
     * Where a result opens on a view of [aspect] (width / height) whose chrome covers [insets]:
     * [NARROW_FILL] of the view's narrower side, and as much of its longer side as [EDGE] leaves,
     * both centred on what the chrome leaves free.
     *
     * Fitted between the chrome alone, a room opened at half the width of a phone held upright and
     * a small scan at a sixth of it: the band between the bar and the sheet is short, and a box
     * seen from above at an angle is about as tall as it is wide. Here the narrower side sets the
     * size, and the longer side only stops a model too tall for it at the view's edge; one that
     * fits between the chrome at that size stays there, centred.
     */
    fun area(aspect: Float, insets: FramingInsets): FramingInsets {
        val free = insets.bounded()
        val upright = aspect <= 1f
        val (narrowLow, narrowHigh) = if (upright) free.left to free.right else free.top to free.bottom
        val (longLow, longHigh) = if (upright) free.top to free.bottom else free.left to free.right
        val narrowCentre = ((narrowLow + 1f - narrowHigh) / 2f).coerceIn(NARROW_FILL / 2f, 1f - NARROW_FILL / 2f)
        val narrow = narrowCentre - NARROW_FILL / 2f to 1f - narrowCentre - NARROW_FILL / 2f
        val longCentre = (longLow + 1f - longHigh) / 2f
        val longHalf = maxOf(minOf(longCentre - EDGE, 1f - EDGE - longCentre), (1f - longLow - longHigh) / 2f)
        val long = longCentre - longHalf to 1f - longCentre - longHalf
        return if (upright) {
            FramingInsets(left = narrow.first, top = long.first, right = narrow.second, bottom = long.second)
        } else {
            FramingInsets(left = long.first, top = narrow.first, right = long.second, bottom = narrow.second)
        }
    }

    /** [model] drawn square, [size] pixels a side, from the screen's view; `null` for an unreadable `.glb`. */
    fun thumbnail(model: RerunScanModel, size: Int, verticalFovDegrees: Double): IntArray? {
        val mesh = RerunGlbMesh.read(model.glb) ?: return null
        val margin = FramingInsets(THUMBNAIL_MARGIN, THUMBNAIL_MARGIN, THUMBNAIL_MARGIN, THUMBNAIL_MARGIN)
        val pose = ArDebugFraming.fit(
            bounds = model.bounds,
            azimuthDegrees = AZIMUTH,
            elevationDegrees = ELEVATION,
            verticalFovDegrees = verticalFovDegrees,
            aspect = 1f,
            insets = margin,
        )
        return RerunModelThumbnail.render(mesh, pose, verticalFovDegrees, size, size)
    }

    /** The share of the view's narrower side a result opens across ([area]). */
    const val NARROW_FILL = 0.8f

    /** The share of a mesh's vertices [framingBounds] leaves out past each end of each axis. */
    const val FRAMING_TAIL = 0.02f

    /** The least room [area] leaves at the ends of the view's longer side, a share of it. */
    const val EDGE = 0.04f

    /** The thumbnail's breathing room on each side, a share of its width. */
    const val THUMBNAIL_MARGIN = 0.06f
}
