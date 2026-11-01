package io.github.sceneview.demo.demos.internal

import kotlin.math.max
import kotlin.math.min

/**
 * The wireframe a reconstructed model is revealed through ([ModelReveal]): every edge of its
 * triangles once, as line-list indices into its own positions, **sorted from the floor up**. A
 * sweep up the model is then a prefix of the index list ([indicesBelow]) — drawn with no
 * re-upload, as the replay reveals a dense cloud as it grew (`DebugLayerNode.showIndices`).
 *
 * Pure, no Filament: plain arrays the JVM tests pin. Built off the main thread.
 */
class RevealWire private constructor(
    /** xyz per vertex: the model's own positions, shared, not copied. */
    val positions: FloatArray,
    val vertexCount: Int,
    /** Two indices per edge, ordered by the edge's lower end, lowest first. */
    val indices: IntArray,
    /** The lower end's height of each edge, in the same order: what [indicesBelow] searches. */
    private val edgeLow: FloatArray,
    /** Lowest and highest vertex height, metres. */
    val minY: Float,
    val maxY: Float,
) {
    val edgeCount: Int get() = edgeLow.size

    /**
     * How many of [indices] draw the edges whose lower end is at or under [fraction] of the
     * model's height (0: the floor, 1: the top): the swept part. Always even.
     */
    fun indicesBelow(fraction: Float): Int {
        if (edgeLow.isEmpty()) return 0
        val y = minY + (maxY - minY) * fraction.coerceIn(0f, 1f)
        // Upper bound: the first edge strictly above y.
        var lo = 0
        var hi = edgeLow.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (edgeLow[mid] <= y) lo = mid + 1 else hi = mid
        }
        return lo * 2
    }

    /** The edges as a line mesh for `DebugLayerNode` in `LINES` mode. */
    fun toMesh(): DebugMesh = DebugMesh(vertexCount).also { mesh ->
        for (v in 0 until vertexCount) mesh.vertex(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2])
        var i = 0
        while (i < indices.size) {
            mesh.segment(indices[i], indices[i + 1])
            i += 2
        }
    }

    companion object {
        /**
         * Above this many edges a model keeps one in every few, picked evenly through the sorted
         * list so the sweep stays whole: past it, a phone-width view is solid lines anyway, and
         * the upload would cost more than the reveal is long.
         */
        const val MAX_EDGES = 400_000

        /** How many triangles or edges go by between two [of] cancellation checks. */
        const val CHECK_EVERY = 65_536

        /**
         * The unique edges of [indices]' triangles over [positions] (xyz per vertex,
         * [vertexCount] of them), each once whichever triangle and winding it came from,
         * sorted by lower end. `null` when there is no triangle. [checkCancelled] is called every
         * [CHECK_EVERY] triangles or edges and before each sort, so a build nobody waits for any
         * more (the screen was left) stops by throwing from it.
         */
        fun of(
            positions: FloatArray,
            vertexCount: Int,
            indices: IntArray,
            maxEdges: Int = MAX_EDGES,
            checkCancelled: () -> Unit = {},
        ): RevealWire? {
            val triangles = indices.size / 3
            if (triangles == 0 || vertexCount == 0) return null
            // Each edge as one long, lower index high: sorted, the duplicates sit side by side.
            val keys = LongArray(triangles * 3)
            var k = 0
            for (t in 0 until triangles) {
                if (t % CHECK_EVERY == 0) checkCancelled()
                for (e in 0 until 3) {
                    val a = indices[t * 3 + e]
                    val b = indices[t * 3 + (e + 1) % 3]
                    if (a == b) continue
                    keys[k++] = (min(a, b).toLong() shl 32) or max(a, b).toLong()
                }
            }
            checkCancelled()
            keys.sort(0, k)
            checkCancelled()
            var unique = 0
            for (i in 0 until k) if (i == 0 || keys[i] != keys[i - 1]) keys[unique++] = keys[i]

            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (v in 0 until vertexCount) {
                val y = positions[v * 3 + 1]
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            // Sorted by lower end: the height above minY (never negative, so its float bits sort
            // as the float does) high, the edge's slot low.
            val order = LongArray(unique)
            for (i in 0 until unique) {
                if (i % CHECK_EVERY == 0) checkCancelled()
                val a = (keys[i] ushr 32).toInt()
                val b = (keys[i] and 0xFFFF_FFFFL).toInt()
                val low = min(positions[a * 3 + 1], positions[b * 3 + 1]) - minY
                order[i] = (java.lang.Float.floatToIntBits(max(low, 0f)).toLong() shl 32) or i.toLong()
            }
            checkCancelled()
            order.sort()
            checkCancelled()
            val step = if (unique > maxEdges) (unique + maxEdges - 1) / maxEdges else 1
            val kept = (unique + step - 1) / step
            val out = IntArray(kept * 2)
            val lows = FloatArray(kept)
            for (j in 0 until kept) {
                val slot = (order[j * step] and 0xFFFF_FFFFL).toInt()
                val a = (keys[slot] ushr 32).toInt()
                val b = (keys[slot] and 0xFFFF_FFFFL).toInt()
                out[j * 2] = a
                out[j * 2 + 1] = b
                lows[j] = min(positions[a * 3 + 1], positions[b * 3 + 1])
            }
            return RevealWire(positions, vertexCount, out, lows, minY, maxY)
        }
    }
}
