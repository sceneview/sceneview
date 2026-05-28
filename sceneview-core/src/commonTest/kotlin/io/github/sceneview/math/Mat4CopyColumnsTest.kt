package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat3
import dev.romainguy.kotlin.math.Mat4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Regression coverage for the allocation-free [Mat4.copyColumnsInto] /
 * [Mat3.copyColumnsInto] buffer-fill overloads added in #2271.
 */
class Mat4CopyColumnsTest {

    private val mat4 = Mat4(
        Float4(1f, 2f, 3f, 4f),
        Float4(5f, 6f, 7f, 8f),
        Float4(9f, 10f, 11f, 12f),
        Float4(13f, 14f, 15f, 16f)
    )

    private val mat3 = Mat3(
        Float3(1f, 2f, 3f),
        Float3(4f, 5f, 6f),
        Float3(7f, 8f, 9f)
    )

    @Test
    fun mat4CopyColumnsMatchesToColumnsFloatArray() {
        val expected = mat4.toColumnsFloatArray()
        val out = FloatArray(16)
        mat4.copyColumnsInto(out)
        assertTrue(expected.contentEquals(out), "copyColumnsInto must match toColumnsFloatArray")
    }

    @Test
    fun mat4CopyColumnsReturnsSameInstance() {
        val out = FloatArray(16)
        val returned = mat4.copyColumnsInto(out)
        assertSame(out, returned, "copyColumnsInto must return the same buffer (proves reuse)")
    }

    @Test
    fun mat4CopyColumnsHonorsOffset() {
        val out = FloatArray(20)
        out[0] = -1f
        out[1] = -1f
        mat4.copyColumnsInto(out, offset = 2)
        // Leading slots untouched.
        assertEquals(-1f, out[0])
        assertEquals(-1f, out[1])
        // 16 floats written starting at offset 2.
        val expected = mat4.toColumnsFloatArray()
        for (i in 0 until 16) {
            assertEquals(expected[i], out[i + 2], "slot ${i + 2}")
        }
    }

    @Test
    fun mat4CopyColumnsTooSmallThrows() {
        assertFailsWith<IllegalArgumentException> {
            mat4.copyColumnsInto(FloatArray(15))
        }
        assertFailsWith<IllegalArgumentException> {
            mat4.copyColumnsInto(FloatArray(16), offset = 1)
        }
    }

    @Test
    fun mat3CopyColumnsMatchesToColumnsFloatArray() {
        val expected = mat3.toColumnsFloatArray()
        val out = FloatArray(9)
        val returned = mat3.copyColumnsInto(out)
        assertSame(out, returned)
        assertTrue(expected.contentEquals(out), "copyColumnsInto must match toColumnsFloatArray")
    }

    @Test
    fun mat3CopyColumnsTooSmallThrows() {
        assertFailsWith<IllegalArgumentException> {
            mat3.copyColumnsInto(FloatArray(8))
        }
    }
}
