package io.github.sceneview.loaders

import org.json.JSONArray
import org.json.JSONObject

/**
 * The rest-pose bounds of a skinned glTF, skinning included (#4949).
 *
 * gltfio's `FilamentAsset.getBoundingBox()` is the POSITION accessors' min / max moved by each mesh
 * node's world transform alone: a skinned mesh is drawn through its joints instead, so a rig whose
 * mesh is authored in centimetres under an armature scaled by 0.01 (the usual Mixamo or Blender
 * export) gets bounds a hundred times too large. `FilamentInstance.recomputeBoundingBoxes()` is
 * not bound in gltfio's Java API, and would need the source data SceneView releases once loaded.
 *
 * This walks the glTF itself: each vertex of a skinned primitive is moved by
 * `Σ weightₖ · world(jointₖ) · inverseBindMatrixₖ`, the matrix Filament's skinning applies. Where
 * the vertices cannot be read (Draco, sparse or meshopt data), the primitive's min / max box moved
 * by each of its skin's joint matrices stands in, which still contains it. A primitive without a
 * skin is moved by its node's world transform, as gltfio does. Morph targets are ignored, as they
 * are by gltfio's box.
 *
 * Filament-free and JVM-testable, like [GltfLimits].
 */
internal object GltfRestPose {

    /**
     * `[minX, minY, minZ, maxX, maxY, maxZ]` in the asset's space, or `null` when the glTF has no
     * skinned mesh (gltfio's box is then exact) or some primitive's extent cannot be told.
     *
     * @param read accessor `index`'s components as floats, normalised when it says so; `null` when
     * its data is not at hand.
     */
    fun bounds(json: JSONObject, read: (index: Int) -> FloatArray?): FloatArray? {
        val nodes = json.optJSONArray("nodes") ?: return null
        val meshes = json.optJSONArray("meshes") ?: return null
        val accessors = json.optJSONArray("accessors") ?: return null
        val skins = json.optJSONArray("skins")
        val world = WorldTransforms(nodes)
        val bounds = Bounds()
        var skinned = false
        for (node in world.instantiated(json.optJSONArray("scenes"))) {
            val item = nodes.optJSONObject(node) ?: continue
            val mesh = meshes.optJSONObject(item.optInt("mesh", -1)) ?: continue
            val skin = skins?.optJSONObject(item.optInt("skin", -1))
            val jointMatrices = skin?.let { jointMatrices(it, world, read) ?: return null }
            if (jointMatrices != null && jointMatrices.isNotEmpty()) skinned = true
            val primitives = mesh.optJSONArray("primitives") ?: continue
            for (p in 0 until primitives.length()) {
                val attributes = primitives.optJSONObject(p)?.optJSONObject("attributes") ?: continue
                val position = attributes.optInt("POSITION", -1).takeIf { it >= 0 } ?: continue
                val included = if (jointMatrices.isNullOrEmpty()) {
                    val box = extent(accessors.optJSONObject(position), position, read) ?: return null
                    bounds.addBox(world[node], box)
                    true
                } else {
                    addSkinned(attributes, position, jointMatrices, accessors, read, bounds)
                }
                if (!included) return null
            }
        }
        return if (skinned) bounds.result() else null
    }

    /** `world(jointⱼ) · inverseBindMatrixⱼ` for each joint of [skin]; `null` when malformed. */
    private fun jointMatrices(skin: JSONObject, world: WorldTransforms, read: (Int) -> FloatArray?): List<DoubleArray>? {
        val joints = skin.optJSONArray("joints") ?: return emptyList()
        val inverseBinds = skin.optInt("inverseBindMatrices", -1).takeIf { it >= 0 }?.let { read(it) ?: return null }
        if (inverseBinds != null && inverseBinds.size < joints.length() * 16) return null
        return List(joints.length()) { j ->
            val joint = joints.optInt(j, -1).takeIf { it in 0 until world.count } ?: return null
            val inverseBind = inverseBinds?.let { m -> DoubleArray(16) { m[j * 16 + it].toDouble() } } ?: IDENTITY
            multiply(world[joint], inverseBind)
        }
    }

    /**
     * Adds the skinned primitive's vertices to [bounds], or its min / max box moved by each joint
     * matrix when they cannot be read. `false` when neither is known.
     */
    @Suppress("LongParameterList")
    private fun addSkinned(
        attributes: JSONObject,
        position: Int,
        jointMatrices: List<DoubleArray>,
        accessors: JSONArray,
        read: (Int) -> FloatArray?,
        bounds: Bounds,
    ): Boolean {
        val sets = generateSequence(0) { it + 1 }
            .takeWhile { attributes.has("JOINTS_$it") }
            .map { attributes.optInt("JOINTS_$it", -1) to attributes.optInt("WEIGHTS_$it", -1) }
            .toList()
        val vertices = read(position)
        val influences = sets.map { (joints, weights) -> (read(joints) ?: return@map null) to (read(weights) ?: return@map null) }
        val count = vertices?.size?.div(3) ?: 0
        val readable = vertices != null && sets.isNotEmpty() &&
            influences.all { it != null && it.first.size >= count * 4 && it.second.size >= count * 4 }
        if (!readable) {
            // Every vertex lies in the box, and is moved by a weighted mean of these matrices.
            val box = extent(accessors.optJSONObject(position), position, read) ?: return false
            jointMatrices.forEach { bounds.addBox(it, box) }
            return true
        }
        val point = DoubleArray(3)
        for (v in 0 until count) {
            var total = 0.0
            point.fill(0.0)
            for (influence in influences) {
                val (joints, weights) = influence!!
                for (k in 0 until 4) {
                    val weight = weights[v * 4 + k].toDouble()
                    val matrix = jointMatrices.getOrNull(joints[v * 4 + k].toInt())
                    if (weight <= 0.0 || matrix == null) continue
                    total += weight
                    for (axis in 0 until 3) {
                        point[axis] += weight * transform(matrix, vertices!!, v * 3, axis)
                    }
                }
            }
            // Weights are normalised on load; a vertex with none collapses and draws nothing.
            if (total > 0.0) bounds.add(point[0] / total, point[1] / total, point[2] / total)
        }
        return true
    }

    /** POSITION accessor [index]'s min / max, read from its data when the JSON omits them. */
    private fun extent(accessor: JSONObject?, index: Int, read: (Int) -> FloatArray?): DoubleArray? {
        val min = accessor?.optJSONArray("min")
        val max = accessor?.optJSONArray("max")
        if (min != null && max != null && min.length() >= 3 && max.length() >= 3) {
            return DoubleArray(6) { if (it < 3) min.optDouble(it) else max.optDouble(it - 3) }
                .takeIf { box -> box.all { it.isFinite() } }
        }
        val vertices = read(index)?.takeIf { it.size >= 3 } ?: return null
        val box = Bounds()
        for (v in 0 until vertices.size / 3) {
            box.add(vertices[v * 3].toDouble(), vertices[v * 3 + 1].toDouble(), vertices[v * 3 + 2].toDouble())
        }
        return box.result()?.let { r -> DoubleArray(6) { r[it].toDouble() } }
    }

    /** Each node's world transform, from its `matrix` or its TRS and its parents'. */
    private class WorldTransforms(private val nodes: JSONArray) {
        val count = nodes.length()
        private val parents = IntArray(count) { -1 }.also { parents ->
            for (node in 0 until count) {
                val children = nodes.optJSONObject(node)?.optJSONArray("children") ?: continue
                for (c in 0 until children.length()) {
                    val child = children.optInt(c, -1)
                    if (child in 0 until count && child != node) parents[child] = node
                }
            }
        }
        private val cache = arrayOfNulls<DoubleArray>(count)

        operator fun get(node: Int): DoubleArray {
            cache[node]?.let { return it }
            // Walked up iteratively: a malformed parent cycle stops after `count` steps.
            val chain = ArrayList<Int>()
            var at = node
            while (at >= 0 && cache[at] == null && chain.size <= count) {
                chain += at
                at = parents[at]
            }
            check(chain.size <= count) { "the glTF nodes form a cycle" }
            var matrix = if (at >= 0) cache[at]!! else IDENTITY
            for (n in chain.asReversed()) {
                matrix = multiply(matrix, local(nodes.optJSONObject(n)))
                cache[n] = matrix
            }
            return matrix
        }

        /** The nodes gltfio instantiates: those of every scene, or every root's tree with none. */
        fun instantiated(scenes: JSONArray?): Set<Int> {
            val roots = buildList {
                if (scenes != null && scenes.length() > 0) {
                    for (s in 0 until scenes.length()) {
                        val sceneNodes = scenes.optJSONObject(s)?.optJSONArray("nodes") ?: continue
                        for (n in 0 until sceneNodes.length()) add(sceneNodes.optInt(n, -1))
                    }
                } else {
                    for (n in 0 until count) if (parents[n] < 0) add(n)
                }
            }
            val visited = LinkedHashSet<Int>()
            val stack = ArrayDeque(roots.filter { it in 0 until count })
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (!visited.add(node)) continue
                val children = nodes.optJSONObject(node)?.optJSONArray("children") ?: continue
                for (c in 0 until children.length()) children.optInt(c, -1).takeIf { it in 0 until count }?.let(stack::addLast)
            }
            return visited
        }
    }

    /** A running axis-aligned box. */
    private class Bounds {
        private val box = doubleArrayOf(
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
        )

        fun add(x: Double, y: Double, z: Double) {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
            box[0] = minOf(box[0], x); box[1] = minOf(box[1], y); box[2] = minOf(box[2], z)
            box[3] = maxOf(box[3], x); box[4] = maxOf(box[4], y); box[5] = maxOf(box[5], z)
        }

        /** The 8 corners of [extent] (`[min, max]`) moved by [matrix]. */
        fun addBox(matrix: DoubleArray, extent: DoubleArray) {
            val corner = DoubleArray(3)
            for (i in 0 until 8) {
                corner[0] = if (i and 1 == 0) extent[0] else extent[3]
                corner[1] = if (i and 2 == 0) extent[1] else extent[4]
                corner[2] = if (i and 4 == 0) extent[2] else extent[5]
                add(transform(matrix, corner, 0, 0), transform(matrix, corner, 0, 1), transform(matrix, corner, 0, 2))
            }
        }

        fun result(): FloatArray? = if (box[0] <= box[3]) FloatArray(6) { box[it].toFloat() } else null
    }

    private val IDENTITY = DoubleArray(16) { if (it % 5 == 0) 1.0 else 0.0 }

    /** [node]'s local matrix, column-major: its `matrix`, else `T · R · S`. */
    private fun local(node: JSONObject?): DoubleArray {
        node ?: return IDENTITY
        node.optJSONArray("matrix")?.takeIf { it.length() == 16 }?.let { m -> return DoubleArray(16) { m.optDouble(it, 0.0) } }
        val t = node.optJSONArray("translation")
        val r = node.optJSONArray("rotation")
        val s = node.optJSONArray("scale")
        val (tx, ty, tz) = List(3) { t?.optDouble(it, 0.0) ?: 0.0 }
        val (sx, sy, sz) = List(3) { s?.optDouble(it, 1.0) ?: 1.0 }
        val (x, y, z, w) = List(4) { r?.optDouble(it, if (it == 3) 1.0 else 0.0) ?: if (it == 3) 1.0 else 0.0 }
        return doubleArrayOf(
            (1 - 2 * (y * y + z * z)) * sx, 2 * (x * y + z * w) * sx, 2 * (x * z - y * w) * sx, 0.0,
            2 * (x * y - z * w) * sy, (1 - 2 * (x * x + z * z)) * sy, 2 * (y * z + x * w) * sy, 0.0,
            2 * (x * z + y * w) * sz, 2 * (y * z - x * w) * sz, (1 - 2 * (x * x + y * y)) * sz, 0.0,
            tx, ty, tz, 1.0,
        )
    }

    /** `a · b`, both column-major. */
    private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { i ->
        val column = i / 4
        val row = i % 4
        (0 until 4).sumOf { k -> a[k * 4 + row] * b[column * 4 + k] }
    }

    /** Component [axis] of point `p[at..at+2]` moved by column-major [m]. */
    private fun transform(m: DoubleArray, p: FloatArray, at: Int, axis: Int) =
        m[axis] * p[at] + m[4 + axis] * p[at + 1] + m[8 + axis] * p[at + 2] + m[12 + axis]

    private fun transform(m: DoubleArray, p: DoubleArray, at: Int, axis: Int) =
        m[axis] * p[at] + m[4 + axis] * p[at + 1] + m[8 + axis] * p[at + 2] + m[12 + axis]
}
