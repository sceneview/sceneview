package io.github.sceneview.loaders

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.render.RenderTestHarness
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device proof for #2305: a GLB whose textures are WebP-encoded loads **with** its textures.
 *
 * The JVM suite ([WebPTextureTranscoderTest]) covers the container surgery with a fake codec. What
 * only a device can show is the other half of the fix — that Android's [BitmapFactory] really
 * decodes the `EXT_texture_webp` payload, that the PNG it re-encodes is a genuine image, and that
 * Filament's `gltfio` then consumes the rewritten GLB and resolves every texture instead of
 * logging `Missing texture provider for image/webp`.
 *
 * `webp_quad.glb` is a 2.6 KB textured quad using `EXT_texture_webp` (declared in
 * `extensionsRequired`), validated with `gltf-transform validate`.
 */
@RunWith(AndroidJUnit4::class)
class WebPTextureLoadTest {

    companion object {
        private lateinit var harness: RenderTestHarness

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = 64, height = 64)
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.destroy()
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().context

    private fun fixture(): ByteBuffer = context.assets.open("webp_quad.glb").use { input ->
        val bytes = input.readBytes()
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
            put(bytes)
            rewind()
        }
    }

    @Test
    fun bitmapFactory_decodes_the_embedded_webp_and_the_png_it_produces_is_a_real_image() {
        val glb = fixture()
        val (sourceJson, sourceBin) = parseGlb(glb)
        val webPView = sourceJson.getJSONArray("bufferViews").getJSONObject(
            sourceJson.getJSONArray("images").getJSONObject(0).getInt("bufferView")
        )
        val webP = sourceBin.copyOfRange(
            webPView.getInt("byteOffset"),
            webPView.getInt("byteOffset") + webPView.getInt("byteLength")
        )
        val decoded = BitmapFactory.decodeByteArray(webP, 0, webP.size)
        assertNotNull("BitmapFactory must decode the WebP texture", decoded)

        val png = WebPTextureTranscoder.platformTranscoder.webPToPng(webP)
        assertNotNull("the platform transcoder must produce PNG bytes", png)
        val reDecoded = BitmapFactory.decodeByteArray(png!!, 0, png.size)
        assertNotNull("the produced PNG must decode back", reDecoded)
        assertEquals(decoded!!.width, reDecoded!!.width)
        assertEquals(decoded.height, reDecoded.height)

        // Positive cue: the PNG carries the actual texture, not a blank placeholder that would
        // still "decode" fine — the source texture has more than one colour, and so must the copy.
        val colors = buildSet {
            for (y in 0 until reDecoded.height step 4) {
                for (x in 0 until reDecoded.width step 4) add(reDecoded.getPixel(x, y))
            }
        }
        assertTrue("the transcoded texture must not be flat (got ${colors.size} colours)", colors.size > 1)
    }

    @Test
    fun filament_loads_every_texture_of_the_transcoded_model() {
        val modelLoader = ModelLoader(harness.engine, context)
        try {
            var model: io.github.sceneview.model.Model? = null
            harness.runOnMain {
                // Goes through the production path, transcoding included.
                model = modelLoader.createModel(fixture(), releaseSourceData = false)
            }
            val loaded = requireNotNull(model)

            // Every resource is resolved: nothing is left waiting on a provider gltfio does not
            // have. On the untranscoded asset this is where `image/webp` stalls the texture.
            harness.runOnMain {
                repeat(10) { modelLoader.updateLoad() }
                assertEquals(1.0f, modelLoader.progress, 0.0001f)
            }
            assertTrue("the model must expose renderable entities", loaded.entities.isNotEmpty())
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun the_buffer_handed_to_filament_no_longer_mentions_webp() {
        val (json, _) = parseGlb(WebPTextureTranscoder.transcode(fixture()))

        assertFalse(json.toString().contains("webp", ignoreCase = true))
        assertEquals("image/png", json.getJSONArray("images").getJSONObject(0).getString("mimeType"))
        assertEquals(0, json.getJSONArray("textures").getJSONObject(0).getInt("source"))
    }

    private fun parseGlb(buffer: ByteBuffer): Pair<JSONObject, ByteArray> {
        val glb = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var offset = glb.position() + 12
        var json: JSONObject? = null
        var bin = ByteArray(0)
        while (offset + 8 <= glb.limit()) {
            val length = glb.getInt(offset)
            val type = glb.getInt(offset + 4)
            val chunk = ByteArray(length)
            glb.duplicate().apply { position(offset + 8) }.get(chunk)
            when (type) {
                0x4E4F534A -> json = JSONObject(String(chunk).trim())
                0x004E4942 -> bin = chunk
            }
            offset += 8 + length + (4 - length % 4) % 4
        }
        return requireNotNull(json) to bin
    }
}
