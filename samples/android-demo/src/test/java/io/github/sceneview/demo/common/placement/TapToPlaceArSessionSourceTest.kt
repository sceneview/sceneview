package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Wiring contracts that cannot instantiate Filament or ARCore in a local JVM test. */
class TapToPlaceArSessionSourceTest {

    private val source = File(
        "src/main/java/io/github/sceneview/demo/common/placement/TapToPlaceArSession.kt"
    ).readText()

    @Test
    fun `placement-independent scene content uses the unconditional SDK slot`() {
        assertTrue(source.contains("sceneContent = extraSceneContent,"))
        assertFalse(source.contains("extraSceneContent?.invoke(this)"))
    }

    @Test
    fun `QA backdrop stays behind a translucent texture viewport and SDK cards`() {
        val backdrop = source.indexOf("        if (qaBackdrop) QaCameraBackdrop(seed = backdropSeed)")
        val scene = source.indexOf("        AutoPlacementScene(")
        assertTrue("QA backdrop must be composed before AutoPlacementScene", backdrop in 0 until scene)
        assertTrue(source.contains("surfaceType = qaCameraBackdropSurfaceType(),"))
        assertTrue(source.contains("isOpaque = !qaCameraBackdropEnabled(),"))
    }
}
