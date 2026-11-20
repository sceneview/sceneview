package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A `.glb` [RerunMeshGlb] wrote, read back into its [RerunMesh] — colours in sRGB again. `null`
 * for any other file: this reads the one layout the scan writes, not glTF at large.
 */
object RerunGlbMesh {
    /** Geometry alone, without allocating colours or indices; shell faces are consecutive quads. */
    class Geometry(val positions: FloatArray, val normals: FloatArray?, val shellQuads: Boolean) {
        val vertexCount: Int get() = positions.size / 3
    }

    fun readGeometry(glb: ByteArray, withNormals: Boolean = true): Geometry? = runCatching {
        Layout(glb).geometry(withNormals)
    }.getOrNull()

    @Suppress("ReturnCount", "MagicNumber")
    fun read(glb: ByteArray): RerunMesh? = runCatching {
        val layout = Layout(glb)
        val geometry = layout.geometry(withNormals = true)
        val n = geometry.vertexCount
        val colorAt = layout.offsetOf(2)
        val colors = IntArray(n) { v ->
            val r = SRGB[glb[colorAt + v * 4].toInt() and 0xFF]
            val g = SRGB[glb[colorAt + v * 4 + 1].toInt() and 0xFF]
            val b = SRGB[glb[colorAt + v * 4 + 2].toInt() and 0xFF]
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val indexCount = layout.accessors[3].int("count")
        layout.buffer.position(layout.offsetOf(3))
        val indices = if (layout.accessors[3].int("componentType") == UNSIGNED_SHORT) {
            val shorts = ShortArray(indexCount)
            layout.buffer.asShortBuffer().get(shorts)
            IntArray(indexCount) { shorts[it].toInt() and 0xFFFF }
        } else {
            IntArray(indexCount).also { layout.buffer.asIntBuffer().get(it) }
        }
        if (indices.any { it !in 0 until n }) return null
        RerunMesh(geometry.positions, checkNotNull(geometry.normals), colors, indices)
    }.getOrNull()

    /** Resolve each accessor once, then copy its contiguous typed buffer in bulk. */
    private class Layout(glb: ByteArray) {
        val buffer: ByteBuffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val root: JsonObject
        val accessors: JsonArray
        private val views: JsonArray
        private val binStart: Int

        init {
            require(buffer.getInt(0) == GLB_MAGIC)
            val jsonLength = buffer.getInt(HEADER)
            root = lenient.parseToJsonElement(String(glb, HEADER + CHUNK_HEADER, jsonLength, Charsets.UTF_8)) as JsonObject
            binStart = HEADER + CHUNK_HEADER + jsonLength + CHUNK_HEADER
            accessors = root["accessors"] as JsonArray
            views = root["bufferViews"] as JsonArray
        }

        fun offsetOf(accessor: Int): Int = binStart +
            views[accessors[accessor].int("bufferView")].int("byteOffset") + accessors[accessor].int("byteOffset")

        fun geometry(withNormals: Boolean): Geometry {
            val count = accessors[0].int("count") * 3
            val positions = floats(0, count)
            val normals = if (withNormals) floats(1, count) else null
            val shell = (root["extras"] as? JsonObject)?.get("sceneviewRoomShell") == JsonPrimitive(true)
            return Geometry(positions, normals, shell)
        }

        private fun floats(accessor: Int, count: Int): FloatArray {
            buffer.position(offsetOf(accessor))
            return FloatArray(count).also { buffer.asFloatBuffer().get(it) }
        }
    }

    private fun Any?.int(key: String) = ((this as JsonObject)[key] as? JsonPrimitive)?.intOrNull ?: 0

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Linear byte → sRGB byte, the inverse of [RerunMeshGlb]'s. */
    private val SRGB = IntArray(256) { i ->
        val c = i / 255.0
        val s = if (c <= LINEAR_KNEE) c * SRGB_LINEAR_SLOPE else (1 + SRGB_OFFSET) * c.pow(1 / SRGB_GAMMA) - SRGB_OFFSET
        (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    private const val LINEAR_KNEE = 0.0031308
    private const val SRGB_LINEAR_SLOPE = 12.92
    private const val SRGB_OFFSET = 0.055
    private const val SRGB_GAMMA = 2.4
    private const val HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val GLB_MAGIC = 0x46546C67
    private const val UNSIGNED_SHORT = 5123
}

/**
 * The export sheet's picture of the model: [mesh] drawn on the CPU from [pose] — the result
 * screen's three-quarter framing — with the faces turned away left out, as the screen's
 * single-sided material leaves them, and a soft light from the camera's upper left. ARGB, the
 * background transparent so the sheet's own surface shows through.
 */
object RerunModelThumbnail {
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    fun render(mesh: RerunMesh, pose: OrbitPose, verticalFovDegrees: Double, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val depth = FloatArray(width * height) { Float.MAX_VALUE }
        val a = Math.toRadians(pose.azimuthDegrees.toDouble())
        val e = Math.toRadians(pose.elevationDegrees.toDouble())
        val sinA = sin(a).toFloat()
        val cosA = cos(a).toFloat()
        val sinE = sin(e).toFloat()
        val cosE = cos(e).toFloat()
        val eyeX = pose.target.x + pose.distance * cosE * sinA
        val eyeY = pose.target.y + pose.distance * sinE
        val eyeZ = pose.target.z + pose.distance * cosE * cosA
        val focal = (height / 2f) / tan(Math.toRadians(verticalFovDegrees / 2)).toFloat()
        val n = mesh.vertexCount
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val sz = FloatArray(n)
        for (v in 0 until n) {
            val rx = mesh.positions[v * 3] - eyeX
            val ry = mesh.positions[v * 3 + 1] - eyeY
            val rz = mesh.positions[v * 3 + 2] - eyeZ
            val x = rx * cosA - rz * sinA
            val y = -rx * sinA * sinE + ry * cosE - rz * cosA * sinE
            val z = -rx * cosE * sinA - ry * sinE - rz * cosE * cosA
            sz[v] = z
            sx[v] = width / 2f + x / max(z, NEAR) * focal
            sy[v] = height / 2f - y / max(z, NEAR) * focal
        }
        // The light: from over the camera's left shoulder, in world space.
        val lx = -cosA * LIGHT_SIDE + sinA * cosE
        val ly = LIGHT_UP
        val lz = sinA * LIGHT_SIDE + cosA * cosE
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        for (t in 0 until mesh.triangleCount) {
            val i0 = mesh.indices[t * 3]
            val i1 = mesh.indices[t * 3 + 1]
            val i2 = mesh.indices[t * 3 + 2]
            // The face's normal, from its vertices': a face turned away, or behind the lens, is not drawn.
            val nx = mesh.normals[i0 * 3] + mesh.normals[i1 * 3] + mesh.normals[i2 * 3]
            val ny = mesh.normals[i0 * 3 + 1] + mesh.normals[i1 * 3 + 1] + mesh.normals[i2 * 3 + 1]
            val nz = mesh.normals[i0 * 3 + 2] + mesh.normals[i1 * 3 + 2] + mesh.normals[i2 * 3 + 2]
            val cx = mesh.positions[i0 * 3] - eyeX
            val cy = mesh.positions[i0 * 3 + 1] - eyeY
            val cz = mesh.positions[i0 * 3 + 2] - eyeZ
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            val behind = minOf(sz[i0], sz[i1], sz[i2]) < NEAR
            val turnedAway = nl == 0f || nx * cx + ny * cy + nz * cz >= 0f
            if (behind || turnedAway) continue
            val lambert = max(0f, (nx * lx + ny * ly + nz * lz) / (nl * ll))
            val shade = AMBIENT + (1f - AMBIENT) * lambert
            val color = shaded(mesh.colors[i0], mesh.colors[i1], mesh.colors[i2], shade)
            fill(pixels, depth, width, height, sx, sy, sz, i0, i1, i2, color)
        }
        return pixels
    }

    @Suppress("LongParameterList", "NestedBlockDepth")
    private fun fill(
        pixels: IntArray,
        depth: FloatArray,
        width: Int,
        height: Int,
        sx: FloatArray,
        sy: FloatArray,
        sz: FloatArray,
        i0: Int,
        i1: Int,
        i2: Int,
        color: Int,
    ) {
        val x0 = sx[i0]
        val y0 = sy[i0]
        val x1 = sx[i1]
        val y1 = sy[i1]
        val x2 = sx[i2]
        val y2 = sy[i2]
        val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
        if (area == 0f) return
        val minX = max(0, min(x0, min(x1, x2)).toInt())
        val maxX = min(width - 1, max(x0, max(x1, x2)).toInt())
        val minY = max(0, min(y0, min(y1, y2)).toInt())
        val maxY = min(height - 1, max(y0, max(y1, y2)).toInt())
        for (py in minY..maxY) {
            val cy = py + HALF
            for (px in minX..maxX) {
                val cx = px + HALF
                val w0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) / area
                val w1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val z = w0 * sz[i0] + w1 * sz[i1] + w2 * sz[i2]
                val at = py * width + px
                if (z < depth[at]) {
                    depth[at] = z
                    pixels[at] = color
                }
            }
        }
    }

    private fun shaded(c0: Int, c1: Int, c2: Int, shade: Float): Int {
        fun channel(shift: Int): Int {
            val sum = ((c0 shr shift) and BYTE) + ((c1 shr shift) and BYTE) + ((c2 shr shift) and BYTE)
            return (sum / 3f * shade).toInt().coerceIn(0, BYTE)
        }
        return (BYTE shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private const val NEAR = 0.05f
    private const val HALF = 0.5f
    private const val AMBIENT = 0.55f
    private const val LIGHT_SIDE = 0.5f
    private const val LIGHT_UP = 0.8f
    private const val BYTE = 0xFF
}
