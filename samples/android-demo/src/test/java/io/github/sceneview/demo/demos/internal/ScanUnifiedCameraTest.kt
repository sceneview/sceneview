package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanUnifiedCameraTest {
    @Test
    fun `two fingers cannot displace the result but still zoom`() {
        val orbit = ArDebugOrbitCamera(drift = false, allowPan = false)
        val target = orbit.pose.target
        orbit.grabBegin(100, 100, true)
        orbit.grabUpdate(400, 500)
        assertEquals(target, orbit.pose.target)
        val before = orbit.pose.distance
        orbit.scrollBegin(200, 200, 100f)
        orbit.scrollUpdate(200, 200, 100f, 150f)
        assertTrue(orbit.pose.distance < before)
        assertEquals(target, orbit.pose.target)
    }

    @Test
    fun `idle result keeps its heading for ten seconds`() {
        val orbit = ArDebugOrbitCamera(drift = false)
        val before = orbit.pose
        repeat(600) { orbit.update(1f / 60f) }
        assertEquals(before, orbit.pose)
    }

    @Test
    fun `capture tint leaves the saved colours untouched`() {
        val colours = intArrayOf(0xFFBE7E4D.toInt())
        val cloud = DenseCloud(floatArrayOf(0f, 0f, 0f), colours)
        val saved = DenseSurfels.atlas(cloud, 0xFF777777.toInt())
        val coat = DenseSurfels.liveAtlas(cloud, 0xFF777777.toInt())
        assertEquals(0xFFBE7E4D.toInt(), cloud.colors[0])
        assertTrue(!saved.contentEquals(coat))
        // Dark and bright observations of the same surface must not leave a patchwork.
        assertEquals(DenseSurfels.tint(0xFF000000.toInt()), DenseSurfels.tint(0xFFFFFFFF.toInt()))
        assertTrue((DenseSurfels.tint(0xFF000000.toInt()) and 0xFF) >= 200)
    }
}
