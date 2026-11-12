package io.github.sceneview.loaders

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class GltfMaterialCompatibilityTest {
    private val json = """{"asset":{"version":"2.0"},
        "extensionsUsed":["KHR_materials_volume","KHR_materials_transmission"],
        "extensionsRequired":["KHR_materials_volume"],
        "materials":[{"extensions":{"KHR_materials_volume":{"thicknessFactor":1},
        "KHR_materials_transmission":{"transmissionFactor":0.9},
        "KHR_materials_iridescence":{"iridescenceFactor":1},
        "KHR_materials_specular":{},"KHR_materials_dispersion":{}}}],
        "images":[{"mimeType":"image/png","uri":"a/b.png"}],
        "extras":{"KHR_materials_volume":"preserve metadata"}}""".trimIndent()

    @Test
    fun drops_only_unrepresentable_material_extensions_and_their_declarations() {
        // A read-only slice with a non-zero position must remain untouched.
        val source = ByteBuffer.wrap(("prefix" + json).toByteArray()).apply { position(6) }
            .asReadOnlyBuffer()
        val prepared = GltfMaterialCompatibility.prepare(source) as ByteBuffer
        assertEquals(6, source.position())
        assertEquals('K', source.get(6 + json.indexOf("KHR_materials_volume")).toInt().toChar())
        val text = GltfBufferUris.jsonText(prepared.duplicate().order(ByteOrder.LITTLE_ENDIAN))!!
        val result = JSONObject(text)
        val extensions = result.getJSONArray("materials").getJSONObject(0).getJSONObject("extensions")
        assertFalse(extensions.has("KHR_materials_volume"))
        assertFalse(extensions.has("KHR_materials_specular"))
        assertFalse(extensions.has("KHR_materials_dispersion"))
        assertEquals(0.9, extensions.getJSONObject("KHR_materials_transmission").getDouble("transmissionFactor"), 0.0)
        assertTrue(extensions.has("KHR_materials_iridescence"))
        assertEquals("preserve metadata", result.getJSONObject("extras").getString("KHR_materials_volume"))
        assertFalse(result.has("extensionsRequired"))
        assertEquals("KHR_materials_transmission", result.getJSONArray("extensionsUsed").getString(0))
        assertTrue("cgltf must see a literal slash in mimeType", text.contains("image/png"))
    }

    @Test
    fun glb_rewrite_preserves_all_trailing_chunks_and_updates_lengths() {
        val encoded = json.toByteArray()
        val padded = (encoded.size + 3) and -4
        val source = ByteBuffer.allocate(20 + padded + 12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546C67); putInt(2); putInt(capacity())
            putInt(padded); putInt(0x4E4F534A); put(encoded)
            repeat(padded - encoded.size) { put(32.toByte()) }
            putInt(4); putInt(0x004E4942); putInt(0x12345678)
            flip()
        }
        val prepared = (GltfMaterialCompatibility.prepare(source) as ByteBuffer).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(prepared.isDirect)
        assertEquals(prepared.remaining(), prepared.getInt(8))
        val tail = 20 + prepared.getInt(12)
        assertEquals(0, tail % 4)
        assertEquals(4, prepared.getInt(tail))
        assertEquals(0x004E4942, prepared.getInt(tail + 4))
        assertEquals(0x12345678, prepared.getInt(tail + 8))
        assertEquals(0, source.position())
    }

    @Test
    fun escaped_extension_name_cannot_bypass_the_workaround() {
        val escaped = json.replace("KHR_materials_volume", "KHR_materials_" + "\\u0076olume")
        val source = ByteBuffer.wrap(escaped.toByteArray())
        val prepared = GltfMaterialCompatibility.prepare(source) as ByteBuffer
        val result = JSONObject(GltfBufferUris.jsonText(prepared.order(ByteOrder.LITTLE_ENDIAN))!!)
        assertFalse(result.getJSONArray("materials").getJSONObject(0)
            .getJSONObject("extensions").has("KHR_materials_volume"))
    }

    @Test
    fun unaffected_payload_is_returned_without_copying() {
        val source = ByteBuffer.wrap("""{"asset":{"version":"2.0"}}""".toByteArray())
        assertSame(source, GltfMaterialCompatibility.prepare(source))
    }
}
