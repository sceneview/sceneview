package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneBakeTest {

    @Test
    fun `a floor's texture runs along X and Z, facing up from where the scan was walked`() {
        val rect = PlaneBake.rectOf(FLOOR, viewpoint = Vec3(0f, 2f, 0f))!!
        assertVec(Vec3(-1f, 0f, -1f), rect.origin)
        assertVec(Vec3(2f, 0f, 0f), rect.u)
        assertVec(Vec3(0f, 0f, 2f), rect.v)
        assertVec(Vec3(0f, 1f, 0f), rect.normal)
    }

    @Test
    fun `a wall's texture runs along it and down it, read upright from the room`() {
        val wall = DebugPlane(
            2,
            DebugPlaneKind.Wall,
            floatArrayOf(-1f, 0f, -2f, 1f, 0f, -2f, 1f, 2f, -2f, -1f, 2f, -2f),
        )
        val rect = PlaneBake.rectOf(wall, viewpoint = Vec3(0f, 1f, 0f))!!
        assertVec(Vec3(0f, 0f, 1f), rect.normal)
        assertVec(Vec3(-1f, 2f, -2f), rect.origin)
        assertVec(Vec3(2f, 0f, 0f), rect.u)
        assertVec(Vec3(0f, -2f, 0f), rect.v)
    }

    @Test
    fun `a degenerate plane has no texture`() {
        assertNull(PlaneBake.rectOf(DebugPlane(1, DebugPlaneKind.Floor, floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f))))
        val line = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f)
        assertNull(PlaneBake.rectOf(DebugPlane(1, DebugPlaneKind.Floor, line)))
    }

    @Test
    fun `a texture has a texel a centimetre, its long side capped`() {
        val square = PlaneRect(Vec3.Zero, Vec3(2f, 0f, 0f), Vec3(0f, 0f, 2f), Vec3(0f, 1f, 0f))
        assertEquals(200 to 200, PlaneBake.sizeOf(square))
        val long = PlaneRect(Vec3.Zero, Vec3(10f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f))
        assertEquals(PlaneBake.MAX_SIDE to 52, PlaneBake.sizeOf(long))
    }

    @Test
    fun `the floor is painted from a photo looking down on it, each corner in its place`() {
        val rect = PlaneBake.rectOf(FLOOR, viewpoint = Vec3(0f, 2f, 0f))!!
        val photo = BakePhoto(LOOKING_DOWN, 4, 4, quadrants())
        val texels = PlaneBake.bake(rect, 4, 4, listOf(photo), LENS)!!

        // Looking down, the photo's top is -Z (the floor's small t) and its right is +X.
        assertEquals(RED, texels[0 * 4 + 0])
        assertEquals(BLUE, texels[0 * 4 + 3])
        assertEquals(GREEN, texels[3 * 4 + 0])
        assertEquals(WHITE, texels[3 * 4 + 3])
    }

    @Test
    fun `what no photo saw takes the colour of what they did`() {
        val big = DebugPlane(1, DebugPlaneKind.Floor, floatArrayOf(-3f, 0f, -3f, 3f, 0f, -3f, 3f, 0f, 3f, -3f, 0f, 3f))
        val rect = PlaneBake.rectOf(big, viewpoint = Vec3(0f, 2f, 0f))!!
        val photo = BakePhoto(LOOKING_DOWN, 4, 4, IntArray(16) { RED })
        val texels = PlaneBake.bake(rect, 12, 12, listOf(photo), LENS)!!

        assertTrue(texels.all { it == RED })
    }

    @Test
    fun `a photo looking away paints nothing and is not picked`() {
        val rect = PlaneBake.rectOf(FLOOR, viewpoint = Vec3(0f, 2f, 0f))!!
        val lookingUp = DebugPose(0f, 2f, 0f, 0.70710677f, 0f, 0f, 0.70710677f)
        val photo = BakePhoto(lookingUp, 4, 4, IntArray(16) { RED })

        assertNull(PlaneBake.bake(rect, 4, 4, listOf(photo), LENS))
        assertEquals(listOf(1), PlaneBake.pickPhotos(rect, listOf(lookingUp, LOOKING_DOWN), LENS))
        assertNotNull(PlaneBake.bake(rect, 4, 4, listOf(BakePhoto(LOOKING_DOWN, 4, 4, quadrants())), LENS))
    }

    private fun quadrants() = IntArray(16) { i ->
        val left = i % 4 < 2
        val top = i / 4 < 2
        when {
            top && left -> RED
            top -> BLUE
            left -> GREEN
            else -> WHITE
        }
    }

    private fun assertVec(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, 1e-5f)
        assertEquals(expected.y, actual.y, 1e-5f)
        assertEquals(expected.z, actual.z, 1e-5f)
    }

    private companion object {
        val FLOOR = DebugPlane(
            1,
            DebugPlaneKind.Floor,
            floatArrayOf(-1f, 0f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f),
        )

        /** Two metres above the origin, looking straight down, the photo's top toward -Z. */
        val LOOKING_DOWN = DebugPose(0f, 2f, 0f, -0.70710677f, 0f, 0f, 0.70710677f)

        /** Sees two metres across at two metres: the whole floor. */
        val LENS = ReplayLens(0.5f, 0.5f)

        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val GREEN = 0xFF00FF00.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
    }
}
