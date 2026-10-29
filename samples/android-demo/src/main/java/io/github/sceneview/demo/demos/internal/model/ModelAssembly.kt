package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.RerunMesh
import kotlin.math.sqrt

/**
 * The finished model, ready to write: vertices split where a chart or a crease needs them, the
 * faces a photo textures first ([texturedIndices]), then the ones that keep their vertex colours
 * ([plainIndices]). [colors] are sRGB ARGB: a textured vertex carries its texture's colour, so a
 * reader that ignores the texture still sees the room.
 */
internal class ModelMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: IntArray,
    /** Per vertex, u then v; `null` without a texture. */
    val uv: FloatArray?,
    val texturedIndices: IntArray,
    val plainIndices: IntArray,
    val texture: PhotoTexture?,
) {
    val vertexCount: Int get() = colors.size
    val triangleCount: Int get() = (texturedIndices.size + plainIndices.size) / 3

    /** The same model with [texture] in place of its own: an atlas resampled, its corners unchanged. */
    fun withTexture(texture: PhotoTexture): ModelMesh =
        ModelMesh(positions, normals, colors, uv, texturedIndices, plainIndices, texture)

    /** All faces in one list, textured first: what a reader of the plain model gets. */
    fun asRerunMesh(): RerunMesh = RerunMesh(positions, normals, colors, texturedIndices + plainIndices)
}

internal object ModelAssembly {
    /**
     * Splits [mesh]'s vertices by chart (a texture coordinate each) and by crease (faces meeting
     * at more than [CREASE_DEGREES] keep their own normals: a wall's corner stays sharp, a
     * cushion stays soft) and gathers the faces by material.
     */
    fun assemble(mesh: WorkMesh, texture: PhotoTexture?): ModelMesh {
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        val topology = MeshTopology(mesh.vertexCount, mesh.indices)
        val group = IntArray(mesh.indices.size)
        val groupNormal = FloatArray(mesh.indices.size * 3)
        creases(mesh, faces, topology, group, groupNormal)
        val ids = HashMap<Long, Int>(mesh.indices.size)
        val positions = FloatList()
        val normals = FloatList()
        val uv = FloatList()
        val colors = IntList()
        val textured = IntList()
        val plain = IntList()
        val atlas = texture?.let { BakePhoto(ORIGIN, it.width, it.height, it.atlas) }
        for (f in 0 until faces.count) {
            val chart = texture?.chart?.get(f) ?: -1
            val out = if (chart >= 0) textured else plain
            for (k in 0 until 3) {
                val corner = f * 3 + k
                val v = mesh.indices[corner]
                val key = (v.toLong() shl KEY_VERTEX_SHIFT) or ((chart + 1).toLong() shl KEY_CHART_SHIFT) or group[corner].toLong()
                val id = ids.getOrPut(key) {
                    positions.add(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])
                    val g = (topology.vertexStart[v] + group[corner]) * 3
                    normals.add(groupNormal[g], groupNormal[g + 1], groupNormal[g + 2])
                    if (texture != null && atlas != null && chart >= 0) {
                        val u = texture.uv[corner * 2]
                        val w = texture.uv[corner * 2 + 1]
                        uv.add(u, w)
                        colors.add(PhotoTexturing.sample(atlas, u * texture.width - HALF, w * texture.height - HALF))
                    } else {
                        uv.add(0f, 0f)
                        colors.add(mesh.colors[v])
                    }
                    colors.size - 1
                }
                out.add(id)
            }
        }
        return ModelMesh(
            positions = positions.toArray(),
            normals = normals.toArray(),
            colors = colors.toArray(),
            uv = if (texture != null && textured.size > 0) uv.toArray() else null,
            texturedIndices = textured.toArray(),
            plainIndices = plain.toArray(),
            texture = texture?.takeIf { textured.size > 0 },
        )
    }

    /**
     * Each corner's smoothing group at its vertex, and each group's normal at
     * `(vertexStart[v] + group) * 3`: a vertex's faces are gathered, largest first, into groups
     * whose normals stay within [CREASE_DEGREES] of the group's.
     */
    private fun creases(mesh: WorkMesh, faces: FaceGeometry, topology: MeshTopology, group: IntArray, normal: FloatArray) {
        val limit = kotlin.math.cos(Math.toRadians(CREASE_DEGREES)).toFloat()
        for (v in 0 until mesh.vertexCount) {
            val start = topology.vertexStart[v]
            val end = topology.vertexStart[v + 1]
            if (start == end) continue
            val incident = (start until end).map { topology.vertexTriangles[it] }.distinct()
                .sortedByDescending { faces.areas[it] }
            var groups = 0
            for (t in incident) {
                var chosen = -1
                for (g in 0 until groups) {
                    val o = (start + g) * 3
                    val length = sqrt(normal[o] * normal[o] + normal[o + 1] * normal[o + 1] + normal[o + 2] * normal[o + 2])
                    if (length <= 0f) continue
                    val cos = (normal[o] * faces.normals[t * 3] + normal[o + 1] * faces.normals[t * 3 + 1] +
                        normal[o + 2] * faces.normals[t * 3 + 2]) / length
                    if (cos >= limit) {
                        chosen = g
                        break
                    }
                }
                if (chosen < 0) chosen = groups++
                check(chosen < 1 shl KEY_CHART_SHIFT) { "vertex $v: more smoothing groups than its key holds" }
                val o = (start + chosen) * 3
                val area = faces.areas[t]
                normal[o] += faces.normals[t * 3] * area
                normal[o + 1] += faces.normals[t * 3 + 1] * area
                normal[o + 2] += faces.normals[t * 3 + 2] * area
                for (k in 0 until 3) if (mesh.indices[t * 3 + k] == v) group[t * 3 + k] = chosen
            }
            for (g in 0 until groups) {
                val o = (start + g) * 3
                val length = sqrt(normal[o] * normal[o] + normal[o + 1] * normal[o + 1] + normal[o + 2] * normal[o + 2])
                if (length > 0f) {
                    normal[o] /= length
                    normal[o + 1] /= length
                    normal[o + 2] /= length
                } else {
                    normal[o + 1] = 1f
                }
            }
        }
    }

    /** Wall to wall is 90°, a sofa's rounded arm a few degrees a face. */
    private const val CREASE_DEGREES = 40.0
    private const val KEY_VERTEX_SHIFT = 32
    private const val KEY_CHART_SHIFT = 8
    private const val HALF = 0.5f
    private val ORIGIN = DebugPose(0f, 0f, 0f)
}

/** A growable primitive list: a room's model is a few hundred thousand vertices. */
internal class FloatList(capacity: Int = 1024) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add(vararg values: Float) {
        if (size + values.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + values.size))
        values.copyInto(data, size)
        size += values.size
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

internal class IntList(capacity: Int = 1024) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(i: Int): Int = data[i]

    /** Drops everything added after the first [length] values. */
    fun truncate(length: Int) {
        size = length
    }

    fun toArray(): IntArray = data.copyOf(size)
}
