package io.github.sceneview.demo.ui.explore

import io.github.sceneview.demo.sources.GalleryModel
import io.github.sceneview.demo.sources.ModelSourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #4622: what the gallery viewer offers towards the 3D AR Model Viewer app, and the link it
 * hands over. The link must be one the viewer's `AppLink.parse` reads back to the same uid:
 * the last `-` segment of the path, 32 characters.
 */
class ModelViewerAppLinkTest {

    private val uid = "5f4e2b8c9a1d4e7f8b3c6a2d1e0f9a8b"

    private fun model(source: ModelSourceId, id: String) =
        GalleryModel(sourceId = source, id = id, name = "Scifi Girl v.01")

    @Test
    fun `a Sketchfab model links to its page by bare uid`() {
        assertEquals(
            "https://sketchfab.com/3d-models/$uid",
            ModelViewerAppLink.modelLink(model(ModelSourceId.SKETCHFAB, uid)),
        )
    }

    @Test
    fun `the link reads back to the same uid the way the viewer parses it`() {
        val link = ModelViewerAppLink.modelLink(model(ModelSourceId.SKETCHFAB, uid))!!
        val parsed = link.trimEnd('/').substringAfterLast('/').substringAfterLast('-')
        assertEquals(uid, parsed)
        assertEquals(32, parsed.length)
    }

    @Test
    fun `installed app - open the model in it`() {
        assertEquals(
            ModelViewerHandoff.OpenInApp("https://sketchfab.com/3d-models/$uid"),
            ModelViewerAppLink.handoff(model(ModelSourceId.SKETCHFAB, uid), appInstalled = true),
        )
    }

    @Test
    fun `app not installed - offer its Play listing`() {
        assertEquals(
            ModelViewerHandoff.GetApp,
            ModelViewerAppLink.handoff(model(ModelSourceId.SKETCHFAB, uid), appInstalled = false),
        )
    }

    @Test
    fun `other sources offer nothing, installed or not`() {
        for (installed in listOf(true, false)) {
            assertNull(ModelViewerAppLink.handoff(model(ModelSourceId.POLY_HAVEN, "rocking_chair_01"), installed))
            assertNull(ModelViewerAppLink.handoff(model(ModelSourceId.ICOSA, "6dHDz3wzZ0y"), installed))
        }
    }

    @Test
    fun `a Sketchfab id that is not a uid offers nothing`() {
        assertNull(ModelViewerAppLink.modelLink(model(ModelSourceId.SKETCHFAB, "")))
        assertNull(ModelViewerAppLink.modelLink(model(ModelSourceId.SKETCHFAB, uid.dropLast(1))))
        assertNull(ModelViewerAppLink.modelLink(model(ModelSourceId.SKETCHFAB, "$uid/../x")))
        assertNull(ModelViewerAppLink.modelLink(model(ModelSourceId.SKETCHFAB, uid.uppercase())))
    }

    @Test
    fun `the listing points at the viewer package`() {
        assertEquals("com.gorisse.thomas.arcamera", ModelViewerAppLink.PACKAGE)
        assertEquals("market://details?id=com.gorisse.thomas.arcamera", ModelViewerAppLink.MARKET_URL)
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.gorisse.thomas.arcamera",
            ModelViewerAppLink.PLAY_WEB_URL,
        )
    }
}
