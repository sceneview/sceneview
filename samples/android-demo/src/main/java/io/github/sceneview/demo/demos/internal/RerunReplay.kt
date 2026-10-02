package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs

/*
 * The Rerun demo's bundled replay: one real session — a phone walking a room — shipped with the
 * app so the demo opens on something worth seeing on every device, the emulator included (#2754).
 *
 * The session is a log in the bridge's own wire format (see [parseArDebugLog]) plus what a log
 * cannot carry inline: the camera frames, the planes' photo textures and the lens, described by
 * the manifest. Everything here is pure Kotlin, tested on the JVM; the view turns it into
 * textures and triangles.
 */

/**
 * Where the bundled replay lives in the app's assets: three files. The photos travel in one
 * archive ([MEDIA]) that the manifest indexes — one asset to open instead of 187, and one entry
 * in the APK's CREDITS.md instead of 187.
 */
object RerunReplayAssets {
    const val DIR = "rerun/showcase"
    const val LOG = "$DIR/showcase-session.jsonl"
    const val MANIFEST = "$DIR/showcase-manifest.json"
    const val MEDIA = "$DIR/showcase-media.bin"
}

/** Where one photo sits in the media archive: [length] bytes from [offset]. */
data class MediaSpan(val offset: Int, val length: Int)

/**
 * The camera's lens as frustum proportions: the half-extents of the image plane at one metre.
 * A frustum drawn with the real lens puts its photo exactly over what the camera saw.
 */
data class ReplayLens(val halfWidthPerDepth: Float, val halfHeightPerDepth: Float) {
    companion object {
        /** The generic portrait phone the live view draws, with no intrinsics to hand. */
        val Default = ReplayLens(DebugFrustum.HALF_WIDTH_PER_DEPTH, DebugFrustum.HALF_HEIGHT_PER_DEPTH)

        /** From pinhole intrinsics: image [width] × [height] pixels, focal lengths [fx], [fy]. */
        fun of(width: Float, height: Float, fx: Float, fy: Float): ReplayLens? {
            if (minOf(width, height, fx, fy) <= 0f) return null
            return ReplayLens(width / 2f / fx, height / 2f / fy)
        }
    }
}

/**
 * A plane's photo texture, laid on the plane: [origin] is the world position of the texture's
 * first texel corner (u = 0, v = 0), [u] and [v] its two full edges in world space.
 */
class ReplayPlaneTexture(
    val planeId: Int,
    val path: String,
    val origin: Vec3,
    val u: Vec3,
    val v: Vec3,
) {
    private val uu = u.dot(u)
    private val vv = v.dot(v)

    /** Texture coordinates of the world point ([x], [y], [z]), unclamped. */
    fun uvOf(x: Float, y: Float, z: Float): Pair<Float, Float> {
        val d = Vec3(x - origin.x, y - origin.y, z - origin.z)
        val s = if (uu > 1e-9f) d.dot(u) / uu else 0f
        val t = if (vv > 1e-9f) d.dot(v) / vv else 0f
        return s to t
    }
}

/**
 * Who recorded a `.svscan` v2 and with what: [tier] `lidar`, `depth`, `mono` or `sparse`,
 * [depthSource] `arkit_lidar`, `arcore_raw_depth`, `ai_mono` or `feature_points`.
 */
data class ScanDevice(val platform: String, val model: String, val tier: String, val depthSource: String) {
    companion object {
        const val TIER_DEPTH = "depth"
        const val TIER_SPARSE = "sparse"
        const val SOURCE_RAW_DEPTH = "arcore_raw_depth"
        const val SOURCE_FEATURE_POINTS = "feature_points"
    }
}

/**
 * The dense cloud of a `.svscan` v2: the SVPC blob at [path] in the media archive ([SvpcCodec]),
 * [count] surfels of [voxelM], with normals when [normals], inside [bounds]
 * (`[minX, minY, minZ, maxX, maxY, maxZ]`).
 */
data class ReplayDense(
    val path: String,
    val count: Int,
    val voxelM: Float,
    val normals: Boolean,
    val bounds: FloatArray,
) {
    override fun equals(other: Any?) = other is ReplayDense && path == other.path && count == other.count &&
        voxelM == other.voxelM && normals == other.normals && bounds.contentEquals(other.bounds)

    override fun hashCode() = path.hashCode() * 31 + count

    companion object {
        const val PATH = "dense/points.bin"
    }
}

/** What the manifest says about the replay. */
class ReplayManifest(
    val lens: ReplayLens,
    val frameRate: Float,
    val frameCount: Int,
    val floorY: Float?,
    val textures: List<ReplayPlaneTexture>,
    /** Each photo's place in the media archive, keyed by the path the log and manifest use. */
    val media: Map<String, MediaSpan> = emptyMap(),
    /** `1` for every scan before Rerun v2 (no `version` key), `2` from it on. */
    val version: Int = 1,
    /** Who recorded it (v2); `null` for a v1 scan. */
    val device: ScanDevice? = null,
    /** The dense cloud (v2 tiers `lidar`, `depth`, `mono`); `null` when there is none. */
    val dense: ReplayDense? = null,
    /** Milliseconds spent building the dense cloud after Stop (`built.denseMs`), `0` when live. */
    val denseMs: Long = 0L,
) {
    fun textureFor(planeId: Int): ReplayPlaneTexture? = textures.firstOrNull { it.planeId == planeId }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parses a manifest; `null` when it is not one. Unknown keys are ignored. */
        @Suppress("ReturnCount") // one early return per missing section
        fun parse(text: String): ReplayManifest? {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val intrinsics = root["intrinsics"] as? JsonObject
            val lens = intrinsics?.let {
                ReplayLens.of(it.float("width"), it.float("height"), it.float("fx"), it.float("fy"))
            } ?: ReplayLens.Default
            val textures = (root["textures"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val id = (obj["plane"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val path = (obj["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                ReplayPlaneTexture(
                    planeId = id,
                    path = path,
                    origin = obj.vec("origin") ?: return@mapNotNull null,
                    u = obj.vec("u") ?: return@mapNotNull null,
                    v = obj.vec("v") ?: return@mapNotNull null,
                )
            }
            val media = (root["media"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val path = (obj["path"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val offset = (obj["offset"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val length = (obj["length"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                if (offset < 0 || length <= 0) null else path to MediaSpan(offset, length)
            }.toMap()
            val built = root["built"] as? JsonObject
            return ReplayManifest(
                version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 1,
                device = (root["device"] as? JsonObject)?.let(::parseDevice),
                dense = (root["dense"] as? JsonObject)?.let(::parseDense),
                denseMs = (built?.get("denseMs") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
                media = media,
                lens = lens,
                frameRate = root.float("frameRate").takeIf { it > 0f } ?: DEFAULT_FRAME_RATE,
                frameCount = (root["frames"] as? JsonPrimitive)?.intOrNull ?: 0,
                floorY = (root["floorY"] as? JsonPrimitive)?.floatOrNull,
                textures = textures,
            )
        }

        private const val DEFAULT_FRAME_RATE = 10f

        /** The v2 `device` section: who recorded the scan, and with which depth source. */
        private fun parseDevice(obj: JsonObject) = ScanDevice(
            platform = obj.string("platform") ?: "",
            model = obj.string("model") ?: "",
            tier = obj.string("tier") ?: ScanDevice.TIER_SPARSE,
            depthSource = obj.string("depthSource") ?: ScanDevice.SOURCE_FEATURE_POINTS,
        )

        /** The v2 `dense` section; `null` without a path or with no points. */
        private fun parseDense(obj: JsonObject): ReplayDense? {
            val path = obj.string("path") ?: return null
            val count = (obj["count"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return null
            val bounds = (obj["bounds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
            return ReplayDense(
                path = path,
                count = count,
                voxelM = obj.float("voxelM").takeIf { it > 0f } ?: DenseFusion.VOXEL_M,
                normals = (obj["normals"] as? JsonPrimitive)?.content == "true",
                bounds = bounds?.takeIf { it.size == 6 }?.toFloatArray() ?: FloatArray(6),
            )
        }

        private fun JsonObject.float(key: String): Float = (this[key] as? JsonPrimitive)?.floatOrNull ?: 0f

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

        private fun JsonObject.vec(key: String): Vec3? {
            val array = this[key] as? JsonArray ?: return null
            if (array.size != 3) return null
            val c = array.map { (it as? JsonPrimitive)?.floatOrNull ?: return null }
            return Vec3(c[0], c[1], c[2])
        }
    }
}

/**
 * The map points' colours, packed into one small texture: point `i` is texel `i`, so a point's
 * vertices carry the texture coordinate of its own texel and one draw call paints thousands of
 * points in their photo colours. SceneView ships no per-vertex-colour material; this needs none.
 */
object PointColorAtlas {
    /** Texels per side: 128² = 16 384, over [ArDebugTrace.MAX_MAP_POINTS]. */
    const val SIZE = 128

    /**
     * Texture coordinates of point [index]'s texel centre, row `index / SIZE` of [pixels].
     *
     * V counts from the atlas's last row: the image material reads a raw `Texture.setImage`
     * upload like this one bottom-up (the photos, uploaded from a `Bitmap`, read top-down). With
     * V counted from the first row, every point sampled a row [pixels] never wrote — transparent,
     * so the whole coloured layer drew nothing (#4095).
     */
    fun uvOf(index: Int, size: Int = SIZE): Pair<Float, Float> {
        val i = index.coerceIn(0, size * size - 1)
        return ((i % size) + 0.5f) / size to 1f - ((i / size) + 0.5f) / size
    }

    /**
     * RGBA bytes, row-major, of the atlas for [colors] (`0xFFRRGGBB`, `0` meaning "none"):
     * a point without a colour gets [fallback].
     */
    fun pixels(colors: IntArray, fallback: Int, size: Int = SIZE): ByteArray {
        val out = ByteArray(size * size * 4)
        for (i in 0 until minOf(colors.size, size * size)) {
            val c = colors[i].takeIf { it != 0 } ?: fallback
            out[i * 4] = (c shr 16 and 0xFF).toByte()
            out[i * 4 + 1] = (c shr 8 and 0xFF).toByte()
            out[i * 4 + 2] = (c and 0xFF).toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }
}

/** Which camera frames the filmstrip shows: [slots] evenly spread over [count], ends included. */
fun filmstripFrames(count: Int, slots: Int): IntArray {
    if (count <= 0 || slots <= 0) return IntArray(0)
    if (count <= slots) return IntArray(count) { it }
    if (slots == 1) return intArrayOf(0)
    return IntArray(slots) { k -> ((k.toLong() * (count - 1) + (slots - 1) / 2) / (slots - 1)).toInt() }
}

/**
 * The replay's opening shot: the camera starts high and wide, a quarter turn round, and cranes
 * down onto the session as it starts to play — the home hero's entrance, for a room.
 */
object ReplayIntro {
    const val DURATION_S = 2.8f

    /** How much further out, higher and turned the shot starts than where it lands. */
    const val DISTANCE_FACTOR = 2.3f
    const val START_ELEVATION = 68f
    const val TURN_DEGREES = -75f

    /** Where the entrance starts, for a shot landing on [home]. */
    fun startFor(home: OrbitPose): OrbitPose = home.copy(
        azimuthDegrees = home.azimuthDegrees + TURN_DEGREES,
        elevationDegrees = START_ELEVATION,
        distance = home.distance * DISTANCE_FACTOR,
    )

    /** The shot at [progress] (0..1) of the way from [from] to [to]. */
    fun pose(from: OrbitPose, to: OrbitPose, progress: Float): OrbitPose =
        CameraRig.lerp(from, to, ease(progress.coerceIn(0f, 1f)))

    /**
     * `ease-expressive` from DESIGN.md — cubic-bezier(0.2, 0, 0, 1): leaves gently, lands softly.
     * Solved for x by Newton's method, then bisection if a step stalls.
     */
    @Suppress("MagicNumber") // the bezier's own control points
    fun ease(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val x1 = 0.2f
        val x2 = 0f
        val y1 = 0f
        val y2 = 1f
        fun bx(s: Float) = 3f * (1 - s) * (1 - s) * s * x1 + 3f * (1 - s) * s * s * x2 + s * s * s
        fun by(s: Float) = 3f * (1 - s) * (1 - s) * s * y1 + 3f * (1 - s) * s * s * y2 + s * s * s
        fun dx(s: Float) = 3f * (1 - s) * (1 - s) * x1 + 6f * (1 - s) * s * (x2 - x1) + 3f * s * s * (1 - x2)
        var s = t
        repeat(8) {
            val d = dx(s)
            if (abs(d) < 1e-6f) return@repeat
            s = (s - (bx(s) - t) / d).coerceIn(0f, 1f)
        }
        if (abs(bx(s) - t) > 1e-4f) {
            var lo = 0f
            var hi = 1f
            repeat(30) {
                s = (lo + hi) / 2f
                if (bx(s) < t) lo = s else hi = s
            }
        }
        return by(s).coerceIn(0f, 1f)
    }
}

/**
 * Frames per second, averaged over a short window so the figure reads rather than flickers.
 * Fed the frame callback's timestamps.
 */
class FpsMeter(private val windowNanos: Long = 500_000_000L) {
    private var windowStart = 0L
    private var frames = 0

    /** The last full window's rate, 0 until one has elapsed. */
    var fps: Int = 0
        private set

    /** Counts a frame at [nanos]; returns `true` when [fps] changed. */
    fun tick(nanos: Long): Boolean {
        if (windowStart == 0L) {
            windowStart = nanos
            return false
        }
        frames++
        val elapsed = nanos - windowStart
        if (elapsed < windowNanos) return false
        val next = ((frames * 1_000_000_000L + elapsed / 2) / elapsed).toInt()
        frames = 0
        windowStart = nanos
        val changed = next != fps
        fps = next
        return changed
    }
}

/** The replay's textured geometry: photos in frustums, photo planes, coloured points, shadows. */
object ReplayGeometry {
    /** Replay frustums are deeper than the live view's, so their photos read. */
    const val FRUSTUM_DEPTH = 0.3f
    const val KEYFRAME_DEPTH = 0.2f

    /** Keyframe spacing along the replay's path: close enough for a strip of photos. */
    const val KEYFRAME_SPACING_M = 0.38f

    /**
     * The photo a camera at [pose] took, on its frustum's image plane at [depth]: the quad the
     * frustum edges frame, texture top row along its top edge.
     */
    fun addImageQuad(mesh: DebugMesh, pose: DebugPose, depth: Float, lens: ReplayLens) {
        val c = ArDebugGeometry.frustumCorners(pose, depth, lens) // TL, TR, BR, BL
        val a = mesh.vertex(c[0].x, c[0].y, c[0].z, 0f, 0f)
        val b = mesh.vertex(c[1].x, c[1].y, c[1].z, 1f, 0f)
        val d = mesh.vertex(c[2].x, c[2].y, c[2].z, 1f, 1f)
        val e = mesh.vertex(c[3].x, c[3].y, c[3].z, 0f, 1f)
        mesh.quad(a, b, d, e)
    }

    /**
     * A plane's polygon filled with its photo [texture], drawn at [placed] — the same polygon
     * moved to its layer (see [PlaneLayering]: a floor laid flat under the grid, the others a
     * hair off their neighbours), still textured where the photo was taken, at [polygon].
     */
    fun addTexturedPlane(
        mesh: DebugMesh,
        polygon: FloatArray,
        texture: ReplayPlaneTexture,
        placed: FloatArray = polygon,
    ) {
        val n = polygon.size / 3
        if (n < 3 || placed.size != polygon.size) return
        var cx = 0f
        var cy = 0f
        var cz = 0f
        var px = 0f
        var py = 0f
        var pz = 0f
        for (i in 0 until n) {
            cx += polygon[i * 3]; cy += polygon[i * 3 + 1]; cz += polygon[i * 3 + 2]
            px += placed[i * 3]; py += placed[i * 3 + 1]; pz += placed[i * 3 + 2]
        }
        fun put(x: Float, y: Float, z: Float, at: Int): Int {
            val (u, v) = texture.uvOf(x, y, z)
            return if (at < 0) mesh.vertex(px / n, py / n, pz / n, u, v)
            else mesh.vertex(placed[at * 3], placed[at * 3 + 1], placed[at * 3 + 2], u, v)
        }
        val centre = put(cx / n, cy / n, cz / n, -1)
        val first = mesh.vertexCount
        for (i in 0 until n) put(polygon[i * 3], polygon[i * 3 + 1], polygon[i * 3 + 2], i)
        for (i in 0 until n) mesh.triangle(centre, first + i, first + (i + 1) % n)
    }

    /** The map points as tetrahedra, each carrying its own texel of [PointColorAtlas]. */
    fun addColoredPoints(mesh: DebugMesh, points: FloatArray, radius: Float) {
        val count = minOf(points.size / 3, PointColorAtlas.SIZE * PointColorAtlas.SIZE)
        val s = radius * 0.94f
        for (i in 0 until count) {
            val x = points[i * 3]
            val y = points[i * 3 + 1]
            val z = points[i * 3 + 2]
            val (u, v) = PointColorAtlas.uvOf(i)
            val a = mesh.vertex(x + s, y + s, z + s, u, v)
            val b = mesh.vertex(x + s, y - s, z - s, u, v)
            val c = mesh.vertex(x - s, y + s, z - s, u, v)
            val d = mesh.vertex(x - s, y - s, z + s, u, v)
            mesh.triangle(a, b, c)
            mesh.triangle(a, d, b)
            mesh.triangle(a, c, d)
            mesh.triangle(b, d, c)
        }
    }

    /** A soft contact shadow: a horizontal quad of [radius] under ([x], [y], [z]), uv 0..1. */
    fun addShadow(mesh: DebugMesh, x: Float, y: Float, z: Float, radius: Float) {
        val a = mesh.vertex(x - radius, y, z - radius, 0f, 0f)
        val b = mesh.vertex(x + radius, y, z - radius, 1f, 0f)
        val c = mesh.vertex(x + radius, y, z + radius, 1f, 1f)
        val d = mesh.vertex(x - radius, y, z + radius, 0f, 1f)
        mesh.quad(a, b, c, d)
    }
}
