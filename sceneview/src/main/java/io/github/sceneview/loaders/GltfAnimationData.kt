package io.github.sceneview.loaders

import com.google.android.filament.Box
import io.github.sceneview.model.AnimationEnvelope
import org.json.JSONObject
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Compact, detached animation inputs. No JSON, buffer, vertices, inverse binds or Filament calls
 * survive [read]. Joint origins already live in model space through the hierarchy; inverse binds
 * are needed for vertices, not for joint positions. Flesh is estimated at sampling time.
 */
internal class GltfAnimationData private constructor(
    private val nodes: List<Node>,
    private val order: IntArray,
    private val joints: IntArray,
    private val clips: List<Clip>,
) {
    private class Node(
        val parent: Int, val translation: FloatArray, val rotation: FloatArray,
        val scale: FloatArray, val matrix: DoubleArray?,
    )
    private class Clip(val name: String, val tracks: List<Track>, val duration: Float)
    private class Track(
        val node: Int, val path: String, val interpolation: String,
        val times: FloatArray, val values: FloatArray,
    ) {
        val width = if (path == "rotation") 4 else 3

        fun value(time: Float): FloatArray {
            val found = times.binarySearch(time)
            val left = (if (found >= 0) found else -found - 2).coerceIn(times.indices)
            val right = (left + 1).coerceAtMost(times.lastIndex)
            val cubic = interpolation == "CUBICSPLINE"
            fun at(key: Int, part: Int = 1, c: Int) =
                values[(if (cubic) key * 3 + part else key) * width + c]
            val dt = times[right] - times[left]
            val t = if (dt > 0f) ((time - times[left]) / dt).coerceIn(0f, 1f) else 0f
            val a = FloatArray(width) { at(left, c = it) }
            val b = FloatArray(width) { at(right, c = it) }
            val out = when {
                interpolation == "STEP" || left == right || time <= times.first() -> a
                cubic -> FloatArray(width) { c ->
                    val t2 = t * t
                    val t3 = t2 * t
                    (2 * t3 - 3 * t2 + 1) * a[c] + (t3 - 2 * t2 + t) * dt * at(left, 2, c) +
                        (-2 * t3 + 3 * t2) * b[c] + (t3 - t2) * dt * at(right, 0, c)
                }
                path == "rotation" -> {
                    var dot = a.indices.sumOf { (a[it] * b[it]).toDouble() }
                    if (dot < 0.0) { for (c in b.indices) b[c] = -b[c]; dot = -dot }
                    val angle = acos(dot.coerceIn(0.0, 1.0))
                    if (dot > 0.9995) FloatArray(width) { a[it] + t * (b[it] - a[it]) }
                    else FloatArray(width) {
                        ((sin((1 - t) * angle) * a[it] + sin(t * angle) * b[it]) / sin(angle)).toFloat()
                    }
                }
                else -> FloatArray(width) { a[it] + t * (b[it] - a[it]) }
            }
            if (path == "rotation") {
                val length = sqrt(out.sumOf { it.toDouble() * it })
                require(length.isFinite() && length > 0.0)
                for (c in out.indices) out[c] = (out[c] / length).toFloat()
            }
            require(out.all { it.isFinite() })
            return out
        }
    }

    fun clipIndex(name: String): Int = clips.indexOfFirst { it.name == name }

    /** Exact retained primitive-array payload, excluding object headers, references and strings. */
    val retainedArrayBytes: Int
        get() {
            val arrays = clips.flatMap { it.tracks }.flatMap { listOf(it.times, it.values) }.toSet()
            return arrays.sumOf { it.size * 4 } + order.size * 4 + joints.size * 4 +
                nodes.sumOf { (it.translation.size + it.rotation.size + it.scale.size) * 4 + (it.matrix?.size ?: 0) * 8 }
        }

    /** Joint origins for a particular clip/time, available to analytic JVM tests. */
    internal fun jointPositions(clip: Int, time: Float): List<FloatArray> =
        positions(clips.getOrNull(clip), time)

    private fun positions(clip: Clip?, time: Float): List<FloatArray> {
        val translations = nodes.map { it.translation }.toTypedArray()
        val rotations = nodes.map { it.rotation }.toTypedArray()
        val scales = nodes.map { it.scale }.toTypedArray()
        clip?.tracks?.forEach { track ->
            val value = track.value(time)
            when (track.path) {
                "translation" -> translations[track.node] = value
                "rotation" -> rotations[track.node] = value
                "scale" -> scales[track.node] = value
            }
        }
        val world = arrayOfNulls<DoubleArray>(nodes.size)
        for (index in order) {
            val node = nodes[index]
            val local = node.matrix ?: trs(translations[index], rotations[index], scales[index])
            world[index] = if (node.parent < 0) local else multiply(world[node.parent]!!, local)
        }
        return joints.map { index -> FloatArray(3) { world[index]!![12 + it].toFloat() }.also { p ->
            require(p.all { it.isFinite() })
        } }
    }

    fun sample(clip: Int, samples: Int, rest: Box): AnimationEnvelope? {
        val animation = clips.getOrNull(clip)?.takeIf { it.tracks.isNotEmpty() } ?: return null
        val restJoints = extent(positions(null, 0f))
        val center = rest.center
        val half = rest.halfExtent
        val lowMargin = FloatArray(3) { maxOf(0f, restJoints[it] - (center[it] - half[it])) }
        val highMargin = FloatArray(3) { maxOf(0f, center[it] + half[it] - restJoints[3 + it]) }
        val count = samples.coerceIn(2, 120)
        return AnimationEnvelope(listOf(rest) + List(count) { sample ->
            val box = extent(positions(animation, animation.duration * sample / (count - 1)))
            for (axis in 0..2) { box[axis] -= lowMargin[axis]; box[axis + 3] += highMargin[axis] }
            Box(
                (box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2,
                (box[3] - box[0]) / 2, (box[4] - box[1]) / 2, (box[5] - box[2]) / 2,
            )
        })
    }

    companion object {
        /** Bounded preparation, copying only transform and animation inputs while buffers exist. */
        fun read(json: JSONObject, readAccessor: (Int) -> FloatArray?): GltfAnimationData? {
            val skins = json.optJSONArray("skins") ?: return null
            val animations = json.optJSONArray("animations")?.takeIf { it.length() > 0 } ?: return null
            val source = json.optJSONArray("nodes") ?: return null
            require(source.length() in 1..4096 && animations.length() <= 256)
            val joints = linkedSetOf<Int>()
            for (n in 0 until source.length()) {
                val node = source.getJSONObject(n)
                if (!node.has("mesh") || !node.has("skin")) continue
                val skinJoints = skins.getJSONObject(node.getInt("skin")).getJSONArray("joints")
                for (j in 0 until skinJoints.length()) joints += skinJoints.getInt(j).also {
                    require(it in 0 until source.length())
                }
            }
            if (joints.isEmpty()) return null
            val parents = IntArray(source.length()) { -1 }
            for (n in parents.indices) {
                val children = source.getJSONObject(n).optJSONArray("children") ?: continue
                for (c in 0 until children.length()) {
                    val child = children.getInt(c)
                    require(child in parents.indices && child != n && parents[child] == -1)
                    parents[child] = n
                }
            }
            // Only joint ancestors can affect their origins. Parent-first order, without recursion.
            val order = linkedSetOf<Int>()
            for (joint in joints) {
                val chain = mutableListOf<Int>()
                var at = joint
                while (at >= 0 && at !in order) {
                    require(chain.size < source.length()) { "Cyclic glTF hierarchy" }
                    chain += at
                    at = parents[at]
                }
                order.addAll(chain.asReversed())
            }
            val remap = order.withIndex().associate { it.value to it.index }
            val nodes = order.map { n ->
                val node = source.getJSONObject(n)
                Node(
                    remap[parents[n]] ?: -1,
                    node.vector("translation", floatArrayOf(0f, 0f, 0f)),
                    node.vector("rotation", floatArrayOf(0f, 0f, 0f, 1f)),
                    node.vector("scale", floatArrayOf(1f, 1f, 1f)),
                    node.optJSONArray("matrix")?.let { m ->
                        require(m.length() == 16)
                        DoubleArray(16) { m.getDouble(it).also { v -> require(v.isFinite()) } }
                    },
                )
            }
            val accessors = json.getJSONArray("accessors")
            val arrays = mutableMapOf<Int, FloatArray>()
            var components = 0L
            fun accessor(index: Int, type: String): FloatArray {
                val item = accessors.getJSONObject(index)
                require(item.getString("type") == type && item.getInt("componentType") == 5126)
                return arrays.getOrPut(index) {
                    val width = if (type == "SCALAR") 1 else if (type == "VEC3") 3 else 4
                    components += item.getLong("count") * width
                    require(components in 1..1_048_576) // At most 4 MiB of keyframe payload per model.
                    requireNotNull(readAccessor(index)).also { values ->
                        require(values.size.toLong() == item.getLong("count") * width && values.all { it.isFinite() })
                    }
                }
            }
            val clips = List(animations.length()) { a ->
                val animation = animations.getJSONObject(a)
                val samplers = animation.getJSONArray("samplers")
                val channels = animation.getJSONArray("channels")
                var duration = 0f
                val tracks = mutableListOf<Track>()
                val targets = mutableSetOf<Pair<Int, String>>()
                for (c in 0 until channels.length()) {
                    val channel = channels.getJSONObject(c)
                    val target = channel.getJSONObject("target")
                    val sampler = samplers.getJSONObject(channel.getInt("sampler"))
                    val times = accessor(sampler.getInt("input"), "SCALAR")
                    require(times.isNotEmpty() && times.first() >= 0f)
                    require((1 until times.size).all { times[it] > times[it - 1] })
                    duration = maxOf(duration, times.last())
                    val node = remap[target.optInt("node", -1)] ?: continue
                    val path = target.getString("path")
                    if (path == "weights") continue // Morph displacement is outside the joint heuristic.
                    require(path in listOf("translation", "rotation", "scale") && nodes[node].matrix == null)
                    require(targets.add(node to path))
                    val interpolation = sampler.optString("interpolation", "LINEAR")
                    require(interpolation in listOf("LINEAR", "STEP", "CUBICSPLINE"))
                    val values = accessor(sampler.getInt("output"), if (path == "rotation") "VEC4" else "VEC3")
                    val width = if (path == "rotation") 4 else 3
                    require(values.size == times.size * width * if (interpolation == "CUBICSPLINE") 3 else 1)
                    tracks += Track(node, path, interpolation, times, values)
                }
                Clip(animation.optString("name"), tracks, duration)
            }
            return GltfAnimationData(nodes, IntArray(nodes.size) { it }, joints.map { remap.getValue(it) }.toIntArray(), clips)
        }

        private fun JSONObject.vector(key: String, default: FloatArray): FloatArray {
            val value = optJSONArray(key) ?: return default
            require(value.length() == default.size)
            return FloatArray(default.size) { value.getDouble(it).toFloat().also { v -> require(v.isFinite()) } }
        }

        private fun extent(points: List<FloatArray>) = FloatArray(6) { c ->
            if (c < 3) points.minOf { it[c] } else points.maxOf { it[c - 3] }
        }

        private fun trs(t: FloatArray, r: FloatArray, s: FloatArray): DoubleArray {
            val x = r[0].toDouble(); val y = r[1].toDouble(); val z = r[2].toDouble(); val w = r[3].toDouble()
            return doubleArrayOf(
                (1 - 2 * (y * y + z * z)) * s[0], 2 * (x * y + z * w) * s[0], 2 * (x * z - y * w) * s[0], 0.0,
                2 * (x * y - z * w) * s[1], (1 - 2 * (x * x + z * z)) * s[1], 2 * (y * z + x * w) * s[1], 0.0,
                2 * (x * z + y * w) * s[2], 2 * (y * z - x * w) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0.0,
                t[0].toDouble(), t[1].toDouble(), t[2].toDouble(), 1.0,
            )
        }

        private fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(16) { i ->
            (0..3).sumOf { k -> a[k * 4 + i % 4] * b[i / 4 * 4 + k] }
        }
    }
}
