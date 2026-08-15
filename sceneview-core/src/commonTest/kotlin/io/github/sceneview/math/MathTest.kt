package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.pow
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MathTest {

    private fun assertClose(expected: Float, actual: Float, epsilon: Float = 0.001f) {
        assertTrue(abs(expected - actual) < epsilon, "Expected $expected but got $actual")
    }

    @Test
    fun typeAliasesAreCorrectTypes() {
        val pos: Position = Float3(1f, 2f, 3f)
        val rot: Rotation = Float3(0f, 90f, 0f)
        val scl: Scale = Float3(1f, 1f, 1f)
        assertEquals(1f, pos.x)
        assertEquals(90f, rot.y)
        assertEquals(1f, scl.z)
    }

    /**
     * Regression guard for the PhysicsDemo-invisible-spheres bug:
     *
     * `Scale(1f)` MUST produce the uniform scale `(1, 1, 1)`. The named-arg form
     * `Scale(x = 1f)` produces the singular `(1, 0, 0)` — that's kotlin-math's
     * three-arg primary constructor with default `y = 0f, z = 0f`.
     *
     * If a future refactor ever makes `Scale(1f)` resolve to the primary three-arg
     * constructor (e.g. by removing `Float3(v: Float)`), this test fails loudly
     * before the change reaches the node composables.
     */
    @Test
    fun scaleSingleArgIsUniform() {
        val uniform: Scale = Scale(1f)
        assertEquals(1f, uniform.x)
        assertEquals(1f, uniform.y)
        assertEquals(1f, uniform.z)

        // And the named-arg form stays (intentionally) non-uniform — callers
        // who want uniform scale must use the positional single-arg form. This
        // locks in the behaviour so a future kotlin-math upgrade that e.g.
        // changes the primary constructor to fill y/z from x will surface here.
        val partial: Scale = Scale(x = 1f)
        assertEquals(1f, partial.x)
        assertEquals(0f, partial.y)
        assertEquals(0f, partial.z)
    }

    @Test
    fun floatArrayToFloat3() {
        val arr = floatArrayOf(1f, 2f, 3f)
        val v = arr.toFloat3()
        assertEquals(1f, v.x)
        assertEquals(2f, v.y)
        assertEquals(3f, v.z)
    }

    @Test
    fun transformWithDefaultValues() {
        val t = Transform()
        // Identity transform
        assertClose(1f, t.x.x) // scale x
        assertClose(1f, t.y.y) // scale y
        assertClose(1f, t.z.z) // scale z
        assertClose(1f, t.w.w) // homogeneous
    }

    @Test
    fun mat4ToColumnsFloatArray() {
        val identity = Mat4()
        val arr = identity.toColumnsFloatArray()
        assertEquals(16, arr.size)
        assertClose(1f, arr[0])  // m[0][0]
        assertClose(0f, arr[1])  // m[1][0]
        assertClose(1f, arr[5])  // m[1][1]
        assertClose(1f, arr[15]) // m[3][3]
    }

    @Test
    fun lerpHalfway() {
        val start = Float3(0f, 0f, 0f)
        val end = Float3(10f, 20f, 30f)
        val result = lerp(start, end, 0.5f)
        assertClose(5f, result.x)
        assertClose(10f, result.y)
        assertClose(15f, result.z)
    }

    @Test
    fun colorOfBasic() {
        val c = colorOf(0.5f, 0.3f, 0.1f, 1.0f)
        assertClose(0.5f, c.x) // r
        assertClose(0.3f, c.y) // g
        assertClose(0.1f, c.z) // b
        assertClose(1.0f, c.w) // a
    }

    @Test
    fun colorOfGrayscale() {
        val c = colorOf(rgb = 0.5f)
        assertClose(0.5f, c.x)
        assertClose(0.5f, c.y)
        assertClose(0.5f, c.z)
        assertClose(1.0f, c.w)
    }

    @Test
    fun floatAlmostEquals() {
        assertTrue(1.0f almostEquals 1.0f)
        assertTrue(1.0f almostEquals (1.0f + Float.MIN_VALUE))
    }

    @Test
    fun floatEqualsWithDelta() {
        assertTrue(1.0f.equals(1.05f, 0.1f))
        assertTrue(!1.0f.equals(1.2f, 0.1f))
    }

    @Test
    fun toLinearSpace() {
        val linear = floatArrayOf(0.5f).toLinearSpace()
        // 0.5^2.2 ≈ 0.2176
        assertClose(0.2176f, linear[0], 0.01f)
    }

    /**
     * `toLinearSpace()` was rewritten to fill a `FloatArray` in place instead of mapping through
     * a boxed `List<Float>` (#3157). It must still convert every element, in order, and leave the
     * receiver untouched.
     */
    @Test
    fun toLinearSpaceConvertsEveryElementAndDoesNotMutateReceiver() {
        val source = floatArrayOf(0f, 0.25f, 0.5f, 1f)
        val linear = source.toLinearSpace()
        assertEquals(source.size, linear.size)
        for (i in source.indices) {
            assertClose(pow(source[i], 2.2f), linear[i])
        }
        // Distinct array, receiver unchanged.
        assertTrue(linear !== source)
        assertEquals(0.25f, source[1])
        assertEquals(0, floatArrayOf().toLinearSpace().size)
    }

    /**
     * `toColumnsDoubleArray()` was rewritten to fill a `DoubleArray` directly instead of going
     * through `toColumnsFloatArray().map { }.toDoubleArray()` (#3157). Since it now enumerates
     * `x.x, x.y, … w.w` by hand, the failure mode it can regress into is a wrong or transposed
     * order, and that is what this pins: sixteen distinct, non-float-exact values, compared
     * position by position against [toColumnsFloatArray].
     *
     * What it deliberately does **not** pin is precision. Both matrices here are built from a
     * `FloatArray`, whose storage already rounds to 32 bits on every target — including
     * Kotlin/JS, where `Float` is a plain `Number` but `FloatArray` is a `Float32Array`. So the
     * two paths are bit-identical by construction and no fixture routed through a `FloatArray`
     * could tell them apart. A `Mat4` whose components came from JS arithmetic could; there is no
     * JS caller of `toColumnsDoubleArray()` today (the only consumer is Android's
     * `Camera.setCustomProjection`, where `Float` is genuinely 32-bit), so that gap is unreachable
     * rather than untested.
     */
    @Test
    fun toColumnsDoubleArrayMatchesFloatColumns() {
        val matrix = Mat4.of(*FloatArray(16) { (it + 1) * 0.1f })
        val floats = matrix.toColumnsFloatArray()
        val doubles = matrix.toColumnsDoubleArray()
        assertEquals(16, doubles.size)
        for (i in floats.indices) {
            assertEquals(floats[i].toDouble(), doubles[i])
        }
        // Distinct values, so a transposed or shifted enumeration cannot pass by coincidence.
        assertEquals(16, doubles.toSet().size)
    }

    // ── slerp TRS-tuple overload (#2265) ──────────────────────────────────────

    /**
     * The pre-decomposed TRS-tuple `slerp` overload must produce the same result as the
     * `Transform`-based overload (which is now implemented in terms of it). Position and scale
     * are compared component-wise; the quaternion is compared via |dot| ≈ 1 to tolerate the
     * sign double-cover introduced by the matrix decompose/recompose round-trip on the
     * Transform path.
     */
    @Test
    fun slerpTrsTupleMatchesTransformOverload() {
        val start = Transform(
            position = Float3(0f, 0f, 0f),
            rotation = Float3(0f, 0f, 0f),
            scale = Float3(1f, 1f, 1f)
        )
        val end = Transform(
            position = Float3(10f, 20f, 30f),
            rotation = Float3(0f, 90f, 0f),
            scale = Float3(2f, 4f, 8f)
        )
        val deltaSeconds = 1.0 / 60.0
        val speed = 5f

        val viaTransform = slerp(start, end, deltaSeconds, speed)
        val (position, quaternion, scale) = slerp(
            startPosition = start.position,
            startQuaternion = start.quaternion,
            startScale = start.scale,
            endPosition = end.position,
            endQuaternion = end.quaternion,
            endScale = end.scale,
            deltaSeconds = deltaSeconds,
            speed = speed
        )

        assertClose(viaTransform.position.x, position.x)
        assertClose(viaTransform.position.y, position.y)
        assertClose(viaTransform.position.z, position.z)
        assertClose(viaTransform.scale.x, scale.x)
        assertClose(viaTransform.scale.y, scale.y)
        assertClose(viaTransform.scale.z, scale.z)

        val q = viaTransform.quaternion
        val dot = quaternion.x * q.x + quaternion.y * q.y + quaternion.z * q.z + quaternion.w * q.w
        assertTrue(abs(dot) > 0.9999f, "quaternion mismatch: dot=$dot")
    }

    /** `deltaSeconds <= 0` (or `speed <= 0`) yields a zero factor → the start components. */
    @Test
    fun slerpTrsTupleZeroDeltaReturnsStart() {
        val startPosition = Float3(1f, 2f, 3f)
        val startQuaternion = Float3(0f, 45f, 0f).toQuaternion()
        val startScale = Float3(2f, 2f, 2f)

        val (position, quaternion, scale) = slerp(
            startPosition = startPosition,
            startQuaternion = startQuaternion,
            startScale = startScale,
            endPosition = Float3(9f, 9f, 9f),
            endQuaternion = Float3(0f, 90f, 0f).toQuaternion(),
            endScale = Float3(5f, 5f, 5f),
            deltaSeconds = 0.0,
            speed = 5f
        )

        assertClose(startPosition.x, position.x)
        assertClose(startPosition.y, position.y)
        assertClose(startPosition.z, position.z)
        assertClose(startScale.x, scale.x)
        val dot = quaternion.x * startQuaternion.x + quaternion.y * startQuaternion.y +
            quaternion.z * startQuaternion.z + quaternion.w * startQuaternion.w
        assertTrue(abs(dot) > 0.9999f, "expected start quaternion, dot=$dot")
    }
}
