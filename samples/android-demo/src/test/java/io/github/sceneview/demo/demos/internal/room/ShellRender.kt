package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunJson
import io.github.sceneview.demo.demos.internal.model.JvmImages
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A room shell drawn off the phone, as the result shows it: the `.glb` itself read back — its
 * quads, their UVs, their embedded JPEGs — rasterised with back faces culled (the dollhouse the
 * single-sided walls give), and the object points drawn over it as small squares of about 2 cm,
 * z-buffered. The prototype's `render.py` views: four dollhouse corners and one from above.
 */
internal object ShellRender {
    const val PX = 900

    class Image(val argb: IntArray, val width: Int, val height: Int)

    /** A textured triangle soup read from a `.glb` [RoomShellGlb] wrote. */
    class Mesh(val positions: FloatArray, val normals: FloatArray, val uvs: FloatArray, val faces: List<Pair<IntArray, JvmImages.Image>>)

    @Suppress("UNCHECKED_CAST")
    fun read(glb: ByteArray): Mesh {
        val buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = buffer.getInt(12)
        val root = RerunJson.parse(String(glb, 20, jsonLength, Charsets.UTF_8)) as Map<String, Any?>
        val binStart = 20 + jsonLength + 8
        val accessors = root["accessors"] as List<Map<String, Any?>>
        val views = root["bufferViews"] as List<Map<String, Any?>>
        fun Map<String, Any?>.int(key: String) = (this[key] as? Number)?.toInt() ?: 0
        fun floats(accessor: Int, size: Int): FloatArray {
            val a = accessors[accessor]
            val at = binStart + views[a.int("bufferView")].int("byteOffset") + a.int("byteOffset")
            return FloatArray(a.int("count") * size) { buffer.getFloat(at + it * 4) }
        }
        fun shorts(accessor: Int): IntArray {
            val a = accessors[accessor]
            val at = binStart + views[a.int("bufferView")].int("byteOffset") + a.int("byteOffset")
            return IntArray(a.int("count")) { buffer.getShort(at + it * 2).toInt() and 0xFFFF }
        }
        val materials = root["materials"] as List<Map<String, Any?>>
        val textures = root["textures"] as List<Map<String, Any?>>
        val images = root["images"] as List<Map<String, Any?>>
        val mesh = (root["meshes"] as List<Map<String, Any?>>).single()
        val faces = (mesh["primitives"] as List<Map<String, Any?>>).map { primitive ->
            val material = materials[primitive.int("material")]
            val pbr = material["pbrMetallicRoughness"] as Map<String, Any?>
            val texture = textures[(pbr["baseColorTexture"] as Map<String, Any?>).int("index")]
            val view = views[images[texture.int("source")].int("bufferView")]
            val jpeg = glb.copyOfRange(binStart + view.int("byteOffset"), binStart + view.int("byteOffset") + view.int("byteLength"))
            shorts(primitive.int("indices")) to checkNotNull(JvmImages.decode(jpeg)) { "texture not decoded" }
        }
        return Mesh(floats(0, 3), floats(1, 3), floats(4, 2), faces)
    }

    /**
     * [mesh] and [objects] from the room-frame eye at azimuth [azimuthDeg] round the box's centre —
     * `null` for straight down — at `render.py`'s distance, 38° tall, [PX] square.
     */
    fun view(mesh: Mesh, objects: DenseCloud, box: RoomBox, azimuthDeg: Double?): Image {
        val span = max(box.width, box.depth)
        val ca = (box.a0 + box.a1) / 2
        val cb = (box.b0 + box.b1) / 2
        val cy = box.floorY + CENTRE_LIFT_M
        val (ea, ey, eb) = if (azimuthDeg == null) {
            Triple(ca, cy + span * TOP_DISTANCE, cb + TOP_NUDGE)
        } else {
            val r = Math.toRadians(azimuthDeg)
            Triple(ca + (cos(r) * span * SIDE_DISTANCE).toFloat(), cy + span * HEIGHT_DISTANCE, cb + (sin(r) * span * SIDE_DISTANCE).toFloat())
        }
        val eye = floatArrayOf(box.worldX(ea, eb), ey, box.worldZ(ea, eb))
        val target = floatArrayOf(box.worldX(ca, cb), cy, box.worldZ(ca, cb))
        return draw(mesh, objects, eye, target)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private fun draw(mesh: Mesh, objects: DenseCloud, eye: FloatArray, target: FloatArray): Image {
        val f = normalize(FloatArray(3) { target[it] - eye[it] })
        val r = normalize(cross(f, floatArrayOf(0f, 1f, 0f)))
        val u = cross(r, f)
        val fl = (PX / 2f / tan(Math.toRadians(FOV_DEG / 2)).toFloat())
        val argb = IntArray(PX * PX) { BACKGROUND }
        val zb = FloatArray(PX * PX) { Float.POSITIVE_INFINITY }
        val p = mesh.positions
        fun cam(v: Int, out: FloatArray) {
            val dx = p[v * 3] - eye[0]
            val dy = p[v * 3 + 1] - eye[1]
            val dz = p[v * 3 + 2] - eye[2]
            out[0] = dx * r[0] + dy * r[1] + dz * r[2]
            out[1] = dx * u[0] + dy * u[1] + dz * u[2]
            out[2] = dx * f[0] + dy * f[1] + dz * f[2]
        }
        val c = Array(3) { FloatArray(3) }
        for ((indices, texture) in mesh.faces) {
            for (t in 0 until indices.size / 3) {
                val vs = IntArray(3) { indices[t * 3 + it] }
                // Back faces culled, as Filament culls a single-sided material.
                val v0 = vs[0]
                val toEye = (eye[0] - p[v0 * 3]) * mesh.normals[v0 * 3] + (eye[1] - p[v0 * 3 + 1]) * mesh.normals[v0 * 3 + 1] +
                    (eye[2] - p[v0 * 3 + 2]) * mesh.normals[v0 * 3 + 2]
                if (toEye <= 0f) continue
                for (k in 0 until 3) cam(vs[k], c[k])
                if (c.any { it[2] < NEAR }) continue
                val sx = FloatArray(3) { PX / 2f + c[it][0] / c[it][2] * fl }
                val sy = FloatArray(3) { PX / 2f - c[it][1] / c[it][2] * fl }
                val iz = FloatArray(3) { 1f / c[it][2] }
                val area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sy[1] - sy[0]) * (sx[2] - sx[0])
                if (area == 0f) continue
                val x0 = max(0, floor(sx.min()).toInt())
                val x1 = min(PX - 1, ceil(sx.max()).toInt())
                val y0 = max(0, floor(sy.min()).toInt())
                val y1 = min(PX - 1, ceil(sy.max()).toInt())
                for (y in y0..y1) for (x in x0..x1) {
                    val px = x + 0.5f
                    val py = y + 0.5f
                    val w0 = ((sx[1] - px) * (sy[2] - py) - (sy[1] - py) * (sx[2] - px)) / area
                    val w1 = ((sx[2] - px) * (sy[0] - py) - (sy[2] - py) * (sx[0] - px)) / area
                    val w2 = 1f - w0 - w1
                    if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                    val inv = w0 * iz[0] + w1 * iz[1] + w2 * iz[2]
                    val z = 1f / inv
                    val i = y * PX + x
                    if (z >= zb[i]) continue
                    val uu = (w0 * mesh.uvs[vs[0] * 2] * iz[0] + w1 * mesh.uvs[vs[1] * 2] * iz[1] + w2 * mesh.uvs[vs[2] * 2] * iz[2]) * z
                    val vv = (w0 * mesh.uvs[vs[0] * 2 + 1] * iz[0] + w1 * mesh.uvs[vs[1] * 2 + 1] * iz[1] + w2 * mesh.uvs[vs[2] * 2 + 1] * iz[2]) * z
                    val tx = (uu * texture.width).toInt().coerceIn(0, texture.width - 1)
                    val ty = (vv * texture.height).toInt().coerceIn(0, texture.height - 1)
                    zb[i] = z
                    argb[i] = texture.argb[ty * texture.width + tx]
                }
            }
        }
        val q = objects.positions
        for (k in 0 until objects.count) {
            val dx = q[k * 3] - eye[0]
            val dy = q[k * 3 + 1] - eye[1]
            val dz = q[k * 3 + 2] - eye[2]
            val z = dx * f[0] + dy * f[1] + dz * f[2]
            if (z < NEAR) continue
            val px = PX / 2f + (dx * r[0] + dy * r[1] + dz * r[2]) / z * fl
            val py = PX / 2f - (dx * u[0] + dy * u[1] + dz * u[2]) / z * fl
            val radius = ceil(POINT_M * fl / z / 2f).toInt().coerceIn(1, MAX_RADIUS)
            for (oy in -radius + 1 until radius) for (ox in -radius + 1 until radius) {
                val x = (px + ox).toInt()
                val y = (py + oy).toInt()
                if (x !in 0 until PX || y !in 0 until PX) continue
                val i = y * PX + x
                if (z < zb[i]) {
                    zb[i] = z
                    argb[i] = objects.colors[k]
                }
            }
        }
        return Image(argb, PX, PX)
    }

    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun normalize(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }

    private const val FOV_DEG = 38.0
    private const val NEAR = 0.1f
    private const val POINT_M = 0.022f
    private const val MAX_RADIUS = 4
    private const val CENTRE_LIFT_M = 0.6f
    private const val SIDE_DISTANCE = 1.5
    private const val HEIGHT_DISTANCE = 1.25f
    private const val TOP_DISTANCE = 2.6f
    private const val TOP_NUDGE = 0.01f
    private const val BACKGROUND = 0xFFF5F5F5.toInt()
}
