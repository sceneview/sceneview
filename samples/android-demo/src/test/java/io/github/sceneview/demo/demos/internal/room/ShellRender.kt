package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunJson
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.model.JvmImages
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
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
 * single-sided walls give), its object mesh when it has one (vertex-coloured, double-sided,
 * shaded by one light from above as the lit material is), and the object points drawn over it as
 * small squares of about 2 cm, z-buffered. The prototype's `render.py` views: four dollhouse
 * corners and one from above.
 */
internal object ShellRender {
    const val PX = 900

    class Image(val argb: IntArray, val width: Int, val height: Int)

    /** A textured triangle soup read from a `.glb` [RoomShellGlb] wrote, and its object mesh. */
    class Mesh(
        val positions: FloatArray,
        val normals: FloatArray,
        val uvs: FloatArray,
        val faces: List<Pair<IntArray, JvmImages.Image>>,
        val objects: RerunMesh? = null,
    )

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
        fun ints(accessor: Int): IntArray {
            val a = accessors[accessor]
            val at = binStart + views[a.int("bufferView")].int("byteOffset") + a.int("byteOffset")
            return IntArray(a.int("count")) { buffer.getInt(at + it * 4) }
        }
        fun colours(accessor: Int): IntArray {
            val a = accessors[accessor]
            val at = binStart + views[a.int("bufferView")].int("byteOffset") + a.int("byteOffset")
            return IntArray(a.int("count")) { v ->
                var c = 0xFF shl 24
                for (k in 0 until 3) c = c or (SRGB[glb[at + v * 4 + k].toInt() and 0xFF] shl (16 - 8 * k))
                c
            }
        }
        val primitives = mesh["primitives"] as List<Map<String, Any?>>
        val objects = primitives.firstOrNull { materials[it.int("material")]["name"] == RoomShellGlb.OBJECTS }?.let { primitive ->
            val attributes = primitive["attributes"] as Map<String, Any?>
            RerunMesh(
                floats(attributes.int("POSITION"), 3),
                floats(attributes.int("NORMAL"), 3),
                colours(attributes.int("COLOR_0")),
                ints(primitive.int("indices")),
            )
        }
        val faces = primitives.filter { materials[it.int("material")]["name"] != RoomShellGlb.OBJECTS }.map { primitive ->
            val material = materials[primitive.int("material")]
            val pbr = material["pbrMetallicRoughness"] as Map<String, Any?>
            val texture = textures[(pbr["baseColorTexture"] as Map<String, Any?>).int("index")]
            val view = views[images[texture.int("source")].int("bufferView")]
            val jpeg = glb.copyOfRange(binStart + view.int("byteOffset"), binStart + view.int("byteOffset") + view.int("byteLength"))
            shorts(primitive.int("indices")) to checkNotNull(JvmImages.decode(jpeg)) { "texture not decoded" }
        }
        return Mesh(floats(0, 3), floats(1, 3), floats(4, 2), faces, objects)
    }

    /**
     * A fused surface on its own — a scan with no room box — from the eye at azimuth
     * [azimuthDeg] round the middle of its bounds (`null` for straight down), at the prototype's
     * `tsdf.py` distance: 1.3 spans out and one up.
     */
    fun view(surface: RerunMesh, azimuthDeg: Double?): Image {
        val b = robustBounds(surface)
        val centre = floatArrayOf((b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2)
        val span = max(b[3] - b[0], b[5] - b[2])
        val eye = if (azimuthDeg == null) {
            floatArrayOf(centre[0], centre[1] + span * TOP_DISTANCE, centre[2] + TOP_NUDGE)
        } else {
            val r = Math.toRadians(azimuthDeg)
            floatArrayOf(centre[0] + (cos(r) * span * MESH_SIDE).toFloat(), centre[1] + span, centre[2] + (sin(r) * span * MESH_SIDE).toFloat())
        }
        return draw(Mesh(FloatArray(0), FloatArray(0), FloatArray(0), emptyList(), surface), DenseCloud(FloatArray(0), IntArray(0)), eye, centre)
    }

    /** [surface]'s bounds without its farthest half-percent each way, as `tsdf.py` frames. */
    private fun robustBounds(surface: RerunMesh): FloatArray {
        val n = surface.vertexCount
        if (n == 0) return FloatArray(6)
        val out = FloatArray(6)
        for (a in 0 until 3) {
            val v = FloatArray(n) { surface.positions[it * 3 + a] }
            v.sort()
            out[a] = v[(n * TRIM).toInt()] - BOUNDS_PAD_M
            out[a + 3] = v[(n * (1 - TRIM)).toInt().coerceAtMost(n - 1)] + BOUNDS_PAD_M
        }
        return out
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
        mesh.objects?.let { drawShaded(it, eye, r, u, f, fl, argb, zb) }
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

    /**
     * [surface]'s triangles, both sides, each vertex's colour shaded by its normal turned to the
     * eye against one light from above — `0.45 + 0.55 · cos` — and interpolated.
     */
    @Suppress("LongParameterList", "NestedBlockDepth")
    private fun drawShaded(
        surface: RerunMesh,
        eye: FloatArray,
        r: FloatArray,
        u: FloatArray,
        f: FloatArray,
        fl: Float,
        argb: IntArray,
        zb: FloatArray,
    ) {
        val p = surface.positions
        val light = normalize(floatArrayOf(LIGHT_X, LIGHT_Y, LIGHT_Z))
        val sx = FloatArray(3)
        val sy = FloatArray(3)
        val sz = FloatArray(3)
        val ix = surface.indices
        val shade = FloatArray(3)
        triangles@ for (t in 0 until surface.triangleCount) {
            for (k in 0 until 3) {
                val v = ix[t * 3 + k]
                val dx = p[v * 3] - eye[0]
                val dy = p[v * 3 + 1] - eye[1]
                val dz = p[v * 3 + 2] - eye[2]
                val z = dx * f[0] + dy * f[1] + dz * f[2]
                if (z < NEAR) continue@triangles
                sx[k] = PX / 2f + (dx * r[0] + dy * r[1] + dz * r[2]) / z * fl
                sy[k] = PX / 2f - (dx * u[0] + dy * u[1] + dz * u[2]) / z * fl
                sz[k] = z
            }
            val a = ix[t * 3]
            val b = ix[t * 3 + 1]
            val c = ix[t * 3 + 2]
            val e1 = floatArrayOf(p[b * 3] - p[a * 3], p[b * 3 + 1] - p[a * 3 + 1], p[b * 3 + 2] - p[a * 3 + 2])
            val e2 = floatArrayOf(p[c * 3] - p[a * 3], p[c * 3 + 1] - p[a * 3 + 1], p[c * 3 + 2] - p[a * 3 + 2])
            val n = cross(e1, e2)
            val length = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (length <= 0f) continue
            // Double-sided: the side the eye sees, each corner lit by its own normal turned that way.
            val toEye = (eye[0] - p[a * 3]) * n[0] + (eye[1] - p[a * 3 + 1]) * n[1] + (eye[2] - p[a * 3 + 2]) * n[2]
            val facing = if (toEye < 0f) -1f else 1f
            for (k in 0 until 3) {
                val v = ix[t * 3 + k]
                val nn = surface.normals
                // A vertex normal points the triangle's way, or it is turned to.
                val along = nn[v * 3] * n[0] + nn[v * 3 + 1] * n[1] + nn[v * 3 + 2] * n[2]
                val sign = if (along < 0f) -facing else facing
                val cosine = (nn[v * 3] * light[0] + nn[v * 3 + 1] * light[1] + nn[v * 3 + 2] * light[2]) * sign
                shade[k] = AMBIENT + (1f - AMBIENT) * cosine.coerceIn(0f, 1f)
            }
            val area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sy[1] - sy[0]) * (sx[2] - sx[0])
            if (area == 0f) continue
            val x0 = max(0, floor(minOf(sx[0], sx[1], sx[2])).toInt())
            val x1 = min(PX - 1, ceil(maxOf(sx[0], sx[1], sx[2])).toInt())
            val y0 = max(0, floor(minOf(sy[0], sy[1], sy[2])).toInt())
            val y1 = min(PX - 1, ceil(maxOf(sy[0], sy[1], sy[2])).toInt())
            val ca = surface.colors[a]
            val cb = surface.colors[b]
            val cc = surface.colors[c]
            for (y in y0..y1) for (x in x0..x1) {
                val px = x + 0.5f
                val py = y + 0.5f
                val w0 = ((sx[1] - px) * (sy[2] - py) - (sy[1] - py) * (sx[2] - px)) / area
                val w1 = ((sx[2] - px) * (sy[0] - py) - (sy[2] - py) * (sx[0] - px)) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val z = w0 * sz[0] + w1 * sz[1] + w2 * sz[2]
                val i = y * PX + x
                if (z >= zb[i]) continue
                zb[i] = z
                var out = 0xFF shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val channel = (ca shr shift and 0xFF) * w0 * shade[0] + (cb shr shift and 0xFF) * w1 * shade[1] +
                        (cc shr shift and 0xFF) * w2 * shade[2]
                    out = out or (channel.toInt().coerceIn(0, 255) shl shift)
                }
                argb[i] = out
            }
        }
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
    private const val MESH_SIDE = 1.3
    private const val TRIM = 0.005f
    private const val BOUNDS_PAD_M = 0.1f
    private const val AMBIENT = 0.45f
    private const val LIGHT_X = 0.35f
    private const val LIGHT_Y = 0.85f
    private const val LIGHT_Z = 0.4f

    /** Linear byte → sRGB byte: [RerunMeshGlb.LINEAR] undone, to draw `COLOR_0` as it was coloured. */
    private val SRGB = IntArray(256).also { out ->
        val forward = RerunMeshGlb.LINEAR
        for (l in 0 until 256) {
            // The sRGB byte whose linear byte is nearest to l.
            var best = 0
            for (s in 0 until 256) if (abs((forward[s].toInt() and 0xFF) - l) < abs((forward[best].toInt() and 0xFF) - l)) best = s
            out[l] = best
        }
    }
}
