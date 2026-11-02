package io.github.sceneview.demo.demos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for #4735 and the Scan redesign (PR 1): the dollhouse stands its room on the
 * dimensioned grid with the parts still to scan; the scan's result keeps the grid and the room's
 * dimensions for More › Measure only, and neither it nor its plan lays the parts still to scan.
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
    fun `the 3D model keeps its grid and dimensions for Measure, without the parts to scan`() {
        val model = body("RerunModelUi.kt", "RerunModelScreen")
        assertTrue("RerunModelScreen was renamed or moved — point this contract at it.", model.contains("SceneView("))
        for (layer in listOf("ScanGridLayer(", "ResultMeasureLayer(")) {
            assertTrue("RerunModelScreen must draw $layer", model.contains(layer))
        }
        assertTrue("the grid and the dimensions are drawn on Measure only", model.contains("takeIf { measuring }"))
        assertFalse("the result shows no zone to scan again", model.contains("RescanZoneLayer("))
        assertFalse("the result shows no pill to scan again", model.contains("RescanZonesPill("))
    }

    @Test
    fun `the plan screen lays no part still to scan on its plan`() {
        val screen = body("RerunPlanUi.kt", "RerunPlanScreen")
        assertTrue("RerunPlanScreen was renamed or moved — point this contract at it.", screen.contains("RerunPlanView("))
        assertFalse("RerunPlanScreen hands no zones to the plan", screen.contains("zones = "))
    }
}
