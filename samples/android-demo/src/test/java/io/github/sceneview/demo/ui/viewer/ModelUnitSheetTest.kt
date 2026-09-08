package io.github.sceneview.demo.ui.viewer

import io.github.sceneview.demo.OpenedModelIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #3543 — the two pure pieces behind the scale question. */
class ModelUnitSheetTest {

    @Test fun `a size is labelled in the unit a person would say it in`() {
        assertEquals("2 mm", formatSize(0.002f))
        assertEquals("2 m", formatSize(2f))
        assertEquals("7 cm", formatSize(0.07f))
        assertEquals("1.5 m", formatSize(1.54f))
        assertEquals("155 m", formatSize(155f))
        // Never "1.9999 m" on a button.
        assertEquals("2 m", formatSize(1.9999f))
        assertEquals("0 mm", formatSize(0f))
        assertEquals("0 mm", formatSize(Float.NaN))
    }

    @Test fun `only the three unit-less formats are asked about`() {
        assertEquals("stl", OpenedModelIntent.unitLessFormat("part.STL"))
        assertEquals("obj", OpenedModelIntent.unitLessFormat("scan.obj"))
        assertEquals("ply", OpenedModelIntent.unitLessFormat("cloud.ply"))
        // glTF and 3MF both state their size, so neither is ever asked.
        assertNull(OpenedModelIntent.unitLessFormat("helmet.glb"))
        assertNull(OpenedModelIntent.unitLessFormat("print.3mf"))
        assertNull(OpenedModelIntent.unitLessFormat("noextension"))
    }
}
