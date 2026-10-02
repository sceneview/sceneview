package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The raw depth's lens and colour lookup, on the framing a Pixel gives by default: a 4:3 CPU
 * image (640×480), a 16:9 GPU texture (1920×1080) cropped from the same sensor, and a 16:9
 * raw depth (160×90) aligned with the texture.
 */
class DepthAlignmentTest {
    // One sensor: the texture is the CPU image ×3, its top and bottom 180 px cropped away.
    private val image = ScanIntrinsics(fx = 500f, fy = 500f, cx = 320f, cy = 240f, width = 640, height = 480)
    private val texture = ScanIntrinsics(fx = 1500f, fy = 1500f, cx = 960f, cy = 540f, width = 1920, height = 1080)

    @Test
    fun `a 16 by 9 depth takes the texture's lens, square pixels kept`() {
        val lens = DepthAlignment.depthLens(texture, image, DEPTH_W, DEPTH_H)
        assertNotNull(lens)
        lens!!
        assertEquals(125f, lens.fx, 1e-4f)
        assertEquals(125f, lens.fy, 1e-4f)
        assertEquals(80f, lens.cx, 1e-4f)
        assertEquals(45f, lens.cy, 1e-4f)
    }

    @Test
    fun `a door 2 m tall at 3 m measures 2 m, where the CPU image's lens made it a third taller`() {
        val lens = DepthAlignment.depthLens(texture, image, DEPTH_W, DEPTH_H)!!
        // Where the true lens sees the door's top and bottom, 1 m above and below the axis.
        val top = lens.cy - lens.fy * 1f / DOOR_DISTANCE_M
        val bottom = lens.cy + lens.fy * 1f / DOOR_DISTANCE_M
        fun height(fy: Float, cy: Float): Float {
            val t = DepthBackProjection.cameraPoint(0, top.toInt(), DOOR_DISTANCE_M, lens.fx, fy, lens.cx, cy)
            val b = DepthBackProjection.cameraPoint(0, bottom.toInt(), DOOR_DISTANCE_M, lens.fx, fy, lens.cx, cy)
            return t.y - b.y
        }
        assertEquals(DOOR_HEIGHT_M, height(lens.fy, lens.cy), DOOR_HEIGHT_M * 0.01f)
        // The lens the scan used before: the CPU image's, scaled to the depth axis by axis.
        val oldFy = image.fy * DEPTH_H / image.height
        val oldCy = image.cy * DEPTH_H / image.height
        assertEquals(DOOR_HEIGHT_M * 4f / 3f, height(oldFy, oldCy), DOOR_HEIGHT_M * 0.01f)
    }

    @Test
    fun `a depth pixel takes its colour from the CPU image pixel on the same ray`() {
        val lens = DepthAlignment.depthLens(texture, image, DEPTH_W, DEPTH_H)!!
        val map = DepthAlignment.toImage(lens, image)
        fun u(x: Int) = map[0] * x + map[1]
        fun v(y: Int) = map[2] * y + map[3]
        // The centre is the centre; the depth's top row is the CPU image's row 60, not row 0:
        // the texture, and so the depth, never saw the CPU image's top and bottom bands.
        assertEquals(320f, u(80), 1e-3f)
        assertEquals(240f, v(45), 1e-3f)
        assertEquals(60f, v(0), 1e-3f)
        assertEquals(420f, v(DEPTH_H), 1e-3f)
        assertEquals(0f, u(0), 1e-3f)
        assertEquals(640f, u(DEPTH_W), 1e-3f)
    }

    @Test
    fun `a depth with the CPU image's aspect takes the CPU image's lens`() {
        val lens = DepthAlignment.depthLens(texture, image, 160, 120)!!
        assertEquals(125f, lens.fx, 1e-4f)
        assertEquals(125f, lens.fy, 1e-4f)
        assertEquals(60f, lens.cy, 1e-4f)
    }

    @Test
    fun `without a texture lens, or with no framing matching, the depth is not guessed at`() {
        assertNull(DepthAlignment.depthLens(null, image, DEPTH_W, DEPTH_H))
        assertNull(DepthAlignment.depthLens(texture, image, 100, 100))
        assertNull(DepthAlignment.depthLens(texture, image, 0, 90))
        val same = DepthAlignment.depthLens(null, image, 640, 480)!!
        assertEquals(image.fy, same.fy, 1e-4f)
        assertEquals(640, same.width)
    }

    private companion object {
        const val DEPTH_W = 160
        const val DEPTH_H = 90
        const val DOOR_DISTANCE_M = 3f
        const val DOOR_HEIGHT_M = 2f
    }
}
