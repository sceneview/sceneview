package io.github.sceneview.demo

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for "Open with SceneView" (#3482) — the ingress that lets a `.3mf` shared out of ChatGPT
 * land in this app.
 *
 * The two things worth pinning are the two that silently break the feature: which intents are
 * treated as a file (a deep link must not be), and which name/MIME pairs count as a model (a share
 * sheet labels a `.3mf` `application/octet-stream`, so the extension has to carry that case). Both
 * are pure functions; Robolectric is here only for a real `android.net.Uri` parser.
 */
@RunWith(RobolectricTestRunner::class)
class OpenedModelIntentTest {

    @Test
    fun `a VIEW intent on a file is a model`() {
        val uri = Uri.parse("content://com.android.providers.downloads/document/42")
        val intent = Intent(Intent.ACTION_VIEW, uri)

        assertEquals(uri, OpenedModelIntent.modelUri(intent))
    }

    @Test
    fun `a SEND intent carries its file in EXTRA_STREAM`() {
        val uri = Uri.parse("content://media/external/downloads/17")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "model/3mf"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        assertEquals(uri, OpenedModelIntent.modelUri(intent))
    }

    @Test
    fun `a deep link is not a file`() {
        // Both deep-link shapes reach the same activity through their own VIEW filters;
        // DeepLinkRouter owns them, and treating either as a file would try to open a demo id.
        assertNull(
            OpenedModelIntent.modelUri(
                Intent(Intent.ACTION_VIEW, Uri.parse("sceneview://demo/model-viewer"))
            )
        )
        assertNull(
            OpenedModelIntent.modelUri(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://sceneview.github.io/open?demo=ar-placement"))
            )
        )
    }

    @Test
    fun `a launcher intent carries no file`() {
        assertNull(OpenedModelIntent.modelUri(Intent(Intent.ACTION_MAIN)))
        assertNull(OpenedModelIntent.modelUri(null))
    }

    @Test
    fun `a 3mf is recognised by extension even when the sender calls it a binary blob`() {
        // This is the case that actually happens: most Android share sheets have no MIME type
        // registered for 3MF and fall back to octet-stream.
        assertTrue(OpenedModelIntent.looksLikeModel("dragon.3mf", "application/octet-stream"))
        assertTrue(OpenedModelIntent.looksLikeModel("DRAGON.3MF", null))
        assertTrue(OpenedModelIntent.looksLikeModel("chatgpt export.3mf", "*/*"))
    }

    @Test
    fun `a 3mf is recognised by MIME type when there is no file name`() {
        // A content:// URI often has no usable name — the declared type is all there is.
        assertTrue(OpenedModelIntent.looksLikeModel(null, "model/3mf"))
        assertTrue(
            OpenedModelIntent.looksLikeModel(
                null,
                "application/vnd.ms-package.3dmanufacturing-3dmodel+xml"
            )
        )
        assertTrue(OpenedModelIntent.looksLikeModel(null, "model/gltf-binary; charset=utf-8"))
    }

    @Test
    fun `glTF rides along with 3MF`() {
        assertTrue(OpenedModelIntent.looksLikeModel("helmet.glb", null))
        assertTrue(OpenedModelIntent.looksLikeModel("helmet.gltf", null))
    }

    @Test
    fun `anything else is refused`() {
        // The manifest's octet-stream filter is broad by necessity; this is the layer that
        // keeps a PDF or a ZIP from reaching the loader and failing with no explanation.
        assertFalse(OpenedModelIntent.looksLikeModel("invoice.pdf", "application/octet-stream"))
        assertFalse(OpenedModelIntent.looksLikeModel("archive.zip", "application/zip"))
        assertFalse(OpenedModelIntent.looksLikeModel("photo.jpg", "image/jpeg"))
        assertFalse(OpenedModelIntent.looksLikeModel(null, "application/octet-stream"))
        assertFalse(OpenedModelIntent.looksLikeModel(null, null))
    }

    // A file's own bytes are the last word, and on a real share the only word. See
    // `OpenedModelIntent.stage`: measured on emulator-5554, a shared `.3mf` arrives with no
    // queryable display name AND `application/octet-stream`, so both metadata signals are blank.

    @Test
    fun `a real 3MF is recognised from its bytes alone`() {
        val file = temporary("no-name-no-type")
        file.writeBytes(threeMfBytes())

        assertEquals("3mf", OpenedModelIntent.detectFormat(file))
        // The pair that arrives from a share sheet, which metadata alone would refuse.
        assertFalse(OpenedModelIntent.looksLikeModel(null, "application/octet-stream"))
    }

    @Test
    fun `a plain ZIP is not a 3MF`() {
        // A .docx, a .jar and any ordinary archive all open with `PK`; ZIP magic alone would
        // claim every one of them, so the package has to be opened and looked into.
        val file = temporary("archive")
        file.writeBytes(zipOf("notes.txt" to "hello"))

        assertNull(OpenedModelIntent.detectFormat(file))
    }

    @Test
    fun `glTF is recognised binary and text`() {
        val glb = temporary("binary")
        glb.writeBytes("glTF".toByteArray() + byteArrayOf(2, 0, 0, 0, 32, 0, 0, 0))
        assertEquals("glb", OpenedModelIntent.detectFormat(glb))

        val gltf = temporary("text")
        gltf.writeText("""{ "asset": { "version": "2.0" }, "scenes": [] }""")
        assertEquals("gltf", OpenedModelIntent.detectFormat(gltf))
    }

    @Test
    fun `an unrelated file is refused by content too`() {
        val text = temporary("prose")
        text.writeText("this is not a model, it is a shopping list")
        assertNull(OpenedModelIntent.detectFormat(text))

        val json = temporary("other-json")
        json.writeText("""{ "name": "not a model" }""")
        assertNull(OpenedModelIntent.detectFormat(json))

        assertNull(OpenedModelIntent.detectFormat(temporary("empty")))
    }

    @Test
    fun `every advertised extension is one the code accepts`() {
        // The manifest lists path patterns; this object lists extensions. A file type advertised
        // in one and missing from the other opens the chooser and then fails to load.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val advertised = Regex("""android:pathPattern="\.\*\\\\\.([A-Za-z0-9]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1].lowercase() }
            .toSet()

        assertTrue("no pathPattern found in the manifest", advertised.isNotEmpty())
        assertEquals(OpenedModelIntent.SupportedExtensions, advertised)
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun temporary(name: String): File = temporaryFolder.newFile(name)

    /** A minimal but genuine 3MF package: an OPC ZIP holding a `3D/3dmodel.model` part. */
    private fun threeMfBytes(): ByteArray = zipOf(
        "[Content_Types].xml" to
            """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/""" +
            """package/2006/content-types"><Default Extension="model" ContentType="application/""" +
            """vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""",
        "3D/3dmodel.model" to
            """<?xml version="1.0"?><model unit="millimeter"><resources><object id="1"><mesh>""" +
            """<vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/>""" +
            """<vertex x="0" y="10" z="0"/></vertices>""" +
            """<triangles><triangle v1="0" v2="1" v3="2"/></triangles>""" +
            """</mesh></object></resources><build><item objectid="1"/></build></model>"""
    )

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
