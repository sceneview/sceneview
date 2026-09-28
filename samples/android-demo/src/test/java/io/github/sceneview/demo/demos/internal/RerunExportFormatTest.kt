package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class RerunExportFormatTest {
    @Test
    fun `the sheet lists the three formats Android writes, in the iOS order`() {
        assertEquals(listOf("rrd", "glb", "ply"), RerunExportFormat.entries.map { it.extension })
    }

    @Test
    fun `a file is named after the session's title`() {
        assertEquals("room-sep-28-2-32-pm.glb", RerunExportFormat.Glb.fileName("Room · Sep 28, 2:32 PM"))
        assertEquals("space.rrd", RerunExportFormat.Rrd.fileName("· · ·"))
    }

    @Test
    fun `the share-all label counts the formats`() {
        assertEquals("Share all three", RerunExportFormat.SHARE_ALL)
        assertEquals(3, RerunExportFormat.entries.size)
    }
}
