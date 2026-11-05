package io.github.sceneview.loaders

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * #4949: a skinned model's bounds follow its joints, not the accessors' unskinned min / max.
 *
 * The rig is the usual centimetre export: a 189-unit-tall triangle at the root, skinned to one
 * joint under an armature scaled by 0.01. gltfio's box is 189 m tall; the drawn mesh is 1.89 m.
 */
@RunWith(RobolectricTestRunner::class) // org.json is an Android API
class GltfRestPoseTest {

    @Test
    fun `a centimetre rig under a 0_01 armature measures its drawn size`() {
        assertBounds(floatArrayOf(-0.1f, 0f, 0f, 0.1f, 1.89f, 0f), rig().restPoseBounds())
    }

    @Test
    fun `unreadable weights fall back to the box moved by each joint`() {
        // A sparse accessor is not read: the POSITION min / max moved by the one joint stands in.
        assertBounds(floatArrayOf(-0.1f, 0f, 0f, 0.1f, 1.89f, 0f), rig(sparseWeights = true).restPoseBounds())
    }

    @Test
    fun `a skin without inverse bind matrices uses each joint's world transform`() {
        // world(joint) = scale(0.01) · translate(0, 100, 0): y from 1 to 1 + 1.89.
        assertBounds(floatArrayOf(-0.1f, 1f, 0f, 0.1f, 2.89f, 0f), rig(inverseBinds = false).restPoseBounds())
    }

    @Test
    fun `two joints blend by their weights`() {
        // Half on a joint moved +2 on X, half on one moved −1: the vertex lands at +0.5.
        assertBounds(floatArrayOf(-0.1f + 0.5f, 0f, 0f, 0.1f + 0.5f, 1.89f, 0f), rig(blend = true).restPoseBounds())
    }

    @Test
    fun `a model without skins keeps gltfio's box`() {
        assertNull(rig(skinned = false).restPoseBounds())
    }

    @Test
    fun `a payload that is not glTF has no rest pose`() {
        assertNull(GltfLimits.parse(ByteBuffer.wrap("not a glTF".toByteArray())).restPoseBounds())
    }

    private fun assertBounds(expected: FloatArray, actual: FloatArray?) {
        assertArrayEquals(expected, actual ?: error("no rest-pose bounds"), 1e-4f)
    }

    /**
     * Node 0 holds the mesh, at the root; node 1 is the armature, scaled by 0.01; node 2 its joint,
     * 100 units up (1 m once scaled) — and node 3, with [blend], a second joint.
     */
    private fun rig(
        skinned: Boolean = true,
        inverseBinds: Boolean = true,
        sparseWeights: Boolean = false,
        blend: Boolean = false,
    ): GltfLimits.Parsed {
        val positions = floatArrayOf(-10f, 0f, 0f, 10f, 0f, 0f, 0f, 189f, 0f)
        val joints = if (blend) byteArrayOf(0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0) else ByteArray(12)
        val weights = FloatArray(12) { if (it % 4 == 0) (if (blend) 0.5f else 1f) else if (blend && it % 4 == 1) 0.5f else 0f }
        // inverse(world(joint)) · scale(0.01), the bind of a centimetre mesh: translate(0, −100, 0).
        val bind = FloatArray(16) { if (it % 5 == 0) 1f else 0f }.also { it[13] = -100f }
        // The second joint sits 300 units left of the first (−3 m), bound as if it were the first.
        val bindSecond = bind.copyOf().also { it[12] = 300f }
        val bin = ByteBuffer.allocate(BIN_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            positions.forEach(::putFloat)
            put(joints)
            weights.forEach(::putFloat)
            bind.forEach(::putFloat)
            bindSecond.forEach(::putFloat)
        }.array()
        val attributes = if (skinned) """"POSITION": 0, "JOINTS_0": 1, "WEIGHTS_0": 2""" else """"POSITION": 0"""
        val sparse = if (sparseWeights) """, "sparse": {"count": 1, "indices": {"bufferView": 2, "componentType": 5121}, "values": {"bufferView": 2}}""" else ""
        val skinJoints = if (blend) "[2, 3]" else "[2]"
        val bindsAccessor = if (inverseBinds) """, "inverseBindMatrices": 3""" else ""
        // The first joint moves +2 m on X once bound, the second −1 m: see bindSecond.
        val firstJoint = if (blend) """"translation": [200, 100, 0]""" else """"translation": [0, 100, 0]"""
        val json = """
            {
              "asset": {"version": "2.0"},
              "scene": 0,
              "scenes": [{"nodes": [0, 1]}],
              "nodes": [
                {"mesh": 0${if (skinned) """, "skin": 0""" else ""}},
                {"scale": [0.01, 0.01, 0.01], "children": [2${if (blend) ", 3" else ""}]},
                {$firstJoint},
                {"translation": [-400, 100, 0]}
              ],
              "meshes": [{"primitives": [{"attributes": {$attributes}}]}],
              ${if (skinned) """"skins": [{"joints": $skinJoints$bindsAccessor}],""" else ""}
              "accessors": [
                {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3",
                 "min": [-10, 0, 0], "max": [10, 189, 0]},
                {"bufferView": 1, "componentType": 5121, "count": 3, "type": "VEC4"},
                {"bufferView": 2, "componentType": 5126, "count": 3, "type": "VEC4"$sparse},
                {"bufferView": 3, "componentType": 5126, "count": ${if (blend) 2 else 1}, "type": "MAT4"}
              ],
              "bufferViews": [
                {"buffer": 0, "byteOffset": 0, "byteLength": 36},
                {"buffer": 0, "byteOffset": 36, "byteLength": 12},
                {"buffer": 0, "byteOffset": 48, "byteLength": 48},
                {"buffer": 0, "byteOffset": 96, "byteLength": 128}
              ],
              "buffers": [{"byteLength": $BIN_SIZE}]
            }
        """.trimIndent()
        return GltfLimits.parse(glb(json, bin))
    }

    private fun glb(json: String, bin: ByteArray): ByteBuffer {
        val jsonBytes = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val total = 12 + 8 + jsonBytes.size + 8 + bin.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67)
            putInt(2)
            putInt(total)
            putInt(jsonBytes.size)
            putInt(0x4E4F534A)
            put(jsonBytes)
            putInt(bin.size)
            putInt(0x004E4942)
            put(bin)
            flip()
        }
    }

    private companion object {
        const val BIN_SIZE = 224
    }
}
