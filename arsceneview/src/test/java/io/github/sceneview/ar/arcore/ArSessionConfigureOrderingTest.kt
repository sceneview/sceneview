package io.github.sceneview.ar.arcore

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for the Pixel 4a `UnsupportedConfigurationException` regression measured on
 * 2026-08-14. The native ARCore session cannot be exercised in a JVM test.
 */
class ArSessionConfigureOrderingTest {

    @Test
    fun `configuration fallbacks run before ARCore configure`() {
        val source = File("src/main/java/io/github/sceneview/ar/arcore/ArSession.kt")
            .readText()
            .replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*"""), "")
            .replace(Regex("""\s+"""), "")
        val body = source.substringAfter("overridefunconfigure(config:Config){")
            .substringBefore("onConfigChanged(this,config)")
        val nativeConfigure = body.indexOf("super.configure(config)")
        assertTrue("ARSession.configure must delegate to ARCore", nativeConfigure >= 0)

        listOf(
            "config.depthMode=Config.DepthMode.DISABLED",
            "config.flashMode=resolveFlashMode(",
            "config.lightEstimationMode=Config.LightEstimationMode.DISABLED",
            "config.semanticMode=resolveSemanticMode(",
        ).forEach { fallback ->
            val fallbackIndex = body.indexOf(fallback)
            assertTrue("$fallback must run before super.configure(config)", fallbackIndex in 0 until nativeConfigure)
        }
    }
}
