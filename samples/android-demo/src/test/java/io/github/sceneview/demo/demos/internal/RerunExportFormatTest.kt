package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class RerunExportFormatTest {
    @Test
    fun `the model comes first, then the scan's raw material, then a space's measurements`() {
        assertEquals(listOf("glb", "ply", "svscan", "rrd", "json"), RerunExportFormat.entries.map { it.extension })
    }

    @Test
    fun `a file is named after the scan's title`() {
        assertEquals("room-sep-28-2-32-pm.glb", RerunExportFormat.Model.fileName("Room · Sep 28, 2:32 PM"))
        assertEquals("space.rrd", RerunExportFormat.Recording.fileName("· · ·"))
        // The scan file keeps its title, as the landing's Share names it.
        assertEquals("Room · Sep 28, 2-32 PM.svscan", RerunExportFormat.Scan.fileName("Room · Sep 28, 2:32 PM"))
    }

    @Test
    fun `only the formats the source can make are offered`() {
        val files = RerunExportFormat.entries - RerunExportFormat.Measurements
        assertEquals(files, RerunExportFormat.available(hasModel = true, hasCapture = true))
        assertEquals(listOf(RerunExportFormat.Model), RerunExportFormat.available(hasModel = true, hasCapture = false))
        // A recording without a result: its planes as a glTF scene, and the rest.
        assertEquals(files, RerunExportFormat.available(hasModel = false, hasCapture = true))
        assertEquals(emptyList<RerunExportFormat>(), RerunExportFormat.available(hasModel = false, hasCapture = false))
    }

    @Test
    fun `a space's measurements are offered only where a space was measured`() {
        assertEquals(
            RerunExportFormat.entries,
            RerunExportFormat.available(hasModel = true, hasCapture = true, hasMeasurements = true),
        )
        assertEquals("application/json", RerunExportFormat.Measurements.mimeType)
        assertEquals(
            "cupboard-or-shelf-nov-14-5-40-pm.json",
            RerunExportFormat.Measurements.fileName("Cupboard or shelf · Nov 14, 5:40 PM"),
        )
    }

    @Test
    fun `the model is a glTF binary to a share sheet or a file picker`() {
        assertEquals("model/gltf-binary", RerunExportFormat.Model.mimeType)
    }
}
