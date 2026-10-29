package io.github.sceneview.demo.demos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for #4735: every result view stands its room on the same dimensioned grid, and
 * shows the parts still to scan in the same "not scanned yet" look.
 *
 * The 3D views build Filament nodes that do not exist on the JVM, so they cannot be composed here:
 * which layers each one draws is pinned at the source level, the way `DollhousePreviewWiringTest`
 * pins its camera. The layers themselves are proven by `ResultFloorTest` and `RescanZoneMeshTest`,
 * the plan's look by `RerunPlanLookRenderTest`.
 */
class ResultFloorWiringTest {

    // JVM tests run with the module directory as CWD.
    private val demos = File("src/main/java/io/github/sceneview/demo/demos")

    private fun String.oneLine() = replace(Regex("""\s+"""), " ")

    /** [name]'s body in [file], from its signature to the next top-level declaration, on one line. */
    private fun body(file: String, name: String): String = File(demos, file).readText()
        .substringAfter("fun $name(", missingDelimiterValue = "")
        .substringBefore("\n}\n")
        .oneLine()

    @Test
    fun `the dollhouse stands on the grid, dimensioned, the parts still to scan on it`() {
        val preview = body("DollhouseLayers.kt", "DollhousePreview")
        assertTrue("DollhousePreview was renamed or moved — point this contract at it.", preview.contains("SceneView("))
        for (layer in listOf("ResultGridLayer(", "ResultMeasureLayer(", "RescanZoneLayer(")) {
            assertTrue("DollhousePreview must draw $layer", preview.contains(layer))
        }
    }

    @Test
    fun `the 3D model keeps its grid and gains the room's dimensions on it`() {
        val model = body("RerunModelUi.kt", "RerunModelScreen")
        assertTrue("RerunModelScreen was renamed or moved — point this contract at it.", model.contains("SceneView("))
        for (layer in listOf("ScanGridLayer(", "ResultMeasureLayer(", "RescanZoneLayer(")) {
            assertTrue("RerunModelScreen must draw $layer", model.contains(layer))
        }
    }

    @Test
    fun `the plan screen lays the parts still to scan on its plan`() {
        val screen = body("RerunPlanUi.kt", "RerunPlanScreen")
        assertTrue("RerunPlanScreen must hand its zones to the plan", screen.contains("zones = "))
    }
}
