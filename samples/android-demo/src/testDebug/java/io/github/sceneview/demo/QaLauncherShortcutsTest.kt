package io.github.sceneview.demo

import android.net.Uri
import io.github.sceneview.demo.fragments.ArRerunFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the static launcher shortcuts of the QA app (`src/qaLauncher/res/xml/qa_launcher_shortcuts.xml`,
 * long-press "SV QA"). "AR Recording" is the one visible way into the ARCore session recorder,
 * so its link must keep landing on the Session MP4 mode of `ar-rerun` through the same
 * [DeepLinkRouter] path [MainActivity] uses, and must keep naming the QA app explicitly (the
 * store app also handles `sceneview://demo`, an implicit intent would offer a chooser).
 */
@RunWith(RobolectricTestRunner::class)
class QaLauncherShortcutsTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private fun shortcut(id: String): Element {
        // Unit tests run with the module directory as working directory.
        val file = File("src/qaLauncher/res/xml/qa_launcher_shortcuts.xml")
        assertTrue("missing $file", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val shortcuts = factory.newDocumentBuilder().parse(file).getElementsByTagName("shortcut")
        return (0 until shortcuts.length).map { shortcuts.item(it) as Element }
            .single { it.getAttributeNS(android, "shortcutId") == id }
    }

    private fun Element.intent(): Element = getElementsByTagName("intent").item(0) as Element

    @Test
    fun `AR Recording opens the Session MP4 mode of ar-rerun`() {
        val data = Uri.parse(shortcut("ar_recording").intent().getAttributeNS(android, "data"))

        val demoId = DeepLinkRouter.parse(data)
        val launch = DeepLinkRouter.resolveLaunch(
            demoId = demoId,
            rawId = DeepLinkRouter.extractCandidate(data),
            tabParam = DeepLinkRouter.parseTabParam(data),
        )

        assertEquals("ar-rerun", launch.demoId)
        val sessionMp4 = ArRerunFragment.modes.indexOfFirst { it.key == "session-mp4" }
        assertTrue(sessionMp4 >= 0)
        assertEquals(sessionMp4, launch.initialTab)
    }

    @Test
    fun `AR Recording targets the QA app explicitly`() {
        val intent = shortcut("ar_recording").intent()
        assertEquals("android.intent.action.VIEW", intent.getAttributeNS(android, "action"))
        assertEquals("io.github.sceneview.demo.qa", intent.getAttributeNS(android, "targetPackage"))
        assertEquals(MainActivity::class.java.name, intent.getAttributeNS(android, "targetClass"))
    }
}
