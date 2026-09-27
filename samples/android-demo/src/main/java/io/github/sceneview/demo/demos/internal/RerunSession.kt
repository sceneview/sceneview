package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * A room scan saved on the phone: the same three things the bundled replay ships — a log in the
 * Rerun bridge's wire format, a manifest, the photos in one archive — so a scan reopened from the
 * phone's storage goes through exactly the code the sample does and looks exactly like it.
 * Pure Kotlin, round-trip tested on the JVM.
 */

/** Writes events back in the wire format [parseArDebugEvent] reads. */
object ArDebugLogWriter {
    /** One JSON line for [event], or `null` for one that has nothing finite to say. */
    fun line(event: ArDebugEvent): String? = when (event) {
        is ArDebugEvent.CameraPose -> pose(event.nanos, "camera_pose", "world/camera", event.pose)
        is ArDebugEvent.Anchor -> pose(event.nanos, "anchor", "world/anchors/${event.id}", event.pose)
        is ArDebugEvent.Points -> points(event)
        is ArDebugEvent.Plane -> buildJsonObject {
            put("t", event.nanos)
            put("type", "plane")
            put("entity", "world/planes/${event.id}")
            put("kind", event.kind.wireName)
            put("polygon", triples(event.polygon))
        }.toString()
        is ArDebugEvent.Image -> buildJsonObject {
            put("t", event.nanos)
            put("type", "image")
            put("entity", "world/camera/image")
            put("path", event.path)
        }.toString()
    }

    /** The whole log, one event per line. */
    fun write(events: List<ArDebugEvent>): String = buildString {
        for (event in events) {
            val line = line(event) ?: continue
            append(line).append('\n')
        }
    }

    private fun pose(nanos: Long, type: String, entity: String, pose: DebugPose): String? {
        val values = floatArrayOf(pose.x, pose.y, pose.z, pose.qx, pose.qy, pose.qz, pose.qw)
        if (values.any { !it.isFinite() }) return null
        return buildJsonObject {
            put("t", nanos)
            put("type", type)
            put("entity", entity)
            putJsonArray("translation") { for (i in 0 until 3) add(JsonPrimitive(values[i])) }
            putJsonArray("quaternion") { for (i in 3 until 7) add(JsonPrimitive(values[i])) }
        }.toString()
    }

    // An empty observation is written too: the replay's live points follow the latest one.
    private fun points(event: ArDebugEvent.Points): String {
        val count = event.positions.size / 3
        return buildJsonObject {
            put("t", event.nanos)
            put("type", "point_cloud")
            put("entity", "world/points")
            put("positions", triples(event.positions))
            event.confidences?.let { c -> putJsonArray("confidences") { c.forEach { add(JsonPrimitive(it)) } } }
            event.colors?.takeIf { it.size == count }?.let { colors ->
                putJsonArray("colors") { colors.forEach { add(rgb(it)) } }
            }
        }.toString()
    }

    /** `0xFFRRGGBB` → `[r, g, b]`; `0` (no colour) → `[-1, -1, -1]`, which reads back as none. */
    private fun rgb(color: Int): JsonArray = buildJsonArray {
        if (color == 0) {
            repeat(3) { add(JsonPrimitive(-1)) }
        } else {
            add(JsonPrimitive(color shr 16 and 0xFF))
            add(JsonPrimitive(color shr 8 and 0xFF))
            add(JsonPrimitive(color and 0xFF))
        }
    }

    /** Flat `[x,y,z, …]` → `[[x,y,z], …]`, skipping any vertex that is not finite. */
    private fun triples(flat: FloatArray): JsonArray = buildJsonArray {
        for (i in 0 until flat.size / 3) {
            val x = flat[i * 3]
            val y = flat[i * 3 + 1]
            val z = flat[i * 3 + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            add(buildJsonArray { add(JsonPrimitive(x)); add(JsonPrimitive(y)); add(JsonPrimitive(z)) })
        }
    }
}

/** The manifest [ReplayManifest.parse] reads, written back. */
fun ReplayManifest.toJson(): String = buildJsonObject {
    // The lens as intrinsics of a unit focal length: `width / 2 / fx` gives it back exactly.
    putJsonObject("intrinsics") {
        put("width", lens.halfWidthPerDepth * 2f)
        put("height", lens.halfHeightPerDepth * 2f)
        put("fx", 1f)
        put("fy", 1f)
    }
    put("frameRate", frameRate)
    put("frames", frameCount)
    floorY?.let { put("floorY", it) }
    putJsonArray("textures") {
        for (texture in textures) {
            add(
                buildJsonObject {
                    put("plane", texture.planeId)
                    put("path", texture.path)
                    put("origin", vec(texture.origin))
                    put("u", vec(texture.u))
                    put("v", vec(texture.v))
                },
            )
        }
    }
    putJsonArray("media") {
        for ((path, span) in media) {
            add(
                buildJsonObject {
                    put("path", path)
                    put("offset", span.offset)
                    put("length", span.length)
                },
            )
        }
    }
}.toString()

private fun vec(v: Vec3): JsonArray = buildJsonArray {
    add(JsonPrimitive(v.x))
    add(JsonPrimitive(v.y))
    add(JsonPrimitive(v.z))
}

/**
 * A saved scan's picture in the sessions list: its point cloud seen from above and aside, in the
 * points' own colours, the way the replay first shows it. Pure: points in, pixels out.
 */
object ScanCover {
    const val SIZE = 192

    /** A point the photos did not colour, drawn in the replay's own neutral. */
    private const val FALLBACK = 0xFFB8C2D6.toInt()
    private const val YAW_DEG = 35.0
    private const val ELEVATION_DEG = 30.0

    /** The share of points the picture is framed on: a few strays do not shrink the room. */
    private const val FRAMED = 0.95f
    private const val MARGIN = 0.9f
    private const val DOT = 2

    /**
     * [size] × [size] `0xAARRGGBB` pixels of the flat `[x,y,z, …]` [points], [colors] one per point
     * (`0` for none) or `null`; transparent around them. `null` without a point to draw.
     */
    fun render(points: FloatArray, colors: IntArray?, size: Int = SIZE): IntArray? {
        val n = points.size / 3
        if (n == 0) return null
        val yaw = Math.toRadians(YAW_DEG)
        val elevation = Math.toRadians(ELEVATION_DEG)
        val (cy, sy) = kotlin.math.cos(yaw).toFloat() to kotlin.math.sin(yaw).toFloat()
        val (ce, se) = kotlin.math.cos(elevation).toFloat() to kotlin.math.sin(elevation).toFloat()
        val sx = FloatArray(n)
        val sv = FloatArray(n)
        val depth = FloatArray(n)
        for (i in 0 until n) {
            val x = points[i * 3]
            val y = points[i * 3 + 1]
            val z = points[i * 3 + 2]
            val x1 = x * cy - z * sy
            val z1 = x * sy + z * cy
            sx[i] = x1
            sv[i] = y * ce - z1 * se
            depth[i] = y * se + z1 * ce
        }
        val midX = median(sx)
        val midY = median(sv)
        val reach = FloatArray(n) { maxOf(kotlin.math.abs(sx[it] - midX), kotlin.math.abs(sv[it] - midY)) }
        reach.sort()
        val radius = reach[((n - 1) * FRAMED).toInt()].coerceAtLeast(1e-3f)
        val scale = size / 2f * MARGIN / radius
        val out = IntArray(size * size)
        for (i in (0 until n).sortedBy { depth[it] }) {
            val px = (size / 2f + (sx[i] - midX) * scale).toInt()
            val py = (size / 2f - (sv[i] - midY) * scale).toInt()
            val color = colors?.getOrNull(i)?.takeIf { it != 0 } ?: FALLBACK
            dot(out, size, px, py, color)
        }
        return out
    }

    private fun dot(out: IntArray, size: Int, px: Int, py: Int, color: Int) {
        for (dy in 0 until DOT) {
            for (dx in 0 until DOT) {
                val x = px + dx
                val y = py + dy
                if (x in 0 until size && y in 0 until size) out[y * size + x] = color
            }
        }
    }

    private fun median(values: FloatArray): Float = values.sortedArray()[values.size / 2]
}

/**
 * What the sessions list shows of a saved scan without opening it: when it was made, how long it
 * ran, and the figures the Record screen counted.
 */
data class RerunSessionMeta(
    val createdAtMillis: Long,
    val durationSeconds: Float,
    val points: Int,
    val surfaces: Int,
    val photos: Int,
) {
    fun toJson(): String = buildJsonObject {
        put("format", FORMAT)
        put("version", VERSION)
        put("createdAt", createdAtMillis)
        put("duration", durationSeconds)
        put("points", points)
        put("surfaces", surfaces)
        put("photos", photos)
    }.toString()

    companion object {
        /** The format's name, so a file of the same shape from elsewhere is not taken for one. */
        const val FORMAT = "sceneview-rerun-session"
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `null` when [text] is not a session's header, or one from a newer version. */
        @Suppress("ReturnCount") // one early return per check
        fun parse(text: String): RerunSessionMeta? {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            if ((root["format"] as? JsonPrimitive)?.content != FORMAT) return null
            val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return null
            if (version > VERSION) return null
            return RerunSessionMeta(
                createdAtMillis = (root["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                durationSeconds = (root["duration"] as? JsonPrimitive)?.floatOrNull ?: 0f,
                points = (root["points"] as? JsonPrimitive)?.intOrNull ?: 0,
                surfaces = (root["surfaces"] as? JsonPrimitive)?.intOrNull ?: 0,
                photos = (root["photos"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }
    }
}

/**
 * One saved scan, whole: its header, an optional cover picture for the list, and the three parts
 * of a replay — [manifest] and [log] as text, [media] the photo archive the manifest indexes.
 */
class RerunSessionFile(
    val meta: RerunSessionMeta,
    val cover: ByteArray?,
    val manifest: String,
    val log: String,
    val media: ByteArray,
)

/**
 * The file a scan is saved in: a zip, header first so the list reads it without inflating the
 * rest. The text is deflated; the photos, already compressed, are stored as they are.
 */
object RerunSessionFormat {
    const val EXTENSION = "sceneview-scan"
    const val MIME_TYPE = "application/zip"

    private const val META = "session.json"
    private const val COVER = "cover.png"
    private const val MANIFEST = "manifest.json"
    private const val LOG = "session.jsonl"
    private const val MEDIA = "media.bin"

    /** Nothing in a scan comes near this; a file that claims more is not one of ours. */
    private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024

    fun write(file: RerunSessionFile, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.deflated(META, file.meta.toJson().toByteArray())
            file.cover?.let { zip.stored(COVER, it) }
            zip.deflated(MANIFEST, file.manifest.toByteArray())
            zip.deflated(LOG, file.log.toByteArray())
            zip.stored(MEDIA, file.media)
        }
    }

    /** The whole scan, or `null` when [input] is not a readable one. Does not close [input]. */
    @Suppress("ReturnCount") // one early return per missing part
    fun read(input: InputStream): RerunSessionFile? {
        val parts = HashMap<String, ByteArray>()
        runCatching {
            val zip = ZipInputStream(input)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name in PARTS) parts[entry.name] = zip.readBounded() ?: return null
            }
        }.getOrElse { return null }
        val meta = parts[META]?.let { RerunSessionMeta.parse(String(it)) } ?: return null
        return RerunSessionFile(
            meta = meta,
            cover = parts[COVER],
            manifest = parts[MANIFEST]?.let(::String) ?: return null,
            log = parts[LOG]?.let(::String) ?: return null,
            media = parts[MEDIA] ?: ByteArray(0),
        )
    }

    /** The header and the cover only, read from the start of the file. Does not close [input]. */
    fun readHeader(input: InputStream): Pair<RerunSessionMeta, ByteArray?>? = runCatching {
        val zip = ZipInputStream(input)
        var meta: RerunSessionMeta? = null
        var cover: ByteArray? = null
        // The header comes first: past it, the list has what it needs.
        var entry = zip.nextEntry
        while (entry != null && entry.name in HEADER) {
            if (entry.name == META) {
                meta = zip.readBounded()?.let { RerunSessionMeta.parse(String(it)) }
            } else {
                cover = zip.readBounded()
            }
            entry = zip.nextEntry
        }
        meta?.let { it to cover }
    }.getOrNull()

    private val PARTS = setOf(META, COVER, MANIFEST, LOG, MEDIA)
    private val HEADER = setOf(META, COVER)

    private fun ZipInputStream.readBounded(): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun ZipOutputStream.deflated(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
        write(bytes)
        closeEntry()
    }

    private fun ZipOutputStream.stored(name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        putNextEntry(
            ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            },
        )
        write(bytes)
        closeEntry()
    }

    private const val BUFFER_BYTES = 64 * 1024
}
