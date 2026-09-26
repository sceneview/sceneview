// Derived from Google Sceneform (Apache-2.0), modified by the SceneView contributors.
package io.github.sceneview.collision

import io.github.sceneview.logging.logWarning
import kotlin.math.sqrt

/**
 * 4x4 Matrix representing translation, scale, and rotation. Column major, right handed [0, 4, 8,
 * 12] [1, 5, 9, 13] [2, 6, 10, 14] [3, 7, 11, 15]
 */
class Matrix {
    var data = FloatArray(16)

    constructor() {
        set(IDENTITY_DATA)
    }

    constructor(data: FloatArray) {
        set(data)
    }

    fun set(data: FloatArray?) {
        if (data == null || data.size != 16) {
            logWarning(TAG, "Cannot set Matrix, invalid data.")
            return
        }

        for (i in data.indices) {
            this.data[i] = data[i]
        }
    }

    fun set(m: Matrix) {
        Preconditions.checkNotNull(m, "Parameter \"m\" was null.")
        set(m.data)
    }

    fun decomposeTranslation(destTranslation: Vector3) {
        destTranslation.x = data[12]
        destTranslation.y = data[13]
        destTranslation.z = data[14]
    }

    fun decomposeScale(destScale: Vector3) {
        val temp = Vector3(data[0], data[1], data[2])
        destScale.x = temp.length()
        temp.set(data[4], data[5], data[6])
        destScale.y = temp.length()
        temp.set(data[8], data[9], data[10])
        destScale.z = temp.length()
    }

    fun decomposeRotation(decomposedScale: Vector3, destRotation: Quaternion) {
        // Pre-#1126 this method used `this` as scratch: called the destMatrix overload
        // with `this` to normalize in-place, ran extractQuaternion off the now-normalized
        // data, then restored from a 16-field snapshot. Any concurrent read of `data`
        // during those ~30 instructions saw a normalized (rotation-only) matrix instead
        // of the full TRS — silently wrong matrices on hot frames where Compose and the
        // render thread both touch the same node transform.
        //
        // Fix: compute the normalized rotation values into immutable locals and run the
        // quaternion extraction off those. `data` is now read-only within this method
        // (single snapshot at call entry), so concurrent readers never observe a partial
        // state. The destMatrix overload below is unchanged and remains safe as long as
        // callers don't pass `this` (the only such caller was the buggy block above).
        val sx = decomposedScale.x
        val sy = decomposedScale.y
        val sz = decomposedScale.z
        val nm0 = if (sx != 0f) data[0] / sx else 0f
        val nm1 = if (sx != 0f) data[1] / sx else 0f
        val nm2 = if (sx != 0f) data[2] / sx else 0f
        val nm4 = if (sy != 0f) data[4] / sy else 0f
        val nm5 = if (sy != 0f) data[5] / sy else 0f
        val nm6 = if (sy != 0f) data[6] / sy else 0f
        val nm8 = if (sz != 0f) data[8] / sz else 0f
        val nm9 = if (sz != 0f) data[9] / sz else 0f
        val nm10 = if (sz != 0f) data[10] / sz else 0f

        val trace = nm0 + nm5 + nm10
        if (trace > 0) {
            val s = sqrt(trace + 1.0).toFloat() * 2.0f
            destRotation.w = 0.25f * s
            destRotation.x = (nm6 - nm9) / s
            destRotation.y = (nm8 - nm2) / s
            destRotation.z = (nm1 - nm4) / s
        } else if (nm0 > nm5 && nm0 > nm10) {
            val s = sqrt(1.0f + nm0 - nm5 - nm10.toDouble()).toFloat() * 2.0f
            destRotation.w = (nm6 - nm9) / s
            destRotation.x = 0.25f * s
            destRotation.y = (nm4 + nm1) / s
            destRotation.z = (nm8 + nm2) / s
        } else if (nm5 > nm10) {
            val s = sqrt(1.0f + nm5 - nm0 - nm10.toDouble()).toFloat() * 2.0f
            destRotation.w = (nm8 - nm2) / s
            destRotation.x = (nm4 + nm1) / s
            destRotation.y = 0.25f * s
            destRotation.z = (nm9 + nm6) / s
        } else {
            val s = sqrt(1.0f + nm10 - nm0 - nm5.toDouble()).toFloat() * 2.0f
            destRotation.w = (nm1 - nm4) / s
            destRotation.x = (nm8 + nm2) / s
            destRotation.y = (nm9 + nm6) / s
            destRotation.z = 0.25f * s
        }
        destRotation.normalize()
    }

    fun decomposeRotation(decomposedScale: Vector3, destMatrix: Matrix) {
        if (decomposedScale.x != 0.0f) {
            for (i in 0..2) {
                destMatrix.data[i] = data[i] / decomposedScale.x
            }
        }

        destMatrix.data[3] = 0.0f

        if (decomposedScale.y != 0.0f) {
            for (i in 4..6) {
                destMatrix.data[i] = data[i] / decomposedScale.y
            }
        }

        destMatrix.data[7] = 0.0f

        if (decomposedScale.z != 0.0f) {
            for (i in 8..10) {
                destMatrix.data[i] = data[i] / decomposedScale.z
            }
        }

        destMatrix.data[11] = 0.0f
        destMatrix.data[12] = 0.0f
        destMatrix.data[13] = 0.0f
        destMatrix.data[14] = 0.0f
        destMatrix.data[15] = 1.0f
    }

    fun extractQuaternion(destQuaternion: Quaternion) {
        val trace = data[0] + data[5] + data[10]

        if (trace > 0) {
            val s = sqrt(trace + 1.0).toFloat() * 2.0f
            destQuaternion.w = 0.25f * s
            destQuaternion.x = (data[6] - data[9]) / s
            destQuaternion.y = (data[8] - data[2]) / s
            destQuaternion.z = (data[1] - data[4]) / s
        } else if (data[0] > data[5] && data[0] > data[10]) {
            val s = sqrt(1.0f + data[0] - data[5] - data[10].toDouble()).toFloat() * 2.0f
            destQuaternion.w = (data[6] - data[9]) / s
            destQuaternion.x = 0.25f * s
            destQuaternion.y = (data[4] + data[1]) / s
            destQuaternion.z = (data[8] + data[2]) / s
        } else if (data[5] > data[10]) {
            val s = sqrt(1.0f + data[5] - data[0] - data[10].toDouble()).toFloat() * 2.0f
            destQuaternion.w = (data[8] - data[2]) / s
            destQuaternion.x = (data[4] + data[1]) / s
            destQuaternion.y = 0.25f * s
            destQuaternion.z = (data[9] + data[6]) / s
        } else {
            val s = sqrt(1.0f + data[10] - data[0] - data[5].toDouble()).toFloat() * 2.0f
            destQuaternion.w = (data[1] - data[4]) / s
            destQuaternion.x = (data[8] + data[2]) / s
            destQuaternion.y = (data[9] + data[6]) / s
            destQuaternion.z = 0.25f * s
        }
        destQuaternion.normalize()
    }

    fun makeTranslation(translation: Vector3) {
        Preconditions.checkNotNull(translation, "Parameter \"translation\" was null.")
        set(IDENTITY_DATA)
        setTranslation(translation)
    }

    fun setTranslation(translation: Vector3) {
        data[12] = translation.x
        data[13] = translation.y
        data[14] = translation.z
    }

    fun makeRotation(rotation: Quaternion) {
        Preconditions.checkNotNull(rotation, "Parameter \"rotation\" was null.")

        set(IDENTITY_DATA)

        rotation.normalize()

        val xx = rotation.x * rotation.x
        val xy = rotation.x * rotation.y
        val xz = rotation.x * rotation.z
        val xw = rotation.x * rotation.w

        val yy = rotation.y * rotation.y
        val yz = rotation.y * rotation.z
        val yw = rotation.y * rotation.w

        val zz = rotation.z * rotation.z
        val zw = rotation.z * rotation.w

        data[0] = 1.0f - 2.0f * (yy + zz)
        data[4] = 2.0f * (xy - zw)
        data[8] = 2.0f * (xz + yw)

        data[1] = 2.0f * (xy + zw)
        data[5] = 1.0f - 2.0f * (xx + zz)
        data[9] = 2.0f * (yz - xw)

        data[2] = 2.0f * (xz - yw)
        data[6] = 2.0f * (yz + xw)
        data[10] = 1.0f - 2.0f * (xx + yy)
    }

    fun makeScale(scale: Float) {
        set(IDENTITY_DATA)
        data[0] = scale
        data[5] = scale
        data[10] = scale
    }

    fun makeScale(scale: Vector3) {
        Preconditions.checkNotNull(scale, "Parameter \"scale\" was null.")
        set(IDENTITY_DATA)
        data[0] = scale.x
        data[5] = scale.y
        data[10] = scale.z
    }

    fun makeTrs(translation: Vector3, rotation: Quaternion, scale: Vector3) {
        val mdsqx = 1 - 2 * rotation.x * rotation.x
        val sqy = rotation.y * rotation.y
        val dsqz = 2 * rotation.z * rotation.z
        val dqxz = 2 * rotation.x * rotation.z
        val dqyw = 2 * rotation.y * rotation.w
        val dqxy = 2 * rotation.x * rotation.y
        val dqzw = 2 * rotation.z * rotation.w
        val dqxw = 2 * rotation.x * rotation.w
        val dqyz = 2 * rotation.y * rotation.z

        data[0] = (1 - 2 * sqy - dsqz) * scale.x
        data[4] = (dqxy - dqzw) * scale.y
        data[8] = (dqxz + dqyw) * scale.z

        data[1] = (dqxy + dqzw) * scale.x
        data[5] = (mdsqx - dsqz) * scale.y
        data[9] = (dqyz - dqxw) * scale.z

        data[2] = (dqxz - dqyw) * scale.x
        data[6] = (dqyz + dqxw) * scale.y
        data[10] = (mdsqx - 2 * sqy) * scale.z

        data[12] = translation.x
        data[13] = translation.y
        data[14] = translation.z
        data[15] = 1.0f
    }

    fun transformPoint(vector: Vector3): Vector3 {
        Preconditions.checkNotNull(vector, "Parameter \"vector\" was null.")

        val result = Vector3()
        val vx = vector.x
        val vy = vector.y
        val vz = vector.z
        result.x = data[0] * vx
        result.x += data[4] * vy
        result.x += data[8] * vz
        result.x += data[12]

        result.y = data[1] * vx
        result.y += data[5] * vy
        result.y += data[9] * vz
        result.y += data[13]

        result.z = data[2] * vx
        result.z += data[6] * vy
        result.z += data[10] * vz
        result.z += data[14]
        return result
    }

    fun transformDirection(vector: Vector3): Vector3 {
        Preconditions.checkNotNull(vector, "Parameter \"vector\" was null.")

        val result = Vector3()
        val vx = vector.x
        val vy = vector.y
        val vz = vector.z
        result.x = data[0] * vx
        result.x += data[4] * vy
        result.x += data[8] * vz

        result.y = data[1] * vx
        result.y += data[5] * vy
        result.y += data[9] * vz

        result.z = data[2] * vx
        result.z += data[6] * vy
        result.z += data[10] * vz
        return result
    }

    companion object {
        private const val TAG = "Matrix"

        val IDENTITY_DATA = floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        )

        fun multiply(lhs: Matrix, rhs: Matrix, dest: Matrix) {
            Preconditions.checkNotNull(lhs, "Parameter \"lhs\" was null.")
            Preconditions.checkNotNull(rhs, "Parameter \"rhs\" was null.")

            var m00 = 0f
            var m01 = 0f
            var m02 = 0f
            var m03 = 0f
            var m10 = 0f
            var m11 = 0f
            var m12 = 0f
            var m13 = 0f
            var m20 = 0f
            var m21 = 0f
            var m22 = 0f
            var m23 = 0f
            var m30 = 0f
            var m31 = 0f
            var m32 = 0f
            var m33 = 0f

            for (i in 0..3) {
                val lhs0 = lhs.data[0 + i * 4]
                val lhs1 = lhs.data[1 + i * 4]
                val lhs2 = lhs.data[2 + i * 4]
                val lhs3 = lhs.data[3 + i * 4]
                val rhs0 = rhs.data[0 * 4 + i]
                val rhs1 = rhs.data[1 * 4 + i]
                val rhs2 = rhs.data[2 * 4 + i]
                val rhs3 = rhs.data[3 * 4 + i]

                m00 += lhs0 * rhs0
                m01 += lhs1 * rhs0
                m02 += lhs2 * rhs0
                m03 += lhs3 * rhs0

                m10 += lhs0 * rhs1
                m11 += lhs1 * rhs1
                m12 += lhs2 * rhs1
                m13 += lhs3 * rhs1

                m20 += lhs0 * rhs2
                m21 += lhs1 * rhs2
                m22 += lhs2 * rhs2
                m23 += lhs3 * rhs2

                m30 += lhs0 * rhs3
                m31 += lhs1 * rhs3
                m32 += lhs2 * rhs3
                m33 += lhs3 * rhs3
            }

            dest.data[0] = m00
            dest.data[1] = m01
            dest.data[2] = m02
            dest.data[3] = m03
            dest.data[4] = m10
            dest.data[5] = m11
            dest.data[6] = m12
            dest.data[7] = m13
            dest.data[8] = m20
            dest.data[9] = m21
            dest.data[10] = m22
            dest.data[11] = m23
            dest.data[12] = m30
            dest.data[13] = m31
            dest.data[14] = m32
            dest.data[15] = m33
        }

        fun invert(matrix: Matrix, dest: Matrix): Boolean {
            Preconditions.checkNotNull(matrix, "Parameter \"matrix\" was null.")
            Preconditions.checkNotNull(dest, "Parameter \"dest\" was null.")

            val m0 = matrix.data[0]
            val m1 = matrix.data[1]
            val m2 = matrix.data[2]
            val m3 = matrix.data[3]
            val m4 = matrix.data[4]
            val m5 = matrix.data[5]
            val m6 = matrix.data[6]
            val m7 = matrix.data[7]
            val m8 = matrix.data[8]
            val m9 = matrix.data[9]
            val m10 = matrix.data[10]
            val m11 = matrix.data[11]
            val m12 = matrix.data[12]
            val m13 = matrix.data[13]
            val m14 = matrix.data[14]
            val m15 = matrix.data[15]

            dest.data[0] =
                m5 * m10 * m15 - m5 * m11 * m14 - m9 * m6 * m15 + m9 * m7 * m14 + m13 * m6 * m11 - m13 * m7 * m10
            dest.data[4] =
                -m4 * m10 * m15 + m4 * m11 * m14 + m8 * m6 * m15 - m8 * m7 * m14 - m12 * m6 * m11 + m12 * m7 * m10
            dest.data[8] =
                m4 * m9 * m15 - m4 * m11 * m13 - m8 * m5 * m15 + m8 * m7 * m13 + m12 * m5 * m11 - m12 * m7 * m9
            dest.data[12] =
                -m4 * m9 * m14 + m4 * m10 * m13 + m8 * m5 * m14 - m8 * m6 * m13 - m12 * m5 * m10 + m12 * m6 * m9
            dest.data[1] =
                -m1 * m10 * m15 + m1 * m11 * m14 + m9 * m2 * m15 - m9 * m3 * m14 - m13 * m2 * m11 + m13 * m3 * m10
            dest.data[5] =
                m0 * m10 * m15 - m0 * m11 * m14 - m8 * m2 * m15 + m8 * m3 * m14 + m12 * m2 * m11 - m12 * m3 * m10
            dest.data[9] =
                -m0 * m9 * m15 + m0 * m11 * m13 + m8 * m1 * m15 - m8 * m3 * m13 - m12 * m1 * m11 + m12 * m3 * m9
            dest.data[13] =
                m0 * m9 * m14 - m0 * m10 * m13 - m8 * m1 * m14 + m8 * m2 * m13 + m12 * m1 * m10 - m12 * m2 * m9
            dest.data[2] =
                m1 * m6 * m15 - m1 * m7 * m14 - m5 * m2 * m15 + m5 * m3 * m14 + m13 * m2 * m7 - m13 * m3 * m6
            dest.data[6] =
                -m0 * m6 * m15 + m0 * m7 * m14 + m4 * m2 * m15 - m4 * m3 * m14 - m12 * m2 * m7 + m12 * m3 * m6
            dest.data[10] =
                m0 * m5 * m15 - m0 * m7 * m13 - m4 * m1 * m15 + m4 * m3 * m13 + m12 * m1 * m7 - m12 * m3 * m5
            dest.data[14] =
                -m0 * m5 * m14 + m0 * m6 * m13 + m4 * m1 * m14 - m4 * m2 * m13 - m12 * m1 * m6 + m12 * m2 * m5
            dest.data[3] =
                -m1 * m6 * m11 + m1 * m7 * m10 + m5 * m2 * m11 - m5 * m3 * m10 - m9 * m2 * m7 + m9 * m3 * m6
            dest.data[7] =
                m0 * m6 * m11 - m0 * m7 * m10 - m4 * m2 * m11 + m4 * m3 * m10 + m8 * m2 * m7 - m8 * m3 * m6
            dest.data[11] =
                -m0 * m5 * m11 + m0 * m7 * m9 + m4 * m1 * m11 - m4 * m3 * m9 - m8 * m1 * m7 + m8 * m3 * m5
            dest.data[15] =
                m0 * m5 * m10 - m0 * m6 * m9 - m4 * m1 * m10 + m4 * m2 * m9 + m8 * m1 * m6 - m8 * m2 * m5

            var det = m0 * dest.data[0] + m1 * dest.data[4] + m2 * dest.data[8] + m3 * dest.data[12]

            if (det == 0f) {
                return false
            }

            det = 1.0f / det

            for (i in 0..15) {
                dest.data[i] *= det
            }

            return true
        }

        fun equals(lhs: Matrix, rhs: Matrix): Boolean {
            Preconditions.checkNotNull(lhs, "Parameter \"lhs\" was null.")
            Preconditions.checkNotNull(rhs, "Parameter \"rhs\" was null.")

            var result = true
            for (i in 0..15) {
                result = result and MathHelper.almostEqualRelativeAndAbs(lhs.data[i], rhs.data[i])
            }
            return result
        }
    }
}
