package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class RerunExportFormatTest {
    @Test
    fun `the model comes first, then the scan's raw material`() {
        assertEquals(listOf("glb", "ply", "svscan", "rrd"), RerunExportFormat.entries.map { it.extension })
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
        assertEquals(RerunExportFormat.entries, RerunExportFormat.available(hasModel = true, hasCapture = true))
        assertEquals(listOf(RerunExportFormat.Model), RerunExportFormat.available(hasModel = true, hasCapture = false))
        // A recording without a result: its planes as a glTF scene, and the rest.
        assertEquals(RerunExportFormat.entries, RerunExportFormat.available(hasModel = false, hasCapture = true))
        assertEquals(emptyList<RerunExportFormat>(), RerunExportFormat.available(hasModel = false, hasCapture = false))
    }

    @Test
    fun `the model is a glTF binary to a share sheet or a file picker`() {
        assertEquals("model/gltf-binary", RerunExportFormat.Model.mimeType)
    }
}
