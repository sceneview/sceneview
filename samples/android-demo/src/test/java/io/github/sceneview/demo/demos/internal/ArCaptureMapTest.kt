package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/** JVM tests for the AR Recording live floor plan (#4083) — no ARCore needed. */
class ArCaptureMapTest {

    @Test
    fun `a phone held still adds one path point`() {
        val map = CaptureMapRecorder()
        repeat(50) { map.addCamera(0.001f * it / 50f, 0f, 0f, tracking = true) }
        assertEquals(1, map.snapshot().path.size)
    }

    @Test
    fun `untracked samples draw nothing`() {
        val map = CaptureMapRecorder()
        map.addCamera(1f, 1f, 0f, tracking = false)
        val snapshot = map.snapshot()
        assertTrue(snapshot.isEmpty)
        assertNull(snapshot.current)
    }

    @Test
    fun `a keyframe every quarter metre of travel`() {
        val map = CaptureMapRecorder()
        // One metre in 5 cm steps: the start plus one keyframe per 25 cm.
        for (i in 0..20) map.addCamera(i * 0.05f, 0f, 0f, tracking = true)
        assertEquals(5, map.snapshot().keyframes.size)
    }

    @Test
    fun `a long walk is thinned but keeps both ends`() {
        val map = CaptureMapRecorder(maxPathPoints = 100)
        for (i in 0..400) map.addCamera(i * 0.05f, 0f, 0f, tracking = true)
        val path = map.snapshot().path
        assertTrue(path.size <= 100)
        assertEquals(0f, path.first().x, 1e-6f)
        assertEquals(400 * 0.05f, path.last().x, 1e-4f)
    }

    @Test
    fun `a merged surface is forgotten and an updated one replaced`() {
        val map = CaptureMapRecorder()
        val small = listOf(MapPoint(0f, 0f), MapPoint(1f, 0f), MapPoint(1f, 1f))
        val grown = small + MapPoint(0f, 1f)
        map.updateSurface(MapSurface(1, small, vertical = false))
        map.updateSurface(MapSurface(2, small, vertical = true))
        map.updateSurface(MapSurface(1, grown, vertical = false))
        map.forgetSurface(2)
        val surfaces = map.snapshot().surfaces
        assertEquals(1, surfaces.size)
        assertEquals(4, surfaces.single().outline.size)
    }

    @Test
    fun `heading is zero towards world minus z and grows clockwise from above`() {
        assertEquals(0f, headingOf(0f, -1f), 1e-6f)
        assertEquals((PI / 2).toFloat(), headingOf(1f, 0f), 1e-6f)
    }

    @Test
    fun `reset empties the map`() {
        val map = CaptureMapRecorder()
        map.addCamera(0f, 0f, 0f, tracking = true)
        map.reset()
        assertTrue(map.snapshot().isEmpty)
    }

    @Test
    fun `the QA map has a path, keyframes and surfaces`() {
        assertFalse(QA_CAPTURE_MAP.isEmpty)
        assertTrue(QA_CAPTURE_MAP.keyframes.size > 3)
        assertEquals(3, QA_CAPTURE_MAP.surfaces.size)
    }
}
