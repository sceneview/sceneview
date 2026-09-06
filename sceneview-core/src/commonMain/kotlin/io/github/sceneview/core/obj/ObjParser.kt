package io.github.sceneview.core.obj

import io.github.sceneview.core.threemf.IntArrayBuilder
import io.github.sceneview.core.threemf.ThreeMfUnit
import kotlin.math.sqrt

internal object ObjParser {
    fun parse(bytes: ByteArray, unit: ThreeMfUnit, resolver: ((String) -> ByteArray?)?): ObjModel {
        val state = State()
        objLines(bytes) { tokens, line -> state.read(tokens, line) }
        if (state.groups.isEmpty()) objError("OBJ contains no faces")
        val materials = LinkedHashMap<String, ObjMaterial>()
        if (resolver != null) {
            for (path in state.libraries) {
                resolver(path)?.let { ObjMtl.read(it, materials) }
            }
        }
        return ObjModel(unit, state.finish(), materials)
    }

    private class State {
        val positions = ObjFloats()
        val normals = ObjFloats()
        val uv = ObjFloats()
        val groups = LinkedHashMap<Pair<String, String>, Group>()
        val libraries = LinkedHashSet<String>()
        private var objectName = ""
        private var groupName = ""
        private var material: String? = null

        fun read(tokens: ObjTokens, line: Int) {
            when (tokens.next()) {
                "v" -> repeat(3) { positions.add(tokens.number(line)) }
                "vn" -> repeat(3) { normals.add(tokens.number(line)) }
                "vt" -> {
                    uv.add(tokens.number(line))
                    uv.add(tokens.next()?.let { number(it, line) } ?: 0f)
                    // OBJ permits a third texture coordinate; GLB uses only u/v.
                }
                "o" -> { objectName = tokens.rest(); groupName = "" }
                "g" -> groupName = tokens.rest()
                "usemtl" -> material = tokens.rest().ifEmpty { null }
                "mtllib" -> {
                    var path = tokens.next(quoted = true)
                    while (path != null) {
                        libraries += path.replace('\\', '/')
                        path = tokens.next(quoted = true)
                    }
                }
                "f" -> face(tokens, line)
            }
        }

        private fun face(tokens: ObjTokens, line: Int) {
            val corners = ArrayList<Corner>()
            var token = tokens.next()
            while (token != null) {
                corners += corner(token, line)
                token = tokens.next()
            }
            if (corners.size > 3 && corners.first().position == corners.last().position) {
                corners.removeAt(corners.lastIndex)
            }
            if (corners.size < 3) objError("OBJ line $line: a face needs at least three vertices")
            val group = groups.getOrPut(objectName to groupName) {
                Group(listOf(objectName, groupName).filter { it.isNotEmpty() }.joinToString("/").ifEmpty { "default" })
            }
            val triangles = ObjTriangulation.triangulate(corners.map { it.position }, positions, line)
            for (index in triangles) {
                val corner = corners[index]
                group.corners.add(corner.position)
                group.corners.add(corner.texture)
                group.corners.add(corner.normal)
            }
            repeat(triangles.size / 3) { group.materials += material }
        }

        private fun corner(token: String, line: Int): Corner {
            val first = token.indexOf('/')
            if (first < 0) return Corner(index(token, positions.size / 3, line), -1, -1)
            val second = token.indexOf('/', first + 1)
            if (second >= 0 && token.indexOf('/', second + 1) >= 0) objError("OBJ line $line: invalid face '$token'")
            val vertex = index(token.substring(0, first), positions.size / 3, line)
            val texture = token.substring(first + 1, if (second < 0) token.length else second)
            val normal = if (second < 0) "" else token.substring(second + 1)
            if ((second < 0 && texture.isEmpty()) || (second >= 0 && normal.isEmpty())) {
                objError("OBJ line $line: incomplete face index '$token'")
            }
            return Corner(
                vertex,
                if (texture.isEmpty()) -1 else index(texture, uv.size / 2, line),
                if (normal.isEmpty()) -1 else index(normal, normals.size / 3, line)
            )
        }

        /** Resolve each index against the corresponding table as it exists at this face. */
        private fun index(token: String, count: Int, line: Int): Int {
            val value = token.toIntOrNull() ?: objError("OBJ line $line: invalid index '$token'")
            val resolved = if (value > 0) value - 1 else count + value
            if (value == 0 || resolved !in 0 until count) objError("OBJ line $line: index $value out of range ($count)")
            return resolved
        }

        fun finish(): List<ObjGroup> {
            val packed = groups.values.map { it to it.corners.toArray() }
            // Area-weighted sums in double precision avoid overflow on large but finite positions.
            val smooth = DoubleArray(positions.size)
            for ((_, corners) in packed) {
                for (at in corners.indices step 9) accumulate(corners, at, smooth)
            }
            return packed.map { (group, corners) -> expand(group, corners, smooth) }
        }

        private fun accumulate(corners: IntArray, at: Int, smooth: DoubleArray) {
            val a = corners[at] * 3
            val b = corners[at + 3] * 3
            val c = corners[at + 6] * 3
            val ux = positions[b].toDouble() - positions[a]
            val uy = positions[b + 1].toDouble() - positions[a + 1]
            val uz = positions[b + 2].toDouble() - positions[a + 2]
            val vx = positions[c].toDouble() - positions[a]
            val vy = positions[c + 1].toDouble() - positions[a + 1]
            val vz = positions[c + 2].toDouble() - positions[a + 2]
            for (corner in 0 until 3) {
                val offset = corners[at + corner * 3] * 3
                smooth[offset] += uy * vz - uz * vy
                smooth[offset + 1] += uz * vx - ux * vz
                smooth[offset + 2] += ux * vy - uy * vx
            }
        }

        private fun expand(group: Group, corners: IntArray, smooth: DoubleArray): ObjGroup {
            val outPositions = FloatArray(corners.size)
            val outNormals = FloatArray(corners.size)
            val outUv = if ((1 until corners.size step 3).any { corners[it] >= 0 }) {
                FloatArray(corners.size / 3 * 2)
            } else null
            for (at in corners.indices step 3) {
                val vertex = corners[at] * 3
                val normal = corners[at + 2] * 3
                repeat(3) { outPositions[at + it] = positions[vertex + it] }
                val x = if (normal >= 0) normals[normal].toDouble() else smooth[vertex]
                val y = if (normal >= 0) normals[normal + 1].toDouble() else smooth[vertex + 1]
                val z = if (normal >= 0) normals[normal + 2].toDouble() else smooth[vertex + 2]
                val length = sqrt(x * x + y * y + z * z)
                outNormals[at] = if (length > 0.0) (x / length).toFloat() else 0f
                outNormals[at + 1] = if (length > 0.0) (y / length).toFloat() else 1f
                outNormals[at + 2] = if (length > 0.0) (z / length).toFloat() else 0f
                val texture = corners[at + 1] * 2
                if (outUv != null && texture >= 0) {
                    outUv[at / 3 * 2] = uv[texture]
                    outUv[at / 3 * 2 + 1] = uv[texture + 1]
                }
            }
            return ObjGroup(group.name, outPositions, outNormals, outUv, group.materials)
        }
    }

    private class Group(val name: String) {
        val corners = IntArrayBuilder()
        val materials = ArrayList<String?>()
    }

    private data class Corner(val position: Int, val texture: Int, val normal: Int)
}
