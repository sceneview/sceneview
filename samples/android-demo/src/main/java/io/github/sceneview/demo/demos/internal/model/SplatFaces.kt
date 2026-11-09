package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.core.splat.SplatCloud
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * [all]'s splats sorted by the way their surfel faces, so the Photo layer opens the room as the
 * model does. The model's material is single-sided: a surface seen from behind is not drawn, and
 * from outside the room that is its ceiling and the walls nearest the eye — a dollhouse. A splat
 * has no back, so the whole cloud draws those as broad bands across the room. Here the splats are
 * put in six faces — the room's two horizontal axes and up, each way — and a face is drawn only
 * while the eye stands on the side its surfels face ([visible]), as a back face is culled. The
 * horizontal axes are the room's own (its walls' mean direction, a quarter turn folded), so a room
 * scanned at any heading has its walls in one face each.
 *
 * A face's plane is where its wall stands: the [EDGE_SHARE] quantile of its splats along its axis
 * from the side it faces away from, so the furniture facing the same way does not pull it into the
 * room. Splats with no normal (round ones, `SurfelSplats` without normals) are always drawn.
 */
internal class SplatFaces private constructor(
    val all: SplatCloud,
    private val face: ByteArray,
    private val cosYaw: Float,
    private val sinYaw: Float,
    private val planes: FloatArray,
) {
    /** The faces drawn from the eye at ([x], [y], [z]), one bit per face, [ROUND] always set. */
    fun visible(x: Float, y: Float, z: Float): Int {
        val u = x * cosYaw + z * sinYaw
        val v = -x * sinYaw + z * cosYaw
        var mask = 1 shl ROUND
        for (f in 0 until FACES) {
            val at = when (f / 2) {
                0 -> u
                1 -> y
                else -> v
            }
            // Even faces look along their axis (+), odd ones against it (−).
            val seen = if (f % 2 == 0) at > planes[f] else at < planes[f]
            if (seen) mask = mask or (1 shl f)
        }
        return mask
    }

    /**
     * [visible] from the faces [was] drawn the frame before: a face comes or goes only once the eye
     * is [HYSTERESIS_M] past its plane, so an eye that hovers on a plane (an orbit at the ceiling's
     * height, a finger resting mid-drag) does not swap the splats back and forth every frame —
     * each swap is a new sort with a new filter (#4896).
     */
    fun visible(x: Float, y: Float, z: Float, was: Int): Int {
        val now = visible(x, y, z)
        if (now == was) return now
        val u = x * cosYaw + z * sinYaw
        val v = -x * sinYaw + z * cosYaw
        var mask = now
        for (f in 0 until FACES) {
            val bit = 1 shl f
            if ((now xor was) and bit == 0) continue
            val at = when (f / 2) {
                0 -> u
                1 -> y
                else -> v
            }
            // Too close to the plane to call: the face stays as it was.
            if (kotlin.math.abs(at - planes[f]) < HYSTERESIS_M) mask = (mask and bit.inv()) or (was and bit)
        }
        return mask
    }

    /**
     * Which of [all]'s splats are in the faces of [mask], as `SplatNode.shownSplats` reads it;
     * `null` when every splat is. O(splats), off the main thread.
     */
    fun shown(mask: Int): BooleanArray? {
        val shown = BooleanArray(all.count) { mask and (1 shl face[it].toInt()) != 0 }
        return if (shown.all { it }) null else shown
    }

    /** [all]'s splats in the faces of [mask], in their order; [all] itself when every face is in. */
    fun cloud(mask: Int): SplatCloud {
        val n = face.count { mask and (1 shl it.toInt()) != 0 }
        if (n == all.count) return all
        val positions = FloatArray(n * 3)
        val scales = FloatArray(n * 3)
        val rotations = FloatArray(n * 4)
        val colors = FloatArray(n * 3)
        val opacities = FloatArray(n)
        var o = 0
        for (i in 0 until all.count) {
            if (mask and (1 shl face[i].toInt()) == 0) continue
            all.positions.copyInto(positions, o * 3, i * 3, i * 3 + 3)
            all.scales.copyInto(scales, o * 3, i * 3, i * 3 + 3)
            all.rotations.copyInto(rotations, o * 4, i * 4, i * 4 + 4)
            all.colors.copyInto(colors, o * 3, i * 3, i * 3 + 3)
            opacities[o] = all.opacities[i]
            o++
        }
        return SplatCloud(n, positions, scales, rotations, colors, opacities)
    }

    companion object {
        /** The six faces: +U, −U, +Y, −Y, +V, −V. */
        const val FACES = 6

        /** The face of the splats with no normal, drawn from everywhere. */
        const val ROUND = 6

        /** Every face. */
        const val ALL = (1 shl (FACES + 1)) - 1

        /** Where a face's plane stands among its splats, from the side it faces away from. */
        const val EDGE_SHARE = 0.1f

        /** How far past a face's plane the eye goes before the face comes or goes, metres. */
        const val HYSTERESIS_M = 0.1f

        /** A normal steeper than this (|y|) counts towards the floor or the ceiling, not a wall. */
        private const val WALL_MAX_Y = 0.5f

        /** [splats]' faces. Pure Kotlin: off the main thread, and on the JVM in tests. */
        fun of(splats: SplatCloud): SplatFaces {
            val n = splats.count
            val normals = FloatArray(n * 3)
            val round = BooleanArray(n)
            var c4 = 0.0
            var s4 = 0.0
            for (i in 0 until n) {
                // A splat is flat along its normal (SurfelSplats): equal scales mean none.
                round[i] = splats.scales[i * 3 + 2] >= splats.scales[i * 3]
                if (round[i]) continue
                normalOf(splats.rotations, i * 4, normals, i * 3)
                val nx = normals[i * 3]
                val nz = normals[i * 3 + 2]
                if (kotlin.math.abs(normals[i * 3 + 1]) < WALL_MAX_Y) {
                    // A quarter turn folded: walls at right angles agree on the room's heading.
                    val a = 4.0 * atan2(nz.toDouble(), nx.toDouble())
                    c4 += cos(a)
                    s4 += sin(a)
                }
            }
            val yaw = if (c4 == 0.0 && s4 == 0.0) 0.0 else atan2(s4, c4) / 4.0
            val cosYaw = cos(yaw).toFloat()
            val sinYaw = sin(yaw).toFloat()
            val face = ByteArray(n)
            val along = Array(FACES) { FloatArrayBuilder() }
            for (i in 0 until n) {
                if (round[i]) {
                    face[i] = ROUND.toByte()
                    continue
                }
                val nx = normals[i * 3]
                val ny = normals[i * 3 + 1]
                val nz = normals[i * 3 + 2]
                val nu = nx * cosYaw + nz * sinYaw
                val nv = -nx * sinYaw + nz * cosYaw
                val x = splats.positions[i * 3]
                val y = splats.positions[i * 3 + 1]
                val z = splats.positions[i * 3 + 2]
                val (axis, sign, at) = when {
                    kotlin.math.abs(ny) >= kotlin.math.abs(nu) && kotlin.math.abs(ny) >= kotlin.math.abs(nv) ->
                        Triple(1, ny, y)
                    kotlin.math.abs(nu) >= kotlin.math.abs(nv) -> Triple(0, nu, x * cosYaw + z * sinYaw)
                    else -> Triple(2, nv, -x * sinYaw + z * cosYaw)
                }
                val f = axis * 2 + if (sign >= 0f) 0 else 1
                face[i] = f.toByte()
                along[f].add(at)
            }
            val planes = FloatArray(FACES) { f ->
                val values = along[f].toSortedArray()
                when {
                    values.isEmpty() -> 0f
                    // A + face's wall is at its low side, a − face's at its high side.
                    f % 2 == 0 -> values[(values.size * EDGE_SHARE).toInt()]
                    else -> values[((values.size - 1) * (1f - EDGE_SHARE)).toInt()]
                }
            }
            return SplatFaces(splats, face, cosYaw, sinYaw, planes)
        }

        /** The unit quaternion `x, y, z, w` at [at] applied to +Z, into [out] at [to]. */
        fun normalOf(rotations: FloatArray, at: Int, out: FloatArray, to: Int) {
            val x = rotations[at]
            val y = rotations[at + 1]
            val z = rotations[at + 2]
            val w = rotations[at + 3]
            out[to] = 2f * (x * z + w * y)
            out[to + 1] = 2f * (y * z - w * x)
            out[to + 2] = 1f - 2f * (x * x + y * y)
        }
    }

    /** A growable float list, without boxing. */
    private class FloatArrayBuilder {
        private var data = FloatArray(INITIAL)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun toSortedArray(): FloatArray = data.copyOf(size).also { it.sort() }

        private companion object {
            const val INITIAL = 1024
        }
    }
}
